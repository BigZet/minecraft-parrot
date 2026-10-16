package io.github.viewerparrots.api;

import io.github.viewerparrots.api.event.ParrotEvent;
import io.github.viewerparrots.bootstrap.CheckpointScheduler;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.diagnostics.AuditLog;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.PhysicalStatus;
import io.github.viewerparrots.service.CommandResultCache;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Typed facade over permission-checked services; no external transport is installed. */
public final class DefaultViewerParrotApi implements ViewerParrotApi {
    private final ServerContext context;
    private final CommandResultCache results;
    private final Set<UUID> publishingRejections = new HashSet<>();
    public DefaultViewerParrotApi(ServerContext context) {
        this.context = Objects.requireNonNull(context);
        this.results = new CommandResultCache(context.clock());
    }

    @Override public CompletableFuture<CommandResult> submit(CommandRequest request, ActorContext actor) {
        requireServerThread();
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actor, "actor");
        long started = AuditLog.started();
        AuditLog.Context identity = diagnosticIdentity(request);
        if (!context.acceptingCommands()) return earlyRejection(request, CommandResult.rejected(request.commandId(), ErrorCode.INVALID_STATE), identity, started);
        if (!context.permissions().isAllowed(actor, request.action())) return earlyRejection(request, CommandResult.rejected(request.commandId(), ErrorCode.PERMISSION_DENIED), identity, started);
        if (!request.parameters().supports(request.action())) return earlyRejection(request, CommandResult.rejected(request.commandId(), ErrorCode.INVALID_ARGUMENT), identity, started);
        Optional<CommandResult> previous = results.find(request.commandId());
        if (previous.isPresent()) {
            AuditLog.debug("api_command_cached", terminalIdentity(request, identity), previous.get().status().name(), null, started, Map.of(), null);
            return results.completion(request.commandId());
        }
        var access = io.github.viewerparrots.settings.CommandAccess.evaluate(actor, request.action(), request.viewerId());
        if (!access.allowed()) return earlyRejection(request, CommandResult.rejected(request.commandId(),
                io.github.viewerparrots.settings.CommandAccess.error(access)), identity, started);
        if (!results.hasCapacity()) return CompletableFuture.completedFuture(CommandResult.rejected(request.commandId(), ErrorCode.SERVER_BUSY));
        if (request.action().category() == ParrotAction.Category.DIAGNOSTIC) {
            var decision = context.rateLimiter().tryAcquireDiagnostic(actor.sourceKey());
            if (!decision.allowed()) return earlyRejection(request,
                    CommandResult.retryLater(request.commandId(), ErrorCode.GLOBAL_RATE_LIMITED, decision.retryAfterTicks()), identity, started);
        }
        boolean gameplay = request.action().countsTowardActionBudget();
        CompletableFuture<CommandResult> work;
        try { work = dispatch(request, actor); }
        catch (RuntimeException error) {
            CommandResult failed = CommandResult.failed(request.commandId(), ErrorCode.INTERNAL_ERROR, request.commandId().toString());
            results.remember(failed);
            AuditLog.error("api_command_failed", identity, "FAILED", ErrorCode.INTERNAL_ERROR.name(), started, Map.of(), error);
            earlyRejection(request, failed, identity, started);
            return results.completion(request.commandId());
        }
        boolean immediatelyRejected = work.isDone() && !work.isCompletedExceptionally() && work.getNow(null).status() == CommandStatus.REJECTED;
        results.remember(new CommandResult(request.commandId(), CommandStatus.ACCEPTED, null, "viewerparrots.api.accepted", Map.of(), 0));
        if (!immediatelyRejected) {
            AuditLog.debug("api_command_accepted", identity, "ACCEPTED", null, started, Map.of(), null);
            if (!gameplay) publish(request, ParrotEvent.Type.COMMAND_ACCEPTED, null, identity);
        }
        work.whenComplete((result, error) -> {
            CommandResult terminal = result;
            if (error != null) {
                AuditLog.error("api_command_failed", terminalIdentity(request, identity), "FAILED", ErrorCode.INTERNAL_ERROR.name(), started, Map.of(), error);
                terminal = CommandResult.failed(request.commandId(), ErrorCode.INTERNAL_ERROR, request.commandId().toString());
            }
            AuditLog.Context terminalIdentity = terminalIdentity(request, identity);
            results.remember(terminal);
            if (!gameplay) publish(request, immediatelyRejected ? ParrotEvent.Type.COMMAND_REJECTED : ParrotEvent.Type.COMMAND_COMPLETED,
                    terminal.code() == null ? null : terminal.code().name(), terminalIdentity);
            AuditLog.debug(immediatelyRejected ? "api_command_rejected" : "api_command_completed", terminalIdentity,
                    terminal.status().name(), terminal.code() == null ? null : terminal.code().name(), started,
                    Map.of("retryAfterTicks", terminal.retryAfterTicks(), "details", terminal.details()), null);
        });
        return results.completion(request.commandId());
    }

    private CompletableFuture<CommandResult> dispatch(CommandRequest request, ActorContext actor) {
        var id = request.commandId();
        var viewer = request.viewerId();
        return switch (request.action()) {
            case CREATE -> context.parrotService().create(id, actor, viewer, ((ActionParameters.Name) request.parameters()).name());
            case RENAME -> context.parrotService().rename(id, actor, viewer, ((ActionParameters.Name) request.parameters()).name());
            case TRAIT -> context.parrotService().setTrait(id, actor, viewer, ((ActionParameters.Trait) request.parameters()).trait());
            case REMOVE -> context.parrotService().remove(id, actor, viewer);
            case RESPAWN -> context.parrotService().respawn(id, actor, viewer);
            case SETSTREAMER -> context.streamerService().assign(id, actor, ((ActionParameters.Target) request.parameters()).playerUuid());
            case RECONCILE -> CompletableFuture.completedFuture(context.parrotService().reconcile(id, actor, viewer));
            case COME, FOLLOW, FREE, FREEFOLLOW, VIEW, DEVIEW, ARM, DEARM, SIT, DANCE, SPIN, SCREAM, LOVE, HAT, DEHAT, COLOR, NAME, EMOTE, EXP, ROULETTE -> {
                CommandResult initial = context.commandService().submit(id, actor, request.action(), viewer, request.parameters());
                yield initial.status() == CommandStatus.ACCEPTED ? context.commandService().completion(id) : CompletableFuture.completedFuture(initial);
            }
            case SAY -> context.speechService().say(id, actor, viewer, (ActionParameters.Speech) request.parameters());
            case RELOAD -> context.reload(id, actor);
            case SAVE -> {
                if (!context.allowsMutations()) yield rejected(request, ErrorCode.STORAGE_UNAVAILABLE);
                if (CheckpointScheduler.isCheckpointPending()) yield rejected(request, ErrorCode.OPERATION_PENDING);
                yield CheckpointScheduler.request(context).handle((checkpoint, error) -> error == null
                        ? CommandResult.succeeded(id, "viewerparrots.save.ok", CommandResult.details("sequence", Long.toString(checkpoint.checkpointSequence())))
                        : CommandResult.failed(id, context.acceptingCommands() ? ErrorCode.STORAGE_UNAVAILABLE : ErrorCode.INVALID_STATE, null));
            }
            case INFO -> {
                Optional<ParrotSnapshot> snapshot = get(viewer);
                yield snapshot.isEmpty() ? context.registry().tombstonesFor(viewer).isEmpty()
                        ? rejected(request, ErrorCode.VIEWER_NOT_FOUND)
                        : CompletableFuture.completedFuture(CommandResult.succeeded(id, "viewerparrots.api.ok",
                                CommandResult.details("viewerId", viewer.value(), "lifecycle", "REMOVED")))
                        : CompletableFuture.completedFuture(CommandResult.succeeded(id, "viewerparrots.api.ok",
                        CommandResult.details("viewerId", viewer.value(), "snapshot", snapshot.get().toString())));
            }
            case LIST -> {
                List<io.github.viewerparrots.domain.ViewerParrotRecord> all = context.registry().allSorted();
                int page = request.parameters() instanceof ActionParameters.Page p ? p.page() : 1;
                int totalPages = Math.max(1, (all.size() + 19) / 20);
                if (page > totalPages) yield rejected(request, ErrorCode.INVALID_ARGUMENT);
                int from = (page - 1) * 20;
                String viewers = all.subList(from, Math.min(from + 20, all.size())).stream().map(r -> r.viewerId().value()).reduce((a, b) -> a + "," + b).orElse("");
                yield CompletableFuture.completedFuture(CommandResult.succeeded(id, "viewerparrots.api.ok",
                        CommandResult.details("page", Integer.toString(page), "totalPages", Integer.toString(totalPages), "viewers", viewers)));
            }
            case STREAMER -> CompletableFuture.completedFuture(CommandResult.succeeded(id, "viewerparrots.api.ok",
                    CommandResult.details("uuid", context.streamerService().configuredUuid().map(Object::toString).orElse(""),
                            "available", Boolean.toString(context.streamerService().onlineTarget().isPresent()))));
            case STATUS -> CompletableFuture.completedFuture(CommandResult.succeeded(id, "viewerparrots.api.ok", statusDetails()));
            case HELP -> CompletableFuture.completedFuture(CommandResult.succeeded(id, "viewerparrots.api.ok",
                    CommandResult.details("commands", java.util.Arrays.stream(ParrotAction.values()).filter(a -> context.permissions().isAllowed(actor, a))
                            .map(Enum::name).reduce((a, b) -> a + "," + b).orElse(""))));
        };
    }

    @Override public Optional<ParrotSnapshot> get(ViewerId viewerId) {
        requireServerThread();
        return context.registry().find(Objects.requireNonNull(viewerId)).map(record -> {
            var runtime = context.runtimes().get(viewerId);
            return new ParrotSnapshot(record, context.lifecycle(viewerId), runtime == null ? null : runtime.behavior(),
                    runtime == null ? null : runtime.targetPlayerUuid().orElse(null), context.cooldowns().remainingFor(viewerId),
                    context.parrotService().operationPending(viewerId), runtime != null && runtime.isBehaviorDisabledByErrors(),
                    runtime == null ? null : runtime.pendingAction().map(p -> p.commandId()).orElse(null), runtime == null ? 0 : runtime.movementDeadlineTick());
        });
    }
    public void clear() { results.clear(); }
    private void requireServerThread() {
        if (!context.server().isOnThread()) throw new IllegalStateException("ViewerParrotApi must be used on the server thread");
    }
    private CompletableFuture<CommandResult> rejected(CommandRequest request, ErrorCode code) {
        return CompletableFuture.completedFuture(CommandResult.rejected(request.commandId(), code));
    }
    private CompletableFuture<CommandResult> earlyRejection(CommandRequest request, CommandResult result, AuditLog.Context identity, long started) {
        AuditLog.debug("api_command_rejected", identity, result.status().name(), result.code().name(), started,
                Map.of("retryAfterTicks", result.retryAfterTicks()), null);
        // A synchronous listener may repeat an already rejected request. Keep the permission
        // check and returned result intact without recursively republishing that same event.
        if (publishingRejections.add(request.commandId())) {
            try { publish(request, ParrotEvent.Type.COMMAND_REJECTED, result.code().name(), identity); }
            finally { publishingRejections.remove(request.commandId()); }
        }
        return CompletableFuture.completedFuture(result);
    }
    private AuditLog.Context diagnosticIdentity(CommandRequest request) {
        return request.viewerId() == null ? AuditLog.Context.viewer(request.commandId(), null, request.action().name())
                : context.registry().find(request.viewerId()).map(record -> AuditLog.Context.of(request.commandId(), record, request.action().name()))
                .orElseGet(() -> AuditLog.Context.viewer(request.commandId(), request.viewerId(), request.action().name()));
    }
    private AuditLog.Context terminalIdentity(CommandRequest request, AuditLog.Context initial) {
        return switch (request.action()) {
            case CREATE, REMOVE, RESPAWN, RENAME, TRAIT -> context.parrotService().diagnosticContext(request.commandId()).orElse(initial);
            default -> initial;
        };
    }
    private void publish(CommandRequest request, ParrotEvent.Type type, String reason, AuditLog.Context identity) {
        context.events().publish(ParrotEvent.of(type, request.viewerId(), identity.incarnationId(),
                identity.generation() == null ? 0L : identity.generation(), context.clock().now(), context.clock().currentTick(), request.commandId(),
                null, request.action().name(), reason));
    }
    private Map<String, String> statusDetails() {
        Map<String, String> details = new LinkedHashMap<>();
        var records = context.registry().allSorted();
        var commands = context.commandService().diagnostics();
        details.put("storageHealth", context.storageHealth().name());
        details.put("faultReason", context.storageFaultReason().orElse(""));
        details.put("registered", Integer.toString(context.registry().size()));
        details.put("loaded", Integer.toString(context.loadedIndex().size()));
        details.put("dead", Long.toString(records.stream().filter(record -> record.physicalStatus() == PhysicalStatus.DEAD).count()));
        details.put("missing", Long.toString(records.stream().filter(record -> record.physicalStatus() == PhysicalStatus.MISSING).count()));
        details.put("reserved", Integer.toString(context.registry().reservedSlots()));
        details.put("maxActiveParrots", Integer.toString(context.config().maxActiveParrots()));
        details.put("pendingReservations", Integer.toString(context.registry().pendingReservations()));
        details.put("tombstones", Integer.toString(context.registry().tombstones().size()));
        details.put("recoveryJobs", Integer.toString(context.recoveryService().activeJobs()));
        details.put("maxRecoveryJobs", Integer.toString(Math.min(io.github.viewerparrots.lifecycle.EntityRecoveryService.MAX_CONCURRENT_JOBS, context.config().recovery().maxConcurrentJobs())));
        details.put("dirty", Boolean.toString(context.registry().isDirty()));
        details.put("dirtyAgeTicks", Long.toString(context.dirtyAgeTicks()));
        details.put("checkpoint", Long.toString(context.repository().checkpointSequence()));
        details.put("queueDepth", Integer.toString(context.writer().queueDepth()));
        details.put("queueCapacity", Integer.toString(context.config().limits().queueCapacity()));
        details.put("runtimeReferences", Integer.toString(context.runtimes().size()));
        details.put("speech", context.speechService().configured() ? "READY" : "NOT_CONFIGURED");
        details.put("speechPending", Integer.toString(context.speechService().pendingCount()));
        commands.forEach((key, value) -> details.put(key, Integer.toString(value)));
        details.put("schedulerLastTickNanos", Long.toString(context.behaviorScheduler().lastTickNanos()));
        details.put("streamerUuid", context.streamerService().configuredUuid().map(UUID::toString).orElse(""));
        return details;
    }
}
