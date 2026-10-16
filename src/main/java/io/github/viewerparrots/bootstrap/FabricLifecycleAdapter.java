package io.github.viewerparrots.bootstrap;

import io.github.viewerparrots.diagnostics.AuditLog;
import io.github.viewerparrots.api.event.ParrotEvent;
import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.domain.LifecycleState;
import io.github.viewerparrots.domain.PhysicalStatus;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.entity.ParrotEntityController;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import io.github.viewerparrots.entity.VanillaVariantMapper;
import io.github.viewerparrots.diagnostics.ErrorCode;
import net.minecraft.entity.Entity;
import io.github.viewerparrots.lifecycle.DeathPolicy;
import io.github.viewerparrots.lifecycle.GenerationValidator;
import io.github.viewerparrots.storage.DtoMapper;
import io.github.viewerparrots.storage.dto.JournalEntryDto;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import io.github.viewerparrots.twitch.TwitchChatBridge;

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
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			TwitchChatBridge.onWorldClosed(server);
			ServerContext.stop(server);
		});
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
		long started = AuditLog.started();
		ServerContext context = ServerContext.start(server);
		TwitchChatBridge.onWorldOpened(context);
		AuditLog.info("server_started", null, context.storageHealth().name(), null, started,
				java.util.Map.of("records", context.registry().size(), "limit", context.config().maxActiveParrots(), "deathMode", context.config().deathMode()), null);

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
		long started = AuditLog.started();
		Optional<ServerContext> maybeContext = ServerContext.current();
		if (maybeContext.isEmpty()) {
			return;
		}
		ServerContext context = maybeContext.get();
		if (context.server() != parrot.getServer()) return;

		if (parrot.isAwaitingAuthorization()) {
			// Сущность только что создана контроллером: запись попадёт в реестр после
			// durable COMMIT, и привяжет её сам сервис. Валидировать её сейчас нельзя —
			// в реестре её ещё нет, и проверка отвергла бы только что созданного попугая.
			return;
		}

		GenerationValidator.Decision decision = GenerationValidator.decide(
				parrot.marker(), parrot.getUuid(), context.registry(), context.isRegistryReady());

		switch (decision.verdict()) {
			case DEFER -> {
				// Реестр не готов: AI выключен, identity не меняется, сущность не удаляется.
				parrot.setAiDisabled(true);
				context.deferEntityValidation(parrot.getUuid(), parrot.getWorld().getRegistryKey());
				AuditLog.debug("entity_load", entityContext(parrot, "LOAD"), "DEFERRED", "REGISTRY_NOT_READY", started, java.util.Map.of("detail", decision.reason()), null);
			}
			case DISCARD -> {
				parrot.setAiDisabled(true);
				AuditLog.warn("entity_load", entityContext(parrot, "LOAD"), "DISCARDED", "ENTITY_IDENTITY_REJECTED", started, java.util.Map.of("detail", decision.reason()), null);
				context.parrotService().controller().discard(parrot);
			}
			case BIND -> {
				context.forgetDeferredEntity(parrot.getUuid(), parrot.getWorld().getRegistryKey());
				ViewerId viewerId = parrot.marker().orElseThrow().viewerId();
				ViewerParrotRecord record = context.registry().find(viewerId).orElseThrow();
				LifecycleState before = context.lifecycle(viewerId);
				Optional<ViewerParrotEntity> existing = context.loadedIndex().find(viewerId);
				if (existing.isPresent() && existing.get() != parrot && !existing.get().isRemoved()) {
					parrot.setAiDisabled(true);
					context.parrotService().controller().discard(parrot);
					return;
				}
				context.loadedIndex().rebind(viewerId, parrot);
				context.markLoaded(viewerId);
				var runtime = context.runtime(viewerId);
				parrot.setVariant(VanillaVariantMapper.toVanilla(record.variant()));
				context.parrotService().controller().applyName(parrot, record);
				context.registry().replace(record.withLocation(ParrotEntityController.locationOf(parrot), context.clock().now()));
				context.shoulderService().onPhysicalLoad(parrot);
				context.parrotService().restorePresentOnBind(viewerId);
				parrot.setForcedDancing(runtime.behavior() == BehaviorState.DANCING);
				parrot.setAiDisabled(false);
				publish(context, record, ParrotEvent.Type.PARROT_BOUND, before.name(), LifecycleState.LOADED.name(), null);
				AuditLog.debug("entity_load", AuditLog.Context.of(null, record, "LOAD"), "BOUND", null, started, java.util.Map.of(), null);
			}
		}
	}

	private static void onParrotUnload(ViewerParrotEntity parrot) {
		long started = AuditLog.started();
		ServerContext.current().ifPresent(context -> {
			if (context.server() != parrot.getServer()) return;
			context.forgetDeferredEntity(parrot.getUuid(), parrot.getWorld().getRegistryKey());
			Optional<ViewerId> viewerId = context.loadedIndex().findViewerId(parrot.getUuid());
			if (viewerId.isEmpty()) {
				return;
			}
			// A delayed unload for a transferred Java object must not unbind its replacement.
			if (context.loadedIndex().find(viewerId.get()).orElse(null) != parrot) return;
			if (context.shoulderService().isStored(parrot)) {
				context.loadedIndex().unbind(viewerId.get());
				context.markLoaded(viewerId.get());
				return;
			}
			Optional<ViewerParrotRecord> original = context.registry().find(viewerId.get());
			// Подсказка о позиции обновляется в памяти; она не влияет на корректность
			// generation, поэтому её потеря при аварии допустима (раздел 8.2 ТЗ).
			context.registry().find(viewerId.get()).ifPresent(record -> {
				ViewerParrotRecord updated = record.withLocation(
						ParrotEntityController.locationOf(parrot), context.clock().now());
				context.registry().replace(updated);
			});
			context.loadedIndex().unbind(viewerId.get());
			context.markUnloaded(viewerId.get());
			if (parrot.getRemovalReason() != Entity.RemovalReason.CHANGED_DIMENSION) {
				context.forgetRuntime(viewerId.get());
			}
			original.ifPresent(record -> publish(context, record, ParrotEvent.Type.PARROT_UNLOADED,
					LifecycleState.LOADED.name(), LifecycleState.UNLOADED.name(),
					parrot.getRemovalReason() == null ? "unload" : parrot.getRemovalReason().name()));
			AuditLog.debug("entity_unload", entityContext(parrot, "UNLOAD"), "UNLOADED", parrot.getRemovalReason() == null ? "UNLOAD" : parrot.getRemovalReason().name(), started, java.util.Map.of(), null);
		});
	}

	/** Registry readiness revisits only UUIDs deferred during startup; no world scan runs in a tick. */
	static void revalidateDeferred(ServerContext context) {
		for (var entry : context.takeDeferredEntities().entrySet()) {
			if (ServerContext.current().orElse(null) != context || !context.isRegistryReady()) return;
			var world = context.server().getWorld(entry.getValue());
			if (world != null && world.getEntity(entry.getKey()) instanceof ViewerParrotEntity parrot) onParrotLoad(parrot);
		}
	}

	private static void onParrotDeath(ViewerParrotEntity parrot) {
		long started = AuditLog.started();
		ServerContext.current().ifPresent(context -> {
			if (context.server() != parrot.getServer()) return;
			Optional<ViewerId> viewerId = context.loadedIndex().findViewerId(parrot.getUuid());
			if (viewerId.isEmpty()) {
				return;
			}
			if (context.loadedIndex().find(viewerId.get()).orElse(null) != parrot) return;
			Optional<ViewerParrotRecord> record = context.registry().find(viewerId.get());
			if (record.isEmpty()) {
				return;
			}

			ViewerParrotRecord dead = record.get().asDead(context.clock().now());
			UUID journalCommandId = UUID.randomUUID();
			AuditLog.Context identity = new AuditLog.Context(journalCommandId, dead.viewerId().value(), dead.incarnationId(), dead.generation(), parrot.getUuid(), "DEATH");
			context.commandService().cancelRuntime(viewerId.get(), ErrorCode.INVALID_STATE);
			context.loadedIndex().unbind(viewerId.get());
			context.forgetRuntime(viewerId.get());
			context.registry().replace(dead);

			// Смерть — факт, который нельзя отменить из-за ошибки I/O, поэтому она
			// применяется в памяти сразу, а журнал пишется следом (раздел 9.6 ТЗ).
			context.writer().submit(() -> {
				JournalEntryDto entry = new JournalEntryDto(JournalEntryDto.CURRENT_SCHEMA_VERSION,
						context.repository().nextJournalSequence(), journalCommandId, JournalEntryDto.Phase.COMMIT,
						dead.viewerId().value(), dead.incarnationId(), dead.generation(),
						DtoMapper.fromDomain(dead), null, null);
				context.repository().appendJournal(entry);
				return entry.sequence();
			}).thenAccept(sequence -> {
				context.registry().journalApplied(sequence);
				AuditLog.info("parrot_died", identity, "DEAD", "DEATH", started, java.util.Map.of("journalSequence", sequence), null);
				publish(context, dead, ParrotEvent.Type.PARROT_DIED, LifecycleState.LOADED.name(), LifecycleState.DEAD.name(), "death");
			}).exceptionally(error -> {
				context.degrade("смерть не записана: " + error.getMessage());
				AuditLog.error("parrot_died", identity, "FAILED", "STORAGE_UNAVAILABLE", started, java.util.Map.of(), error);
				return null;
			});

		});
	}

	/** Capture the entity's marker, including stale generations; never substitute a successor record. */
	private static AuditLog.Context entityContext(ViewerParrotEntity parrot, String action) {
		return parrot.marker().map(marker -> new AuditLog.Context(null, marker.viewerId().value(), marker.incarnationId(), marker.generation(), parrot.getUuid(), action))
				.orElseGet(() -> new AuditLog.Context(null, null, null, null, parrot.getUuid(), action));
	}

	private static void onEndTick(MinecraftServer server) {
		ServerContext.current().ifPresent(context -> {
			if (context.server() != server) return;
			context.monitorTarget();
			context.refreshNames();
			context.shoulderService().tick();
			context.parrotService().tick();
			context.recoveryService().tick();
			context.behaviorScheduler().tick();
			CheckpointScheduler.tick(context);
			TwitchChatBridge.tick(context);
			context.rouletteService().tick();
		});
	}

	private static void publish(ServerContext context, ViewerParrotRecord record, ParrotEvent.Type type,
			String oldState, String newState, String reason) {
		context.events().publish(ParrotEvent.of(type, record.viewerId(), record.incarnationId(), record.generation(),
				context.clock().now(), context.clock().currentTick(), null, oldState, newState, reason));
	}
}
