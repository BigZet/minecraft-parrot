package io.github.viewerparrots.gametest;

import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.api.CommandStatus;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.config.DeathMode;
import io.github.viewerparrots.config.ViewerParrotsConfig;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.domain.DesiredBehavior;
import io.github.viewerparrots.domain.DisplayName;
import io.github.viewerparrots.domain.PhysicalStatus;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Real entity ticks, lifecycle callbacks and terminal command results for the remaining MVP scenarios. */
public final class AcceptanceScenarioGameTests implements FabricGameTest {
    @GameTest(batchId = "acceptance-scenarios", templateName = EMPTY_STRUCTURE, tickLimit = 5000)
    public void movementTargetsDeathAndVanillaRestrictions(TestContext test) {
        if (!FixtureCallbacks.ready(test, () -> movementTargetsDeathAndVanillaRestrictions(test))) return;
        Scenario scenario = new Scenario(test);
        scenario.guard(scenario::start);
    }

    private static final class Scenario {
        private final TestContext test;
        private final ServerContext context = ServerContext.current().orElseThrow();
        private final ActorContext actor = ActorContext.console(4);
        private final ViewerParrotsConfig original = context.config();
        private final String originalFile;
        private final ViewerId id = ViewerId.of("acceptance-" + UUID.randomUUID().toString().substring(0, 8));
        private final Vec3d origin;
        private final Map<BlockKey, BlockState> blocks = new LinkedHashMap<>();
        private final Map<ChunkKey, Boolean> forcedChunks = new LinkedHashMap<>();
        private final List<ServerPlayerEntity> players = new ArrayList<>();
        private final List<Entity> auxiliary = new ArrayList<>();
        private ServerPlayerEntity target;
        private boolean ending;

        Scenario(TestContext test) {
            this.test = test;
            origin = Vec3d.ofBottomCenter(test.getAbsolutePos(new BlockPos(4, 3, 4)));
            originalFile = SettingsFixture.snapshot();
        }

        void start() {
            configure(DeathMode.IMMORTAL, true);
            platform(test.getWorld(), origin, 18);
            target = player(origin);
            assign(() -> await(context.parrotService().create(UUID.randomUUID(), actor, id, DisplayName.of("Acceptance scenarios")), result -> {
                success(result, "Create scenario");
                position(parrot(), origin);
                later(10, this::followThenSit);
            }));
        }

        void followThenSit() {
            ViewerParrotEntity bird = parrot();
            int age = bird.age;
            Vec3d before = bird.getPos();
            position(target, origin.add(12, 0, 0));
            action(ParrotAction.FOLLOW, result -> {
                success(result, "FOLLOW");
                double initial = before.squaredDistanceTo(target.getPos());
                later(35, () -> {
                    check(bird.age > age, "FOLLOW fixture must receive actual entity ticks");
                    check(bird.getPos().squaredDistanceTo(before) > 0.04, "FOLLOW must physically move the loaded bird");
                    check(bird.squaredDistanceTo(target) < initial, "FOLLOW must approach the target");
                    action(ParrotAction.SIT, seated -> {
                        success(seated, "SIT after FOLLOW");
                        later(3, () -> {
                            Vec3d held = bird.getPos();
                            position(target, origin.add(-12, 0, 0));
                            later(25, () -> {
                                check(context.runtime(id).behavior() == BehaviorState.SITTING && bird.getNavigation().isIdle(), "SIT stops FOLLOW navigation");
                                check(horizontalSquared(bird.getPos(), held) < 0.0001, "SIT must remain in place after target movement");
                                timedNearCome();
                            });
                        });
                    });
                });
            });
        }

