package io.github.viewerparrots.service;

import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.api.CommandStatus;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.api.event.ParrotEvent;
import io.github.viewerparrots.behavior.BehaviorStateMachine;
import io.github.viewerparrots.behavior.LifecycleStateMachine;
import io.github.viewerparrots.behavior.ParrotRuntime;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.diagnostics.AuditLog;
import io.github.viewerparrots.domain.LifecycleState;
import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.domain.DesiredBehavior;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import io.github.viewerparrots.ratelimit.GlobalRateLimiter;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.projectile.thrown.ExperienceBottleEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Server-thread command validation and final completion, including deferred movement. */
public final class ParrotCommandService {
    private final ServerContext context;
    private final CommandResultCache resultCache;
    private final Map<UUID, PendingCommand> pendingCommands = new HashMap<>();
    private final Map<ViewerId, UUID> activeIntentCommands = new HashMap<>();
    private final Map<ErrorCode, Integer> rejectionCounts = new EnumMap<>(ErrorCode.class);

    private static final class PendingCommand {
        final ViewerParrotRecord identity;
        final ParrotAction action;
        final long startedNanos;
        boolean durable;
        boolean acceptedPublished;
        java.util.List<UUID> displacedCommands = java.util.List.of();
        CommandResult movementResult;
        PendingCommand(ViewerParrotRecord identity, ParrotAction action, long startedNanos) {
            this.identity = identity;
            this.action = action;
            this.startedNanos = startedNanos;
        }
    }

    public ParrotCommandService(ServerContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.resultCache = new CommandResultCache(context.clock());
    }

    public CommandResultCache resultCache() { return this.resultCache; }
    public CompletableFuture<CommandResult> completion(UUID commandId) { return this.resultCache.completion(commandId); }

    public CommandResult submit(UUID commandId, ActorContext actor, ParrotAction action, ViewerId viewerId) {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(viewerId, "viewerId");
        long started = AuditLog.started();
        AuditLog.Context identity = diagnosticIdentity(commandId, viewerId, action.name());
        if (!this.context.acceptingCommands()) {
            CommandResult result = CommandResult.rejected(commandId, ErrorCode.INVALID_STATE);
            auditRejected(identity, result, started);
            return result;
        }
        // Even duplicate delivery crosses the permission boundary.
        if (!this.context.permissions().isAllowed(actor, action)) {
            CommandResult result = reject(commandId, viewerId, action, ErrorCode.PERMISSION_DENIED);
            auditRejected(identity, result, started);
            return result;
        }
        Optional<CommandResult> cached = this.resultCache.find(commandId);
        if (cached.isPresent()) {
            AuditLog.debug("command_cached", identity, cached.get().status().name(), null, started, Map.of(), null);
            return cached.get();
        }

        CommandResult result;
        try { result = evaluate(commandId, action, viewerId, started); }
        catch (RuntimeException error) {
            AuditLog.error("command_failed", identity, "FAILED", ErrorCode.INTERNAL_ERROR.name(), started, Map.of(), error);
            throw error;
        }
        if (result.status() == CommandStatus.REJECTED) {
            this.resultCache.remember(result);
            this.rejectionCounts.merge(result.code(), 1, Integer::sum);
            publishCurrent(ParrotEvent.Type.COMMAND_REJECTED, viewerId, commandId, null, action.name(), result.code().name());
            auditRejected(identity, result, started);
        }
        return this.resultCache.find(commandId).orElse(result);
    }

    private CommandResult reject(UUID commandId, ViewerId viewerId, ParrotAction action, ErrorCode code) {
        this.rejectionCounts.merge(code, 1, Integer::sum);
        publishCurrent(ParrotEvent.Type.COMMAND_REJECTED, viewerId, commandId, null, action.name(), code.name());
        return CommandResult.rejected(commandId, code);
    }

