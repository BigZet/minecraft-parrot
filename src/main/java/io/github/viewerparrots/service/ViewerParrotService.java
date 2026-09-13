package io.github.viewerparrots.service;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.domain.DisplayName;
import io.github.viewerparrots.domain.LifecycleState;
import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.domain.ParrotVariant;
import io.github.viewerparrots.domain.PhysicalStatus;
import io.github.viewerparrots.domain.Tombstone;
import io.github.viewerparrots.domain.Viewer;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.entity.ParrotEntityController;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import io.github.viewerparrots.lifecycle.EntityRecoveryService;
import io.github.viewerparrots.storage.DtoMapper;
import io.github.viewerparrots.storage.dto.JournalEntryDto;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Операции жизненного цикла регистраций (раздел 9 ТЗ).
 *
 * <p>Все методы вызываются на server thread. Файловый I/O уходит на writer и возвращается
 * обратно в игровой поток; перед применением результата состояние проверяется заново,
 * потому что за время записи мир мог измениться.
 *
 * <p>Порядок durable-операции неизменен: резерв слота → PREPARE → spawn → COMMIT →
 * включение AI. При любом отказе после PREPARE записывается ABORT, а подготовленная
 * сущность удаляется.
 */
public final class ViewerParrotService {
	private final ServerContext context;
	private final ParrotEntityController controller;
	private final TraitSelector traitSelector;

	public ViewerParrotService(ServerContext context) {
		this.context = context;
		this.controller = new ParrotEntityController(context.config());
		this.traitSelector = new TraitSelector(new Random());
	}

	public ParrotEntityController controller() {
		return this.controller;
	}

	// --- create --------------------------------------------------------------

	public CompletableFuture<CommandResult> create(UUID commandId, ViewerId viewerId, DisplayName displayName) {
		if (!this.context.allowsMutations()) {
			return completed(CommandResult.rejected(commandId, ErrorCode.STORAGE_UNAVAILABLE));
		}
		if (this.context.registry().contains(viewerId)) {
			return completed(CommandResult.rejected(commandId, ErrorCode.PARROT_ALREADY_EXISTS));
		}

		Optional<ServerPlayerEntity> target = this.context.streamerService().onlineTarget();
		if (this.context.streamerService().configuredUuid().isEmpty()) {
			return completed(CommandResult.rejected(commandId, ErrorCode.TARGET_NOT_CONFIGURED));
		}
		if (target.isEmpty()) {
			return completed(CommandResult.rejected(commandId, ErrorCode.TARGET_UNAVAILABLE));
		}

		int maxActive = this.context.config().maxActiveParrots();
		if (!this.context.registry().reserveSlot(maxActive)) {
			return completed(CommandResult.rejected(commandId, ErrorCode.MAX_ACTIVE_PARROTS_REACHED,
					Map.of("limit", Integer.toString(maxActive))));
		}

		ServerWorld world = target.get().getServerWorld();
		Optional<Vec3d> position = this.context.safePositionFinder().findNear(world, target.get().getPos());
		if (position.isEmpty()) {
			this.context.registry().releaseReservation();
			return completed(CommandResult.rejected(commandId, ErrorCode.NO_SAFE_POSITION));
		}

		Instant now = this.context.clock().now();
		UUID incarnationId = UUID.randomUUID();
		UUID entityUuid = UUID.randomUUID();
		ParrotVariant variant = this.traitSelector.selectVariant();
		ParrotTrait trait = this.traitSelector.selectTrait(this.context.config().behavior().traitWeights());

		Viewer viewer = new Viewer(viewerId, displayName, now);
		ViewerParrotRecord prepared = ViewerParrotRecord.created(viewer, incarnationId, entityUuid,
				variant, trait, null, now);
		UUID operationId = UUID.randomUUID();

		return writeJournal(JournalEntryDto.Phase.PREPARE, operationId, prepared)
				.thenCompose(ignored -> finishCreate(commandId, operationId, prepared, entityUuid))
				.exceptionally(error -> {
					this.context.registry().releaseReservation();
					this.context.degrade("PREPARE не записан: " + error.getMessage());
					ViewerParrotsMod.LOGGER.error("PREPARE для {} не записан", viewerId, error);
					return CommandResult.failed(commandId, ErrorCode.STORAGE_UNAVAILABLE, error.getMessage());
				});
	}

