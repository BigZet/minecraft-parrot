package io.github.viewerparrots.gametest;

import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.api.CommandStatus;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.domain.*;
import io.github.viewerparrots.entity.ModEntities;
import io.github.viewerparrots.entity.ParrotMarker;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Real Minecraft server acceptance, kept out of the release JAR. */
public final class ViewerParrotsGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void markerAndTrackedDanceRoundTrip(TestContext test) {
        ViewerId id = ViewerId.of("nbt-test");
        ViewerParrotRecord record = ViewerParrotRecord.created(
                new Viewer(id, DisplayName.of("NBT Дмитрий"), Instant.EPOCH), UUID.randomUUID(),
                UUID.randomUUID(), ParrotVariant.BLUE, ParrotTrait.COWARD, null, Instant.EPOCH);
        ViewerParrotEntity original = ModEntities.VIEWER_PARROT.create(test.getWorld());
        original.setMarker(ParrotMarker.of(record));
        original.setUuid(record.entityUuid());
        original.setForcedDancing(true);
        test.assertTrue(original.isSongPlaying(), "Tracked dance must select the vanilla party pose");
        NbtCompound nbt = new NbtCompound();
        original.writeNbt(nbt);
        ViewerParrotEntity restored = ModEntities.VIEWER_PARROT.create(test.getWorld());
        restored.readNbt(nbt);
        test.assertTrue(restored.getUuid().equals(record.entityUuid()), "NBT preserves UUID");
        test.assertTrue(restored.marker().orElseThrow().equals(ParrotMarker.of(record)), "NBT preserves marker");
        test.assertTrue(!restored.isForcedDancing(), "Temporary dance is cancelled on restore");
        test.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1400)
    public void lifecycleAndCommandsOnDedicatedServer(TestContext test) {
        ServerContext context = ServerContext.current().orElseThrow();
        if (context.storageHealth() == ServerContext.StorageHealth.LOADING) {
            test.waitAndRun(1, () -> lifecycleAndCommandsOnDedicatedServer(test));
            return;
        }
        test.assertTrue(context.allowsMutations(), "Registry must initialize before gameplay");
        for (int x = -10; x <= 26; x++) {
            for (int z = -10; z <= 18; z++) {
                test.getWorld().setBlockState(test.getAbsolutePos(new BlockPos(x, 1, z)),
                        Blocks.STONE.getDefaultState());
            }
        }
        ServerPlayerEntity streamer = test.createMockCreativeServerPlayerInWorld();
        Vec3d target = Vec3d.ofBottomCenter(test.getAbsolutePos(new BlockPos(4, 2, 4)));
        streamer.refreshPositionAndAngles(target.x, target.y, target.z, 0, 0);
        await(test, context.streamerService().assign(UUID.randomUUID(), ActorContext.console(4), streamer.getUuid()), assigned -> {
        checkSuccess(test, assigned, "setstreamer");
        ViewerId id = ViewerId.of("acceptance-" + UUID.randomUUID().toString().substring(0, 8));
        UUID commandId = UUID.randomUUID();
        await(test, context.parrotService().create(commandId, ActorContext.console(4), id, DisplayName.of("Дмитрий")), result -> {
            checkSuccess(test, result, "create");
            ViewerParrotRecord created = context.registry().find(id).orElseThrow();
            ViewerParrotEntity parrot = context.loadedIndex().find(id).orElseThrow();
            test.assertTrue(!parrot.isAiDisabled(), "AI starts after COMMIT");
            test.assertTrue(parrot.getCustomName().getString().equals("Дмитрий"), "Name is visible literal text");
            test.assertTrue(created.entityUuid().equals(parrot.getUuid()), "Committed UUID matches entity");
            await(test, context.parrotService().create(UUID.randomUUID(), ActorContext.console(4), id, DisplayName.of("Другой")), duplicate -> {
                test.assertTrue(duplicate.code() == ErrorCode.PARROT_ALREADY_EXISTS, "Duplicate create must reject");
                test.assertTrue(context.loadedIndex().find(id).orElseThrow() == parrot, "Duplicate cannot replace entity");
                await(test, context.parrotService().setTrait(UUID.randomUUID(), ActorContext.console(4), id, ParrotTrait.NORMAL), trait -> {
                    checkSuccess(test, trait, "trait");
                    submitThen(test, context, ParrotAction.SIT, id, sit -> {
                    test.assertTrue(context.runtime(id).behavior() == BehaviorState.SITTING, "Sit applies immediately");
                    UUID danceCommand = UUID.randomUUID();
                    CommandResult dance = context.commandService().submit(danceCommand, ActorContext.console(4), ParrotAction.DANCE, id);
                    test.assertTrue(dance.status() == CommandStatus.ACCEPTED, "Dance is accepted until timer expires");
                    test.assertTrue(parrot.isForcedDancing(), "Dance works without jukebox");
                    await(test, context.commandService().completion(danceCommand), danceResult -> {
                        checkSuccess(test, danceResult, "dance completion");
                        test.assertTrue(!parrot.isForcedDancing(), "Dance expires");
                        test.assertTrue(context.runtime(id).behavior() == BehaviorState.SITTING, "Dance restores sit intention");
                        CommandResult denied = context.commandService().submit(UUID.randomUUID(), ActorContext.console(0), ParrotAction.SCREAM, id);
                        test.assertTrue(denied.code() == ErrorCode.PERMISSION_DENIED, "Service rechecks permissions");
                        checkSuccess(test, context.commandService().submit(UUID.randomUUID(), ActorContext.console(4), ParrotAction.SCREAM, id), "scream");
                        test.assertTrue(context.commandService().submit(UUID.randomUUID(), ActorContext.console(4), ParrotAction.SCREAM, id).code() == ErrorCode.COMMAND_ON_COOLDOWN,
                                "Repeated scream shares sound cooldown");
                        movementAndRemove(test, context, streamer, id, parrot, created);
                    });
                    });
                });
            });
        });
        });
    }

    private static void movementAndRemove(TestContext test, ServerContext context, ServerPlayerEntity streamer,
            ViewerId id, ViewerParrotEntity parrot, ViewerParrotRecord created) {
        Vec3d far = streamer.getPos().add(16, 0, 0);
        parrot.refreshPositionAndAngles(far.x, far.y, far.z, 0, 0);
        submitThen(test, context, ParrotAction.COME, id, come -> {
            ViewerParrotEntity moved = context.loadedIndex().find(id).orElseThrow();
            test.assertTrue(moved.getUuid().equals(created.entityUuid()), "Come preserves UUID");
            test.assertTrue(moved.squaredDistanceTo(streamer) <= 9.5, "Come reaches configured arrival radius");
            submitThen(test, context, ParrotAction.FOLLOW, id, follow -> {
            submitThen(test, context, ParrotAction.SIT, id, sit -> {
            test.assertTrue(context.runtime(id).behavior() == BehaviorState.SITTING, "Sit stops follow immediately");
            float health = moved.getHealth();
            moved.damage(test.getWorld().getDamageSources().generic(), 2);
            test.assertTrue(moved.getHealth() == health, "IMMORTAL blocks ordinary damage");
            await(test, context.parrotService().rename(UUID.randomUUID(), ActorContext.console(4), id, DisplayName.of("Новое имя")), renamed -> {
                checkSuccess(test, renamed, "rename");
                test.assertTrue(moved.getCustomName().getString().equals("Новое имя"), "Rename refreshes entity");
                cycleLoadUnload(test, context, streamer, id, created, 0);
            });
            });
            });
        });
    }

    private static void cycleLoadUnload(TestContext test, ServerContext context, ServerPlayerEntity streamer,
            ViewerId id, ViewerParrotRecord original, int cycle) {
        if (cycle == 100) {
            finishRemoveAndRecreate(test, context, streamer, id, original);
            return;
        }
        ViewerParrotEntity current = context.loadedIndex().find(id).orElseThrow();
        NbtCompound nbt = new NbtCompound();
        current.writeNbt(nbt);
        current.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        test.assertTrue(context.loadedIndex().find(id).isEmpty(), "Unload clears entity index");
        test.assertTrue(!context.runtimes().containsKey(id), "Unload clears runtime references");
        ViewerParrotEntity restored = ModEntities.VIEWER_PARROT.create(test.getWorld());
        restored.readNbt(nbt);
        test.getWorld().spawnEntity(restored);
        test.assertTrue(context.loadedIndex().find(id).orElseThrow() == restored, "Load binds current generation");
        test.assertTrue(restored.getUuid().equals(original.entityUuid()), "100 lifecycle cycles preserve UUID");
        test.assertTrue(context.registry().find(id).orElseThrow().occupiesCapacitySlot(), "Unload retains reserved capacity");
        test.waitAndRun(1, () -> cycleLoadUnload(test, context, streamer, id, original, cycle + 1));
    }

    private static void finishRemoveAndRecreate(TestContext test, ServerContext context, ServerPlayerEntity streamer,
            ViewerId id, ViewerParrotRecord original) {
        ViewerParrotEntity current = context.loadedIndex().find(id).orElseThrow();
        await(test, context.parrotService().remove(UUID.randomUUID(), ActorContext.console(4), id), removed -> {
            checkSuccess(test, removed, "remove");
            test.assertTrue(context.loadedIndex().find(id).isEmpty(), "Remove clears index");
            test.assertTrue(current.isRemoved(), "Remove discards entity after durable tombstone");
            test.assertTrue(!context.registry().tombstonesFor(id).isEmpty(), "Remove preserves tombstone");
            test.assertTrue(!context.runtimes().containsKey(id), "Remove releases runtime references");
            await(test, context.parrotService().create(UUID.randomUUID(), ActorContext.console(4), id, DisplayName.of("Дмитрий снова")), recreated -> {
                checkSuccess(test, recreated, "recreate");
                ViewerParrotRecord fresh = context.registry().find(id).orElseThrow();
                test.assertTrue(!fresh.incarnationId().equals(original.incarnationId()), "Recreate assigns new incarnation");
                ViewerParrotEntity stale = ModEntities.VIEWER_PARROT.create(test.getWorld());
                stale.setUuid(original.entityUuid());
                stale.setMarker(ParrotMarker.of(original));
                stale.refreshPositionAndAngles(streamer.getX(), streamer.getY(), streamer.getZ(), 0, 0);
                test.getWorld().spawnEntity(stale);
                test.assertTrue(stale.isRemoved(), "Late old incarnation is rejected before AI");
                test.assertTrue(context.loadedIndex().find(id).orElseThrow().getUuid().equals(fresh.entityUuid()),
                        "Stale entity cannot replace current binding");
                await(test, context.parrotService().remove(UUID.randomUUID(), ActorContext.console(4), id), finalRemoval -> {
                    checkSuccess(test, finalRemoval, "final remove");
                    test.getWorld().getServer().getPlayerManager().remove(streamer);
                    test.complete();
                });
            });
        });
    }

    private static <T> void await(TestContext test, CompletableFuture<T> future, Consumer<T> action) {
        if (future.isDone()) {
            action.accept(future.join());
        } else {
            test.waitAndRun(1, () -> await(test, future, action));
        }
    }

    private static void checkSuccess(TestContext test, CommandResult result, String action) {
        test.assertTrue(result.succeeded(), action + " failed: " + result);
    }

    private static void submitThen(TestContext test, ServerContext context, ParrotAction action, ViewerId id,
            Consumer<CommandResult> continuation) {
        UUID command = UUID.randomUUID();
        CommandResult initial = context.commandService().submit(command, ActorContext.console(4), action, id);
        test.assertTrue(initial.status() == CommandStatus.ACCEPTED || initial.succeeded(), action + " rejected: " + initial);
        await(test, context.commandService().completion(command), result -> {
            checkSuccess(test, result, action.name());
            continuation.accept(result);
        });
    }
}