    private CommandResult evaluate(UUID commandId, ParrotAction action, ViewerId viewerId, long started) {
        Optional<ViewerParrotRecord> maybeRecord = this.context.registry().find(viewerId);
        if (maybeRecord.isEmpty()) return CommandResult.rejected(commandId, ErrorCode.VIEWER_NOT_FOUND);
        ViewerParrotRecord record = maybeRecord.get();
        if (!this.context.allowsMutations()) return CommandResult.rejected(commandId, ErrorCode.STORAGE_UNAVAILABLE);
        if (this.context.parrotService().operationPending(viewerId)) return CommandResult.rejected(commandId, ErrorCode.OPERATION_PENDING);

        LifecycleState lifecycle = this.context.lifecycle(viewerId);
        if (!LifecycleStateMachine.allows(lifecycle, action)) {
            ErrorCode code = switch (lifecycle) {
                case DEAD, REMOVED, LOADED -> ErrorCode.INVALID_STATE;
                case MISSING -> ErrorCode.PARROT_ENTITY_MISSING;
                default -> ErrorCode.ENTITY_UNRESOLVED;
            };
            return CommandResult.rejected(commandId, code);
        }
        Optional<ViewerParrotEntity> maybeEntity = this.context.loadedIndex().find(viewerId)
                .or(() -> this.context.shoulderService().find(viewerId));
        if (maybeEntity.isEmpty()) {
            this.context.recoveryService().start(viewerId);
            return CommandResult.rejected(commandId, ErrorCode.ENTITY_UNRESOLVED);
        }
        ViewerParrotEntity parrot = maybeEntity.get();
        if (record.expectedEntityUuid().filter(parrot.getUuid()::equals).isEmpty())
            return CommandResult.rejected(commandId, ErrorCode.ENTITY_UNRESOLVED);
        if (requiresTarget(action)) {
            if (this.context.streamerService().configuredUuid().isEmpty()) return CommandResult.rejected(commandId, ErrorCode.TARGET_NOT_CONFIGURED);
            if (this.context.streamerService().onlineTarget().isEmpty()) return CommandResult.rejected(commandId, ErrorCode.TARGET_UNAVAILABLE);
        }
        if (!isGameAction(action)) return CommandResult.rejected(commandId, ErrorCode.INVALID_ARGUMENT);
        ParrotRuntime runtime = this.context.runtime(viewerId);
        BehaviorStateMachine.Event event = toEvent(action);
        boolean releaseShoulder = CommandPolicy.releasesShoulder(action) && this.context.shoulderService().contains(viewerId);
        // Validate dance/spin against the state after their required shoulder release.
        BehaviorStateMachine.Result transition = BehaviorStateMachine.apply(
                releaseShoulder ? BehaviorState.FREEFOLLOW : runtime.behavior(),
                releaseShoulder ? DesiredBehavior.FREEFOLLOW : runtime.desiredBehavior(), event);
        if (transition instanceof BehaviorStateMachine.Rejected rejected) return CommandResult.rejected(commandId, rejected.code());

        // Preserve prescribed validation order while only consuming at acceptance.
        GlobalRateLimiter.Decision decision = this.context.rateLimiter().check(action);
        if (!decision.allowed()) return CommandResult.retryLater(commandId, ErrorCode.GLOBAL_RATE_LIMITED, decision.retryAfterTicks());
        long remaining = this.context.cooldowns().remainingTicks(viewerId, cooldownKey(action));
        if (remaining > 0) return CommandResult.retryLater(commandId, ErrorCode.COMMAND_ON_COOLDOWN, remaining);

        // A failed mount must leave the old movement/intent intact and consume no cooldown.
        if (action == ParrotAction.ARM) {
            ErrorCode error = this.context.shoulderService().arm(parrot,
                    this.context.streamerService().onlineTarget().orElseThrow());
            if (error != null) return CommandResult.rejected(commandId, error);
        } else if (releaseShoulder) {
            ErrorCode error = this.context.shoulderService().release(viewerId);
            if (error != null) return CommandResult.rejected(commandId, error);
            maybeEntity = this.context.loadedIndex().find(viewerId);
            if (maybeEntity.isEmpty() || record.expectedEntityUuid().filter(maybeEntity.get().getUuid()::equals).isEmpty())
                return CommandResult.rejected(commandId, ErrorCode.ENTITY_UNRESOLVED);
            parrot = maybeEntity.get();
            // Release changes SHOULDER to FREEFOLLOW; SPIN must retain that actual new state.
            transition = BehaviorStateMachine.apply(runtime.behavior(), runtime.desiredBehavior(), event);
            if (transition instanceof BehaviorStateMachine.Rejected rejected)
                return CommandResult.rejected(commandId, rejected.code());
        }

        this.context.rateLimiter().tryAcquire(action);
        java.util.List<UUID> displaced = preservesBehavior(action)
                ? java.util.List.of() : prepareCancellation(runtime);
        this.context.cooldowns().consume(viewerId, cooldownKey(action), cooldownFor(action));
        PendingCommand pending = new PendingCommand(record, action, started);
        pending.displacedCommands = displaced;
        this.pendingCommands.put(commandId, pending);
        if (action == ParrotAction.FOLLOW || action == ParrotAction.SIT) this.activeIntentCommands.put(viewerId, commandId);
        CommandResult accepted = accepted(commandId, viewerId, action, "viewerparrots.action.accepted", null);
        this.resultCache.remember(accepted);

        if (CommandPolicy.shouldDelay(this.context.config().behavior().enabled(), record.trait(), action)) {
            runtime.cancelAutonomousMovement();
            parrot.getNavigation().stop();
            int min = this.context.config().behavior().lazyMinTicks();
            int max = this.context.config().behavior().lazyMaxTicks();
            long delay = min + this.context.behaviorRandom().nextInt(max - min + 1);
            runtime.setTargetPlayerUuid(this.context.streamerService().configuredUuid().orElse(null));
            runtime.setPendingAction(new ParrotRuntime.PendingAction(commandId, event,
                    this.context.clock().currentTick() + delay, record.generation()));
            CommandResult delayed = accepted(commandId, viewerId, action, "viewerparrots.action.delayed", delay);
            this.resultCache.remember(delayed);
            publishAccepted(commandId, pending);
            return delayed;
        }
        return execute(commandId, viewerId, runtime, parrot, (BehaviorStateMachine.Accepted) transition, action);
    }