	private CompletableFuture<CommandResult> finishCreate(UUID commandId, UUID operationId,
			ViewerParrotRecord prepared, UUID entityUuid) {
		// Мы снова на server thread. Цель и позиция проверяются повторно: за время
		// записи стример мог выйти или переместиться (раздел 9.1 ТЗ).
		Optional<ServerPlayerEntity> target = this.context.streamerService().onlineTarget();
		if (target.isEmpty()) {
			return abort(commandId, operationId, prepared, ErrorCode.TARGET_UNAVAILABLE);
		}

		ServerWorld world = target.get().getServerWorld();
		Optional<Vec3d> position = this.context.safePositionFinder().findNear(world, target.get().getPos());
		if (position.isEmpty()) {
			return abort(commandId, operationId, prepared, ErrorCode.NO_SAFE_POSITION);
		}

		Optional<ViewerParrotEntity> spawned = this.controller.spawnPrepared(
				world, position.get(), target.get().getYaw(), prepared, entityUuid);
		if (spawned.isEmpty()) {
			return abort(commandId, operationId, prepared, ErrorCode.NO_SAFE_POSITION);
		}

		ViewerParrotEntity parrot = spawned.get();
		ViewerParrotRecord committed = prepared.withLocation(
				ParrotEntityController.locationOf(parrot), this.context.clock().now());

		return writeJournal(JournalEntryDto.Phase.COMMIT, operationId, committed)
				.thenApply(ignored -> {
					this.context.registry().put(committed);
					this.context.registry().releaseReservation();
					this.context.loadedIndex().bind(committed.viewerId(), parrot);
					this.controller.authorize(parrot);

					ViewerParrotsMod.LOGGER.info("create viewerId={} incarnation={} generation={} entity={}",
							committed.viewerId(), committed.incarnationId(),
							committed.generation(), entityUuid);
					return CommandResult.succeeded(commandId, "viewerparrots.create.ok",
							CommandResult.details("viewerId", committed.viewerId().value(),
									"variant", committed.variant().name(),
									"trait", committed.trait().name()));
				})
				.exceptionally(error -> {
					// COMMIT не записан: подготовленную сущность удаляем и уходим в degraded.
					this.controller.discard(parrot);
					this.context.registry().releaseReservation();
					this.context.degrade("COMMIT не записан: " + error.getMessage());
					ViewerParrotsMod.LOGGER.error("COMMIT для {} не записан", prepared.viewerId(), error);
					return CommandResult.failed(commandId, ErrorCode.STORAGE_UNAVAILABLE, error.getMessage());
				});
	}

	private CompletableFuture<CommandResult> abort(UUID commandId, UUID operationId,
			ViewerParrotRecord prepared, ErrorCode reason) {
		return writeJournal(JournalEntryDto.Phase.ABORT, operationId, null, prepared)
				.handle((ignored, error) -> {
					this.context.registry().releaseReservation();
					if (error != null) {
						ViewerParrotsMod.LOGGER.error("ABORT для {} не записан", prepared.viewerId(), error);
					}
					return CommandResult.rejected(commandId, reason);
				});
	}

	// --- remove --------------------------------------------------------------

	public CompletableFuture<CommandResult> remove(UUID commandId, ViewerId viewerId) {
		if (!this.context.allowsMutations()) {
			return completed(CommandResult.rejected(commandId, ErrorCode.STORAGE_UNAVAILABLE));
		}

		Optional<ViewerParrotRecord> existing = this.context.registry().find(viewerId);
		if (existing.isEmpty()) {
			// Повторный remove для уже удалённой записи успешен как no-op (раздел 9.5 ТЗ).
			boolean hadTombstone = !this.context.registry().tombstonesFor(viewerId).isEmpty();
			return completed(hadTombstone
					? CommandResult.rejected(commandId, ErrorCode.ALREADY_REMOVED)
					: CommandResult.rejected(commandId, ErrorCode.VIEWER_NOT_FOUND));
		}

		ViewerParrotRecord record = existing.get();
		Tombstone tombstone = Tombstone.of(record, this.context.clock().now());
		UUID operationId = UUID.randomUUID();

		// Надгробие фиксируется ДО удаления сущности: если сервер упадёт между шагами,
		// валидатор отвергнет выгруженную копию по надгробию (раздел 8.2 ТЗ).
		return writeTombstone(operationId, tombstone)
				.thenApply(ignored -> {
					this.context.registry().remove(viewerId, tombstone.removedAt());
					this.context.cooldowns().clear(viewerId);
					this.context.loadedIndex().find(viewerId).ifPresent(this.controller::discard);
					this.context.loadedIndex().unbind(viewerId);

					ViewerParrotsMod.LOGGER.info("remove viewerId={} incarnation={} generation={}",
							viewerId, record.incarnationId(), record.generation());
					return CommandResult.succeeded(commandId, "viewerparrots.remove.ok",
							CommandResult.details("viewerId", viewerId.value()));
				})
				.exceptionally(error -> {
					this.context.degrade("tombstone не записан: " + error.getMessage());
					ViewerParrotsMod.LOGGER.error("Надгробие для {} не записано", viewerId, error);
					return CommandResult.failed(commandId, ErrorCode.STORAGE_UNAVAILABLE, error.getMessage());
				});
	}

