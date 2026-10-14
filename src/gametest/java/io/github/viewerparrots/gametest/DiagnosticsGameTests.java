package io.github.viewerparrots.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.viewerparrots.api.ActionParameters;
import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.api.CommandRequest;
import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.api.CommandStatus;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.api.event.ParrotEvent;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.config.ConfigLoader;
import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.config.DeathMode;
import io.github.viewerparrots.domain.DisplayName;
import io.github.viewerparrots.domain.PhysicalStatus;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.nio.file.Files;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Real durable services and control completion observed through their actual SLF4J logger. */
public final class DiagnosticsGameTests implements FabricGameTest {
    @GameTest(batchId = "diagnostics", templateName = EMPTY_STRUCTURE, tickLimit = 600)
    public void structuredCommandsRetainIdentityAndListenerOrder(TestContext test) {
        if (!FixtureCallbacks.ready(test, () -> structuredCommandsRetainIdentityAndListenerOrder(test))) return;
        new Scenario(test).run();
    }

    @GameTest(batchId = "diagnostics-policy", templateName = EMPTY_STRUCTURE, tickLimit = 600)
    public void deathModeReloadRejectsPendingDeadRespawnButAllowsMissingReplacement(TestContext test) {
        if (!FixtureCallbacks.ready(test, () -> deathModeReloadRejectsPendingDeadRespawnButAllowsMissingReplacement(test))) return;
        PolicyScenario scenario = new PolicyScenario(test);
        scenario.safely(scenario::start);
    }

    private static final class PolicyScenario {
        final TestContext test;
        final ServerContext context = ServerContext.current().orElseThrow();
        final ViewerId viewer = ViewerId.of("policy-" + UUID.randomUUID().toString().substring(0, 8));
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);
        String originalConfig;
        ServerPlayerEntity target;
        ViewerParrotRecord dead;
        boolean failed;

