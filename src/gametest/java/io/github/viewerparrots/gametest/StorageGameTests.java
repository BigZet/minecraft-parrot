package io.github.viewerparrots.gametest;

import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.domain.*;
import io.github.viewerparrots.entity.ModEntities;
import io.github.viewerparrots.entity.ParrotMarker;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Exercises the actual asynchronous lifecycle services and Minecraft callbacks. */
public final class StorageGameTests implements FabricGameTest {
    private static final ActorContext ADMIN = ActorContext.console(4);
    private static final ActorContext DENIED = ActorContext.console(0);

    @GameTest(batchId = "storage", templateName = EMPTY_STRUCTURE, tickLimit = 1800)
    public void concurrentCrudCapacityDeathAndGeneration(TestContext test) {
        ServerContext context = ServerContext.current().orElseThrow();
        if (context.storageHealth() == ServerContext.StorageHealth.LOADING) {
            test.waitAndRun(1, () -> concurrentCrudCapacityDeathAndGeneration(test));
            return;
        }
        test.assertTrue(context.allowsMutations(), "Storage must be ready");
        for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++)
            test.getWorld().setBlockState(test.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
        ServerPlayerEntity streamer = test.createMockCreativeServerPlayerInWorld();
        Vec3d position = Vec3d.ofBottomCenter(test.getAbsolutePos(new BlockPos(1, 2, 1)));
        streamer.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
        String prefix = "storage-" + UUID.randomUUID().toString().substring(0, 8);
        ViewerId first = ViewerId.of(prefix + "-first");
        ViewerId second = ViewerId.of(prefix + "-second");
        ViewerId third = ViewerId.of(prefix + "-third");
        await(test, context.parrotService().create(UUID.randomUUID(), DENIED, first, DisplayName.of("Denied")), denied -> {
            test.assertTrue(denied.code() == ErrorCode.PERMISSION_DENIED, "Lifecycle service checks permissions");
            test.assertTrue(!context.registry().contains(first), "Denied create cannot register or spawn");
            await(test, context.streamerService().assign(UUID.randomUUID(), ADMIN, streamer.getUuid()), assigned -> {
                success(test, assigned, "assign streamer");
                UUID command = UUID.randomUUID();
                CompletableFuture<CommandResult> creating = context.parrotService().create(command, ADMIN, first, DisplayName.of("First"));
                CompletableFuture<CommandResult> duplicateDelivery = context.parrotService().create(command, ADMIN, first, DisplayName.of("First"));
                test.assertTrue(creating == duplicateDelivery, "Repeated command ID reuses the in-flight future");
                CompletableFuture<CommandResult> immediateNext = creating.thenCompose(created ->
                        context.parrotService().rename(UUID.randomUUID(), ADMIN, first, DisplayName.of("Next callback")));
                CompletableFuture<CommandResult> racing = context.parrotService().create(UUID.randomUUID(), ADMIN, first, DisplayName.of("Race"));
                await(test, racing, rejected -> {
                    test.assertTrue(rejected.code() == ErrorCode.OPERATION_PENDING, "Concurrent same-viewer mutation is rejected before PREPARE");
                    await(test, creating, created -> {
                        success(test, created, "first create");
                        await(test, immediateNext, renamed -> {
                            success(test, renamed, "mutation submitted from completion listener");
                            concurrentDifferentViewers(test, context, streamer, prefix, first, second, third);
                        });
                    });
                });
            });
        });
    }