	// --- rename и trait -------------------------------------------------------

	public CompletableFuture<CommandResult> rename(UUID commandId, ViewerId viewerId, DisplayName newName) {
		return mutate(commandId, viewerId, record -> record.withDisplayName(newName, this.context.clock().now()),
				"viewerparrots.rename.ok", updated ->
						this.context.loadedIndex().find(viewerId)
								.ifPresent(parrot -> this.controller.applyName(parrot, updated)));
	}

	public CompletableFuture<CommandResult> setTrait(UUID commandId, ViewerId viewerId, ParrotTrait trait) {
		return mutate(commandId, viewerId, record -> record.withTrait(trait, this.context.clock().now()),
				"viewerparrots.trait.ok", updated -> {
				});
	}

	private CompletableFuture<CommandResult> mutate(UUID commandId, ViewerId viewerId,
			java.util.function.UnaryOperator<ViewerParrotRecord> change, String messageKey,
			java.util.function.Consumer<ViewerParrotRecord> afterCommit) {
		if (!this.context.allowsMutations()) {
			return completed(CommandResult.rejected(commandId, ErrorCode.STORAGE_UNAVAILABLE));
		}
		Optional<ViewerParrotRecord> existing = this.context.registry().find(viewerId);
		if (existing.isEmpty()) {
			return completed(CommandResult.rejected(commandId, ErrorCode.VIEWER_NOT_FOUND));
		}

		ViewerParrotRecord updated = change.apply(existing.get());
		UUID operationId = UUID.randomUUID();

		return writeJournal(JournalEntryDto.Phase.COMMIT, operationId, updated)
				.thenApply(ignored -> {
					// Запись могла измениться, пока шла запись на диск.
					Optional<ViewerParrotRecord> stillThere = this.context.registry().find(viewerId);
					if (stillThere.isEmpty() || stillThere.get().revision() >= updated.revision()) {
						return CommandResult.rejected(commandId, ErrorCode.INVALID_STATE);
					}
					this.context.registry().replace(updated);
					afterCommit.accept(updated);
					return CommandResult.succeeded(commandId, messageKey,
							CommandResult.details("viewerId", viewerId.value()));
				})
				.exceptionally(error -> {
					this.context.degrade("операция не записана: " + error.getMessage());
					return CommandResult.failed(commandId, ErrorCode.STORAGE_UNAVAILABLE, error.getMessage());
				});
	}

	// --- respawn ---------------------------------------------------------------