    /** Called again by the scheduler after LAZY; cooldown and budgets are already spent. */
    public CommandResult execute(UUID commandId, ViewerId viewerId, ParrotRuntime runtime,
            ViewerParrotEntity parrot, BehaviorStateMachine.Accepted transition, ParrotAction action) {
        PendingCommand pending = this.pendingCommands.get(commandId);
        if (pending == null) return CommandResult.rejected(commandId, ErrorCode.INVALID_STATE);
        boolean validIdentity = this.context.registry().find(viewerId).map(record ->
                record.incarnationId().equals(pending.identity.incarnationId())
                && record.generation() == pending.identity.generation()
                && record.expectedEntityUuid().filter(parrot.getUuid()::equals).isPresent()).orElse(false);
        if (!validIdentity) {
            cancel(commandId, viewerId, ErrorCode.ENTITY_UNRESOLVED);
            return this.resultCache.find(commandId).orElseThrow();
        }
        if (!this.context.allowsMutations()) {
            CommandResult failed = CommandResult.failed(commandId, ErrorCode.STORAGE_UNAVAILABLE, null);
            completeDeferred(commandId, viewerId, failed);
            return failed;
        }
        String oldState = runtime.behavior().name();
        if (!preservesBehavior(action)) {
            runtime.cancelAutonomousMovement();
            parrot.getNavigation().stop();
        }
        runtime.apply(transition, this.context.clock().currentTick());
        String newState = runtime.behavior().name();
        switch (action) {
            case HAT -> parrot.setHatId(io.github.viewerparrots.cosmetic.ParrotHats.randomDifferent(
                    parrot.hatId(), this.context.behaviorRandom()));
            case DEHAT -> parrot.setHatId("");
            case VIEW -> parrot.setViewingStreamer(true);
            case DEVIEW -> parrot.setViewingStreamer(false);
            case SPIN -> {
                runtime.cancelAutonomousMovement();
                parrot.getNavigation().stop();
                parrot.setVelocity(parrot.getVelocity().multiply(0.0, 1.0, 0.0));
                parrot.startSpin();
            }
            case DANCE -> {
                runtime.startDance(commandId, this.context.clock().currentTick() + this.context.config().dance().durationTicks());
                parrot.setForcedDancing(true);
            }
            case COME -> {
                runtime.startMovement(commandId, this.context.clock().currentTick() + this.context.config().come().timeoutTicks());
                runtime.setTargetPlayerUuid(this.context.streamerService().configuredUuid().orElse(null));
                parrot.setForcedDancing(false);
            }
            case SIT -> parrot.setForcedDancing(false);
            case FOLLOW -> {
                runtime.setTargetPlayerUuid(this.context.streamerService().configuredUuid().orElse(null));
                parrot.setForcedDancing(false);
            }
            case FREE -> {
                runtime.setTargetPlayerUuid(null);
                parrot.setForcedDancing(false);
            }
            case FREEFOLLOW, DEARM, ARM -> {
                runtime.setTargetPlayerUuid(this.context.streamerService().configuredUuid().orElse(null));
                parrot.setForcedDancing(false);
            }
            case SCREAM -> this.context.soundService().playScream(parrot);
            case LOVE -> {
                if (this.context.shoulderService().contains(viewerId) && parrot.getWorld() instanceof ServerWorld world) {
                    // Shoulder NBT has no tracked entity ID for the vanilla entity-status packet.
                    world.spawnParticles(ParticleTypes.HEART, parrot.getX(), parrot.getY() + 0.5,
                            parrot.getZ(), 7, 0.2, 0.2, 0.2, 0.02);
                } else parrot.getWorld().sendEntityStatus(parrot, EntityStatuses.ADD_BREEDING_PARTICLES);
            }
            case EXP -> {
                if (!dropExperienceBottle(parrot)) {
                    CommandResult failed = CommandResult.failed(commandId, ErrorCode.INTERNAL_ERROR, null);
                    completeDeferred(commandId, viewerId, failed);
                    return failed;
                }
            }
            default -> { }
        }
        this.context.shoulderService().update(parrot);

        // Install the action before notifying listeners. A listener may synchronously
        // supersede it; the old stack frame must then stop before persisting or applying anything else.
        publishAccepted(commandId, pending);
        if (this.pendingCommands.get(commandId) != pending) return this.resultCache.find(commandId).orElseThrow();

        CompletableFuture<Void> durable = preservesBehavior(action)
                ? CompletableFuture.completedFuture(null) : persistDesiredBehavior(viewerId, runtime);
        durable.whenComplete((ignored, error) -> {
            if (this.pendingCommands.get(commandId) != pending) return;
            if (error != null) {
                AuditLog.error("command_persistence_failed", AuditLog.Context.of(commandId, pending.identity, action.name()),
                        "FAILED", ErrorCode.STORAGE_UNAVAILABLE.name(), pending.startedNanos, Map.of(), error);
                runtime.resetTransientState();
                parrot.getNavigation().stop();
                completeDeferred(commandId, viewerId, CommandResult.failed(commandId, ErrorCode.STORAGE_UNAVAILABLE, null));
                return;
            }
            if (!oldState.equals(newState)) publish(pending.identity, ParrotEvent.Type.BEHAVIOR_CHANGED, commandId, oldState, newState, null);
            pending.durable = true;
            if (action == ParrotAction.COME || action == ParrotAction.DANCE) {
                if (pending.movementResult != null) completeDeferred(commandId, viewerId, pending.movementResult);
            } else {
                completeDeferred(commandId, viewerId, CommandResult.succeeded(commandId, "viewerparrots.action.ok",
                        CommandResult.details("viewerId", viewerId.value(), "action", action.name())));
            }
        });
        AuditLog.debug("command_executed", AuditLog.Context.of(commandId, pending.identity, action.name()),
                "EXECUTED", null,
                pending.startedNanos, Map.of("behavior", runtime.behavior().name(), "desiredBehavior", runtime.desiredBehavior().name()), null);
        return this.resultCache.find(commandId).orElseGet(() -> accepted(commandId, viewerId, action, "viewerparrots.action.accepted", null));
    }