    private static void concurrentDifferentViewers(TestContext test, ServerContext context, ServerPlayerEntity streamer,
            String prefix, ViewerId first, ViewerId second, ViewerId third) {
        CompletableFuture<CommandResult> createSecond = context.parrotService().create(UUID.randomUUID(), ADMIN, second, DisplayName.of("Second"));
        CompletableFuture<CommandResult> createThird = context.parrotService().create(UUID.randomUUID(), ADMIN, third, DisplayName.of("Third"));
        await(test, CompletableFuture.allOf(createSecond, createThird), ignored -> {
            success(test, createSecond.join(), "concurrent second create");
            success(test, createThird.join(), "concurrent third create");
            test.assertTrue(context.allowsMutations(), "Concurrent writers must not allocate duplicate journal sequences");
            test.assertTrue(context.loadedIndex().isLoaded(first) && context.loadedIndex().isLoaded(second)
                    && context.loadedIndex().isLoaded(third), "Three independent registrations have entities");
            CompletableFuture<CommandResult> rename = context.parrotService().rename(UUID.randomUUID(), ADMIN, first, DisplayName.of("Renamed"));
            CompletableFuture<CommandResult> trait = context.parrotService().setTrait(UUID.randomUUID(), ADMIN, second, ParrotTrait.COWARD);
            CompletableFuture<CommandResult> removal = context.parrotService().remove(UUID.randomUUID(), ADMIN, third);
            await(test, CompletableFuture.allOf(rename, trait, removal), completed -> {
                success(test, rename.join(), "concurrent rename");
                success(test, trait.join(), "concurrent trait");
                success(test, removal.join(), "concurrent remove");
                test.assertTrue(!context.registry().contains(third) && !context.runtimes().containsKey(third), "Remove cleans registry and runtime");
                test.assertTrue(context.registry().find(second).orElseThrow().trait() == ParrotTrait.COWARD, "Trait is durable in memory");
                int tombstones = context.registry().tombstonesFor(third).size();
                long appliedSequence = context.registry().appliedJournalSequence();
                await(test, context.parrotService().remove(UUID.randomUUID(), ADMIN, third), repeated -> {
                    test.assertTrue(repeated.succeeded(), "Repeated remove is an idempotent successful no-op");
                    test.assertTrue("true".equals(repeated.details().get("noOp")), "Repeated remove explicitly reports no-op");
                    test.assertTrue(context.registry().tombstonesFor(third).size() == tombstones,
                            "Repeated remove creates no additional tombstone");
                    test.assertTrue(context.registry().appliedJournalSequence() == appliedSequence,
                            "Repeated remove appends no journal mutation");
                    capacity(test, context, () -> deathAndRespawn(test, context, streamer, first, second));
                });
            });
        });
    }

    private static void capacity(TestContext test, ServerContext context, Runnable next) {
        test.assertTrue(context.config().maxActiveParrots() == 50, "Acceptance uses the default capacity of 50");
        List<ViewerId> dormantIds = new ArrayList<>();
        int needed = 50 - context.registry().reservedSlots();
        for (int i = 0; i < needed; i++) {
            ViewerId id = ViewerId.of("capacity-" + UUID.randomUUID());
            dormantIds.add(id);
            ViewerParrotRecord record = ViewerParrotRecord.created(new Viewer(id, DisplayName.of("Dormant"), Instant.EPOCH),
                    UUID.randomUUID(), UUID.randomUUID(), ParrotVariant.GREEN, ParrotTrait.NORMAL, null, Instant.EPOCH);
            context.registry().put(record);
        }
        test.assertTrue(context.registry().reservedSlots() == 50, "Dormant physical instances retain capacity");
        ViewerId excess = ViewerId.of("excess-" + UUID.randomUUID());
        await(test, context.parrotService().create(UUID.randomUUID(), ADMIN, excess, DisplayName.of("51")), rejected -> {
            test.assertTrue(rejected.code() == ErrorCode.MAX_ACTIVE_PARROTS_REACHED, "The 51st reserved instance is rejected");
            test.assertTrue(!context.registry().contains(excess), "Capacity rejection leaves no partial registration");
            test.assertTrue(context.registry().pendingReservations() == 0, "Capacity rejection leaks no spawn reservation");
            for (ViewerId id : dormantIds) context.registry().remove(id, Instant.now());
            next.run();
        });
    }