        PolicyScenario(TestContext test) { this.test = test; }
        void start() {
            try { originalConfig = Files.readString(context.configFile()); }
            catch (IOException error) { throw new IllegalStateException(error); }
            for (int x = -8; x <= 16; x++) for (int z = -8; z <= 16; z++)
                test.getWorld().setBlockState(test.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
            target = test.createMockCreativeServerPlayerInWorld();
            Vec3d position = Vec3d.ofBottomCenter(test.getAbsolutePos(new BlockPos(4, 2, 4)));
            target.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
            writeConfig(DeathMode.MANUAL_RESPAWN);
            await(context.reload(UUID.randomUUID(), Scenario.ADMIN), reloaded -> {
                successful(reloaded);
                await(context.streamerService().assign(UUID.randomUUID(), Scenario.ADMIN, target.getUuid()), assigned -> {
                    successful(assigned);
                    await(context.parrotService().create(UUID.randomUUID(), Scenario.ADMIN, viewer, DisplayName.of("Policy")), created -> {
                        successful(created);
                        var bird = context.loadedIndex().find(viewer).orElseThrow();
                        bird.kill();
                        test.assertTrue(context.registry().find(viewer).orElseThrow().physicalStatus() == PhysicalStatus.DEAD,
                                "Actual administrative death creates the DEAD fixture");
                        bird.discard();
                        dead = context.registry().find(viewer).orElseThrow();
                        await(context.writer().submit(() -> true), ignored -> gateReload());
                    });
                });
            });
        }
        void gateReload() {
            context.writer().submit(() -> {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("QA writer gate timed out");
                return true;
            });
            waitForGate();
        }
        void waitForGate() {
            if (entered.getCount() != 0) { test.waitAndRun(1, () -> safely(this::waitForGate)); return; }
            writeConfig(DeathMode.VANILLA_FINAL);
            // Both requests enter under MANUAL. The actual reload read is queued before
            // PREPARE, so its server callback publishes VANILLA_FINAL before finishRespawn.
            CompletableFuture<CommandResult> reload = context.reload(UUID.randomUUID(), Scenario.ADMIN);
            CompletableFuture<CommandResult> respawn = context.parrotService().respawn(UUID.randomUUID(), Scenario.ADMIN, viewer);
            test.assertTrue(!respawn.isDone() && context.config().deathMode() == DeathMode.MANUAL_RESPAWN,
                    "DEAD respawn is genuinely pending under the original policy");
            release.countDown();
            await(reload, reloaded -> {
                successful(reloaded);
                test.assertTrue(context.config().deathMode() == DeathMode.VANILLA_FINAL, "Actual reload published the restrictive policy");
                await(respawn, result -> {
                    test.assertTrue(result.status() == CommandStatus.REJECTED && result.code() == io.github.viewerparrots.diagnostics.ErrorCode.RESPAWN_DISABLED,
                            "Pending DEAD respawn obeys the newly published death policy: " + result);
                    var current = context.registry().find(viewer).orElseThrow();
                    test.assertTrue(current.equals(dead) && !context.loadedIndex().isLoaded(viewer)
                                    && context.registry().pendingReservations() == 0 && !context.parrotService().operationPending(viewer),
                            "Policy rejection retains the DEAD identity and releases temporary resources");
                    await(context.parrotService().remove(UUID.randomUUID(), Scenario.ADMIN, viewer), removed -> {
                        successful(removed);
                        missingReplacement();
                    });
                });
            });
        }
        void missingReplacement() {
            await(context.parrotService().create(UUID.randomUUID(), Scenario.ADMIN, viewer, DisplayName.of("Missing")), created -> {
                successful(created);
                ViewerParrotRecord original = context.registry().find(viewer).orElseThrow();
                context.loadedIndex().find(viewer).orElseThrow().discard();
                context.recoveryService().start(viewer);
                waitForMissing(original, context.clock().currentTick() + 200);
            });
        }
        void waitForMissing(ViewerParrotRecord original, long deadline) {
            if (context.registry().find(viewer).orElseThrow().physicalStatus() != PhysicalStatus.MISSING) {
                test.assertTrue(context.clock().currentTick() < deadline, "Actual missing-body recovery completes within its deadline");
                test.waitAndRun(1, () -> safely(() -> waitForMissing(original, deadline)));
                return;
            }
            await(context.parrotService().respawn(UUID.randomUUID(), Scenario.ADMIN, viewer), replacement -> {
                successful(replacement);
                ViewerParrotRecord current = context.registry().find(viewer).orElseThrow();
                test.assertTrue(current.generation() == original.generation() + 1
                                && current.incarnationId().equals(original.incarnationId()) && !current.entityUuid().equals(original.entityUuid()),
                        "VANILLA_FINAL still allows legitimate MISSING replacement with a new generation and UUID");
                await(context.parrotService().remove(UUID.randomUUID(), Scenario.ADMIN, viewer), removed -> {
                    successful(removed);
                    restoreConfig();
                    removeTarget();
                    test.complete();
                });
            });
        }
        void writeConfig(DeathMode mode) {
            JsonObject candidate = JsonParser.parseString(ConfigLoader.toJson(context.config())).getAsJsonObject();
            candidate.addProperty("deathMode", mode.name());
            candidate.getAsJsonObject("behavior").addProperty("enabled", false);
            try { Files.writeString(context.configFile(), candidate.toString()); }
            catch (IOException error) { throw new IllegalStateException(error); }
        }
        void successful(CommandResult result) { test.assertTrue(result.succeeded(), "Policy fixture operation succeeds: " + result); }
        <T> void await(CompletableFuture<T> future, Consumer<T> next) {
            if (future.isDone()) safely(() -> next.accept(future.join()));
            else test.waitAndRun(1, () -> await(future, next));
        }
        void safely(Runnable work) {
            if (failed) return;
            try { work.run(); }
            catch (RuntimeException | Error error) {
                failed = true;
                release.countDown();
                cleanup(error);
            }
        }
        void cleanup(Throwable error) {
            if (context.parrotService().operationPending(viewer)) { test.waitAndRun(1, () -> cleanup(error)); return; }
            CompletableFuture<CommandResult> removal = context.registry().contains(viewer)
                    ? context.parrotService().remove(UUID.randomUUID(), Scenario.ADMIN, viewer)
                    : CompletableFuture.completedFuture(null);
            cleanupAfterRemoval(removal, error);
        }
        void cleanupAfterRemoval(CompletableFuture<CommandResult> removal, Throwable error) {
            if (!removal.isDone()) { test.waitAndRun(1, () -> cleanupAfterRemoval(removal, error)); return; }
            try { restoreConfig(); } catch (RuntimeException restore) { error.addSuppressed(restore); }
            removeTarget();
            test.waitAndRun(1, () -> { if (error instanceof Error fatal) throw fatal; throw (RuntimeException) error; });
        }
        void restoreConfig() {
            if (originalConfig == null) return;
            try { Files.writeString(context.configFile(), originalConfig); }
            catch (IOException error) { throw new IllegalStateException(error); }
            successful(FixtureCallbacks.finish(context.server(), context.reload(UUID.randomUUID(), Scenario.ADMIN)));
        }
        void removeTarget() {
            if (target != null && context.server().getPlayerManager().getPlayer(target.getUuid()) != null)
                context.server().getPlayerManager().remove(target);
        }
    }