    public void completeDeferred(UUID commandId, ViewerId viewerId, CommandResult result) {
        if (!result.status().isTerminal()) return;
        PendingCommand pending = this.pendingCommands.remove(commandId);
        if (pending == null) return;
        this.activeIntentCommands.remove(viewerId, commandId);
        this.resultCache.remember(result);
        publish(pending.identity, ParrotEvent.Type.COMMAND_COMPLETED, commandId,
                CommandStatus.ACCEPTED.name(), result.status().name(), result.code() == null ? null : result.code().name());
        AuditLog.debug("command_completed", AuditLog.Context.of(commandId, pending.identity, pending.action.name()),
                result.status().name(), result.code() == null ? null : result.code().name(), pending.startedNanos,
                Map.of("retryAfterTicks", result.retryAfterTicks(), "details", result.details()), null);
    }

    public void cancelDeferred(UUID commandId, ViewerId viewerId, ErrorCode reason) {
        cancel(commandId, viewerId, reason);
    }

    public void behaviorChanged(ViewerId viewerId, String oldState, String newState, String reason) {
        if (!oldState.equals(newState)) {
            long started = AuditLog.started();
            AuditLog.Context identity = diagnosticIdentity(null, viewerId, "BEHAVIOR");
            publishCurrent(ParrotEvent.Type.BEHAVIOR_CHANGED, viewerId, null, oldState, newState, reason);
            AuditLog.debug("behavior_changed", identity, newState, reason, started,
                    Map.of("oldState", oldState, "newState", newState), null);
        }
    }

