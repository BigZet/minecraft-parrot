package io.github.viewerparrots.service;

import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.api.CommandStatus;
import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.api.event.ParrotEvent;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.diagnostics.AuditLog;
import io.github.viewerparrots.domain.DesiredBehavior;
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
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import java.time.Instant;
import java.util.Map;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.function.Supplier;
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
	private ParrotEntityController controller;
	private final TraitSelector traitSelector;
	private final Set<ViewerId> operations = new HashSet<>();
	private final Map<UUID, CachedOperation> operationResults = new LinkedHashMap<>();
	private final Map<UUID, AuditAttempt> activeAudits = new LinkedHashMap<>();
	private final ArrayDeque<Runnable> spawnContinuations = new ArrayDeque<>();
	private boolean accepting = true;
	private record CachedOperation(long expiresAtTick, CompletableFuture<CommandResult> result, AuditAttempt audit) { }
	private static final class AuditAttempt {
		final long startedNanos;
		AuditLog.Context identity;
		AuditAttempt(long startedNanos, AuditLog.Context identity) {
			this.startedNanos = startedNanos;
			this.identity = identity;
		}
	}

	/** Immutable diagnostic metadata for the facade, including an identity removed by its completion listener. */
	public Optional<AuditLog.Context> diagnosticContext(UUID commandId) {
		AuditAttempt active = this.activeAudits.get(commandId);
		CachedOperation cached = this.operationResults.get(commandId);
		return Optional.ofNullable(active != null ? active.identity : cached == null ? null : cached.audit().identity);
	}

	public ViewerParrotService(ServerContext context) {
		this.context = context;
		this.controller = new ParrotEntityController(context.config());
		this.traitSelector = new TraitSelector(new Random());
	}

	public ParrotEntityController controller() {
		return this.controller;
	}

	public void onConfigReload() { this.controller = new ParrotEntityController(this.context.config()); }
	public boolean operationPending(ViewerId viewerId) { return this.operations.contains(viewerId); }
	public int operationCount() { return this.operations.size(); }
	public void beginShutdown() { this.accepting = false; }

	public void tick() {
		int count = this.spawnContinuations.size();
		for (int i = 0; i < count; i++) this.spawnContinuations.removeFirst().run();
		if (this.accepting && this.context.allowsMutations()) {
			for (ViewerId id : this.context.loadedIndex().loadedViewerIds()) {
				if (!operationPending(id) && this.context.registry().find(id)
						.filter(record -> record.physicalStatus() == PhysicalStatus.MISSING).isPresent()) restorePresentOnBind(id);
			}
		}
	}

	private CompletableFuture<CommandResult> deferSpawn(Supplier<CompletableFuture<CommandResult>> work) {
		CompletableFuture<CommandResult> result = new CompletableFuture<>();
		this.spawnContinuations.addLast(() -> {
			try { work.get().whenComplete((value, error) -> {
				if (error == null) result.complete(value); else result.completeExceptionally(error);
			}); } catch (RuntimeException error) { result.completeExceptionally(error); }
		});
		return result;
	}

	private CompletableFuture<CommandResult> authorizedMutation(UUID commandId, ActorContext actor,
			ParrotAction action, ViewerId viewerId, Supplier<CompletableFuture<CommandResult>> work) {
		AuditAttempt audit = new AuditAttempt(AuditLog.started(), diagnosticIdentity(commandId, viewerId, action.name()));
		if (!this.context.permissions().isAllowed(actor, action)) {
			CommandResult denied = CommandResult.rejected(commandId, ErrorCode.PERMISSION_DENIED);
			auditOutcome(audit, denied, null);
			return completed(denied);
		}
        var access = io.github.viewerparrots.settings.CommandAccess.evaluate(actor, action, viewerId);
        if (!access.allowed()) return completed(CommandResult.rejected(commandId, io.github.viewerparrots.settings.CommandAccess.error(access)));
        boolean newCreate = action == ParrotAction.CREATE && !this.operationResults.containsKey(commandId);
        if (newCreate) {
            long remaining = context.cooldowns().remainingTicks(viewerId, action);
            if (remaining > 0) return completed(CommandResult.retryLater(commandId, ErrorCode.COMMAND_ON_COOLDOWN, remaining));
            var budget = context.rateLimiter().check(action);
            if (!budget.allowed()) return completed(CommandResult.retryLater(commandId, ErrorCode.GLOBAL_RATE_LIMITED, budget.retryAfterTicks()));
        }
        var result = guarded(commandId, viewerId, audit, work);
        if (newCreate && !(result.isDone() && !result.isCompletedExceptionally()
                && result.getNow(null).status() == CommandStatus.REJECTED)) {
            context.cooldowns().consume(viewerId, action, access.cooldownTicks());
            context.rateLimiter().tryAcquire(action);
        }
        return result;
	}

	private CompletableFuture<CommandResult> guarded(UUID commandId, ViewerId viewerId,
			String action, Supplier<CompletableFuture<CommandResult>> work) {
		return guarded(commandId, viewerId,
				new AuditAttempt(AuditLog.started(), diagnosticIdentity(commandId, viewerId, action)), work);
	}

	private CompletableFuture<CommandResult> guarded(UUID commandId, ViewerId viewerId, AuditAttempt audit,
			Supplier<CompletableFuture<CommandResult>> work) {
		long now = this.context.clock().currentTick();
		this.operationResults.entrySet().removeIf(entry -> entry.getValue().expiresAtTick() <= now
				&& entry.getValue().result().isDone());
		CachedOperation cached = this.operationResults.get(commandId);
		if (cached != null) {
			AuditLog.debug("mutation_cached", cached.audit().identity, "CACHED", null, audit.startedNanos, Map.of(), null);
			return cached.result();
		}
		if (this.operationResults.size() >= 1024) {
			var oldest = this.operationResults.entrySet().iterator();
			while (oldest.hasNext()) {
				if (oldest.next().getValue().result().isDone()) { oldest.remove(); break; }
			}
		}
		if (!this.accepting || this.operations.size() >= this.context.config().limits().queueCapacity()
				|| this.context.writer().queueDepth() >= this.context.config().limits().queueCapacity()
				|| this.operationResults.size() >= 1024) {
			CommandResult busy = CommandResult.rejected(commandId, ErrorCode.SERVER_BUSY);
			auditOutcome(audit, busy, null);
			return completed(busy);
		}
		if (!this.operations.add(viewerId)) {
			CommandResult pending = CommandResult.rejected(commandId, ErrorCode.OPERATION_PENDING);
			auditOutcome(audit, pending, null);
			return completed(pending);
		}
		this.activeAudits.put(commandId, audit);
		CompletableFuture<CommandResult> result;
		try { result = work.get(); }
		catch (RuntimeException error) {
			this.operations.remove(viewerId);
			this.activeAudits.remove(commandId);
			auditOutcome(audit, null, error);
			throw error;
		}
		CommandResult immediate = result.isDone() && !result.isCompletedExceptionally() ? result.getNow(null) : null;
		if (!result.isCompletedExceptionally() && (immediate == null || immediate.status() != CommandStatus.REJECTED))
			AuditLog.debug("mutation_accepted", audit.identity, "ACCEPTED", null, audit.startedNanos, Map.of(), null);
		CompletableFuture<CommandResult> completed = result.whenComplete((value, error) -> {
			this.operations.remove(viewerId);
			this.activeAudits.remove(commandId);
			auditOutcome(audit, value, error);
		});
		this.operationResults.put(commandId, new CachedOperation(now + 6000L, completed, audit));
		return completed;
	}

	public CompletableFuture<CommandResult> create(UUID id, ActorContext actor, ViewerId viewer, DisplayName name) {
		return authorizedMutation(id, actor, ParrotAction.CREATE, viewer, () -> createInternal(id, viewer, name));
	}
	CompletableFuture<CommandResult> create(UUID id, ViewerId viewer, DisplayName name) {
		return create(id, ActorContext.console(4), viewer, name);
	}
	public CompletableFuture<CommandResult> remove(UUID id, ActorContext actor, ViewerId viewer) {
		return authorizedMutation(id, actor, ParrotAction.REMOVE, viewer, () -> removeInternal(id, viewer));
	}
	CompletableFuture<CommandResult> remove(UUID id, ViewerId viewer) {
		return remove(id, ActorContext.console(4), viewer);
	}
	public CompletableFuture<CommandResult> rename(UUID id, ActorContext actor, ViewerId viewer, DisplayName name) {
		return authorizedMutation(id, actor, ParrotAction.RENAME, viewer, () -> renameInternal(id, viewer, name));
	}
	CompletableFuture<CommandResult> rename(UUID id, ViewerId viewer, DisplayName name) {
		return rename(id, ActorContext.console(4), viewer, name);
	}
	public CompletableFuture<CommandResult> setTrait(UUID id, ActorContext actor, ViewerId viewer, ParrotTrait trait) {
		return authorizedMutation(id, actor, ParrotAction.TRAIT, viewer, () -> setTraitInternal(id, viewer, trait));
	}
	CompletableFuture<CommandResult> setTrait(UUID id, ViewerId viewer, ParrotTrait trait) {
		return setTrait(id, ActorContext.console(4), viewer, trait);
	}
	public CompletableFuture<CommandResult> respawn(UUID id, ActorContext actor, ViewerId viewer) {
		return authorizedMutation(id, actor, ParrotAction.RESPAWN, viewer, () -> respawnInternal(id, viewer));
	}
	CompletableFuture<CommandResult> respawn(UUID id, ViewerId viewer) {
		return respawn(id, ActorContext.console(4), viewer);
	}
	public CommandResult reconcile(UUID id, ActorContext actor, ViewerId viewer) {
		AuditAttempt audit = new AuditAttempt(AuditLog.started(), diagnosticIdentity(id, viewer, ParrotAction.RECONCILE.name()));
		try {
			CommandResult result = authorizedReconcile(id, actor, viewer);
			auditOutcome(audit, result, null);
			return result;
		} catch (RuntimeException error) {
			auditOutcome(audit, null, error);
			throw error;
		}
	}
	private CommandResult authorizedReconcile(UUID id, ActorContext actor, ViewerId viewer) {
		if (!this.context.acceptingCommands()) return CommandResult.rejected(id, ErrorCode.INVALID_STATE);
		if (!this.context.permissions().isAllowed(actor, ParrotAction.RECONCILE))
			return CommandResult.rejected(id, ErrorCode.PERMISSION_DENIED);
		if (!this.context.allowsMutations()) return CommandResult.rejected(id, ErrorCode.STORAGE_UNAVAILABLE);
		if (operationPending(viewer)) return CommandResult.rejected(id, ErrorCode.OPERATION_PENDING);
		return reconcileInternal(id, viewer);
	}
	CommandResult reconcile(UUID id, ViewerId viewer) { return reconcile(id, ActorContext.console(4), viewer); }

	// --- create --------------------------------------------------------------

	private CompletableFuture<CommandResult> createInternal(UUID commandId, ViewerId viewerId, DisplayName displayName) {
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
				variant, trait, null, now).withDesiredBehavior(DesiredBehavior.FREE, now);
		UUID operationId = UUID.randomUUID();

		return writeJournal(JournalEntryDto.Phase.PREPARE, operationId, prepared)
				.thenCompose(sequence -> {
					this.context.registry().journalApplied(sequence);
					return finishCreate(commandId, operationId, prepared, entityUuid);
				})
				.exceptionally(error -> {
					this.context.registry().releaseReservation();
					this.context.degrade("PREPARE не записан: " + error.getMessage());
					auditStorageFailure(commandId, prepared, "PREPARE", error);
					return CommandResult.failed(commandId, ErrorCode.STORAGE_UNAVAILABLE, AuditLog.safeText(error.getMessage()));
				});
	}

	private CompletableFuture<CommandResult> finishCreate(UUID commandId, UUID operationId,
			ViewerParrotRecord prepared, UUID entityUuid) {
		if (!this.accepting || !this.context.allowsMutations() || this.context.registry().contains(prepared.viewerId()))
			return abort(commandId, operationId, prepared, ErrorCode.INVALID_STATE);
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
		if (!this.context.behaviorScheduler().tryAcquireTeleport())
			return deferSpawn(() -> finishCreate(commandId, operationId, prepared, entityUuid));

		Optional<ViewerParrotEntity> spawned = this.controller.spawnPrepared(
				world, position.get(), target.get().getYaw(), prepared, entityUuid);
		if (spawned.isEmpty()) {
			return abort(commandId, operationId, prepared, ErrorCode.NO_SAFE_POSITION);
		}

		ViewerParrotEntity parrot = spawned.get();
		ViewerParrotRecord committed = prepared.withLocation(
				ParrotEntityController.locationOf(parrot), this.context.clock().now());
		boolean[] reservationReleased = {false};

		return writeJournal(JournalEntryDto.Phase.COMMIT, operationId, committed)
				.thenApply(sequence -> {
					this.context.registry().put(committed);
					this.context.registry().journalApplied(sequence);
					this.context.registry().releaseReservation();
					reservationReleased[0] = true;
					CommandResult unavailable = bindCommittedOrExplainFailure(commandId, committed, parrot);
					auditCommitted(commandId, committed, sequence);
					publish(ParrotEvent.Type.PARROT_CREATED, this.context.registry().find(committed.viewerId()).orElse(committed), commandId);
					if (unavailable != null) return unavailable;

					return CommandResult.succeeded(commandId, "viewerparrots.create.ok",
							CommandResult.details("viewerId", committed.viewerId().value(),
									"variant", committed.variant().name(),
									"trait", committed.trait().name()));
				})
				.exceptionally(error -> {
					// COMMIT не записан: подготовленную сущность удаляем и уходим в degraded.
					this.controller.discard(parrot);
					if (!reservationReleased[0]) this.context.registry().releaseReservation();
					this.context.degrade("COMMIT не записан: " + error.getMessage());
					auditStorageFailure(commandId, prepared, "COMMIT", error);
					return CommandResult.failed(commandId, ErrorCode.STORAGE_UNAVAILABLE, AuditLog.safeText(error.getMessage()));
				});
	}

	private CompletableFuture<CommandResult> abort(UUID commandId, UUID operationId,
			ViewerParrotRecord prepared, ErrorCode reason) {
		return writeJournal(JournalEntryDto.Phase.ABORT, operationId, null, prepared)
				.handle((ignored, error) -> {
					if (error == null) this.context.registry().journalApplied(ignored);
					this.context.registry().releaseReservation();
					if (error != null) {
						this.context.degrade("ABORT not durable: " + error.getMessage());
						auditStorageFailure(commandId, prepared, "ABORT", error);
					}
					return CommandResult.rejected(commandId, reason);
				});
	}

	// --- remove --------------------------------------------------------------

	private CompletableFuture<CommandResult> removeInternal(UUID commandId, ViewerId viewerId) {
		if (!this.context.allowsMutations()) {
			return completed(CommandResult.rejected(commandId, ErrorCode.STORAGE_UNAVAILABLE));
		}

		Optional<ViewerParrotRecord> existing = this.context.registry().find(viewerId);
		if (existing.isEmpty()) {
			// Повторный remove для уже удалённой записи успешен как no-op (раздел 9.5 ТЗ).
			boolean hadTombstone = !this.context.registry().tombstonesFor(viewerId).isEmpty();
			return completed(hadTombstone
					? CommandResult.succeeded(commandId, "viewerparrots.remove.ok",
							CommandResult.details("viewerId", viewerId.value(), "noOp", "true"))
					: CommandResult.rejected(commandId, ErrorCode.VIEWER_NOT_FOUND));
		}

		ViewerParrotRecord record = existing.get();
		Tombstone tombstone = Tombstone.of(record, this.context.clock().now());
		UUID operationId = UUID.randomUUID();

		// Надгробие фиксируется ДО удаления сущности: если сервер упадёт между шагами,
		// валидатор отвергнет выгруженную копию по надгробию (раздел 8.2 ТЗ).
		return writeTombstone(operationId, tombstone)
				.thenApply(sequence -> {
					if (!sameRegistration(record)) return CommandResult.rejected(commandId, ErrorCode.INVALID_STATE);
					this.context.registry().remove(viewerId, tombstone.removedAt());
					this.context.registry().journalApplied(sequence);
					this.context.forgetRuntime(viewerId);
					this.context.behaviorScheduler().forgetFault(viewerId);
					this.context.recoveryService().cancel(viewerId);
					this.context.cooldowns().clear(viewerId);
					this.context.loadedIndex().find(viewerId).ifPresent(this.controller::discard);
					this.context.loadedIndex().unbind(viewerId);
					auditCommitted(commandId, record, sequence);
					publish(ParrotEvent.Type.PARROT_REMOVED, record, commandId);
					return CommandResult.succeeded(commandId, "viewerparrots.remove.ok",
							CommandResult.details("viewerId", viewerId.value()));
				})
				.exceptionally(error -> {
					this.context.degrade("tombstone не записан: " + error.getMessage());
					auditStorageFailure(commandId, record, "TOMBSTONE", error);
					return CommandResult.failed(commandId, ErrorCode.STORAGE_UNAVAILABLE, AuditLog.safeText(error.getMessage()));
				});
	}

	// --- rename и trait -------------------------------------------------------

	private CompletableFuture<CommandResult> renameInternal(UUID commandId, ViewerId viewerId, DisplayName newName) {
		return mutate(commandId, viewerId, record -> record.withDisplayName(newName, this.context.clock().now()),
				"viewerparrots.rename.ok", updated ->
						this.context.loadedIndex().find(viewerId)
								.ifPresent(parrot -> this.controller.applyName(parrot, updated)));
	}

	/**
	 * Фиксирует изменение намерения журналом (раздел 8.2 ТЗ).
	 *
	 * <p>The future completes after the journal is durable and the registry callback applies.
	 * Command adapters wait for it before reporting terminal success. An I/O failure blocks
	 * further mutations and cancels authoritative behavior through the degraded context.
	 */
	/** Durable-часть {@code !name}; {@code null} убирает кличку. Имя над птицей игровой путь уже обновил. */
	public CompletableFuture<Void> recordNickname(ViewerId viewerId, DisplayName nickname) {
		Optional<ViewerParrotRecord> existing = this.context.registry().find(viewerId);
		if (existing.isEmpty()) {
			return CompletableFuture.failedFuture(new IllegalStateException("viewer removed"));
		}
		if (java.util.Objects.equals(existing.get().viewer().nickname(), nickname)) return CompletableFuture.completedFuture(null);
		UUID id = UUID.randomUUID();
		return guarded(id, viewerId, "PERSIST_NICKNAME", () -> mutate(id, viewerId,
				record -> record.withNickname(nickname, this.context.clock().now()),
				"viewerparrots.action.ok", updated -> {})).thenApply(result -> {
			if (!result.succeeded()) throw new java.util.concurrent.CompletionException(
					new IllegalStateException(result.code().name()));
			return null;
		});
	}

	/** Durable-часть {@code !color}: права, cooldown и состояние уже проверены игровым путём команд. */
	public CompletableFuture<Void> recordVariant(ViewerId viewerId, ParrotVariant variant) {
		Optional<ViewerParrotRecord> existing = this.context.registry().find(viewerId);
		if (existing.isEmpty()) {
			return CompletableFuture.failedFuture(new IllegalStateException("viewer removed"));
		}
		if (existing.get().variant() == variant) return CompletableFuture.completedFuture(null);
		UUID id = UUID.randomUUID();
		return guarded(id, viewerId, "PERSIST_VARIANT", () -> mutate(id, viewerId,
				record -> record.withVariant(variant, this.context.clock().now()),
				"viewerparrots.action.ok", updated -> {})).thenApply(result -> {
			if (!result.succeeded()) throw new java.util.concurrent.CompletionException(
					new IllegalStateException(result.code().name()));
			return null;
		});
	}

	public CompletableFuture<Void> recordDesiredBehavior(ViewerId viewerId, DesiredBehavior desiredBehavior) {
		Optional<ViewerParrotRecord> existing = this.context.registry().find(viewerId);
		if (existing.isEmpty()) {
			return CompletableFuture.failedFuture(new IllegalStateException("viewer removed"));
		}

		if (existing.get().desiredBehavior() == desiredBehavior) return CompletableFuture.completedFuture(null);
		UUID id = UUID.randomUUID();
		return guarded(id, viewerId, "PERSIST_INTENT", () -> mutate(id, viewerId,
				record -> record.withDesiredBehavior(desiredBehavior, this.context.clock().now()),
				"viewerparrots.action.ok", updated -> {})).thenApply(result -> {
			if (!result.succeeded()) throw new java.util.concurrent.CompletionException(
					new IllegalStateException(result.code().name()));
			return null;
		});
	}

	/** A late exact-current entity is authorized; durably clear the earlier local missing result. */
	public CompletableFuture<Void> restorePresentOnBind(ViewerId viewerId) {
		var record = this.context.registry().find(viewerId);
		if (record.isEmpty() || record.get().physicalStatus() != PhysicalStatus.MISSING)
			return CompletableFuture.completedFuture(null);
		if (operationPending(viewerId)) return CompletableFuture.completedFuture(null);
		UUID id = UUID.randomUUID();
		return guarded(id, viewerId, "RESTORE_PRESENT", () -> mutate(id, viewerId, current -> new ViewerParrotRecord(
				current.viewer(), current.incarnationId(), current.generation(), current.entityUuid(),
				current.variant(), current.trait(), current.desiredBehavior(), PhysicalStatus.PRESENT,
				current.lastKnownLocation(), current.lastSpawnedAt(), this.context.clock().now(), current.revision() + 1),
				"viewerparrots.reconcile.bound", updated -> {})).thenApply(result -> {
			if (!result.succeeded()) throw new java.util.concurrent.CompletionException(new IllegalStateException(result.code().name()));
			return null;
		});
	}

	private CompletableFuture<CommandResult> setTraitInternal(UUID commandId, ViewerId viewerId, ParrotTrait trait) {
		return mutate(commandId, viewerId, record -> record.withTrait(trait, this.context.clock().now()),
				"viewerparrots.trait.ok", updated -> {
					this.context.commandService().cancelRuntime(viewerId, ErrorCode.INVALID_STATE);
					var runtime = this.context.runtimes().get(viewerId);
					if (runtime != null) runtime.resetTransientState();
					this.context.loadedIndex().find(viewerId).ifPresent(parrot -> {
						parrot.setForcedDancing(false);
						parrot.getNavigation().stop();
					});
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
				.thenApply(sequence -> {
					// Запись могла измениться, пока шла запись на диск.
					Optional<ViewerParrotRecord> stillThere = this.context.registry().find(viewerId);
					if (!samePhysicalRegistration(existing.get())) {
						return CommandResult.rejected(commandId, ErrorCode.INVALID_STATE);
					}
					ViewerParrotRecord applied = change.apply(stillThere.orElseThrow());
					this.context.registry().replace(applied);
					this.context.registry().journalApplied(sequence);
					afterCommit.accept(applied);
					auditCommitted(commandId, applied, sequence);
					return CommandResult.succeeded(commandId, messageKey,
							CommandResult.details("viewerId", viewerId.value()));
				})
				.exceptionally(error -> {
					this.context.degrade("операция не записана: " + error.getMessage());
					auditStorageFailure(commandId, updated, "COMMIT", error);
					return CommandResult.failed(commandId, ErrorCode.STORAGE_UNAVAILABLE, AuditLog.safeText(error.getMessage()));
				});
	}

	// --- respawn ---------------------------------------------------------------

	private CompletableFuture<CommandResult> respawnInternal(UUID commandId, ViewerId viewerId) {
		if (!this.context.allowsMutations()) {
			return completed(CommandResult.rejected(commandId, ErrorCode.STORAGE_UNAVAILABLE));
		}
		Optional<ViewerParrotRecord> existing = this.context.registry().find(viewerId);
		if (existing.isEmpty()) {
			return completed(CommandResult.rejected(commandId, ErrorCode.VIEWER_NOT_FOUND));
		}

		ViewerParrotRecord record = existing.get();

		if (io.github.viewerparrots.cage.CageJail.isJailed(viewerId)) {
			// Узник Parrot Cages не получает новое тело на свободе.
			return completed(CommandResult.rejected(commandId, ErrorCode.PARROT_CAGED));
		}
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
		var limit = this.context.rateLimiter().check(ParrotAction.RESPAWN);
		if (!limit.allowed()) return completed(CommandResult.retryLater(commandId, ErrorCode.GLOBAL_RATE_LIMITED, limit.retryAfterTicks()));
		long cooldown = this.context.cooldowns().remainingTicks(viewerId, ParrotAction.RESPAWN);
		if (cooldown > 0) return completed(CommandResult.retryLater(commandId, ErrorCode.COMMAND_ON_COOLDOWN, cooldown));

		// Замена MISSING использует существующий слот; respawn DEAD требует свободного.
		boolean needsSlot = !record.occupiesCapacitySlot();
		if (needsSlot && !this.context.registry().reserveSlot(this.context.config().maxActiveParrots())) {
			return completed(CommandResult.rejected(commandId, ErrorCode.MAX_ACTIVE_PARROTS_REACHED));
		}

		UUID newEntityUuid = UUID.randomUUID();
		this.context.rateLimiter().tryAcquire(ParrotAction.RESPAWN);
		this.context.cooldowns().consume(viewerId, ParrotAction.RESPAWN, this.context.config().cooldowns().respawn());
		ViewerParrotRecord prepared = record.asRespawned(newEntityUuid, this.context.clock().now());
		UUID operationId = UUID.randomUUID();

		return writeJournal(JournalEntryDto.Phase.PREPARE, operationId, prepared)
				.thenCompose(sequence -> {
					this.context.registry().journalApplied(sequence);
					return finishRespawn(commandId, operationId, prepared, newEntityUuid, needsSlot);
				})
				.exceptionally(error -> {
					if (needsSlot) {
						this.context.registry().releaseReservation();
					}
					this.context.degrade("PREPARE respawn не записан: " + error.getMessage());
					auditStorageFailure(commandId, prepared, "PREPARE", error);
					return CommandResult.failed(commandId, ErrorCode.STORAGE_UNAVAILABLE, AuditLog.safeText(error.getMessage()));
				});
	}

	private CompletableFuture<CommandResult> finishRespawn(UUID commandId, UUID operationId,
			ViewerParrotRecord prepared, UUID newEntityUuid, boolean reservedSlot) {
		if (!this.accepting || !this.context.allowsMutations() || this.context.loadedIndex().isLoaded(prepared.viewerId())
				|| !this.context.registry().find(prepared.viewerId()).map(record ->
					record.incarnationId().equals(prepared.incarnationId()) && record.generation() + 1 == prepared.generation()).orElse(false))
			return abortRespawn(commandId, operationId, prepared, ErrorCode.INVALID_STATE, reservedSlot);
		Optional<ServerPlayerEntity> target = this.context.streamerService().onlineTarget();
		if (target.isEmpty()) {
			return abortRespawn(commandId, operationId, prepared, ErrorCode.TARGET_UNAVAILABLE, reservedSlot);
		}

		ServerWorld world = target.get().getServerWorld();
		Optional<Vec3d> position = this.context.safePositionFinder().findNear(world, target.get().getPos());
		if (position.isEmpty()) {
			return abortRespawn(commandId, operationId, prepared, ErrorCode.NO_SAFE_POSITION, reservedSlot);
		}
		if (!this.context.behaviorScheduler().tryAcquireTeleport())
			return deferSpawn(() -> finishRespawn(commandId, operationId, prepared, newEntityUuid, reservedSlot));
		if (deadRespawnDisabled(prepared))
			return abortRespawn(commandId, operationId, prepared, ErrorCode.RESPAWN_DISABLED, reservedSlot);

		Optional<ViewerParrotEntity> spawned = this.controller.spawnPrepared(
				world, position.get(), target.get().getYaw(), prepared, newEntityUuid);
		if (spawned.isEmpty()) {
			return abortRespawn(commandId, operationId, prepared, ErrorCode.NO_SAFE_POSITION, reservedSlot);
		}

		ViewerParrotEntity parrot = spawned.get();
		// ENTITY_LOAD listeners may publish a reload synchronously. The prepared body
		// remains reversible until COMMIT is submitted; a confirmed COMMIT is never undone.
		if (deadRespawnDisabled(prepared)) {
			this.controller.discard(parrot);
			return abortRespawn(commandId, operationId, prepared, ErrorCode.RESPAWN_DISABLED, reservedSlot);
		}
		ViewerParrotRecord committed = prepared.withLocation(
				ParrotEntityController.locationOf(parrot), this.context.clock().now());
		boolean[] reservationReleased = {false};

		return writeJournal(JournalEntryDto.Phase.COMMIT, operationId, committed)
				.thenApply(sequence -> {
					if (!this.context.registry().find(committed.viewerId()).map(record ->
							record.incarnationId().equals(committed.incarnationId())
							&& record.generation() + 1 == committed.generation()).orElse(false)) {
						this.controller.discard(parrot);
						if (reservedSlot) { this.context.registry().releaseReservation(); reservationReleased[0] = true; }
						return CommandResult.rejected(commandId, ErrorCode.INVALID_STATE);
					}
					this.context.loadedIndex().find(committed.viewerId()).ifPresent(this.controller::discard);
					this.context.loadedIndex().unbind(committed.viewerId());
					this.context.forgetRuntime(committed.viewerId());
					ViewerParrotRecord current = this.context.registry().find(committed.viewerId()).orElseThrow();
					ViewerParrotRecord applied = new ViewerParrotRecord(committed.viewer(), committed.incarnationId(),
							committed.generation(), committed.entityUuid(), committed.variant(), committed.trait(),
							committed.desiredBehavior(), committed.physicalStatus(), committed.lastKnownLocation(),
							committed.lastSpawnedAt(), committed.updatedAt(), Math.max(committed.revision(), current.revision() + 1));
					this.context.registry().replace(applied);
					this.context.registry().journalApplied(sequence);
					if (reservedSlot) {
						this.context.registry().releaseReservation();
						reservationReleased[0] = true;
					}
					this.context.recoveryService().cancel(committed.viewerId());
					CommandResult unavailable = bindCommittedOrExplainFailure(commandId, applied, parrot);
					auditCommitted(commandId, applied, sequence);
					publish(ParrotEvent.Type.PARROT_RESPAWNED, this.context.registry().find(committed.viewerId()).orElse(applied), commandId);
					if (unavailable != null) return unavailable;

					return CommandResult.succeeded(commandId, "viewerparrots.respawn.ok",
							CommandResult.details("viewerId", committed.viewerId().value(),
									"generation", Long.toString(committed.generation())));
				})
				.exceptionally(error -> {
					this.controller.discard(parrot);
					if (reservedSlot && !reservationReleased[0]) {
						this.context.registry().releaseReservation();
					}
					this.context.degrade("COMMIT respawn не записан: " + error.getMessage());
					auditStorageFailure(commandId, prepared, "COMMIT", error);
					return CommandResult.failed(commandId, ErrorCode.STORAGE_UNAVAILABLE, AuditLog.safeText(error.getMessage()));
				});
	}

	/** COMMIT owns the identity; authorization still requires healthy storage and the current live object. */
	private CommandResult bindCommittedOrExplainFailure(UUID commandId, ViewerParrotRecord committed,
			ViewerParrotEntity parrot) {
		parrot.setAwaitingAuthorization(false);
		boolean currentObject = !parrot.isRemoved() && parrot.isAlive()
				&& parrot.getUuid().equals(committed.entityUuid())
				&& parrot.marker().filter(marker -> marker.matches(committed)).isPresent()
				&& parrot.getWorld() instanceof ServerWorld world && world.getEntity(parrot.getUuid()) == parrot;
		if (!this.context.allowsMutations() || !currentObject) {
			parrot.setAiDisabled(true);
			this.context.loadedIndex().unbind(committed.viewerId());
			this.context.forgetRuntime(committed.viewerId());
			if (!this.context.allowsMutations()) {
				this.context.markUnresolved(committed.viewerId());
				return CommandResult.failed(commandId, ErrorCode.STORAGE_UNAVAILABLE, commandId.toString());
			}
			Entity.RemovalReason reason = parrot.getRemovalReason();
			if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK || reason == Entity.RemovalReason.CHANGED_DIMENSION) {
				this.context.markUnloaded(committed.viewerId());
				return CommandResult.failed(commandId, ErrorCode.ENTITY_UNRESOLVED, commandId.toString());
			}
			this.context.registry().find(committed.viewerId()).ifPresent(record ->
					this.context.registry().replace(record.asMissing(this.context.clock().now())));
			this.context.markUnresolved(committed.viewerId());
			return CommandResult.failed(commandId, ErrorCode.PARROT_ENTITY_MISSING, commandId.toString());
		}
		this.context.loadedIndex().bind(committed.viewerId(), parrot);
		this.context.markLoaded(committed.viewerId());
		this.controller.applyName(parrot, committed);
		this.controller.authorize(parrot);
		this.context.runtime(committed.viewerId()).restoreDesired(committed.desiredBehavior());
		return null;
	}

	private CompletableFuture<CommandResult> abortRespawn(UUID commandId, UUID operationId,
			ViewerParrotRecord prepared, ErrorCode reason, boolean reservedSlot) {
		return writeJournal(JournalEntryDto.Phase.ABORT, operationId, null, prepared)
				.handle((ignored, error) -> {
					if (error == null) this.context.registry().journalApplied(ignored);
					else {
						this.context.degrade("respawn ABORT not durable: " + error.getMessage());
						auditStorageFailure(commandId, prepared, "ABORT", error);
					}
					if (reservedSlot) {
						this.context.registry().releaseReservation();
					}
					return CommandResult.rejected(commandId, reason);
				});
	}

	private boolean deadRespawnDisabled(ViewerParrotRecord prepared) {
		return !this.context.config().deathMode().allowsRespawnAfterDeath()
				&& this.context.registry().find(prepared.viewerId())
				.map(record -> record.physicalStatus() == PhysicalStatus.DEAD).orElse(false);
	}

	// --- reconcile -------------------------------------------------------------

	/**
	 * Ограниченная проверка без создания сущностей (раздел 9.3 ТЗ): сначала loaded index
	 * по всем измерениям, затем поиск по ожидаемому UUID в уже загруженных мирах.
	 */
	private CommandResult reconcileInternal(UUID commandId, ViewerId viewerId) {
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
				if (!io.github.viewerparrots.lifecycle.GenerationValidator.decide(found.marker(), found.getUuid(),
						this.context.registry(), this.context.isRegistryReady()).isBind()) continue;
				this.context.loadedIndex().bind(viewerId, found);
				this.context.markLoaded(viewerId);
				this.controller.applyName(found, record);
				found.setVariant(io.github.viewerparrots.entity.VanillaVariantMapper.toVanilla(record.variant()));
				this.context.runtime(viewerId).restoreDesired(record.desiredBehavior());
				found.setAiDisabled(false);
				restorePresentOnBind(viewerId);
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
			case NO_KNOWN_LOCATION, NOTHING_TO_FIND -> this.context.recoveryService().lastFailure(viewerId)
					.map(code -> CommandResult.rejected(commandId, code))
					.orElseGet(() -> CommandResult.succeeded(commandId, "viewerparrots.reconcile.not_found",
							CommandResult.details("viewerId", viewerId.value(), "reason", EntityRecoveryService.NOT_FOUND_REASON)));
		};
	}

	// --- журнал ----------------------------------------------------------------

	private CompletableFuture<Long> writeJournal(JournalEntryDto.Phase phase, UUID operationId,
			ViewerParrotRecord record) {
		return writeJournal(phase, operationId, record, record);
	}

	private CompletableFuture<Long> writeJournal(JournalEntryDto.Phase phase, UUID operationId,
			ViewerParrotRecord payload, ViewerParrotRecord identity) {
		return this.context.writer().submit(() -> {
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

			this.context.repository().appendJournal(entry);
			return entry.sequence();
		});
	}

	private CompletableFuture<Long> writeTombstone(UUID operationId, Tombstone tombstone) {
		return this.context.writer().submit(() -> {
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

			this.context.repository().appendJournal(entry);
			return entry.sequence();
		});
	}

	private boolean sameRegistration(ViewerParrotRecord expected) {
		return this.context.registry().find(expected.viewerId()).map(current ->
				current.incarnationId().equals(expected.incarnationId()) && current.generation() == expected.generation()).orElse(false);
	}

	private boolean samePhysicalRegistration(ViewerParrotRecord expected) {
		return this.context.registry().find(expected.viewerId()).map(current ->
				sameRegistration(expected) && current.physicalStatus() == expected.physicalStatus()
				&& current.expectedEntityUuid().equals(expected.expectedEntityUuid())).orElse(false);
	}

	private void publish(ParrotEvent.Type type, ViewerParrotRecord record, UUID commandId) {
		this.context.events().publish(ParrotEvent.of(type, record.viewerId(), record.incarnationId(), record.generation(),
				this.context.clock().now(), this.context.clock().currentTick(), commandId, null, record.physicalStatus().name(), null));
	}

	private AuditLog.Context diagnosticIdentity(UUID commandId, ViewerId viewerId, String action) {
		return this.context.registry().find(viewerId).map(record -> AuditLog.Context.of(commandId, record, action))
				.orElseGet(() -> AuditLog.Context.viewer(commandId, viewerId, action));
	}

	private void auditCommitted(UUID commandId, ViewerParrotRecord record, long sequence) {
		AuditAttempt audit = this.activeAudits.get(commandId);
		if (audit == null) return;
		audit.identity = AuditLog.Context.of(commandId, record, audit.identity.action());
		boolean lifecycle = switch (audit.identity.action()) { case "CREATE", "REMOVE", "RESPAWN" -> true; default -> false; };
		AuditLog.emit(lifecycle ? AuditLog.Level.INFO : AuditLog.Level.DEBUG, "mutation_committed", audit.identity,
				"COMMITTED", null, audit.startedNanos,
				Map.of("sequence", sequence, "variant", record.variant().name(), "trait", record.trait().name()), null);
	}

	private void auditStorageFailure(UUID commandId, ViewerParrotRecord record, String phase, Throwable error) {
		AuditAttempt audit = this.activeAudits.get(commandId);
		AuditLog.Context identity = AuditLog.Context.of(commandId, record, audit == null ? null : audit.identity.action());
		AuditLog.error("storage_write_failed", identity, "FAILED", ErrorCode.STORAGE_UNAVAILABLE.name(),
				audit == null ? AuditLog.started() : audit.startedNanos, Map.of("phase", phase), error);
	}

	private static void auditOutcome(AuditAttempt audit, CommandResult result, Throwable error) {
		if (error != null || result == null) {
			AuditLog.error("mutation_failed", audit.identity, "FAILED", ErrorCode.INTERNAL_ERROR.name(), audit.startedNanos, Map.of(), error);
			return;
		}
		AuditLog.debug(result.status() == CommandStatus.REJECTED ? "mutation_rejected" : "mutation_completed", audit.identity,
				result.status().name(), result.code() == null ? null : result.code().name(), audit.startedNanos,
				Map.of("retryAfterTicks", result.retryAfterTicks(), "details", result.details()), null);
	}

	private static CompletableFuture<CommandResult> completed(CommandResult result) {
		return CompletableFuture.completedFuture(result);
	}
}
