package io.github.viewerparrots.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.api.CommandStatus;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.api.CommandRequest;
import io.github.viewerparrots.api.ActionParameters;
import io.github.viewerparrots.api.event.ParrotEvent;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.config.ConfigLoader;
import io.github.viewerparrots.config.ViewerParrotsConfig;
import io.github.viewerparrots.command.CommandFeedback;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.domain.DisplayName;
import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.domain.ViewerId;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Config and async API contracts in a real server; every scenario restores its config. */
public final class ContractsGameTests implements FabricGameTest {
    @GameTest(batchId = "contracts", templateName = EMPTY_STRUCTURE, tickLimit = 1400)
    public void atomicReloadDeferredFeedbackAndPermissions(TestContext test) {
        if (!FixtureCallbacks.ready(test, () -> atomicReloadDeferredFeedbackAndPermissions(test))) return;
        Scenario scenario = new Scenario(test);
        scenario.run(scenario::start);
    }

    @GameTest(batchId = "reentrant", templateName = EMPTY_STRUCTURE, tickLimit = 1400)
    public void acceptedListenerCannotOverwriteNewerSitIntent(TestContext test) {
        if (!FixtureCallbacks.ready(test, () -> acceptedListenerCannotOverwriteNewerSitIntent(test))) return;
        Scenario scenario = new Scenario(test);
        scenario.run(scenario::reentrantAcceptance);
    }

    @GameTest(batchId = "feedback", templateName = EMPTY_STRUCTURE)
    public void everyFeedbackOutcomeContainsFullCommandId(TestContext test) {
        if (!FixtureCallbacks.ready(test, () -> everyFeedbackOutcomeContainsFullCommandId(test))) return;
        UUID command = UUID.randomUUID();
        var details = CommandResult.details("viewerId", "viewer", "action", "COME");
        List<CommandResult> outcomes = List.of(
                new CommandResult(command, CommandStatus.ACCEPTED, null, "viewerparrots.action.accepted", details, 0),
                CommandResult.succeeded(command, "viewerparrots.action.ok", details),
                CommandResult.rejected(command, ErrorCode.PERMISSION_DENIED),
                CommandResult.failed(command, ErrorCode.PATH_NOT_FOUND, null),
                new CommandResult(command, CommandStatus.CANCELLED, ErrorCode.TARGET_UNAVAILABLE,
                        "viewerparrots.action.cancelled", CommandResult.details("viewerId", "viewer", "action", "COME", "reason", "TARGET_UNAVAILABLE"), 0),
                CommandResult.succeeded(command, "viewerparrots.create.ok", CommandResult.details("viewerId", "viewer", "variant", "BLUE", "trait", "NORMAL")));
        for (CommandResult result : outcomes) {
            test.assertTrue(CommandFeedback.render(result).getString().contains(command.toString()),
                    result.status() + " feedback must contain the full command UUID");
        }
        test.complete();
    }

    private static final class Scenario {
        private static final ActorContext ADMIN = ActorContext.console(4);
        final TestContext test;
        final ServerContext context = ServerContext.current().orElseThrow();
        final String suffix = UUID.randomUUID().toString().substring(0, 8);
        final ViewerId id = ViewerId.of("contracts-" + suffix);
        final List<ViewerId> createdIds = new ArrayList<>();
        final List<ObservedEvent> events = new ArrayList<>();
        final Consumer<ParrotEvent> listener = this::observe;
        String originalConfig;
        ServerPlayerEntity streamer;
        long danceStart;
        UUID activeDanceId;
        Consumer<ParrotEvent> reentrantListener;
        Consumer<ParrotEvent> danceExpiryListener;
        Consumer<ParrotEvent> cancellationListener;
        boolean failing;

        record ObservedEvent(ParrotEvent event, CommandStatus cachedStatus, boolean lazyActive, boolean movementActive) {}