        void timedNearCome() {
            position(parrot(), origin);
            Vec3d destination = origin.add(16, 0, 0);
            position(target, destination);
            tickingDestination(test.getWorld(), destination, () -> {
                long acceptedAt = context.clock().currentTick();
                UUID command = begin(ParrotAction.COME);
                var timedCompletion = context.commandService().completion(command)
                        .thenApply(result -> new TimedResult(result, context.clock().currentTick()));
                await(timedCompletion, completed -> {
                    success(completed.result(), "COME from sixteen blocks");
                    check(completed.tick() - acceptedAt <= 200, "Near COME completes within 200 actual server ticks: took="
                            + (completed.tick() - acceptedAt) + " result=" + completed.result());
                    check(parrot().squaredDistanceTo(target) <= 9.0001, "Near COME physically reaches its arrival radius");
                    long danceAcceptedAt = context.clock().currentTick();
                    UUID dance = begin(ParrotAction.DANCE);
                    check(parrot().isForcedDancing() && context.runtime(id).behavior() == BehaviorState.DANCING,
                            "Accepted DANCE starts the actual tracked animation");
                    check(context.clock().currentTick() - danceAcceptedAt <= 2, "Simple DANCE begins within two ticks");
                    long sitAcceptedAt = context.clock().currentTick();
                    action(ParrotAction.SIT, result -> {
                        success(result, "SIT after timing probe");
                        check(!parrot().isForcedDancing() && context.runtime(id).behavior() == BehaviorState.SITTING,
                                "SIT cancels the actual animation and applies its hold state");
                        check(context.clock().currentTick() - sitAcceptedAt <= 2, "Simple SIT begins within two ticks after its durable callback");
                        check(context.commandService().completion(dance).join().status() == CommandStatus.CANCELLED,
                                "Replacement SIT completes the displaced dance cancellation");
                        farCome();
                    });
                });
            });
        }

        void farCome() {
            ViewerParrotRecord identity = record();
            Vec3d destination = origin.add(80, 0, 0);
            platform(test.getWorld(), destination, 5);
            position(target, destination);
            check(parrot().squaredDistanceTo(target) > 64 * 64, "Far COME fixture exceeds the pathfinding limit");
            tickingDestination(test.getWorld(), destination, () -> action(ParrotAction.COME, result -> {
                success(result, "COME beyond 64 blocks");
                check(parrot().squaredDistanceTo(target) <= 9.0001, "Far COME reaches the safe arrival radius");
                sameIdentity(identity, "Far teleport");
                noSafeCome();
            }));
        }

        void noSafeCome() {
            ViewerParrotRecord identity = record();
            Vec3d before = parrot().getPos();
            Vec3d unsafe = origin.add(160, 0, 0);
            BlockPos center = BlockPos.ofFloored(unsafe);
            // All bounded candidates and the exact-target fallback contain a hazardous fluid.
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = -9; y <= 4; y++)
                block(test.getWorld(), center.add(x, y, z), Blocks.LAVA.getDefaultState());
            position(target, unsafe);
            action(ParrotAction.COME, result -> {
                check(result.code() == ErrorCode.NO_SAFE_POSITION, "Unsafe destination returns NO_SAFE_POSITION: " + result);
                sameIdentity(identity, "Rejected unsafe teleport");
                check(horizontalSquared(parrot().getPos(), before) < 0.0001, "Unsafe teleport leaves the original entity in place");
                position(target, origin);
                action(ParrotAction.COME, back -> {
                    success(back, "Return from far fixture");
                    voidRescue();
                });
            });
        }

        void voidRescue() {
            ViewerParrotRecord identity = record();
            ViewerParrotEntity bird = parrot();
            position(bird, new Vec3d(origin.x, test.getWorld().getBottomY() - 1, origin.z));
            later(3, () -> {
                check(parrot().getY() >= test.getWorld().getBottomY(), "Loaded bird below the world is rescued");
                check(parrot().squaredDistanceTo(target) <= 9.0001, "Void rescue chooses a safe point near the live target");
                sameIdentity(identity, "Void rescue");
                check(context.behaviorScheduler().workBudget().teleportsUsed() <= 2, "Void rescue shares the global two-teleport budget");
                targetDisconnect();
            });
        }

        void targetDisconnect() {
            position(parrot(), origin);
            position(target, origin.add(16, 0, 0));
            UUID command = begin(ParrotAction.COME);
            test.getWorld().getServer().getPlayerManager().remove(target);
            await(context.commandService().completion(command), result -> {
                cancelledTarget(result, "Target disconnect");
                target = player(origin);
                assign(this::targetDeath);
            });
        }