    /** COME completes after both its durable intent update and actual movement result. */
    public void finishMovement(UUID commandId, ViewerId viewerId, ErrorCode error) {
        PendingCommand pending = this.pendingCommands.get(commandId);
        if (pending == null) return;
        CommandResult result = error == null
                ? CommandResult.succeeded(commandId, "viewerparrots.action.ok", CommandResult.details("viewerId", viewerId.value(), "action", "COME"))
                : CommandResult.failed(commandId, error, null);
        if (pending.durable) completeDeferred(commandId, viewerId, result);
        else pending.movementResult = result;
    }

    public void finishDance(UUID commandId, ViewerId viewerId) {
        PendingCommand pending = this.pendingCommands.get(commandId);
        if (pending == null) return;
        CommandResult result = CommandResult.succeeded(commandId, "viewerparrots.action.ok",
                CommandResult.details("viewerId", viewerId.value(), "action", "DANCE"));
        if (pending.durable) completeDeferred(commandId, viewerId, result);
        else pending.movementResult = result;
    }

    public void cancelRuntime(ViewerId viewerId, ErrorCode reason) {
        ParrotRuntime runtime = this.context.runtimes().get(viewerId);
        if (runtime == null) return;
        java.util.List<UUID> commands = activeIds(runtime);
        runtime.resetTransientState();
        this.context.loadedIndex().find(viewerId).ifPresent(parrot -> {
            parrot.setForcedDancing(false);
            parrot.stopSpin();
            parrot.getNavigation().stop();
        });
        for (UUID id : commands) cancel(id, viewerId, reason);
    }

    public void cancelLazy(ViewerId viewerId, ErrorCode reason) {
        ParrotRuntime runtime = this.context.runtimes().get(viewerId);
        if (runtime == null) return;
        UUID commandId = runtime.pendingAction().map(ParrotRuntime.PendingAction::commandId).orElse(null);
        runtime.clearPendingAction();
        if (commandId != null) cancel(commandId, viewerId, reason);
    }

    private java.util.List<UUID> prepareCancellation(ParrotRuntime runtime) {
        java.util.List<UUID> commands = activeIds(runtime);
        var transition = BehaviorStateMachine.apply(runtime.behavior(), runtime.desiredBehavior(), BehaviorStateMachine.Event.DEACTIVATED);
        if (transition instanceof BehaviorStateMachine.Accepted accepted) runtime.apply(accepted, this.context.clock().currentTick());
        runtime.cancelAutonomousMovement();
        this.activeIntentCommands.remove(runtime.viewerId());
        this.context.loadedIndex().find(runtime.viewerId()).ifPresent(parrot -> {
            parrot.setForcedDancing(false);
            parrot.stopSpin();
            parrot.getNavigation().stop();
        });
        return commands;
    }