    private static void deathAndRespawn(TestContext test, ServerContext context, ServerPlayerEntity streamer,
            ViewerId first, ViewerId second) {
        ViewerParrotRecord original = context.registry().find(second).orElseThrow();
        await(test, context.parrotService().remove(UUID.randomUUID(), DENIED, second), denied -> {
            test.assertTrue(denied.code() == ErrorCode.PERMISSION_DENIED, "Denied remove cannot bypass the service boundary");
            var killed = context.loadedIndex().find(second).orElseThrow();
            killed.kill();
            test.assertTrue(context.registry().find(second).orElseThrow().physicalStatus() == PhysicalStatus.DEAD, "Administrative kill records DEAD");
            test.assertTrue(context.registry().find(second).orElseThrow().expectedEntityUuid().isEmpty(), "DEAD releases expected UUID");
            test.assertTrue(!context.loadedIndex().isLoaded(second), "Death unbinds the entity");
            // The empty GameTest chunk may not tick the vanilla corpse timer. Remove only
            // the already-confirmed DEAD fixture so a stale UUID can actually reach ENTITY_LOAD.
            killed.discard();
            test.waitAndRun(25, () -> await(test, context.parrotService().respawn(UUID.randomUUID(), ADMIN, second), result -> {
                success(test, result, "respawn after death");
                ViewerParrotRecord restored = context.registry().find(second).orElseThrow();
                test.assertTrue(restored.generation() == original.generation() + 1, "Respawn increments generation");
                test.assertTrue(restored.incarnationId().equals(original.incarnationId()) && restored.trait() == original.trait()
                        && restored.variant() == original.variant() && restored.viewer().equals(original.viewer()), "Respawn preserves logical identity and appearance");
                test.assertTrue(!restored.entityUuid().equals(original.entityUuid()), "Respawn assigns a new physical UUID");
                var secondKilled = context.loadedIndex().find(second).orElseThrow();
                secondKilled.kill();
                secondKilled.discard();
                await(test, context.parrotService().respawn(UUID.randomUUID(), ADMIN, second), cooldown -> {
                    test.assertTrue(cooldown.code() == ErrorCode.COMMAND_ON_COOLDOWN, "Accepted respawn consumes its configured cooldown");
                    long remaining = context.cooldowns().remainingTicks(second, io.github.viewerparrots.api.ParrotAction.RESPAWN);
                    test.waitAndRun((int) remaining + 2, () -> await(test, context.parrotService().respawn(UUID.randomUUID(), ADMIN, second), again -> {
                        success(test, again, "respawn at cooldown deadline");
                        test.assertTrue(test.getWorld().getEntity(original.entityUuid()) == null,
                                "Old corpse UUID must leave the Minecraft index before stale NBT can load");
                        var stale = ModEntities.VIEWER_PARROT.create(test.getWorld());
                        stale.setUuid(original.entityUuid());
                        stale.setMarker(ParrotMarker.of(original));
                        stale.refreshPositionAndAngles(streamer.getX(), streamer.getY(), streamer.getZ(), 0, 0);
                        test.assertTrue(test.getWorld().spawnEntity(stale), "Stale fixture must reach ENTITY_LOAD, not duplicate-UUID rejection");
                        test.assertTrue(stale.isRemoved(), "Late old generation is discarded before AI");
                        test.assertTrue(context.loadedIndex().find(second).orElseThrow().getUuid()
                                .equals(context.registry().find(second).orElseThrow().entityUuid()), "Stale generation cannot replace the current binding");
                        cleanup(test, context, streamer, first, second);
                    }));
                });
            }));
        });
    }

    private static void cleanup(TestContext test, ServerContext context, ServerPlayerEntity streamer, ViewerId first, ViewerId second) {
        var one = context.parrotService().remove(UUID.randomUUID(), ADMIN, first);
        var two = context.parrotService().remove(UUID.randomUUID(), ADMIN, second);
        await(test, CompletableFuture.allOf(one, two), ignored -> {
            success(test, one.join(), "cleanup first");
            success(test, two.join(), "cleanup second");
            test.assertTrue(context.allowsMutations(), "All concurrent journal operations preserve healthy storage");
            test.assertTrue(context.registry().pendingReservations() == 0 && context.recoveryService().activeJobs() == 0, "All reservations and tickets are released");
            test.getWorld().getServer().getPlayerManager().remove(streamer);
            test.complete();
        });
    }

    private static <T> void await(TestContext test, CompletableFuture<T> future, Consumer<T> action) {
        if (future.isDone()) action.accept(future.join());
        else test.waitAndRun(1, () -> await(test, future, action));
    }

    private static void success(TestContext test, CommandResult result, String action) {
        test.assertTrue(result.succeeded(), action + " failed: " + result);
    }
}