        void targetDeath() {
            position(parrot(), origin);
            position(target, origin.add(16, 0, 0));
            UUID command = begin(ParrotAction.COME);
            target.setHealth(0);
            await(context.commandService().completion(command), result -> {
                cancelledTarget(result, "Target death");
                target = player(origin.add(12, 0, 0));
                assign(() -> action(ParrotAction.FOLLOW, follow -> {
                    success(follow, "FOLLOW before target death");
                    target.setHealth(0);
                    later(2, () -> {
                        check(context.runtime(id).behavior() == BehaviorState.IDLE, "Dead FOLLOW target pauses actual behavior");
                        check(context.runtime(id).desiredBehavior() == DesiredBehavior.FOLLOWING, "Dead FOLLOW target retains stable intention");
                        target = player(origin.add(12, 0, 0));
                        assign(() -> later(2, () -> {
                            check(context.runtime(id).behavior() == BehaviorState.FOLLOWING, "Live replacement target resumes FOLLOW");
                            action(ParrotAction.SIT, seated -> { success(seated, "SIT before dimension scenario"); dimensionChange(); });
                        }));
                    });
                }));
            });
        }

        void dimensionChange() {
            configure(DeathMode.IMMORTAL, false);
            position(parrot(), origin);
            position(target, origin.add(16, 0, 0));
            ServerWorld nether = test.getWorld().getServer().getWorld(World.NETHER);
            check(nether != null, "Nether is available for actual dimension transfer");
            Vec3d netherPosition = new Vec3d(8.5, 65, 8.5);
            UUID command = begin(ParrotAction.COME);
            target.teleport(nether, netherPosition.x, netherPosition.y, netherPosition.z, 0, 0);
            check(target.getWorld() == nether, "Target fixture actually changes dimensions after COME acceptance");
            await(context.commandService().completion(command), blocked -> {
                check(blocked.code() == ErrorCode.DIMENSION_UNAVAILABLE, "Disabled dimension transfer returns DIMENSION_UNAVAILABLE: " + blocked);
                check(parrot().getWorld() == test.getWorld(), "Blocked transfer keeps the source body bound");
                configure(DeathMode.IMMORTAL, true);
                target.teleport(test.getWorld(), origin.x, origin.y, origin.z, 0, 0);
                later(3, this::deathModes);
            });
        }

        void deathModes() {
            position(parrot(), origin);
            ViewerParrotEntity bird = parrot();
            float health = bird.getHealth();
            bird.damage(bird.getDamageSources().generic(), 2);
            check(bird.getHealth() == health && bird.isAlive(), "IMMORTAL blocks ordinary damage on an already living body");
            ViewerParrotRecord identity = record();
            bird.kill();
            dead(bird, "IMMORTAL administrative kill");
            later(25, () -> respawn(identity, () -> {
                configure(DeathMode.MANUAL_RESPAWN, true);
                ViewerParrotEntity manual = parrot();
                ViewerParrotRecord manualIdentity = record();
                manual.damage(manual.getDamageSources().generic(), 100);
                dead(manual, "MANUAL_RESPAWN ordinary lethal damage");
                later(25, () -> respawn(manualIdentity, () -> {
                    configure(DeathMode.VANILLA_FINAL, true);
                    ViewerParrotEntity finalBird = parrot();
                    finalBird.damage(finalBird.getDamageSources().generic(), 100);
                    dead(finalBird, "VANILLA_FINAL ordinary lethal damage");
                    later(25, () -> await(context.parrotService().respawn(UUID.randomUUID(), actor, id), rejected -> {
                        check(rejected.code() == ErrorCode.RESPAWN_DISABLED, "VANILLA_FINAL rejects respawn: " + rejected);
                        check(!context.loadedIndex().isLoaded(id), "Rejected final respawn does not create an entity");
                        configure(DeathMode.IMMORTAL, true);
                        await(context.parrotService().respawn(UUID.randomUUID(), actor, id), restored -> {
                            success(restored, "Restore restriction fixture");
                            position(parrot(), origin);
                            later(110, this::restrictions);
                        });
                    }));
                }));
            }));
        }