        Scenario(TestContext test) { this.test = test; }
        void run(Runnable action) {
            if (failing) return;
            try { action.run(); }
            catch (RuntimeException | Error failure) { failing = true; cleanupFailure(0, failure); }
        }
        <T> void await(CompletableFuture<T> future, Consumer<T> action) {
            if (future.isDone()) run(() -> action.accept(future.join()));
            else test.waitAndRun(1, () -> run(() -> await(future, action)));
        }
        void successful(CommandResult result, String action) { test.assertTrue(result.succeeded(), action + ": " + result); }
        JsonObject candidate() { return JsonParser.parseString(ConfigLoader.toJson(context.config())).getAsJsonObject(); }
        CommandResult reload(JsonObject json) {
            try { Files.writeString(context.configFile(), json.toString()); }
            catch (IOException error) { throw new IllegalStateException(error); }
            return FixtureCallbacks.finish(context.server(), context.reload(UUID.randomUUID(), ADMIN));
        }
        void observe(ParrotEvent event) {
            if (!event.viewerId().equals(id)) return;
            var runtime = context.runtimes().get(id);
            CommandStatus cached = event.commandId() == null ? null : context.commandService().resultCache()
                    .find(event.commandId()).map(CommandResult::status).orElse(null);
            events.add(new ObservedEvent(event, cached, runtime != null && runtime.pendingAction().isPresent(),
                    runtime != null && runtime.movementCommandId().isPresent()));
        }