    private java.util.List<UUID> activeIds(ParrotRuntime runtime) {
        java.util.Set<UUID> ids = new java.util.LinkedHashSet<>(4);
        runtime.pendingAction().ifPresent(p -> ids.add(p.commandId()));
        runtime.movementCommandId().ifPresent(ids::add);
        runtime.danceCommandId().ifPresent(ids::add);
        UUID intent = this.activeIntentCommands.get(runtime.viewerId());
        if (intent != null) ids.add(intent);
        return java.util.List.copyOf(ids);
    }

    private void cancel(UUID commandId, ViewerId viewerId, ErrorCode reason) {
        PendingCommand pending = this.pendingCommands.get(commandId);
        String action = pending == null ? "" : pending.action.name();
        completeDeferred(commandId, viewerId, new CommandResult(commandId, CommandStatus.CANCELLED, reason,
                "viewerparrots.action.cancelled", CommandResult.details("viewerId", viewerId.value(), "action", action, "reason", reason.name()), 0));
    }

    public void clear() {
        for (UUID id : java.util.List.copyOf(this.pendingCommands.keySet())) {
            PendingCommand pending = this.pendingCommands.get(id);
            cancel(id, pending.identity.viewerId(), ErrorCode.INVALID_STATE);
        }
        this.resultCache.clear();
        this.activeIntentCommands.clear();
    }

    private CompletableFuture<Void> persistDesiredBehavior(ViewerId viewerId, ParrotRuntime runtime) {
        Optional<ViewerParrotRecord> record = this.context.registry().find(viewerId);
        if (record.isEmpty() || record.get().desiredBehavior() == runtime.desiredBehavior()) return CompletableFuture.completedFuture(null);
        return this.context.parrotService().recordDesiredBehavior(viewerId, runtime.desiredBehavior());
    }

    private static CommandResult accepted(UUID id, ViewerId viewerId, ParrotAction action, String key, Long delay) {
        Map<String, String> details = delay == null
                ? CommandResult.details("viewerId", viewerId.value(), "action", action.name())
                : CommandResult.details("viewerId", viewerId.value(), "action", action.name(), "delayTicks", Long.toString(delay));
        return new CommandResult(id, CommandStatus.ACCEPTED, null, key, details, 0);
    }

    private static boolean requiresTarget(ParrotAction action) { return action == ParrotAction.COME || action == ParrotAction.FOLLOW || action == ParrotAction.FREEFOLLOW || action == ParrotAction.ARM || action == ParrotAction.VIEW; }
    private static boolean preservesBehavior(ParrotAction action) { return CommandPolicy.preservesBehavior(action); }
    private static boolean isGameAction(ParrotAction action) { return switch (action) { case COME, FOLLOW, FREE, FREEFOLLOW, VIEW, DEVIEW, ARM, DEARM, SIT, DANCE, SPIN, HAT, DEHAT, SCREAM, LOVE, EXP -> true; default -> false; }; }
    private static ParrotAction cooldownKey(ParrotAction action) { return CommandPolicy.cooldownKey(action); }
    private static BehaviorStateMachine.Event toEvent(ParrotAction action) {
        return switch (action) {
            case COME -> BehaviorStateMachine.Event.COMMAND_COME;
            case FOLLOW -> BehaviorStateMachine.Event.COMMAND_FOLLOW;
            case FREE -> BehaviorStateMachine.Event.COMMAND_FREE;
            case FREEFOLLOW -> BehaviorStateMachine.Event.COMMAND_FREEFOLLOW;
            case VIEW -> BehaviorStateMachine.Event.COMMAND_VIEW;
            case DEVIEW -> BehaviorStateMachine.Event.COMMAND_DEVIEW;
            case ARM -> BehaviorStateMachine.Event.COMMAND_ARM;
            case DEARM -> BehaviorStateMachine.Event.COMMAND_DEARM;
            case SIT -> BehaviorStateMachine.Event.COMMAND_SIT;
            case DANCE -> BehaviorStateMachine.Event.COMMAND_DANCE;
            case SPIN -> BehaviorStateMachine.Event.COMMAND_SPIN;
            case HAT -> BehaviorStateMachine.Event.COMMAND_HAT;
            case DEHAT -> BehaviorStateMachine.Event.COMMAND_DEHAT;
            case SCREAM -> BehaviorStateMachine.Event.COMMAND_SCREAM;
            case LOVE -> BehaviorStateMachine.Event.COMMAND_LOVE;
            case EXP -> BehaviorStateMachine.Event.COMMAND_EXP;
            default -> throw new IllegalArgumentException("not a game action: " + action);
        };
    }
    private long cooldownFor(ParrotAction action) {
        var c = this.context.config().cooldowns();
        return switch (action) { case COME -> c.come(); case FOLLOW -> c.follow(); case SIT -> c.sit(); case DANCE -> c.dance(); case SPIN -> ParrotAction.SPIN_COOLDOWN_TICKS; case HAT, DEHAT -> ParrotAction.HAT_COOLDOWN_TICKS; case FREE, FREEFOLLOW, VIEW, DEVIEW, ARM, DEARM -> ParrotAction.SIMPLE_CONTROL_COOLDOWN_TICKS; case SCREAM -> c.scream(); case LOVE -> 40; case EXP -> ParrotAction.EXP_COOLDOWN_TICKS; default -> 0; };
    }