        void restrictions() {
            ViewerParrotEntity bird = parrot();
            check(bird.age > 100, "Shoulder readiness fixture receives over 100 actual entity ticks");
            check(!bird.isReadyToSitOnPlayer(), "Custom shoulder eligibility remains disabled after the vanilla age threshold");
            target.changeGameMode(GameMode.SURVIVAL);
            target.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.WHEAT_SEEDS, 3));
            check(bird.interactMob(target, Hand.MAIN_HAND) == ActionResult.PASS, "Custom feeding interaction passes without mutation");
            check(target.getMainHandStack().getCount() == 3 && !bird.isTamed() && bird.getOwnerUuid() == null, "Seeds neither feed nor tame a custom bird");
            float health = bird.getHealth();
            target.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.COOKIE, 3));
            check(bird.interactMob(target, Hand.MAIN_HAND) == ActionResult.PASS && bird.getHealth() == health,
                    "Custom cookie interaction neither feeds nor damages");
            check(target.getMainHandStack().getCount() == 3, "Custom cookie interaction does not consume the stack");
            check(!bird.canBeLeashed() && !bird.canUsePortals(true), "Custom entity rejects leash and vanilla portals");
            check(!bird.isBreedingItem(new ItemStack(Items.WHEAT_SEEDS)), "Custom entity does not accept breeding food");

            ParrotEntity vanilla = EntityType.PARROT.create(test.getWorld());
            check(vanilla != null, "Vanilla fixture is constructible");
            position(vanilla, origin.add(1, 0, 0));
            auxiliary.add(vanilla);
            check(test.getWorld().spawnEntity(vanilla), "Vanilla fixture spawns");
            check(!vanilla.isSilent() && vanilla.getAmbientSound() != null && vanilla.canUsePortals(true), "Vanilla sound and portal behavior remains intact");
            check(vanilla.interactMob(target, Hand.MAIN_HAND).isAccepted(), "Vanilla cookie interaction remains accepted");
            check(!vanilla.isAlive(), "Vanilla cookie death remains possible while custom IMMORTAL is active");
            check(target.getMainHandStack().getCount() == 2, "Vanilla feeding retains normal item consumption");

            ServerPlayerEntity shoulderPlayer = player(origin.add(2, 0, 0));
            ParrotEntity vanillaShoulder = EntityType.PARROT.create(test.getWorld());
            check(vanillaShoulder != null, "Vanilla shoulder fixture is constructible");
            position(vanillaShoulder, origin.add(2, 0, 0));
            auxiliary.add(vanillaShoulder);
            check(test.getWorld().spawnEntity(vanillaShoulder), "Vanilla shoulder fixture spawns");
            shoulderPlayer.setOnGround(true);
            check(vanillaShoulder.mountOnto(shoulderPlayer), "Vanilla shoulder mount proves the empty-player fixture is valid");
            target.setOnGround(true);
            check(!bird.mountOnto(target), "Custom bird rejects direct shoulder mounting");
            check(!bird.isRemoved() && context.loadedIndex().find(id).orElseThrow() == bird, "Rejected shoulder mount preserves the authorized physical body");
            later(5, () -> {
                check(bird.isAlive() && !bird.isTamed() && !bird.canUsePortals(true), "Restrictions persist through actual entity ticks");
                finish(null);
            });
        }

        void dead(ViewerParrotEntity bird, String phase) {
            check(!bird.isAlive() && record().physicalStatus() == PhysicalStatus.DEAD, phase + " records durable DEAD intent");
            check(!context.loadedIndex().isLoaded(id) && !context.runtimes().containsKey(id), phase + " releases binding and runtime");
            bird.discard(); // A GameTest corpse outside a ticking arena must not retain its UUID index.
        }

        void respawn(ViewerParrotRecord identity, Runnable next) {
            await(context.parrotService().respawn(UUID.randomUUID(), actor, id), result -> {
                success(result, "Manual respawn");
                ViewerParrotRecord restored = record();
                check(restored.generation() == identity.generation() + 1, "Respawn advances generation exactly once");
                check(restored.incarnationId().equals(identity.incarnationId()) && restored.trait() == identity.trait()
                        && restored.variant() == identity.variant(), "Respawn preserves logical identity and appearance");
                check(!restored.entityUuid().equals(identity.entityUuid()), "Respawn creates a fresh physical UUID");
                position(parrot(), origin);
                next.run();
            });
        }

        UUID begin(ParrotAction action) {
            UUID command = UUID.randomUUID();
            CommandResult accepted = context.commandService().submit(command, actor, action, id);
            check(accepted.status() == CommandStatus.ACCEPTED || accepted.succeeded(), action + " accepted: " + accepted);
            return command;
        }
        void action(ParrotAction action, Consumer<CommandResult> next) {
            UUID command = begin(action);
            await(context.commandService().completion(command), next);
        }
        void cancelledTarget(CommandResult result, String phase) {
            check(result.status() == CommandStatus.CANCELLED && result.code() == ErrorCode.TARGET_UNAVAILABLE, phase + " cancels unfinished COME: " + result);
            check(context.runtime(id).movementCommandId().isEmpty() && context.runtime(id).behavior() != BehaviorState.RETURNING, phase + " clears movement state");
        }
        void sameIdentity(ViewerParrotRecord expected, String phase) {
            check(parrot().getUuid().equals(expected.entityUuid()) && record().generation() == expected.generation()
                    && record().incarnationId().equals(expected.incarnationId()), phase + " preserves UUID, incarnation and generation");
        }
        void assign(Runnable next) {
            await(context.streamerService().assign(UUID.randomUUID(), actor, target.getUuid()), result -> {
                success(result, "Assign target"); next.run();
            });
        }
        ServerPlayerEntity player(Vec3d pos) {
            ServerPlayerEntity player = test.createMockCreativeServerPlayerInWorld();
            players.add(player);
            position(player, pos);
            return player;
        }
        ViewerParrotEntity parrot() { return context.loadedIndex().find(id).orElseThrow(); }
        ViewerParrotRecord record() { return context.registry().find(id).orElseThrow(); }
        void position(Entity entity, Vec3d pos) {
            entity.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0, 0);
            entity.setVelocity(Vec3d.ZERO);
        }
        void platform(ServerWorld world, Vec3d point, int radius) {
            BlockPos center = BlockPos.ofFloored(point).down();
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
                block(world, center.add(x, 0, z), Blocks.STONE.getDefaultState());
                block(world, center.add(x, 1, z), Blocks.AIR.getDefaultState());
                block(world, center.add(x, 2, z), Blocks.AIR.getDefaultState());
            }
        }
        void block(ServerWorld world, BlockPos pos, BlockState state) {
            BlockKey key = new BlockKey(world, pos.toImmutable());
            blocks.putIfAbsent(key, world.getBlockState(pos));
            world.setBlockState(pos, state);
        }
        void tickingDestination(ServerWorld world, Vec3d destination, Runnable next) {
            for (int x : new int[]{-4, 0, 4}) for (int z : new int[]{-4, 0, 4}) {
                ChunkPos chunk = new ChunkPos(BlockPos.ofFloored(destination.add(x, 0, z)));
                ChunkKey key = new ChunkKey(world, chunk);
                forcedChunks.putIfAbsent(key, world.getForcedChunks().contains(chunk.toLong()));
                world.setChunkForced(chunk.x, chunk.z, true);
            }
            awaitTicking(world, destination, next, 1000);
        }
        void awaitTicking(ServerWorld world, Vec3d destination, Runnable next, int remaining) {
            boolean ready = true;
            for (int x : new int[]{-4, 0, 4}) for (int z : new int[]{-4, 0, 4})
                ready &= world.shouldTickEntity(BlockPos.ofFloored(destination.add(x, 0, z)));
            if (ready) next.run();
            else {
                check(remaining > 0, "Destination fixture acquires real entity-ticking chunks: world=" + world.getRegistryKey().getValue()
                        + " time=" + world.getTime() + " trackedTick=" + world.shouldTick(new ChunkPos(BlockPos.ofFloored(destination)))
                        + " future=" + world.getChunkManager().isTickingFutureReady(new ChunkPos(BlockPos.ofFloored(destination)).toLong())
                        + " chunk=" + world.getChunkManager().getChunkLoadingDebugInfo(new ChunkPos(BlockPos.ofFloored(destination))));
                later(1, () -> awaitTicking(world, destination, next, remaining - 1));
            }
        }
        void configure(DeathMode mode, boolean crossDimension) {
            var b = original.behavior(); var c = original.come(); var cooldown = original.cooldowns();
            ViewerParrotsConfig candidate = new ViewerParrotsConfig(original.schemaVersion(), original.maxActiveParrots(), mode,
                    original.nameFormat(), original.nameVisible(), original.permissions(),
                    new ViewerParrotsConfig.Come(c.maxPathfindDistance(), c.arrivalRadius(), c.timeoutTicks(), true, true, crossDimension),
                    original.follow(), original.dance(), new ViewerParrotsConfig.Cooldowns(0, 0, 0, 0, cooldown.scream(), 0), original.sound(),
                    new ViewerParrotsConfig.Behavior(false, b.idleMinTicks(), b.idleMaxTicks(), b.dangerCheckTicks(), b.dangerRadius(),
                            b.lazyMinTicks(), b.lazyMaxTicks(), b.normalSoundChance(), b.loudSoundChance(), b.traitWeights()),
                    original.limits(), original.recovery(), original.storage());
            SettingsFixture.writeGeneral(candidate);
            success(SettingsFixture.reload(context, actor), "Reload scenario configuration");
        }
        void restoreConfig() {
            SettingsFixture.write(originalFile);
            SettingsFixture.reload(context, actor);
        }
        <T> void await(CompletableFuture<T> future, Consumer<T> next) {
            if (future.isDone()) guard(() -> next.accept(future.join()));
            else later(1, () -> await(future, next));
        }
        void later(long ticks, Runnable work) { test.waitAndRun(ticks, () -> guard(work)); }
        void guard(Runnable work) {
            if (ending) return;
            try { work.run(); } catch (RuntimeException | Error failure) { finish(failure); }
        }
        void finish(Throwable failure) {
            ending = true;
            restoreConfig();
            CompletableFuture<?> removed = context.registry().contains(id)
                    ? context.parrotService().remove(UUID.randomUUID(), actor, id) : CompletableFuture.completedFuture(null);
            cleanup(removed, failure);
        }
        void cleanup(CompletableFuture<?> removed, Throwable failure) {
            if (!removed.isDone()) { test.waitAndRun(1, () -> cleanup(removed, failure)); return; }
            auxiliary.forEach(Entity::discard);
            for (ServerPlayerEntity player : players) test.getWorld().getServer().getPlayerManager().remove(player);
            blocks.forEach((key, state) -> key.world().setBlockState(key.pos(), state));
            forcedChunks.forEach((key, wasForced) -> key.world().setChunkForced(key.pos().x, key.pos().z, wasForced));
            if (failure == null) test.complete();
            else test.throwGameTestException(failure.getMessage() == null ? failure.toString() : failure.getMessage());
        }
        void success(CommandResult result, String phase) { check(result.succeeded(), phase + ": " + result); }
        void check(boolean condition, String message) { if (!condition) test.throwGameTestException(message); }
        static double horizontalSquared(Vec3d a, Vec3d b) { return Math.pow(a.x - b.x, 2) + Math.pow(a.z - b.z, 2); }
        private record BlockKey(ServerWorld world, BlockPos pos) { }
        private record ChunkKey(ServerWorld world, ChunkPos pos) { }
        private record TimedResult(CommandResult result, long tick) { }
    }
}