        void start() {
            try { originalConfig = Files.readString(context.configFile()); }
            catch (IOException error) { throw new IllegalStateException(error); }
            for (int x = -10; x <= 26; x++) for (int z = -10; z <= 18; z++) {
                test.getWorld().setBlockState(test.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
            }
            streamer = test.createMockCreativeServerPlayerInWorld();
            Vec3d target = Vec3d.ofBottomCenter(test.getAbsolutePos(new BlockPos(4, 2, 4)));
            streamer.refreshPositionAndAngles(target.x, target.y, target.z, 0, 0);
            context.events().subscribe(listener);
            JsonObject initial = candidate();
            initial.getAsJsonObject("cooldowns").addProperty("come", 0);
            initial.getAsJsonObject("cooldowns").addProperty("follow", 0);
            initial.getAsJsonObject("cooldowns").addProperty("dance", 0);
            initial.getAsJsonObject("behavior").addProperty("enabled", false);
            successful(reload(initial), "initial test config");
            await(context.streamerService().assign(UUID.randomUUID(), ADMIN, streamer.getUuid()), assigned -> {
                successful(assigned, "assign target");
                ViewerId forbidden = ViewerId.of("contracts-forbidden-" + suffix);
                await(context.parrotService().create(UUID.randomUUID(), ActorContext.console(2), forbidden, DisplayName.of("Denied")), denied -> {
                    test.assertTrue(denied.code() == ErrorCode.PERMISSION_DENIED, "Control operator cannot bypass admin permission via service");
                    test.assertTrue(!context.registry().contains(forbidden), "Denied create leaves no registry entry");
                    createParrots();
                });
            });
        }

        void reentrantAcceptance() {
            try { originalConfig = Files.readString(context.configFile()); }
            catch (IOException error) { throw new IllegalStateException(error); }
            for (int x = -10; x <= 26; x++) for (int z = -10; z <= 18; z++) {
                test.getWorld().setBlockState(test.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
            }
            streamer = test.createMockCreativeServerPlayerInWorld();
            Vec3d target = Vec3d.ofBottomCenter(test.getAbsolutePos(new BlockPos(4, 2, 4)));
            streamer.refreshPositionAndAngles(target.x, target.y, target.z, 0, 0);
            JsonObject config = candidate();
            config.getAsJsonObject("behavior").addProperty("enabled", false);
            config.getAsJsonObject("cooldowns").addProperty("come", 0);
            config.getAsJsonObject("cooldowns").addProperty("follow", 0);
            successful(reload(config), "disable traits for reentrant acceptance");
            await(context.streamerService().assign(UUID.randomUUID(), ADMIN, streamer.getUuid()), assigned -> {
                successful(assigned, "reentrant target");
                createdIds.add(id);
                await(context.parrotService().create(UUID.randomUUID(), ADMIN, id, DisplayName.of("Reentrant")), created -> {
                    successful(created, "reentrant create");
                    UUID outer = UUID.randomUUID();
                    UUID sit = UUID.randomUUID();
                    reentrantListener = event -> {
                        if (event.type() == ParrotEvent.Type.COMMAND_ACCEPTED && outer.equals(event.commandId())) {
                            context.commandService().submit(sit, ADMIN, ParrotAction.SIT, id);
                        }
                    };
                    context.events().subscribe(reentrantListener);
                    context.commandService().submit(outer, ADMIN, ParrotAction.COME, id);
                    await(context.commandService().completion(sit), completed -> {
                        successful(completed, "reentrant newer sit");
                        test.assertTrue(context.commandService().resultCache().find(outer).orElseThrow().status() == CommandStatus.CANCELLED,
                                "Newer reentrant SIT cancels outer COME rather than being overwritten");
                        test.assertTrue(context.runtime(id).behavior() == BehaviorState.SITTING
                                        && context.runtime(id).desiredBehavior() == io.github.viewerparrots.domain.DesiredBehavior.SITTING
                                        && context.registry().find(id).orElseThrow().desiredBehavior() == io.github.viewerparrots.domain.DesiredBehavior.SITTING,
                                "Runtime and durable desired intent both retain newer SIT");
                        reentrantDanceExpiry();
                    });
                });
            });
        }

        void reentrantDanceExpiry() {
            JsonObject timing = candidate();
            timing.getAsJsonObject("dance").addProperty("durationTicks", 4);
            timing.getAsJsonObject("cooldowns").addProperty("dance", 0);
            successful(reload(timing), "short dance expiry regression");
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            boolean[] triggered = {false};
            danceExpiryListener = event -> {
                if (!triggered[0] && event.viewerId().equals(id) && event.type() == ParrotEvent.Type.BEHAVIOR_CHANGED
                        && "DANCE_EXPIRED".equals(event.reason())) {
                    triggered[0] = true;
                    context.commandService().submit(second, ADMIN, ParrotAction.DANCE, id);
                }
            };
            context.events().subscribe(danceExpiryListener);
            context.commandService().submit(first, ADMIN, ParrotAction.DANCE, id);
            await(context.commandService().completion(first), completed -> {
                successful(completed, "first dance expiry");
                test.assertTrue(triggered[0] && context.runtime(id).danceCommandId().filter(second::equals).isPresent()
                                && context.loadedIndex().find(id).orElseThrow().isForcedDancing(),
                        "Old expiry cleanup must preserve new dance started by a behavior listener");
                await(context.commandService().completion(second), next -> {
                    successful(next, "reentrant second dance expiry");
                    test.assertTrue(!context.loadedIndex().find(id).orElseThrow().isForcedDancing(), "Second dance ends on its own deadline");
                    reentrantCancellationNotification();
                });
            });
        }

        void reentrantCancellationNotification() {
            UUID original = UUID.randomUUID();
            UUID replacement = UUID.randomUUID();
            UUID newest = UUID.randomUUID();
            cancellationListener = event -> {
                if (event.type() == ParrotEvent.Type.COMMAND_COMPLETED && original.equals(event.commandId())) {
                    context.commandService().submit(newest, ADMIN, ParrotAction.FOLLOW, id);
                }
            };
            context.events().subscribe(cancellationListener);
            var parrot = context.loadedIndex().find(id).orElseThrow();
            Vec3d far = streamer.getPos().add(16, 0, 0);
            parrot.refreshPositionAndAngles(far.x, far.y, far.z, 0, 0);
            CommandResult first = context.commandService().submit(original, ADMIN, ParrotAction.COME, id);
            test.assertTrue(first.status() == CommandStatus.ACCEPTED, "COME is active before cancellation listener regression");
            context.commandService().submit(replacement, ADMIN, ParrotAction.SIT, id);
            await(context.commandService().completion(newest), completed -> {
                successful(completed, "newest FOLLOW from old cancellation notification");
                test.assertTrue(context.commandService().resultCache().find(replacement).orElseThrow().status() == CommandStatus.CANCELLED,
                        "Newer action from cancellation notification supersedes the replacing SIT");
                test.assertTrue(context.runtime(id).behavior() == BehaviorState.FOLLOWING
                                && context.registry().find(id).orElseThrow().desiredBehavior() == io.github.viewerparrots.domain.DesiredBehavior.FOLLOWING,
                        "Old cancellation stack cannot overwrite newest runtime or durable FOLLOW intent");
                cleanup(0);
            });
        }

        void createParrots() {
            List<CompletableFuture<CommandResult>> creates = new ArrayList<>();
            createdIds.add(id);
            for (int i = 0; i < 10; i++) createdIds.add(ViewerId.of("contracts-name-" + suffix + "-" + i));
            for (ViewerId viewerId : createdIds) {
                if (viewerId.equals(id)) {
                    CommandRequest request = new CommandRequest(UUID.randomUUID(), id, ParrotAction.CREATE,
                            new ActionParameters.Name(DisplayName.of("Name")));
                    var created = context.api().submit(request, ADMIN).toCompletableFuture();
                    test.assertTrue(context.api().submit(request, ADMIN).toCompletableFuture() == created,
                            "Typed API deduplicates accepted commandId without launching another create");
                    creates.add(created);
                } else creates.add(context.parrotService().create(UUID.randomUUID(), ADMIN, viewerId, DisplayName.of("Name")));
            }
            await(CompletableFuture.allOf(creates.toArray(CompletableFuture[]::new)), ignored -> {
                creates.forEach(future -> successful(future.join(), "parallel create"));
                var snapshot = context.api().get(id).orElseThrow();
                test.assertTrue(snapshot.record().viewerId().equals(id), "Typed API returns current immutable snapshot");
                CommandRequest wrongType = new CommandRequest(UUID.randomUUID(), id, ParrotAction.TRAIT,
                        new ActionParameters.Name(DisplayName.of("Wrong parameter type")));
                test.assertTrue(context.api().submit(wrongType, ADMIN).toCompletableFuture().join().code() == ErrorCode.INVALID_ARGUMENT,
                        "Typed facade rejects parameters incompatible with action");
                await(context.parrotService().setTrait(UUID.randomUUID(), ADMIN, id, ParrotTrait.LAZY), trait -> {
                    successful(trait, "set lazy");
                    boundedNamesAndPermissions();
                });
            });
        }

        void boundedNamesAndPermissions() {
            // Previous test batches may have left loaded fixtures. Normalize their names
            // to the current config, then measure the real global refresh budget.
            for (ViewerId viewer : context.loadedIndex().loadedViewerIds()) {
                context.registry().find(viewer).ifPresent(record ->
                        context.parrotService().controller().applyName(context.loadedIndex().find(viewer).orElseThrow(), record));
            }
            Map<ViewerId, String> beforeNames = allNames();
            JsonObject named = candidate();
            named.addProperty("nameFormat", "Reloaded {viewer_name}");
            named.addProperty("nameVisible", false);
            named.getAsJsonObject("permissions").addProperty("controlLevel", 4);
            named.getAsJsonObject("permissions").addProperty("adminLevel", 4);
            successful(reload(named), "valid names/permissions reload");
            long before = namedCount();
            test.assertTrue(before == 0, "Reload queues names instead of refreshing synchronously");
            context.refreshNames();
            test.assertTrue(changedNames(beforeNames) == 10, "Exactly ten global loaded names refreshed in first batch");
            int batches = (beforeNames.size() + 9) / 10;
            for (int batch = 1; batch < batches; batch++) {
                Map<ViewerId, String> previousNames = allNames();
                context.refreshNames();
                test.assertTrue(changedNames(previousNames) <= 10, "Subsequent name batches also stay within global budget");
            }
            test.assertTrue(namedCount() == 11, "Every owned loaded name is refreshed after bounded batches");
            test.assertTrue(createdIds.stream().allMatch(viewer -> !context.loadedIndex().find(viewer).orElseThrow().isCustomNameVisible()),
                    "Name visibility follows reloaded config");
            CommandResult denied = context.commandService().submit(UUID.randomUUID(), ActorContext.console(3), ParrotAction.SCREAM, id);
            test.assertTrue(denied.code() == ErrorCode.PERMISSION_DENIED, "Reloaded permissions apply immediately to service");
            UUID command = UUID.randomUUID();
            CommandResult initial = context.commandService().submit(command, ADMIN, ParrotAction.FOLLOW, id);
            test.assertTrue(initial.status() == CommandStatus.ACCEPTED || initial.succeeded(), "Explicit follow accepted with behavior disabled");
            test.assertTrue(context.runtime(id).pendingAction().isEmpty(), "Disabled behavior skips LAZY delay");
            await(context.commandService().completion(command), completed -> {
                successful(completed, "disabled LAZY follow final result");
                assertCommandEvents(command);
                submitThen(ParrotAction.SIT, this::preserveDeadlinesAndRejectAtomically);
            });
        }

        long namedCount() {
            return createdIds.stream().filter(viewer -> context.loadedIndex().find(viewer).orElseThrow()
                    .getCustomName().getString().equals("Reloaded Name")).count();
        }

        Map<ViewerId, String> allNames() {
            Map<ViewerId, String> names = new LinkedHashMap<>();
            for (ViewerId viewer : context.loadedIndex().loadedViewerIds()) {
                var entity = context.loadedIndex().find(viewer).orElseThrow();
                names.put(viewer, entity.getCustomName() == null ? "" : entity.getCustomName().getString());
            }
            return names;
        }
        long changedNames(Map<ViewerId, String> previous) {
            return allNames().entrySet().stream().filter(entry -> !entry.getValue().equals(previous.get(entry.getKey()))).count();
        }

        void preserveDeadlinesAndRejectAtomically() {
            JsonObject enabled = candidate();
            enabled.getAsJsonObject("behavior").addProperty("enabled", true);
            enabled.getAsJsonObject("behavior").addProperty("lazyMinTicks", 80);
            enabled.getAsJsonObject("behavior").addProperty("lazyMaxTicks", 80);
            enabled.getAsJsonObject("dance").addProperty("durationTicks", 80);
            successful(reload(enabled), "enable test LAZY");
            activeDanceId = UUID.randomUUID();
            CommandResult dance = context.commandService().submit(activeDanceId, ADMIN, ParrotAction.DANCE, id);
            test.assertTrue(dance.status() == CommandStatus.ACCEPTED, "DANCE remains accepted throughout its animation");
            test.assertTrue(!context.commandService().completion(activeDanceId).isDone(), "DANCE completion waits for expiry");
            run(() -> {
                danceStart = context.clock().currentTick();
                context.cooldowns().consume(id, ParrotAction.SCREAM, 333);
                long cooldownDeadline = context.clock().currentTick() + context.cooldowns().remainingTicks(id, ParrotAction.SCREAM);
                JsonObject future = candidate();
                future.getAsJsonObject("behavior").addProperty("lazyMinTicks", 1);
                future.getAsJsonObject("behavior").addProperty("lazyMaxTicks", 1);
                future.getAsJsonObject("dance").addProperty("durationTicks", 1);
                future.getAsJsonObject("cooldowns").addProperty("scream", 0);
                successful(reload(future), "future timing reload");
                test.assertTrue(context.clock().currentTick() + context.cooldowns().remainingTicks(id, ParrotAction.SCREAM) == cooldownDeadline,
                        "Reload preserves accepted cooldown deadline");
                test.assertTrue(!context.runtime(id).isDanceExpired(danceStart + 1), "Reload preserves ongoing dance duration");
                test.assertTrue(context.runtime(id).isDanceExpired(danceStart + 80), "Original dance deadline remains exact");
                ViewerParrotsConfig previous = context.config();
                var priorSounds = context.soundService();
                var priorPermissions = context.permissions();
                JsonObject invalid = candidate();
                invalid.addProperty("nameFormat", "Invalid partial name");
                invalid.getAsJsonObject("sound").getAsJsonArray("allowedSounds").add("minecraft:this_sound_does_not_exist");
                CommandResult rejected = reload(invalid);
                test.assertTrue(rejected.code() == ErrorCode.CONFIG_INVALID, "Unknown registry sound rejects reload");
                test.assertTrue(context.config() == previous && context.soundService() == priorSounds && context.permissions() == priorPermissions,
                        "Invalid reload preserves entire active configuration and service references");
                test.assertTrue(rejected.details().get("detail").contains("$.sound.allowedSounds"), "Invalid reload identifies JSON path");
                JsonObject restart = candidate();
                restart.addProperty("nameFormat", "Restart partial name");
                restart.getAsJsonObject("limits").addProperty("queueCapacity", previous.limits().queueCapacity() == 128 ? 64 : 128);
                test.assertTrue(reload(restart).code() == ErrorCode.CONFIG_RESTART_REQUIRED, "Queue capacity requires restart");
                test.assertTrue(context.config() == previous, "Restart-only reload rejects runtime changes atomically");
                test.waitAndRun(82, () -> run(this::preserveLazyDeadlineAndCancel));
            });
        }

        void preserveLazyDeadlineAndCancel() {
                successful(context.commandService().completion(activeDanceId).join(), "DANCE final result after original deadline");
                assertCommandEvents(activeDanceId);
                JsonObject enabled = candidate();
                enabled.getAsJsonObject("behavior").addProperty("lazyMinTicks", 80);
                enabled.getAsJsonObject("behavior").addProperty("lazyMaxTicks", 80);
                successful(reload(enabled), "restore LAZY duration");
                UUID lazyId = UUID.randomUUID();
                CommandResult lazy = context.commandService().submit(lazyId, ADMIN, ParrotAction.FOLLOW, id);
                test.assertTrue(lazy.status() == CommandStatus.ACCEPTED, "LAZY returns ACCEPTED rather than final success");
                long lazyDeadline = context.runtime(id).pendingAction().orElseThrow().executeAtTick();
                JsonObject future = candidate();
                future.getAsJsonObject("behavior").addProperty("lazyMinTicks", 1);
                future.getAsJsonObject("behavior").addProperty("lazyMaxTicks", 1);
                successful(reload(future), "change future LAZY delay");
                test.assertTrue(context.runtime(id).pendingAction().orElseThrow().executeAtTick() == lazyDeadline, "Reload preserves accepted LAZY deadline");
                JsonObject disabled = candidate();
                disabled.getAsJsonObject("behavior").addProperty("enabled", false);
                successful(reload(disabled), "disable pending LAZY");
                await(context.commandService().completion(lazyId), cancelled -> {
                    test.assertTrue(cancelled.status() == CommandStatus.CANCELLED, "Disabling behavior delivers final LAZY cancellation");
                    test.assertTrue(context.runtime(id).pendingAction().isEmpty(), "Disabling behavior clears delayed action");
                    assertCancellationObservedAfterClearing(lazyId);
                    comeCompletesAfterArrival();
                });
        }

        void comeCompletesAfterArrival() {
            test.assertTrue(context.runtime(id).behavior() == BehaviorState.SITTING, "Original dance expires and restores stable intent");
            successful(context.commandService().completion(activeDanceId).join(), "DANCE final result after original deadline");
            assertCommandEvents(activeDanceId);
            var parrot = context.loadedIndex().find(id).orElseThrow();
            Vec3d position = streamer.getPos().add(16, 0, 0);
            parrot.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
            UUID command = UUID.randomUUID();
            CommandResult initial = context.commandService().submit(command, ADMIN, ParrotAction.COME, id);
            test.assertTrue(initial.status() == CommandStatus.ACCEPTED, "COME stays accepted until arrival");
            test.assertTrue(!context.commandService().completion(command).isDone(), "COME does not report completion on navigation start");
            await(context.commandService().completion(command), completed -> {
                successful(completed, "COME final result");
                test.assertTrue(context.loadedIndex().find(id).orElseThrow().squaredDistanceTo(streamer) <= 9.5, "Final COME feedback follows real arrival");
                assertCommandEvents(command);
                delayedSitAndTargetCancellation();
            });
        }

        void delayedSitAndTargetCancellation() {
            JsonObject enabled = candidate();
            enabled.getAsJsonObject("behavior").addProperty("enabled", true);
            enabled.getAsJsonObject("behavior").addProperty("lazyMinTicks", 80);
            enabled.getAsJsonObject("behavior").addProperty("lazyMaxTicks", 80);
            successful(reload(enabled), "enable target cancellation test");
            UUID delayed = UUID.randomUUID();
            context.commandService().submit(delayed, ADMIN, ParrotAction.COME, id);
            test.assertTrue(context.runtime(id).pendingAction().isPresent(), "LAZY delayed COME exists before sit");
            submitThen(ParrotAction.SIT, () -> {
                test.assertTrue(context.commandService().completion(delayed).join().status() == CommandStatus.CANCELLED, "SIT sends final cancellation for displaced LAZY");
                test.assertTrue(context.runtime(id).behavior() == BehaviorState.SITTING, "SIT is immediate and never delayed by LAZY");
                assertCancellationObservedAfterClearing(delayed);
                UUID targetLost = UUID.randomUUID();
                context.commandService().submit(targetLost, ADMIN, ParrotAction.COME, id);
                context.monitorTarget();
                test.getWorld().getServer().getPlayerManager().remove(streamer);
                context.monitorTarget();
                await(context.commandService().completion(targetLost), cancelled -> {
                    test.assertTrue(cancelled.status() == CommandStatus.CANCELLED && cancelled.code() == ErrorCode.TARGET_UNAVAILABLE,
                            "Target loss sends final cancellation and reason");
                    assertCancellationObservedAfterClearing(targetLost);
                    cleanup(0);
                });
            });
        }

        void assertCommandEvents(UUID command) {
            List<ObservedEvent> commandEvents = events.stream().filter(e -> command.equals(e.event.commandId())).toList();
            test.assertTrue(!commandEvents.isEmpty() && commandEvents.getFirst().event.type() == ParrotEvent.Type.COMMAND_ACCEPTED,
                    "Accepted event precedes command effects");
            test.assertTrue(commandEvents.getLast().event.type() == ParrotEvent.Type.COMMAND_COMPLETED, "Completion event follows command effects");
            test.assertTrue(commandEvents.getLast().cachedStatus != CommandStatus.ACCEPTED && commandEvents.getLast().cachedStatus != null,
                    "Completion listeners observe the final cached result");
        }
        void assertCancellationObservedAfterClearing(UUID command) {
            ObservedEvent terminal = events.stream().filter(e -> command.equals(e.event.commandId())
                    && e.event.type() == ParrotEvent.Type.COMMAND_COMPLETED).reduce((a, b) -> b).orElseThrow();
            test.assertTrue(terminal.cachedStatus == CommandStatus.CANCELLED, "Cancellation event follows terminal result storage");
            test.assertTrue(!terminal.lazyActive && !terminal.movementActive, "Cancellation listeners observe cleared pending action and movement");
            test.assertTrue(terminal.event.reason() != null, "Cancellation reason occupies event.reason");
        }
        void submitThen(ParrotAction action, Runnable next) {
            UUID command = UUID.randomUUID();
            CommandResult initial = context.commandService().submit(command, ADMIN, action, id);
            test.assertTrue(initial.status() == CommandStatus.ACCEPTED || initial.succeeded(), action + " rejected: " + initial);
            await(context.commandService().completion(command), result -> { successful(result, action.name()); next.run(); });
        }
        void cleanup(int index) {
            if (index == createdIds.size()) {
                restoreConfig();
                test.complete();
                return;
            }
            await(context.parrotService().remove(UUID.randomUUID(), ADMIN, createdIds.get(index)), removed -> {
                successful(removed, "cleanup registration");
                cleanup(index + 1);
            });
        }

        void cleanupFailure(int index, Throwable failure) {
            if (index == createdIds.size()) {
                try { restoreConfig(); } catch (RuntimeException restoreFailure) { failure.addSuppressed(restoreFailure); }
                test.waitAndRun(1, () -> {
                    if (failure instanceof Error error) throw error;
                    throw (RuntimeException) failure;
                });
                return;
            }
            ViewerId viewer = createdIds.get(index);
            if (context.parrotService().operationPending(viewer)) {
                test.waitAndRun(1, () -> cleanupFailure(index, failure));
            } else if (!context.registry().contains(viewer)) cleanupFailure(index + 1, failure);
            else awaitFailureCleanup(context.parrotService().remove(UUID.randomUUID(), ADMIN, viewer), index, failure);
        }
        void awaitFailureCleanup(CompletableFuture<CommandResult> removal, int index, Throwable failure) {
            if (!removal.isDone()) {
                test.waitAndRun(1, () -> awaitFailureCleanup(removal, index, failure));
                return;
            }
            try {
                CommandResult removed = removal.join();
                if (!removed.succeeded()) failure.addSuppressed(new IllegalStateException("Fixture cleanup failed: " + removed));
            } catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            cleanupFailure(index + 1, failure);
        }
        void restoreConfig() {
            context.events().unsubscribe(listener);
            if (reentrantListener != null) context.events().unsubscribe(reentrantListener);
            if (danceExpiryListener != null) context.events().unsubscribe(danceExpiryListener);
            if (cancellationListener != null) context.events().unsubscribe(cancellationListener);
            if (streamer != null && test.getWorld().getServer().getPlayerManager().getPlayer(streamer.getUuid()) != null) {
                test.getWorld().getServer().getPlayerManager().remove(streamer);
            }
            if (originalConfig == null) return;
            try {
                Files.writeString(context.configFile(), originalConfig);
                CommandResult restored = FixtureCallbacks.finish(context.server(), context.reload(UUID.randomUUID(), ADMIN));
                if (!restored.succeeded()) throw new IllegalStateException("Test could not restore original config: " + restored);
            } catch (IOException error) { throw new IllegalStateException(error); }
        }
    }
}