	public CompletableFuture<CommandResult> respawn(UUID commandId, ViewerId viewerId) {
		if (!this.context.allowsMutations()) {
			return completed(CommandResult.rejected(commandId, ErrorCode.STORAGE_UNAVAILABLE));
		}
		Optional<ViewerParrotRecord> existing = this.context.registry().find(viewerId);
		if (existing.isEmpty()) {
			return completed(CommandResult.rejected(commandId, ErrorCode.VIEWER_NOT_FOUND));
		}

		ViewerParrotRecord record = existing.get();

		if (this.context.loadedIndex().isLoaded(viewerId)) {
			// Замена загруженного экземпляра запрещена: сначала он должен исчезнуть.
			return completed(CommandResult.rejected(commandId, ErrorCode.ENTITY_ALREADY_LOADED));
		}
		if (record.physicalStatus() == PhysicalStatus.DEAD
				&& !this.context.config().deathMode().allowsRespawnAfterDeath()) {
			return completed(CommandResult.rejected(commandId, ErrorCode.RESPAWN_DISABLED));
		}
		if (record.physicalStatus() == PhysicalStatus.PRESENT) {
			// UNRESOLVED и UNLOADED сначала должны завершить поиск (раздел 9.4 ТЗ).
			// Поиск запускается здесь же, иначе запись навсегда осталась бы PRESENT
			// и respawn стал бы недостижим.
			this.context.recoveryService().start(viewerId);
			return completed(CommandResult.rejected(commandId, ErrorCode.ENTITY_UNRESOLVED));
		}

		Optional<ServerPlayerEntity> target = this.context.streamerService().onlineTarget();
		if (target.isEmpty()) {
			return completed(CommandResult.rejected(commandId, ErrorCode.TARGET_UNAVAILABLE));
		}

		// Замена MISSING использует существующий слот; respawn DEAD требует свободного.
		boolean needsSlot = !record.occupiesCapacitySlot();
		if (needsSlot && !this.context.registry().reserveSlot(this.context.config().maxActiveParrots())) {
			return completed(CommandResult.rejected(commandId, ErrorCode.MAX_ACTIVE_PARROTS_REACHED));
		}

		UUID newEntityUuid = UUID.randomUUID();
		ViewerParrotRecord prepared = record.asRespawned(newEntityUuid, this.context.clock().now());
		UUID operationId = UUID.randomUUID();

		return writeJournal(JournalEntryDto.Phase.PREPARE, operationId, prepared)
				.thenCompose(ignored -> finishRespawn(commandId, operationId, prepared, newEntityUuid, needsSlot))
				.exceptionally(error -> {
					if (needsSlot) {
						this.context.registry().releaseReservation();
					}
					this.context.degrade("PREPARE respawn не записан: " + error.getMessage());
					return CommandResult.failed(commandId, ErrorCode.STORAGE_UNAVAILABLE, error.getMessage());
				});
	}

	private CompletableFuture<CommandResult> finishRespawn(UUID commandId, UUID operationId,
			ViewerParrotRecord prepared, UUID newEntityUuid, boolean reservedSlot) {
		Optional<ServerPlayerEntity> target = this.context.streamerService().onlineTarget();
		if (target.isEmpty()) {
			return abortRespawn(commandId, operationId, prepared, ErrorCode.TARGET_UNAVAILABLE, reservedSlot);
		}

		ServerWorld world = target.get().getServerWorld();
		Optional<Vec3d> position = this.context.safePositionFinder().findNear(world, target.get().getPos());
		if (position.isEmpty()) {
			return abortRespawn(commandId, operationId, prepared, ErrorCode.NO_SAFE_POSITION, reservedSlot);
		}

		Optional<ViewerParrotEntity> spawned = this.controller.spawnPrepared(
				world, position.get(), target.get().getYaw(), prepared, newEntityUuid);
		if (spawned.isEmpty()) {
			return abortRespawn(commandId, operationId, prepared, ErrorCode.NO_SAFE_POSITION, reservedSlot);
		}

		ViewerParrotEntity parrot = spawned.get();
		ViewerParrotRecord committed = prepared.withLocation(
				ParrotEntityController.locationOf(parrot), this.context.clock().now());

		return writeJournal(JournalEntryDto.Phase.COMMIT, operationId, committed)
				.thenApply(ignored -> {
					this.context.registry().replace(committed);
					if (reservedSlot) {
						this.context.registry().releaseReservation();
					}
					this.context.loadedIndex().bind(committed.viewerId(), parrot);
					this.controller.authorize(parrot);

					ViewerParrotsMod.LOGGER.info("respawn viewerId={} generation={} entity={}",
							committed.viewerId(), committed.generation(), newEntityUuid);
					return CommandResult.succeeded(commandId, "viewerparrots.respawn.ok",
							CommandResult.details("viewerId", committed.viewerId().value(),
									"generation", Long.toString(committed.generation())));
				})
				.exceptionally(error -> {
					this.controller.discard(parrot);
					if (reservedSlot) {
						this.context.registry().releaseReservation();
					}
					this.context.degrade("COMMIT respawn не записан: " + error.getMessage());
					return CommandResult.failed(commandId, ErrorCode.STORAGE_UNAVAILABLE, error.getMessage());
				});
	}

	private CompletableFuture<CommandResult> abortRespawn(UUID commandId, UUID operationId,
			ViewerParrotRecord prepared, ErrorCode reason, boolean reservedSlot) {
		return writeJournal(JournalEntryDto.Phase.ABORT, operationId, null, prepared)
				.handle((ignored, error) -> {
					if (reservedSlot) {
						this.context.registry().releaseReservation();
					}
					return CommandResult.rejected(commandId, reason);
				});
	}

	// --- reconcile -------------------------------------------------------------