    private static boolean dropExperienceBottle(ViewerParrotEntity parrot) {
        // The owner is ignored by projectile collisions; only vanilla bottle physics award XP.
        var bottle = new ExperienceBottleEntity(parrot.getWorld(), parrot);
        // Keep the entire bottle below the top of the bird's existing collision-free space.
        double y = parrot.getY() + Math.max(0.0, parrot.getHeight() - bottle.getHeight());
        bottle.setPosition(parrot.getX(), y, parrot.getZ());
        bottle.setVelocity(0.0, -0.05, 0.0);
        return parrot.getWorld().spawnEntity(bottle);
    }
    private void publishCurrent(ParrotEvent.Type type, ViewerId viewerId, UUID commandId, String oldState, String newState, String reason) {
        Optional<ViewerParrotRecord> record = this.context.registry().find(viewerId);
        if (record.isPresent()) publish(record.get(), type, commandId, oldState, newState, reason);
        else this.context.events().publish(ParrotEvent.of(type, viewerId, null, 0,
                this.context.clock().now(), this.context.clock().currentTick(), commandId, oldState, newState, reason));
    }
    private void publish(ViewerParrotRecord record, ParrotEvent.Type type, UUID commandId, String oldState, String newState, String reason) {
        this.context.events().publish(ParrotEvent.of(type, record.viewerId(), record.incarnationId(), record.generation(),
                this.context.clock().now(), this.context.clock().currentTick(), commandId, oldState, newState, reason));
    }
    private void publishAccepted(UUID commandId, PendingCommand pending) {
        if (pending.acceptedPublished) return;
        pending.acceptedPublished = true;
        AuditLog.debug("command_accepted", AuditLog.Context.of(commandId, pending.identity, pending.action.name()),
                "ACCEPTED", null, pending.startedNanos, Map.of(), null);
        publish(pending.identity, ParrotEvent.Type.COMMAND_ACCEPTED, commandId, null, pending.action.name(), null);
        // Install the replacement before old completion notifications can issue yet another command.
        java.util.List<UUID> displaced = pending.displacedCommands;
        pending.displacedCommands = java.util.List.of();
        for (UUID previous : displaced) cancel(previous, pending.identity.viewerId(), ErrorCode.INVALID_STATE);
    }
    public Map<String, Integer> diagnostics() {
        int rejected = this.rejectionCounts.values().stream().mapToInt(Integer::intValue).sum();
        return Map.of("cachedResults", this.resultCache.size(), "pendingCommands", this.pendingCommands.size(), "rejectedCommands", rejected);
    }

    private AuditLog.Context diagnosticIdentity(UUID commandId, ViewerId viewerId, String action) {
        return this.context.registry().find(viewerId).map(record -> AuditLog.Context.of(commandId, record, action))
                .orElseGet(() -> AuditLog.Context.viewer(commandId, viewerId, action));
    }
    private static void auditRejected(AuditLog.Context identity, CommandResult result, long started) {
        AuditLog.debug("command_rejected", identity, result.status().name(), result.code().name(), started,
                Map.of("retryAfterTicks", result.retryAfterTicks()), null);
    }
}
