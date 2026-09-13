package io.github.viewerparrots.bootstrap;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.domain.PhysicalStatus;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.entity.ParrotEntityController;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import io.github.viewerparrots.lifecycle.DeathPolicy;
import io.github.viewerparrots.lifecycle.GenerationValidator;
import io.github.viewerparrots.storage.DtoMapper;
import io.github.viewerparrots.storage.dto.JournalEntryDto;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;
import java.util.UUID;

/**
 * Единственное место, где мод подписывается на события Fabric (раздел 3.3 ТЗ).
 *
 * <p>Причина удаления обрабатывается отдельно от факта выгрузки: одно событие unload
 * не доказывает смерть, поэтому обработчик смерти на unload не вызывается.
 */
public final class FabricLifecycleAdapter {

	private FabricLifecycleAdapter() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(FabricLifecycleAdapter::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(ServerContext::stop);
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof ViewerParrotEntity parrot) {
				onParrotLoad(parrot);
			}
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			if (entity instanceof ViewerParrotEntity parrot) {
				onParrotUnload(parrot);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(FabricLifecycleAdapter::onEndTick);
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (!(entity instanceof ViewerParrotEntity)) {
				return true;
			}
			return ServerContext.current()
					.map(context -> DeathPolicy.allowsDamage(context.config().deathMode(), source))
					.orElse(true);
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ViewerParrotEntity parrot) {
				onParrotDeath(parrot);
			}
		});
	}

	private static void onServerStarted(MinecraftServer server) {
		ServerContext context = ServerContext.start(server);
		ViewerParrotsMod.LOGGER.info(
				"Viewer Parrots запущены: хранилище {}, записей {}, лимит {}, deathMode {}",
				context.storageHealth(), context.registry().size(),
				context.config().maxActiveParrots(), context.config().deathMode());

		// Уже загруженные сущности обрабатываются один раз при старте, дальше — по событиям
		// (раздел 9.2 ТЗ запрещает полное сканирование мира каждый тик).
		for (net.minecraft.server.world.ServerWorld world : server.getWorlds()) {
			for (net.minecraft.entity.Entity entity : world.iterateEntities()) {
				if (entity instanceof ViewerParrotEntity parrot) {
					onParrotLoad(parrot);
				}
			}
		}
	}

	private static void onParrotLoad(ViewerParrotEntity parrot) {
		Optional<ServerContext> maybeContext = ServerContext.current();
		if (maybeContext.isEmpty()) {
			return;
		}
		ServerContext context = maybeContext.get();

		GenerationValidator.Decision decision = GenerationValidator.decide(
				parrot.marker(), parrot.getUuid(), context.registry(), context.isRegistryReady());

		switch (decision.verdict()) {
			case DEFER -> {
				// Реестр не готов: AI выключен, identity не меняется, сущность не удаляется.
				parrot.setAiDisabled(true);
				ViewerParrotsMod.LOGGER.debug("Сущность {} отложена: {}", parrot.getUuid(), decision.reason());
			}
			case DISCARD -> {
				parrot.setAiDisabled(true);
				ViewerParrotsMod.LOGGER.warn("Отвергнута сущность {}: {}", parrot.getUuid(), decision.reason());
				context.parrotService().controller().discard(parrot);
			}
			case BIND -> {
				ViewerId viewerId = parrot.marker().orElseThrow().viewerId();
				ViewerParrotRecord record = context.registry().find(viewerId).orElseThrow();
				context.loadedIndex().bind(viewerId, parrot);
				context.parrotService().controller().applyName(parrot, record);
				parrot.setAiDisabled(false);
				ViewerParrotsMod.LOGGER.debug("bind viewerId={} entity={}", viewerId, parrot.getUuid());
			}
		}
	}

	private static void onParrotUnload(ViewerParrotEntity parrot) {
		ServerContext.current().ifPresent(context -> {
			Optional<ViewerId> viewerId = context.loadedIndex().findViewerId(parrot.getUuid());
			if (viewerId.isEmpty()) {
				return;
			}
			// Подсказка о позиции обновляется в памяти; она не влияет на корректность
			// generation, поэтому её потеря при аварии допустима (раздел 8.2 ТЗ).
			context.registry().find(viewerId.get()).ifPresent(record -> {
				ViewerParrotRecord updated = record.withLocation(
						ParrotEntityController.locationOf(parrot), context.clock().now());
				context.registry().replace(updated);
			});
			context.loadedIndex().unbind(viewerId.get());
			ViewerParrotsMod.LOGGER.debug("unload viewerId={} entity={}", viewerId.get(), parrot.getUuid());
		});
	}

	private static void onParrotDeath(ViewerParrotEntity parrot) {
		ServerContext.current().ifPresent(context -> {
			Optional<ViewerId> viewerId = context.loadedIndex().findViewerId(parrot.getUuid());
			if (viewerId.isEmpty()) {
				return;
			}
			Optional<ViewerParrotRecord> record = context.registry().find(viewerId.get());
			if (record.isEmpty()) {
				return;
			}

			ViewerParrotRecord dead = record.get().asDead(context.clock().now());
			context.loadedIndex().unbind(viewerId.get());
			context.registry().replace(dead);

			// Смерть — факт, который нельзя отменить из-за ошибки I/O, поэтому она
			// применяется в памяти сразу, а журнал пишется следом (раздел 9.6 ТЗ).
			JournalEntryDto entry = new JournalEntryDto(
					JournalEntryDto.CURRENT_SCHEMA_VERSION,
					context.repository().nextJournalSequence(),
					UUID.randomUUID(),
					JournalEntryDto.Phase.COMMIT,
					dead.viewerId().value(),
					dead.incarnationId(),
					dead.generation(),
					DtoMapper.fromDomain(dead),
					null,
					null);

			context.writer().submit(() -> {
				context.repository().appendJournal(entry);
				return null;
			}).exceptionally(error -> {
				context.degrade("смерть не записана: " + error.getMessage());
				ViewerParrotsMod.LOGGER.error("Смерть {} не записана в журнал", dead.viewerId(), error);
				return null;
			});

			ViewerParrotsMod.LOGGER.info("death viewerId={} generation={} status={}",
					dead.viewerId(), dead.generation(), PhysicalStatus.DEAD);
		});
	}

	private static void onEndTick(MinecraftServer server) {
		ServerContext.current().ifPresent(CheckpointScheduler::tick);
	}
}