    private static final class Scenario {
        static final ActorContext ADMIN = ActorContext.console(4);
        final TestContext test;
        final ServerContext context = ServerContext.current().orElseThrow();
        final ViewerId viewer = ViewerId.of("diagnostics-" + UUID.randomUUID().toString().substring(0, 8));
        final List<String> messages = new CopyOnWriteArrayList<>();
        final List<ParrotEvent.Type> order = new ArrayList<>();
        final Logger logger = (Logger) LogManager.getLogger("viewerparrots");
        final Level previousLevel = logger.getLevel();
        final AbstractAppender capture = new AbstractAppender("diagnostics-capture-" + viewer.value(), null, null, true, Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) {
                if (messages.size() < 4096) messages.add(event.getMessage().getFormattedMessage());
            }
        };
        ServerPlayerEntity target;
        ViewerParrotRecord identity;
        Consumer<ParrotEvent> failingListener;
        Consumer<ParrotEvent> observingListener;
        Consumer<ParrotEvent> globalListener;
        CountDownLatch saveGate;
        boolean failed;

        Scenario(TestContext test) { this.test = test; }
        void run() {
            capture.start();
            logger.addAppender(capture);
            logger.setLevel(Level.ALL);
            safely(() -> {
                for (int x = -8; x <= 16; x++) for (int z = -8; z <= 16; z++)
                    test.getWorld().setBlockState(test.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
                target = test.createMockCreativeServerPlayerInWorld();
                Vec3d position = Vec3d.ofBottomCenter(test.getAbsolutePos(new BlockPos(4, 2, 4)));
                target.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
                UUID assignment = UUID.randomUUID();
                List<ParrotEvent.Type> globalEvents = new ArrayList<>();
                globalListener = event -> { if (assignment.equals(event.commandId())) globalEvents.add(event.type()); };
                context.events().subscribe(globalListener);
                await(context.api().submit(new CommandRequest(assignment, null, ParrotAction.SETSTREAMER,
                        new ActionParameters.Target(target.getUuid())), ADMIN).toCompletableFuture(), assigned -> {
                    successful(assigned);
                    test.assertTrue(globalEvents.equals(List.of(ParrotEvent.Type.COMMAND_ACCEPTED, ParrotEvent.Type.COMMAND_COMPLETED)),
                            "Global SETSTREAMER publishes both phases with the same real command ID");
                    JsonObject stream = log(assignment, "streamer_completed");
                    test.assertTrue(stream.get("action").getAsString().equals("SETSTREAMER"), "Durable streamer result has actual action");
                    UUID creation = UUID.randomUUID();
                    await(context.parrotService().create(creation, ADMIN, viewer, DisplayName.of("Diagnostics")), created -> {
                        successful(created);
                        identity = context.registry().find(viewer).orElseThrow();
                        assertIdentity(log(creation, "mutation_completed"), identity, "CREATE", "SUCCEEDED");
                        var status = context.api().submit(new CommandRequest(UUID.randomUUID(), null, ParrotAction.STATUS,
                                ActionParameters.None.INSTANCE), ADMIN).toCompletableFuture().join();
                        successful(status);
                        for (String key : List.of("dead", "missing", "reserved", "recoveryJobs", "dirtyAgeTicks", "rejectedCommands", "schedulerLastTickNanos", "faultReason"))
                            test.assertTrue(status.details().containsKey(key), "Typed STATUS exposes required field " + key);
                        test.assertTrue(Integer.parseInt(status.details().get("reserved")) == context.registry().reservedSlots(), "STATUS reports actual reserved capacity");
                        control();
                    });
                });
            });
        }
        void control() {
            UUID sit = UUID.randomUUID();
            failingListener = event -> {
                if (sit.equals(event.commandId()) && event.type() == ParrotEvent.Type.COMMAND_ACCEPTED)
                    throw new IllegalStateException("test listener\nnew line");
            };
            observingListener = event -> {
                if (!sit.equals(event.commandId())) return;
                if (event.type() == ParrotEvent.Type.COMMAND_ACCEPTED || event.type() == ParrotEvent.Type.COMMAND_COMPLETED)
                    order.add(event.type());
                if (event.type() == ParrotEvent.Type.COMMAND_COMPLETED)
                    test.assertTrue(context.commandService().resultCache().find(sit).orElseThrow().status() == CommandStatus.SUCCEEDED,
                            "Completion listener still sees the final cached result");
            };
            context.events().subscribe(failingListener);
            context.events().subscribe(observingListener);
            CommandResult initial = context.commandService().submit(sit, ADMIN, ParrotAction.SIT, viewer);
            test.assertTrue(initial.status() == CommandStatus.ACCEPTED || initial.succeeded(), "Control accepted despite listener failure");
            await(context.commandService().completion(sit), completed -> {
                successful(completed);
                test.assertTrue(order.equals(List.of(ParrotEvent.Type.COMMAND_ACCEPTED, ParrotEvent.Type.COMMAND_COMPLETED)), "Existing event order is preserved");
                test.assertTrue(context.runtime(viewer).behavior() == BehaviorState.SITTING, "Control still applies actual state");
                assertIdentity(log(sit, "command_accepted"), identity, "SIT", "ACCEPTED");
                assertIdentity(log(sit, "command_completed"), identity, "SIT", "SUCCEEDED");
                JsonObject error = log(sit, "listener_failed");
                test.assertTrue(error.get("reason").getAsString().equals("INTERNAL_ERROR"), "Listener failure isolated and structured");
                UUID denied = UUID.randomUUID();
                var reject = context.api().submit(new CommandRequest(denied, viewer, ParrotAction.SIT, ActionParameters.None.INSTANCE), ActorContext.console(0)).toCompletableFuture().join();
                test.assertTrue(reject.status() == CommandStatus.REJECTED, "Facade retains its permission boundary");
                assertIdentity(log(denied, "api_command_rejected"), identity, "SIT", "REJECTED");
                UUID recursiveDenied = UUID.randomUUID();
                CommandRequest deniedRequest = new CommandRequest(recursiveDenied, viewer, ParrotAction.SIT, ActionParameters.None.INSTANCE);
                int[] rejectedEvents = {0};
                boolean[] nestedDenied = {false};
                Consumer<ParrotEvent> recursiveListener = event -> {
                    if (!recursiveDenied.equals(event.commandId()) || event.type() != ParrotEvent.Type.COMMAND_REJECTED) return;
                    rejectedEvents[0]++;
                    nestedDenied[0] = context.api().submit(deniedRequest, ActorContext.console(0)).toCompletableFuture().join().status() == CommandStatus.REJECTED;
                };
                context.events().subscribe(recursiveListener);
                try { context.api().submit(deniedRequest, ActorContext.console(0)).toCompletableFuture().join(); }
                finally { context.events().unsubscribe(recursiveListener); }
                test.assertTrue(rejectedEvents[0] == 1 && nestedDenied[0], "Same-ID rejection listener cannot recursively republish its own event or bypass permission");
                UUID rename = UUID.randomUUID();
                await(context.api().submit(new CommandRequest(rename, viewer, ParrotAction.RENAME,
                        new ActionParameters.Name(DisplayName.of("Renamed"))), ADMIN).toCompletableFuture(), renamed -> {
                    successful(renamed);
                    assertIdentity(log(rename, "api_command_completed"), identity, "RENAME", "SUCCEEDED");
                    UUID removal = UUID.randomUUID();
                    await(context.parrotService().remove(removal, ADMIN, viewer), removed -> {
                        successful(removed);
                        test.assertTrue(!context.registry().contains(viewer) && !context.loadedIndex().isLoaded(viewer), "Actual durable remove remains complete");
                        assertIdentity(log(removal, "mutation_completed"), identity, "REMOVE", "SUCCEEDED");
                        var removedInfo = context.api().submit(new CommandRequest(UUID.randomUUID(), viewer, ParrotAction.INFO,
                                ActionParameters.None.INSTANCE), ADMIN).toCompletableFuture().join();
                        test.assertTrue(removedInfo.succeeded() && "REMOVED".equals(removedInfo.details().get("lifecycle")),
                                "Typed INFO preserves known tombstone semantics for the command adapter");
                        checkpointBusy();
                    });
                });
            });
        }
        void checkpointBusy() {
            saveGate = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(1);
            context.writer().submit(() -> {
                started.countDown();
                if (!saveGate.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("QA checkpoint gate timed out");
                return true;
            });
            checkpointBusyAfterGate(started);
        }
        void checkpointBusyAfterGate(CountDownLatch started) {
            if (started.getCount() != 0) { test.waitAndRun(1, () -> safely(() -> checkpointBusyAfterGate(started))); return; }
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            List<ParrotEvent.Type> firstEvents = new ArrayList<>();
            List<ParrotEvent.Type> secondEvents = new ArrayList<>();
            Consumer<ParrotEvent> saveEvents = event -> {
                if (first.equals(event.commandId())) firstEvents.add(event.type());
                if (second.equals(event.commandId())) secondEvents.add(event.type());
            };
            context.events().subscribe(saveEvents);
            CompletableFuture<CommandResult> saved;
            try {
                saved = context.api().submit(new CommandRequest(first, null, ParrotAction.SAVE, ActionParameters.None.INSTANCE), ADMIN).toCompletableFuture();
                test.assertTrue(!saved.isDone(), "First checkpoint is genuinely waiting for the actual writer");
                var busy = context.api().submit(new CommandRequest(second, null, ParrotAction.SAVE, ActionParameters.None.INSTANCE), ADMIN).toCompletableFuture();
                test.assertTrue(busy.isDone() && busy.join().status() == CommandStatus.REJECTED
                                && busy.join().code() == io.github.viewerparrots.diagnostics.ErrorCode.OPERATION_PENDING,
                        "Second SAVE returns immediate expected busy rejection, never FAILED");
                test.assertTrue(secondEvents.equals(List.of(ParrotEvent.Type.COMMAND_REJECTED)), "Busy SAVE has no false accepted/completed event");
            } catch (RuntimeException | Error error) {
                context.events().unsubscribe(saveEvents);
                throw error;
            } finally { saveGate.countDown(); }
            await(saved, completed -> {
                try {
                    successful(completed);
                    test.assertTrue(firstEvents.equals(List.of(ParrotEvent.Type.COMMAND_ACCEPTED, ParrotEvent.Type.COMMAND_COMPLETED)), "Actual global SAVE retains its successful event sequence");
                } finally { context.events().unsubscribe(saveEvents); }
                detach();
                test.complete();
            });
        }
        JsonObject log(UUID command, String event) {
            List<JsonObject> matching = new ArrayList<>();
            for (String message : messages) {
                if (!message.startsWith("{")) continue;
                JsonObject json = JsonParser.parseString(message).getAsJsonObject();
                if (json.has("commandId") && !json.get("commandId").isJsonNull()
                        && command.toString().equals(json.get("commandId").getAsString())
                        && event.equals(json.get("event").getAsString())) {
                    test.assertTrue(!message.contains("\n") && !message.contains("\r"), "Single-line diagnostic output");
                    matching.add(json);
                }
            }
            test.assertTrue(matching.size() == 1, event + " has exactly one record for the actual command: " + matching.size());
            JsonObject result = matching.getFirst();
            for (String field : List.of("event", "commandId", "viewerId", "incarnationId", "generation", "entityUuid", "action", "status", "reason", "durationMs"))
                test.assertTrue(result.has(field), "Required structured field " + field);
            test.assertTrue(result.get("durationMs").getAsDouble() >= 0, "Actual nonnegative elapsed duration");
            return result;
        }
        void assertIdentity(JsonObject log, ViewerParrotRecord record, String action, String status) {
            test.assertTrue(log.get("viewerId").getAsString().equals(record.viewerId().value()), "Viewer retained");
            test.assertTrue(log.get("incarnationId").getAsString().equals(record.incarnationId().toString()), "Incarnation retained");
            test.assertTrue(log.get("generation").getAsLong() == record.generation(), "Generation retained");
            test.assertTrue(log.get("entityUuid").getAsString().equals(record.entityUuid().toString()), "Entity identity retained including after remove");
            test.assertTrue(log.get("action").getAsString().equals(action) && log.get("status").getAsString().equals(status), "Actual action and outcome");
        }
        void successful(CommandResult result) { test.assertTrue(result.succeeded(), "Operation remains successful: " + result); }
        <T> void await(CompletableFuture<T> future, Consumer<T> next) {
            if (future.isDone()) safely(() -> next.accept(future.join()));
            else test.waitAndRun(1, () -> await(future, next));
        }
        void safely(Runnable work) {
            if (failed) return;
            try { work.run(); }
            catch (RuntimeException | Error error) {
                failed = true;
                detach();
                cleanupFailure(error);
            }
        }
        void cleanupFailure(Throwable error) {
            if (context.parrotService().operationPending(viewer)) {
                test.waitAndRun(1, () -> cleanupFailure(error));
                return;
            }
            if (context.registry().contains(viewer)) {
                CompletableFuture<CommandResult> removed = context.parrotService().remove(UUID.randomUUID(), ADMIN, viewer);
                waitForCleanup(removed, error);
                return;
            }
            reportFailure(error);
        }
        void waitForCleanup(CompletableFuture<CommandResult> removed, Throwable error) {
            if (!removed.isDone()) { test.waitAndRun(1, () -> waitForCleanup(removed, error)); return; }
            reportFailure(error);
        }
        void reportFailure(Throwable error) {
            test.waitAndRun(1, () -> { if (error instanceof Error fatal) throw fatal; throw (RuntimeException) error; });
        }
        void detach() {
            if (saveGate != null) saveGate.countDown();
            if (failingListener != null) context.events().unsubscribe(failingListener);
            if (observingListener != null) context.events().unsubscribe(observingListener);
            if (globalListener != null) context.events().unsubscribe(globalListener);
            logger.removeAppender(capture);
            logger.setLevel(previousLevel);
            capture.stop();
            if (target != null && context.server().getPlayerManager().getPlayer(target.getUuid()) != null)
                context.server().getPlayerManager().remove(target);
        }
    }
}
