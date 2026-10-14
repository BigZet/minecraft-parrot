package io.github.viewerparrots.gametest;

import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.api.CommandStatus;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.domain.DisplayName;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.domain.PhysicalStatus;
import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.entity.ModEntities;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import io.github.viewerparrots.storage.JsonParrotRepository;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.WorldSavePath;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Deliberately holds the writer between spawn and COMMIT; test hooks are not shipped. */
public final class PreparedCommitGameTests implements FabricGameTest {
    private static final AtomicReference<Consumer<ViewerParrotEntity>> LOAD_INTERCEPTOR = new AtomicReference<>();
    static {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof ViewerParrotEntity parrot && parrot.isAwaitingAuthorization()) {
                Consumer<ViewerParrotEntity> handler = LOAD_INTERCEPTOR.get();
                if (handler != null) handler.accept(parrot);
            }
        });
    }

    @GameTest(batchId = "prepared-loss", templateName = EMPTY_STRUCTURE, tickLimit = 1400)
    public void removedAndUnloadedPreparedObjectsAreNotAuthorized(TestContext test) {
        Scenario scenario = new Scenario(test);
        scenario.setup(() -> scenario.exercise(false, false, Entity.RemovalReason.DISCARDED,
                () -> scenario.exercise(true, false, Entity.RemovalReason.UNLOADED_TO_CHUNK, scenario::finish)));
    }

    @GameTest(batchId = "prepared-degraded", templateName = EMPTY_STRUCTURE, tickLimit = 1400)
    public void storageFailureDuringCommitNeverEnablesPreparedAi(TestContext test) {
        Scenario scenario = new Scenario(test);
        scenario.setup(() -> scenario.exercise(false, true, null,
                () -> scenario.exercise(true, true, null, scenario::finish)));
    }

    @GameTest(batchId = "prepared-late-generation", templateName = EMPTY_STRUCTURE, tickLimit = 1800)
    public void lateOldBindingCannotSurviveOrUndoTheReplacementCommit(TestContext test) {
        Scenario scenario = new Scenario(test);
        scenario.setup(() -> scenario.lateOldBind(false, () -> scenario.lateOldBind(true, scenario::finish)));
    }

    @GameTest(batchId = "mutation-death", templateName = EMPTY_STRUCTURE, tickLimit = 10000)
    public void pendingMutationRejectsOnDeathAndConfirmedDeadAttributesSurviveReplay(TestContext test) {
        Scenario scenario = new Scenario(test);
        scenario.setup(() -> scenario.mutationDuringDeath(false,
                () -> scenario.mutationDuringDeath(true, scenario::finish)));
    }

    private static final class Scenario {
        private static final ActorContext ADMIN = ActorContext.console(4);
        final TestContext test;
        final ServerContext context = ServerContext.current().orElseThrow();
        final List<ViewerId> ids = new ArrayList<>();
        final List<ViewerParrotEntity> captured = new ArrayList<>();
        final List<CountDownLatch> gates = new ArrayList<>();
        ServerContext.StorageHealth originalHealth;
        String originalReason;
        ServerPlayerEntity streamer;

        Scenario(TestContext test) { this.test = test; }
        void setup(Runnable next) {
            if (context.storageHealth() == ServerContext.StorageHealth.LOADING) {
                test.waitAndRun(1, () -> setup(next));
                return;
            }
            originalHealth = context.storageHealth();
            originalReason = context.storageFaultReason().orElse(null);
            guard(() -> {
                test.assertTrue(originalHealth == ServerContext.StorageHealth.READY, "Test begins with healthy storage");
                for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++)
                    test.getWorld().setBlockState(test.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
                streamer = test.createMockCreativeServerPlayerInWorld();
                Vec3d position = Vec3d.ofBottomCenter(test.getAbsolutePos(new BlockPos(1, 2, 1)));
                streamer.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
                await(context.streamerService().assign(UUID.randomUUID(), ADMIN, streamer.getUuid()), assigned -> {
                    test.assertTrue(assigned.succeeded(), "Target assignment succeeds");
                    next.run();
                });
            });
        }

        void exercise(boolean respawn, boolean degraded, Entity.RemovalReason removal, Runnable next) {
            ViewerId id = ViewerId.of("prepared-" + UUID.randomUUID());
            ids.add(id);
            if (respawn) {
                await(context.parrotService().create(UUID.randomUUID(), ADMIN, id, DisplayName.of("Seed")), seed -> {
                    test.assertTrue(seed.succeeded(), "Seed registration for respawn succeeds");
                    ViewerParrotEntity old = context.loadedIndex().find(id).orElseThrow();
                    old.kill();
                    old.discard();
                    openWindow(id, true, degraded, removal, next);
                });
            } else openWindow(id, false, degraded, removal, next);
        }

        void openWindow(ViewerId id, boolean respawn, boolean degraded, Entity.RemovalReason removal, Runnable next) {
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch entered = new CountDownLatch(1);
            gates.add(release);
            AtomicReference<ViewerParrotEntity> prepared = new AtomicReference<>();
            LOAD_INTERCEPTOR.set(parrot -> {
                if (!parrot.marker().orElseThrow().viewerId().equals(id)) return;
                LOAD_INTERCEPTOR.set(null);
                prepared.set(parrot);
                captured.add(parrot);
                context.writer().submitForShutdown(() -> {
                    entered.countDown();
                    if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Prepared test gate timed out");
                    return null;
                });
            });
            CompletableFuture<CommandResult> committing = respawn
                    ? context.parrotService().respawn(UUID.randomUUID(), ADMIN, id)
                    : context.parrotService().create(UUID.randomUUID(), ADMIN, id, DisplayName.of("Prepared"));
            waitUntil(() -> prepared.get() != null && entered.getCount() == 0, () -> {
                ViewerParrotEntity parrot = prepared.get();
                var marker = parrot.marker().orElseThrow();
                test.assertTrue(!committing.isDone(), "COMMIT is actually queued behind the controlled writer gate");
                test.assertTrue(parrot.isAiDisabled(), "Prepared entity starts without AI");
                test.assertTrue(!context.loadedIndex().isLoaded(id), "Prepared entity is not indexed before COMMIT");
                if (degraded) context.degrade("test-only failure while prepared COMMIT is queued");
                else parrot.remove(removal);
                release.countDown();
                await(committing, result -> {
                    ViewerParrotRecord durable = context.registry().find(id).orElseThrow();
                    test.assertTrue(durable.incarnationId().equals(marker.incarnationId())
                            && durable.generation() == marker.generation() && durable.entityUuid().equals(parrot.getUuid()),
                            "A durable COMMIT retains its chosen identity even when authorization is impossible");
                    if (degraded) {
                        test.assertTrue(parrot.isAiDisabled(), "A late COMMIT must never re-enable AI after storage degraded");
                        test.assertTrue(!result.succeeded(), "Storage failure cannot report gameplay success");
                    } else {
                        test.assertTrue(!context.loadedIndex().isLoaded(id), "Removed/unloaded Java object must not enter loaded index");
                        test.assertTrue(!context.runtimes().containsKey(id), "Lost prepared object must not create a loaded runtime");
                        test.assertTrue(!result.succeeded(), "Absent prepared entity cannot report gameplay success");
                    }
                    test.assertTrue(context.registry().pendingReservations() == 0, "COMMIT releases exactly one reservation");
                    restoreInjectedHealth();
                    parrot.discard();
                    await(context.parrotService().remove(UUID.randomUUID(), ADMIN, id), cleaned -> {
                        test.assertTrue(cleaned.succeeded(), "Durable failed-to-authorize registration remains removable");
                        next.run();
                    });
                });
            });
        }

        void lateOldBind(boolean oldDiesAfterNewCommitQueued, Runnable next) {
            ViewerId id = ViewerId.of("late-commit-" + UUID.randomUUID());
            ids.add(id);
            await(context.parrotService().create(UUID.randomUUID(), ADMIN, id, DisplayName.of("Late old")), seed -> {
                test.assertTrue(seed.succeeded(), "Seed creation for late generation succeeds");
                ViewerParrotEntity original = context.loadedIndex().find(id).orElseThrow();
                ViewerParrotRecord originalRecord = context.registry().find(id).orElseThrow();
                NbtCompound oldNbt = new NbtCompound();
                original.writeNbt(oldNbt);
                original.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                context.registry().replace(context.registry().find(id).orElseThrow().asMissing(context.clock().now()));
                waitUntil(() -> test.getWorld().getEntity(originalRecord.entityUuid()) == null, () -> {
                    CountDownLatch release = new CountDownLatch(1);
                    CountDownLatch entered = new CountDownLatch(1);
                    gates.add(release);
                    AtomicReference<ViewerParrotEntity> prepared = new AtomicReference<>();
                    LOAD_INTERCEPTOR.set(parrot -> {
                        if (!parrot.marker().orElseThrow().viewerId().equals(id)) return;
                        LOAD_INTERCEPTOR.set(null);
                        prepared.set(parrot); captured.add(parrot);
                        context.writer().submitForShutdown(() -> {
                            entered.countDown();
                            if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Late generation gate timed out");
                            return null;
                        });
                    });
                    CompletableFuture<CommandResult> replacement = context.parrotService().respawn(UUID.randomUUID(), ADMIN, id);
                    waitUntil(() -> prepared.get() != null && entered.getCount() == 0, () -> {
                        test.assertTrue(!replacement.isDone(), "Replacement COMMIT is pending during late old load");
                        ViewerParrotEntity lateOld = ModEntities.VIEWER_PARROT.create(test.getWorld());
                        lateOld.readNbt(oldNbt); captured.add(lateOld);
                        test.assertTrue(test.getWorld().spawnEntity(lateOld), "Exact old UUID reaches ENTITY_LOAD before COMMIT");
                        test.assertTrue(context.loadedIndex().find(id).orElseThrow() == lateOld && !lateOld.isAiDisabled(),
                                "The currently committed old generation may bind before replacement COMMIT");
                        if (oldDiesAfterNewCommitQueued) {
                            lateOld.kill(); lateOld.discard();
                            test.assertTrue(context.registry().find(id).orElseThrow().physicalStatus() == PhysicalStatus.DEAD,
                                    "Old death is observed before new registry callback, while its journal write queues after new COMMIT");
                        }
                        release.countDown();
                        await(replacement, result -> {
                            test.assertTrue(result.succeeded(), "Durable replacement remains usable: " + result);
                            ViewerParrotEntity current = context.loadedIndex().find(id).orElseThrow();
                            test.assertTrue(current == prepared.get() && !current.isAiDisabled(), "Only the new prepared object becomes authorized");
                            test.assertTrue(lateOld.isRemoved(), "Late old physical object is discarded after durable replacement COMMIT");
                            ViewerParrotRecord chosen = context.registry().find(id).orElseThrow();
                            var directory = context.server().getSavePath(WorldSavePath.ROOT).resolve("data/viewerparrots");
                            CompletableFuture<ViewerParrotRecord> replay = context.writer().submit(() -> {
                                JsonParrotRepository reader = new JsonParrotRepository(directory);
                                try { return reader.load().records().stream().filter(record -> record.viewerId().equals(id)).findFirst().orElseThrow(); }
                                finally { reader.close(); }
                            });
                            await(replay, reconstructed -> {
                                test.assertTrue(reconstructed.generation() == chosen.generation()
                                                && reconstructed.incarnationId().equals(chosen.incarnationId())
                                                && reconstructed.entityUuid().equals(chosen.entityUuid()),
                                        "Journal replay retains the new generation even when older death was written later");
                                current.discard();
                                await(context.parrotService().remove(UUID.randomUUID(), ADMIN, id), cleaned -> {
                                    test.assertTrue(cleaned.succeeded(), "Late-generation fixture cleanup succeeds"); next.run();
                                });
                            });
                        });
                    });
                });
            });
        }

        void mutationDuringDeath(boolean traitMutation, Runnable next) {
            ViewerId id = ViewerId.of("mutation-death-" + UUID.randomUUID());
            ids.add(id);
            await(context.parrotService().create(UUID.randomUUID(), ADMIN, id, DisplayName.of("Before death")), seed -> {
                test.assertTrue(seed.succeeded(), "Living mutation/death fixture creation succeeds");
                ViewerParrotEntity parrot = context.loadedIndex().find(id).orElseThrow();
                ViewerParrotRecord beforeDeath = context.registry().find(id).orElseThrow();
                captured.add(parrot);
                CountDownLatch entered = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                gates.add(release);
                context.writer().submitForShutdown(() -> {
                    entered.countDown();
                    if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Mutation/death gate timed out");
                    return null;
                });
                waitUntil(() -> entered.getCount() == 0, () -> {
                    CompletableFuture<CommandResult> mutation = traitMutation
                            ? context.parrotService().setTrait(UUID.randomUUID(), ADMIN, id, ParrotTrait.THIEF)
                            : context.parrotService().rename(UUID.randomUUID(), ADMIN, id, DisplayName.of("Confirmed after death"));
                    test.assertTrue(!mutation.isDone() && context.parrotService().operationPending(id),
                            "The attribute change is pending while its living snapshot waits for I/O");
                    parrot.kill(); parrot.discard();
                    test.assertTrue(context.registry().find(id).orElseThrow().physicalStatus() == PhysicalStatus.DEAD,
                            "Actual death changes registry before pending mutation callback");
                    release.countDown();
                    await(mutation, changed -> {
                        test.assertTrue(changed.status() == CommandStatus.REJECTED && changed.code() == ErrorCode.INVALID_STATE,
                                "Physical-state revalidation explicitly rejects mutation whose entity died during I/O");
                        ViewerParrotRecord rejected = context.registry().find(id).orElseThrow();
                        test.assertTrue(rejected.viewer().displayName().equals(beforeDeath.viewer().displayName())
                                        && rejected.trait() == beforeDeath.trait(),
                                "A rejected mutation never claims an in-memory canonical change");
                        verifyCanonicalReplay(id, rejected, () -> {
                            CompletableFuture<CommandResult> retry = traitMutation
                                    ? context.parrotService().setTrait(UUID.randomUUID(), ADMIN, id, ParrotTrait.THIEF)
                                    : context.parrotService().rename(UUID.randomUUID(), ADMIN, id, DisplayName.of("Confirmed after death"));
                            await(retry, committed -> {
                                test.assertTrue(committed.succeeded(), "Administrative attributes remain mutable on stable DEAD registration");
                                ViewerParrotRecord confirmed = context.registry().find(id).orElseThrow();
                                test.assertTrue(traitMutation ? confirmed.trait() == ParrotTrait.THIEF
                                                : confirmed.viewer().displayName().value().equals("Confirmed after death"),
                                        "Successful retry applies its canonical attribute");
                                test.assertTrue(!context.loadedIndex().isLoaded(id) && !context.runtimes().containsKey(id),
                                        "Stable DEAD administrative mutation cannot create a loaded runtime without an entity");
                                verifyCanonicalReplay(id, confirmed, () ->
                                        await(context.parrotService().remove(UUID.randomUUID(), ADMIN, id), cleaned -> {
                                            test.assertTrue(cleaned.succeeded(), "Mutation/death fixture cleanup succeeds"); next.run();
                                        }));
                            });
                        });
                    });
                });
            });
        }

        void verifyCanonicalReplay(ViewerId id, ViewerParrotRecord confirmed, Runnable next) {
            var directory = context.server().getSavePath(WorldSavePath.ROOT).resolve("data/viewerparrots");
            CompletableFuture<ViewerParrotRecord> replay = context.writer().submit(() -> {
                JsonParrotRepository reader = new JsonParrotRepository(directory);
                try { return reader.load().records().stream().filter(record -> record.viewerId().equals(id)).findFirst().orElseThrow(); }
                finally { reader.close(); }
            });
            await(replay, reconstructed -> {
                test.assertTrue(reconstructed.physicalStatus() == PhysicalStatus.DEAD && reconstructed.entityUuid() == null,
                        "Journal replay retains the real death");
                test.assertTrue(reconstructed.viewer().displayName().equals(confirmed.viewer().displayName())
                                && reconstructed.trait() == confirmed.trait()
                                && reconstructed.desiredBehavior() == confirmed.desiredBehavior(),
                        "Journal replay preserves every confirmed canonical attribute when death raced with mutation");
                next.run();
            });
        }

        void finish() {
            LOAD_INTERCEPTOR.set(null);
            test.getWorld().getServer().getPlayerManager().remove(streamer);
            test.complete();
        }

        void waitUntil(java.util.function.BooleanSupplier condition, Runnable action) {
            if (condition.getAsBoolean()) guard(action);
            else test.waitAndRun(1, () -> guard(() -> waitUntil(condition, action)));
        }

        <T> void await(CompletableFuture<T> future, Consumer<T> action) {
            if (future.isDone()) guard(() -> action.accept(future.join()));
            else test.waitAndRun(1, () -> guard(() -> await(future, action)));
        }

        void restoreInjectedHealth() {
            if (originalHealth == null) return;
            try {
                var health = ServerContext.class.getDeclaredField("storageHealth");
                var reason = ServerContext.class.getDeclaredField("storageFaultReason");
                health.setAccessible(true); reason.setAccessible(true);
                health.set(context, originalHealth); reason.set(context, originalReason);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }

        void guard(Runnable action) {
            try { action.run(); }
            catch (RuntimeException | Error failure) {
                LOAD_INTERCEPTOR.set(null);
                gates.forEach(CountDownLatch::countDown);
                restoreInjectedHealth();
                for (ViewerParrotEntity parrot : captured) parrot.discard();
                for (ViewerId id : ids) {
                    context.loadedIndex().unbind(id); context.forgetRuntime(id);
                    context.registry().remove(id, context.clock().now());
                }
                if (streamer != null) test.getWorld().getServer().getPlayerManager().remove(streamer);
                throw failure;
            }
        }
    }
}
