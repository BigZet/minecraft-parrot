package io.github.viewerparrots.gametest;

import com.google.gson.JsonParser;
import io.github.viewerparrots.api.*;
import io.github.viewerparrots.bootstrap.FabricLifecycleAdapter;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.bootstrap.StorageWriter;
import io.github.viewerparrots.domain.*;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.entity.ModEntities;
import io.github.viewerparrots.entity.ParrotMarker;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.entity.Entity;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Actual read gates contain only latches and strings; worker hooks never touch a world or TestContext. */
public final class BootstrapIoGameTests implements FabricGameTest {
    private static final ActorContext ADMIN = ActorContext.console(4);
    private static final AtomicReference<Observation> OBSERVATION = new AtomicReference<>();
    public static void beforeConfigIo() { beforeIo("config"); }
    public static void beforeRegistryIo() { beforeIo("registry"); }
    private static void beforeIo(String kind) {
        Observation observation = OBSERVATION.get();
        if (observation == null) return;
        String thread = Thread.currentThread().getName();
        observation.reads.add(kind + ":" + thread);
        if (!kind.equals(observation.gatedKind)) return;
        observation.entered.countDown();
        // The old synchronous implementation must report RED without deadlocking the server.
        if (!thread.equals("viewerparrots-storage")) return;
        try {
            if (!observation.release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Bootstrap I/O gate timed out");
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
    private static final class Observation {
        final String gatedKind;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final ConcurrentLinkedQueue<String> reads = new ConcurrentLinkedQueue<>();
        Observation(String kind) { this.gatedKind = kind; }
    }
    @GameTest(batchId = "bootstrap-io", templateName = EMPTY_STRUCTURE, tickLimit = 12000)
    public void loadingDefersExactSavedIdentityAndKeepsServerTicksRunning(TestContext test) {
        Scenario scenario = new Scenario(test); scenario.guard(scenario::bootstrap);
    }
    @GameTest(batchId = "reload-io", templateName = EMPTY_STRUCTURE, tickLimit = 12000)
    public void reloadIsAtomicNonblockingAndCannotPublishAfterContextStops(TestContext test) {
        Scenario scenario = new Scenario(test); scenario.guard(scenario::reload);
    }

    @GameTest(batchId = "shutdown-ingress", templateName = EMPTY_STRUCTURE, tickLimit = 12000)
    public void shutdownCompletionListenersCannotAdmitFreshCommandsOrRecovery(TestContext test) {
        Scenario scenario = new Scenario(test); scenario.guard(scenario::shutdownIngress);
    }

    @GameTest(batchId = "shutdown-affinity", templateName = EMPTY_STRUCTURE, tickLimit = 12000)
    public void stoppedMinecraftServerStillQueuesIoCallbacksOnItsOwnThread(TestContext test) {
        if (ServerContext.current().orElseThrow().storageHealth() == ServerContext.StorageHealth.LOADING) {
            test.waitAndRun(1, () -> stoppedMinecraftServerStillQueuesIoCallbacksOnItsOwnThread(test));
            return;
        }
        MinecraftServer server = test.getWorld().getServer();
        StorageWriter writer = new StorageWriter(server, 4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> callback = new AtomicReference<>();
        try {
            var stopped = MinecraftServer.class.getDeclaredField("stopped");
            stopped.setAccessible(true);
            boolean original = stopped.getBoolean(server);
            Thread serverThread = Thread.currentThread();
            CompletableFuture<Void> work = writer.submit(() -> {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("callback-affinity gate timed out");
                return 42;
            }).thenAccept(value -> callback.set(Thread.currentThread()));
            stopped.setBoolean(server, true);
            test.assertTrue(server.isStopped(), "Fixture exercises Minecraft's actual stopped-server execute policy");
            release.countDown();
            finishAffinity(test, writer, work, callback, serverThread, stopped, original);
        } catch (ReflectiveOperationException error) {
            release.countDown(); writer.close(); throw new IllegalStateException(error);
        }
    }

    private static void finishAffinity(TestContext test, StorageWriter writer, CompletableFuture<Void> work,
            AtomicReference<Thread> callback, Thread serverThread, java.lang.reflect.Field stopped, boolean original) {
        if (!work.isDone()) {
            test.waitAndRun(1, () -> finishAffinity(test, writer, work, callback, serverThread, stopped, original));
            return;
        }
        try {
            stopped.setBoolean(test.getWorld().getServer(), original);
            work.getNow(null);
            test.assertTrue(callback.get() == serverThread,
                    "Even after Minecraft marks itself stopped, I/O callbacks must be queued for the server thread");
            test.complete();
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        finally { writer.close(); }
    }
    private static final class Scenario {
        final TestContext test;
        final List<Observation> gates = new ArrayList<>();
        ServerContext context;
        String originalConfig;
        ViewerId fixtureId;
        ViewerParrotEntity fixture;
        Scenario(TestContext test) { this.test = test; context = ServerContext.current().orElseThrow(); }
        void bootstrap() {
            until(() -> context.storageHealth() != ServerContext.StorageHealth.LOADING, () -> {
                check(context.allowsMutations(), "Bootstrap fixture starts READY");
                fixtureId = ViewerId.of("bootstrap-" + UUID.randomUUID());
                BlockPos pos = test.getAbsolutePos(new BlockPos(1, 3, 1));
                ViewerParrotRecord record = ViewerParrotRecord.created(new Viewer(fixtureId, DisplayName.of("Saved identity"), context.clock().now()),
                        UUID.randomUUID(), UUID.randomUUID(), ParrotVariant.BLUE, ParrotTrait.NORMAL,
                        new WorldLocation(test.getWorld().getRegistryKey().getValue().toString(), pos.getX() + .5, pos.getY(), pos.getZ() + .5),
                        context.clock().now()).withDesiredBehavior(DesiredBehavior.SITTING, context.clock().now());
                context.registry().put(record);
                fixture = ModEntities.VIEWER_PARROT.create(test.getWorld());
                fixture.setUuid(record.entityUuid()); fixture.setMarker(ParrotMarker.of(record)); fixture.setPersistent(); fixture.setNoGravity(true);
                fixture.refreshPositionAndAngles(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0);
                check(test.getWorld().spawnEntity(fixture), "Exact fixture UUID is loaded");
                ServerContext.stop(test.getWorld().getServer());
                Observation observation = observe("config");
                context = restart();
                check(context.storageHealth() == ServerContext.StorageHealth.LOADING,
                        "Starting returns LOADING without synchronously reading files");
                check(fixture.isAiDisabled() && !fixture.isRemoved() && context.loadedIndex().size() == 0 && context.runtimes().isEmpty(),
                        "LOADING freezes exact existing identity without deleting it or creating runtime state");
                await(context.parrotService().create(UUID.randomUUID(), ADMIN, ViewerId.of("loading-denied"), DisplayName.of("Denied")), rejected -> {
                    check(rejected.code() == ErrorCode.STORAGE_UNAVAILABLE, "LOADING explicitly blocks mutations");
                    until(() -> observation.entered.getCount() == 0, () -> later(5, () -> {
                        check(context.storageHealth() == ServerContext.StorageHealth.LOADING,
                                "Five game ticks run while configuration I/O is blocked");
                        observation.release.countDown();
                        until(() -> context.storageHealth() != ServerContext.StorageHealth.LOADING, () -> {
                            check(context.allowsMutations(), "Asynchronous registry callback publishes READY");
                            check(observation.reads.contains("registry:viewerparrots-storage")
                                            && observation.reads.stream().allMatch(read -> read.endsWith(":viewerparrots-storage")),
                                    "Both configuration and registry reads use the one writer thread");
                            check(context.loadedIndex().find(fixtureId).orElseThrow() == fixture && !fixture.isAiDisabled()
                                            && fixture.marker().orElseThrow().matches(context.registry().find(fixtureId).orElseThrow()),
                                    "READY revalidates the exact deferred identity before enabling AI");
                            OBSERVATION.set(null);
                            await(context.parrotService().remove(UUID.randomUUID(), ADMIN, fixtureId), removed -> {
                                check(removed.succeeded(), "Identity fixture cleans up durably"); stopDuringLoading();
                            });
                        });
                    }));
                });
            });
        }
        void stopDuringLoading() {
            ServerContext.stop(test.getWorld().getServer());
            Observation observation = observe("config");
            ServerContext retired = restart();
            check(retired.storageHealth() == ServerContext.StorageHealth.LOADING, "Second initialization starts asynchronously");
            until(() -> observation.entered.getCount() == 0, () -> {
                observation.release.countDown();
                ServerContext.stop(test.getWorld().getServer());
                check(retired.storageHealth() != ServerContext.StorageHealth.READY,
                        "Initialization drained during shutdown cannot publish READY into a retired context");
                OBSERVATION.set(null); context = restart();
                until(() -> context.storageHealth() != ServerContext.StorageHealth.LOADING, () -> {
                    check(context.allowsMutations() && ServerContext.current().orElseThrow() == context,
                            "Replacement readiness belongs to the new context"); finish();
                });
            });
        }
        void reload() {
            until(() -> context.storageHealth() != ServerContext.StorageHealth.LOADING, () -> {
                try { originalConfig = Files.readString(context.configFile()); }
                catch (Exception error) { throw new IllegalStateException(error); }
                var before = context.config();
                var json = JsonParser.parseString(originalConfig).getAsJsonObject();
                json.addProperty("nameFormat", "async {viewer_name}"); writeConfig(json.toString());
                Observation observation = observe("config");
                CompletableFuture<CommandResult> work = submitReload();
                AtomicReference<Boolean> callbackOnServer = new AtomicReference<>();
                work.thenAccept(result -> callbackOnServer.set(test.getWorld().getServer().isOnThread()));
                check(!work.isDone(), "Reload returns a pending future instead of reading files in the game tick");
                until(() -> observation.entered.getCount() == 0, () -> {
                    await(submitReload(), rejected -> {
                        check(rejected.code() == ErrorCode.OPERATION_PENDING, "Concurrent reload is bounded");
                        later(5, () -> {
                            check(context.config() == before && !work.isDone(), "Five ticks run with the old immutable config while read is blocked");
                            observation.release.countDown();
                            await(work, result -> {
                                check(result.succeeded() && context.config().nameFormat().equals("async {viewer_name}"), "Valid config publishes atomically after I/O");
                                check(Boolean.TRUE.equals(callbackOnServer.get())
                                                && observation.reads.stream().allMatch(read -> read.equals("config:viewerparrots-storage")),
                                        "Reload reads on writer and completes on server thread");
                                OBSERVATION.set(null); staleReload();
                            });
                        });
                    });
                });
            });
        }
        void staleReload() {
            ServerContext retired = context; var before = context.config();
            var json = JsonParser.parseString(originalConfig).getAsJsonObject();
            json.addProperty("nameFormat", "retired {viewer_name}"); writeConfig(json.toString());
            Observation observation = observe("config");
            CompletableFuture<CommandResult> work = submitReload();
            until(() -> observation.entered.getCount() == 0, () -> {
                observation.release.countDown(); ServerContext.stop(test.getWorld().getServer());
                check(work.isDone() && work.getNow(null).code() == ErrorCode.INVALID_STATE && retired.config() == before,
                        "Delayed reload cannot publish after its context stops");
                OBSERVATION.set(null); writeConfig(originalConfig); context = restart();
                until(() -> context.storageHealth() != ServerContext.StorageHealth.LOADING, () -> {
                    check(context.allowsMutations() && context.config().nameFormat().equals(
                            JsonParser.parseString(originalConfig).getAsJsonObject().get("nameFormat").getAsString()), "New context loads its own restored config");
                    finish();
                });
            });
        }

        void shutdownIngress() {
            until(() -> context.storageHealth() != ServerContext.StorageHealth.LOADING, () -> {
                for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++)
                    test.getWorld().setBlockState(test.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
                var player = test.createMockCreativeServerPlayerInWorld();
                Vec3d pos = Vec3d.ofBottomCenter(test.getAbsolutePos(new BlockPos(1, 2, 1)));
                player.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0, 0);
                await(context.streamerService().assign(UUID.randomUUID(), ADMIN, player.getUuid()), assigned -> {
                    check(assigned.succeeded(), "Shutdown fixture assigns its live target");
                    fixtureId = ViewerId.of("shutdown-" + UUID.randomUUID());
                    await(context.parrotService().create(UUID.randomUUID(), ADMIN, fixtureId, DisplayName.of("Shutdown fixture")), created -> {
                        check(created.succeeded(), "Shutdown fixture creates an authorized entity");
                        fixture = context.loadedIndex().find(fixtureId).orElseThrow();
                        UUID danceId = UUID.randomUUID();
                        check(context.commandService().submit(danceId, ADMIN, ParrotAction.DANCE, fixtureId).status() == CommandStatus.ACCEPTED,
                                "A genuine unfinished command will complete while shutdown clears transient work");
                        ServerContext retired = context;
                        AtomicReference<CommandResult> raw = new AtomicReference<>(), recovery = new AtomicReference<>();
                        AtomicReference<CompletableFuture<CommandResult>> typed = new AtomicReference<>(), target = new AtomicReference<>();
                        AtomicReference<Boolean> observed = new AtomicReference<>(false);
                        retired.commandService().completion(danceId).thenAccept(cancelled -> {
                            observed.set(true);
                            raw.set(retired.commandService().submit(UUID.randomUUID(), ADMIN, ParrotAction.SIT, fixtureId));
                            typed.set(retired.api().submit(new CommandRequest(UUID.randomUUID(), fixtureId, ParrotAction.SIT, ActionParameters.None.INSTANCE), ADMIN).toCompletableFuture());
                            target.set(retired.streamerService().assign(UUID.randomUUID(), ADMIN, player.getUuid()));
                            fixture.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                            recovery.set(retired.parrotService().reconcile(UUID.randomUUID(), ADMIN, fixtureId));
                        });
                        ServerContext.stop(test.getWorld().getServer());
                        check(Boolean.TRUE.equals(observed.get()), "Shutdown actually invokes the unfinished command's completion listener");
                        check(raw.get().status() == CommandStatus.REJECTED && raw.get().code() == ErrorCode.INVALID_STATE,
                                "Raw gameplay ingress rejects fresh shutdown work");
                        check(typed.get().isDone() && typed.get().getNow(null).code() == ErrorCode.INVALID_STATE,
                                "Typed API ingress rejects before dispatching or publishing fresh work");
                        check(target.get().isDone() && target.get().getNow(null).code() == ErrorCode.INVALID_STATE,
                                "Streamer assignment cannot queue a write after the final snapshot");
                        check(recovery.get().code() == ErrorCode.INVALID_STATE && retired.recoveryService().activeJobs() == 0,
                                "Direct reconcile cannot restart recovery after shutdown cancelled every job");
                        test.getWorld().getServer().getPlayerManager().remove(player);
                        fixture.discard(); context = restart();
                        until(() -> context.storageHealth() != ServerContext.StorageHealth.LOADING, () ->
                                await(context.parrotService().remove(UUID.randomUUID(), ADMIN, fixtureId), removed -> {
                                    check(removed.succeeded(), "Shutdown identity fixture cleans up after the replacement initializes"); finish();
                                }));
                    });
                });
            });
        }
        CompletableFuture<CommandResult> submitReload() {
            return context.api().submit(new CommandRequest(UUID.randomUUID(), null, ParrotAction.RELOAD, ActionParameters.None.INSTANCE), ADMIN).toCompletableFuture();
        }
        Observation observe(String kind) { Observation value = new Observation(kind); gates.add(value); OBSERVATION.set(value); return value; }
        void writeConfig(String json) {
            try { Files.writeString(context.configFile(), json); } catch (Exception error) { throw new IllegalStateException(error); }
        }
        ServerContext restart() {
            try {
                var method = FabricLifecycleAdapter.class.getDeclaredMethod("onServerStarted", net.minecraft.server.MinecraftServer.class);
                method.setAccessible(true); method.invoke(null, test.getWorld().getServer()); return ServerContext.current().orElseThrow();
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        void until(java.util.function.BooleanSupplier condition, Runnable next) { if (condition.getAsBoolean()) guard(next); else later(1, () -> until(condition, next)); }
        <T> void await(CompletableFuture<T> work, Consumer<T> next) { if (work.isDone()) guard(() -> next.accept(work.getNow(null))); else later(1, () -> await(work, next)); }
        void later(long ticks, Runnable next) { test.waitAndRun(ticks, () -> guard(next)); }
        void check(boolean condition, String message) { test.assertTrue(condition, message); }
        void finish() { OBSERVATION.set(null); gates.forEach(gate -> gate.release.countDown()); test.complete(); }
        void guard(Runnable work) {
            try { work.run(); }
            catch (RuntimeException | Error error) {
                OBSERVATION.set(null); gates.forEach(gate -> gate.release.countDown());
                if (fixture != null) fixture.discard();
                ServerContext.current().ifPresent(active -> {
                    if (fixtureId != null) active.registry().remove(fixtureId, active.clock().now());
                    if (originalConfig != null) try { Files.writeString(active.configFile(), originalConfig); }
                    catch (Exception restoreFailure) { error.addSuppressed(restoreFailure); }
                });
                if (ServerContext.current().isEmpty()) context = restart();
                throw error;
            }
        }
    }
}