	/**
	 * Ограниченная проверка без создания сущностей (раздел 9.3 ТЗ): сначала loaded index
	 * по всем измерениям, затем поиск по ожидаемому UUID в уже загруженных мирах.
	 */
	public CommandResult reconcile(UUID commandId, ViewerId viewerId) {
		Optional<ViewerParrotRecord> existing = this.context.registry().find(viewerId);
		if (existing.isEmpty()) {
			return CommandResult.rejected(commandId, ErrorCode.VIEWER_NOT_FOUND);
		}
		ViewerParrotRecord record = existing.get();

		if (this.context.loadedIndex().isLoaded(viewerId)) {
			return CommandResult.succeeded(commandId, "viewerparrots.reconcile.loaded",
					CommandResult.details("viewerId", viewerId.value(), "state", LifecycleState.LOADED.name()));
		}

		Optional<UUID> expected = record.expectedEntityUuid();
		if (expected.isEmpty()) {
			return CommandResult.succeeded(commandId, "viewerparrots.reconcile.no_entity",
					CommandResult.details("viewerId", viewerId.value(), "status", record.physicalStatus().name()));
		}

		for (ServerWorld world : this.context.server().getWorlds()) {
			if (world.getEntity(expected.get()) instanceof ViewerParrotEntity found) {
				this.context.loadedIndex().bind(viewerId, found);
				return CommandResult.succeeded(commandId, "viewerparrots.reconcile.bound",
						CommandResult.details("viewerId", viewerId.value(),
								"dimension", world.getRegistryKey().getValue().toString()));
			}
		}

		// В загруженных мирах сущности нет: остаётся ограниченный поиск с временной
		// загрузкой одного чанка последней известной позиции.
		EntityRecoveryService.StartResult started = this.context.recoveryService().start(viewerId);
		return switch (started) {
			case FOUND_IMMEDIATELY -> CommandResult.succeeded(commandId, "viewerparrots.reconcile.bound",
					CommandResult.details("viewerId", viewerId.value(), "dimension", "loaded"));
			case STARTED, ALREADY_RUNNING -> CommandResult.succeeded(commandId,
					"viewerparrots.reconcile.searching",
					CommandResult.details("viewerId", viewerId.value(),
							"timeoutTicks", Integer.toString(this.context.config().recovery().timeoutTicks())));
			case BUSY -> CommandResult.rejected(commandId, ErrorCode.SERVER_BUSY);
			// Поиск уже завершён отрицательно и запись переведена в MISSING.
			case NO_KNOWN_LOCATION, NOTHING_TO_FIND -> CommandResult.succeeded(commandId,
					"viewerparrots.reconcile.not_found",
					CommandResult.details("viewerId", viewerId.value(),
							"reason", EntityRecoveryService.NOT_FOUND_REASON));
		};
	}

	// --- журнал ----------------------------------------------------------------

	private CompletableFuture<Void> writeJournal(JournalEntryDto.Phase phase, UUID operationId,
			ViewerParrotRecord record) {
		return writeJournal(phase, operationId, record, record);
	}

	private CompletableFuture<Void> writeJournal(JournalEntryDto.Phase phase, UUID operationId,
			ViewerParrotRecord payload, ViewerParrotRecord identity) {
		JournalEntryDto entry = new JournalEntryDto(
				JournalEntryDto.CURRENT_SCHEMA_VERSION,
				this.context.repository().nextJournalSequence(),
				operationId,
				phase,
				identity.viewerId().value(),
				identity.incarnationId(),
				identity.generation(),
				payload == null ? null : DtoMapper.fromDomain(payload),
				null,
				null);

		return this.context.writer().submit(() -> {
			this.context.repository().appendJournal(entry);
			return null;
		});
	}

	private CompletableFuture<Void> writeTombstone(UUID operationId, Tombstone tombstone) {
		JournalEntryDto entry = new JournalEntryDto(
				JournalEntryDto.CURRENT_SCHEMA_VERSION,
				this.context.repository().nextJournalSequence(),
				operationId,
				JournalEntryDto.Phase.COMMIT,
				tombstone.viewerId().value(),
				tombstone.incarnationId(),
				tombstone.lastGeneration(),
				null,
				DtoMapper.fromDomain(tombstone),
				null);

		return this.context.writer().submit(() -> {
			this.context.repository().appendJournal(entry);
			return null;
		});
	}

	private static CompletableFuture<CommandResult> completed(CommandResult result) {
		return CompletableFuture.completedFuture(result);
	}
}
