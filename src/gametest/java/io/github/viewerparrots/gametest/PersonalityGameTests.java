package io.github.viewerparrots.gametest;

import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.api.CommandStatus;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.behavior.ParrotRuntime;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.config.ConfigLoader;
import io.github.viewerparrots.config.ViewerParrotsConfig;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.domain.DisplayName;
import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Sequential server-side personality acceptance; probability thresholds also have deterministic unit tests. */
public final class PersonalityGameTests implements FabricGameTest {
	@GameTest(batchId = "personality", templateName = EMPTY_STRUCTURE, tickLimit = 1800)
	public void idleTraitsShareLimitsAndNeverBlockCommands(TestContext test) {
        if (!FixtureCallbacks.ready(test, () -> idleTraitsShareLimitsAndNeverBlockCommands(test))) return;
		Scenario scenario = new Scenario(test);
		scenario.guard(scenario::start);
	}

	private static final class Scenario {
		private final TestContext test;
		private final ServerContext context = ServerContext.current().orElseThrow();
		private final ViewerParrotsConfig originalConfig = context.config();
		private final String originalFile;
		private final ActorContext actor = ActorContext.console(4);
		private final ViewerId normalId = ViewerId.of("normal-" + UUID.randomUUID().toString().substring(0, 8));
		private final ViewerId loudId = ViewerId.of("loud-" + UUID.randomUUID().toString().substring(0, 8));
		private ServerPlayerEntity target;
		private ViewerParrotEntity normal, loud;
		private ZombieEntity threat;
		private boolean failing;
		private final Map<ChunkPos, Boolean> forcedChunks = new LinkedHashMap<>();

		Scenario(TestContext test) {
			this.test = test;
			try { originalFile = Files.readString(context.configFile()); }
			catch (IOException error) { throw new IllegalStateException(error); }
		}
		void start() {
			for (int x = -10; x <= 30; x++) for (int z = -10; z <= 18; z++) {
				test.getWorld().setBlockState(test.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
			}
			target = test.createMockCreativeServerPlayerInWorld();
			Vec3d position = Vec3d.ofBottomCenter(test.getAbsolutePos(new BlockPos(4, 2, 4)));
			target.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
			configure(0, 1);
			await(context.streamerService().assign(UUID.randomUUID(), actor, target.getUuid()), result -> {
				check(result.succeeded(), "Assign target: " + result);
				await(context.parrotService().create(UUID.randomUUID(), actor, normalId, DisplayName.of("Normal acceptance")), created -> {
					check(created.succeeded(), "Create NORMAL: " + created);
					normal = context.loadedIndex().find(normalId).orElseThrow();
					await(context.parrotService().create(UUID.randomUUID(), actor, loudId, DisplayName.of("Loud acceptance")), second -> {
						check(second.succeeded(), "Create LOUD: " + second);
						loud = context.loadedIndex().find(loudId).orElseThrow();
						trait(normalId, ParrotTrait.NORMAL, () -> trait(loudId, ParrotTrait.LOUD, this::loudSound));
					});
				});
			});
		}

		void loudSound() {
			long now = context.clock().currentTick();
			context.runtime(normalId).scheduleIdle(now + 1200);
			context.runtime(loudId).scheduleIdle(now);
			later(2, () -> {
				check(loud.isSilent() && loud.getAmbientSound() == null, "Vanilla sounds must not bypass the scheduler");
				long next = context.runtime(loudId).nextIdleTick() - context.clock().currentTick();
				check(next >= 598 && next <= 2400, "Each idle opportunity schedules independent bounded jitter");
				check(context.cooldowns().remainingTicks(loudId, ParrotAction.SCREAM) > 0, "LOUD opportunity played through shared SCREAM deadline");
				check(context.cooldowns().remainingTicks(normalId, ParrotAction.SCREAM) == 0, "Dormant NORMAL schedule does not emit sounds");
				CommandResult global = context.commandService().submit(UUID.randomUUID(), actor, ParrotAction.SCREAM, normalId);
				check(global.code() == ErrorCode.GLOBAL_RATE_LIMITED, "Command and idle sound must share global budget");
				check(context.cooldowns().remainingTicks(normalId, ParrotAction.SCREAM) == 0, "Rejected sound cannot spend viewer cooldown");
				later(22, this::normalSound);
			});
		}

		void normalSound() {
			// Global quota has refilled; the longer per-viewer sound deadline still rejects this command.
			CommandResult repeat = context.commandService().submit(UUID.randomUUID(), actor, ParrotAction.SCREAM, loudId);
			check(repeat.code() == ErrorCode.COMMAND_ON_COOLDOWN, "Command sound must share the autonomous deadline");
			configure(1, 1);
			context.runtime(loudId).scheduleIdle(Long.MAX_VALUE);
			context.runtime(normalId).scheduleIdle(context.clock().currentTick());
			later(2, () -> {
				check(context.cooldowns().remainingTicks(normalId, ParrotAction.SCREAM) > 0, "NORMAL opportunity also uses shared sound deadline");
				later(22, this::idleFlight);
			});
		}

		void idleFlight() {
			configure(0, 0);
			Vec3d position = target.getPos().add(0, 1, 0);
			normal.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
			normal.setVelocity(Vec3d.ZERO);
			context.behaviorRandom().setSeed(1); // next jitter/double/boolean selects FLIGHT deterministically.
			context.runtime(normalId).scheduleIdle(context.clock().currentTick());
			later(2, () -> {
				ParrotRuntime.Maneuver flight = context.runtime(normalId).maneuver().orElse(null);
				check(flight != null, "Seeded NORMAL opportunity must start a flight on an open safe platform");
				check(targetDistanceSquared(flight) <= 9.00001, "Idle destination is at most 3 blocks away");
				check(flight.endsAtTick() - context.clock().currentTick() <= 40, "Idle flight deadline is at most 40 ticks");
				monitorManeuver(flight, 3, () -> trait(normalId, ParrotTrait.COWARD, this::coward));
			});
		}

		void coward() {
			Vec3d position = Vec3d.ofBottomCenter(test.getAbsolutePos(new BlockPos(4, 3, 4)));
			normal.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
			normal.setVelocity(Vec3d.ZERO);
			// A closer player is deliberately on the opposite side from the hostile mob.
			target.refreshPositionAndAngles(position.x + 1, position.y, position.z, 0, 0);
			threat = EntityType.ZOMBIE.create(test.getWorld());
			threat.setAiDisabled(true);
			threat.setInvulnerable(true);
			threat.refreshPositionAndAngles(position.x - 2, position.y, position.z, 0, 0);
			test.getWorld().spawnEntity(threat);
			context.runtime(normalId).scheduleIdle(Long.MAX_VALUE);
			context.runtime(normalId).scheduleDanger(context.clock().currentTick());
			later(2, () -> {
				ParrotRuntime.Maneuver escape = context.runtime(normalId).maneuver().orElse(null);
				check(escape != null, "COWARD must start an escape from a local hostile mob");
				check(escape.targetX() > escape.originX(), "COWARD moves away from hostile, never away from closer player");
				context.runtime(normalId).scheduleDanger(Long.MAX_VALUE);
				check(targetDistanceSquared(escape) <= 16.00001, "COWARD destination is at most 4 blocks away");
				check(context.runtime(normalId).behavior() == BehaviorState.IDLE, "Autonomous maneuver preserves IDLE state");
				monitorManeuver(escape, 4, () -> {
					threat.discard();
					trait(normalId, ParrotTrait.NORMAL, this::faultThenCome);
				});
			});
		}

		void monitorManeuver(ParrotRuntime.Maneuver maneuver, double bound, Runnable next) {
			if (context.runtime(normalId).maneuver().isEmpty()) {
				check(context.clock().currentTick() <= maneuver.endsAtTick() + 1, "Maneuver clears within 40 ticks");
				check(normal.getNavigation().isIdle(), "Maneuver navigation stops at its deadline");
				check(context.runtime(normalId).behavior() == BehaviorState.IDLE, "Maneuver does not change stable intention");
				next.run();
			} else {
				Vec3d origin = new Vec3d(maneuver.originX(), maneuver.originY(), maneuver.originZ());
				check(normal.getPos().squaredDistanceTo(origin) <= bound * bound + 0.00001, "Actual maneuver displacement stays within bound");
				check(context.clock().currentTick() <= maneuver.endsAtTick() + 1, "Maneuver may not exceed its deadline");
				later(1, () -> monitorManeuver(maneuver, bound, next));
			}
		}

		void faultThenCome() {
			ParrotRuntime runtime = context.runtime(normalId);
			runtime.recordBehaviorError(); runtime.recordBehaviorError(); runtime.recordBehaviorError();
			runtime.scheduleIdle(context.clock().currentTick());
			later(2, () -> {
				check(runtime.isBehaviorDisabledByErrors() && runtime.maneuver().isEmpty(), "Three trait faults stop autonomous movement");
				comeFromHeld(ParrotAction.SIT, () -> later(context.cooldowns().remainingTicks(normalId, ParrotAction.COME) + 1,
						() -> comeFromHeld(ParrotAction.DANCE, this::cleanup)));
			});
		}

		void comeFromHeld(ParrotAction hold, Runnable next) {
			// Keep the held entity in the already ticking test arena; move the target sixteen blocks away.
			Vec3d position = Vec3d.ofBottomCenter(test.getAbsolutePos(new BlockPos(4, 3, 4)));
			Vec3d farTarget = position.add(16, 0, 0);
			target.refreshPositionAndAngles(farTarget.x, farTarget.y, farTarget.z, 0, 0);
			forceTicking(target.getPos());
			forceTicking(position);
			normal.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
			normal.setVelocity(Vec3d.ZERO);
			UUID heldCommand = UUID.randomUUID();
			CommandResult held = context.commandService().submit(heldCommand, actor, hold, normalId);
			check(held.status() == CommandStatus.ACCEPTED || held.succeeded(), "Hold command accepted: " + hold);
			Runnable afterHold = () -> later(10, () -> {
				check(hold == ParrotAction.DANCE ? normal.isForcedDancing() : normal.isInSittingPose(), "Old " + hold + " hold goal is active before replacement");
				UUID command = UUID.randomUUID();
				CommandResult accepted = context.commandService().submit(command, actor, ParrotAction.COME, normalId);
				check(accepted.status() == CommandStatus.ACCEPTED || accepted.succeeded(), "Explicit COME remains accepted after trait fault");
				later(3, () -> {
					check(!normal.getNavigation().isIdle(), "Old " + hold + " goal must not stop newly accepted COME navigation");
					if (hold == ParrotAction.DANCE) {
						check(!normal.isForcedDancing(), "COME clears the old dance flag");
						var finished = context.commandService().completion(heldCommand);
						check(finished.isDone(), "COME must finish the interrupted dance future");
						check(finished.join().status() == CommandStatus.CANCELLED,
								"COME completes the interrupted dance as cancelled");
					}
					await(context.commandService().completion(command), result -> {
						check(result.succeeded(), "Explicit COME completes after trait fault: " + result);
						check(context.loadedIndex().find(normalId).orElseThrow().squaredDistanceTo(target) <= 9.01, "Faulted viewer still arrives at target");
						next.run();
					});
				});
			});
			if (hold == ParrotAction.DANCE) afterHold.run();
			else await(context.commandService().completion(heldCommand), result -> {
				check(result.succeeded(), "SIT durable intent succeeds before COME"); afterHold.run();
			});
		}
		void forceTicking(Vec3d position) {
			ChunkPos chunk = new ChunkPos(BlockPos.ofFloored(position));
			forcedChunks.putIfAbsent(chunk, test.getWorld().getForcedChunks().contains(chunk.toLong()));
			test.getWorld().setChunkForced(chunk.x, chunk.z, true);
		}
		void restoreTicking() {
			forcedChunks.forEach((chunk, original) -> test.getWorld().setChunkForced(chunk.x, chunk.z, original));
			forcedChunks.clear();
		}

		void cleanup() {
			restoreConfig();
			await(context.parrotService().remove(UUID.randomUUID(), actor, normalId), first -> {
				check(first.succeeded(), "Remove NORMAL");
				await(context.parrotService().remove(UUID.randomUUID(), actor, loudId), second -> {
					check(second.succeeded(), "Remove LOUD");
					restoreTicking();
					test.getWorld().getServer().getPlayerManager().remove(target);
					test.complete();
				});
			});
		}
		void trait(ViewerId id, ParrotTrait trait, Runnable next) {
			await(context.parrotService().setTrait(UUID.randomUUID(), actor, id, trait), result -> {
				check(result.succeeded(), "Set trait " + trait + ": " + result); next.run();
			});
		}
		<T> void await(CompletableFuture<T> future, Consumer<T> next) {
			if (future.isDone()) guard(() -> next.accept(future.join()));
			else later(1, () -> await(future, next));
		}
		void later(long ticks, Runnable work) { test.waitAndRun(ticks, () -> guard(work)); }
		void guard(Runnable work) {
			if (failing) return;
			try { work.run(); } catch (RuntimeException | Error failure) {
				failing = true;
				restoreConfig();
				if (threat != null) threat.discard();
				CompletableFuture<?> first = context.registry().contains(normalId)
						? context.parrotService().remove(UUID.randomUUID(), actor, normalId) : CompletableFuture.completedFuture(null);
				CompletableFuture<?> second = context.registry().contains(loudId)
						? context.parrotService().remove(UUID.randomUUID(), actor, loudId) : CompletableFuture.completedFuture(null);
				finishFailure(CompletableFuture.allOf(first, second), failure);
			}
		}
		void finishFailure(CompletableFuture<?> cleanup, Throwable failure) {
			if (!cleanup.isDone()) { test.waitAndRun(1, () -> finishFailure(cleanup, failure)); return; }
			if (target != null) test.getWorld().getServer().getPlayerManager().remove(target);
			restoreTicking();
			test.throwGameTestException(failure.getMessage() == null ? failure.toString() : failure.getMessage());
		}
		void check(boolean condition, String message) {
			if (!condition) test.throwGameTestException(message);
		}
		static double targetDistanceSquared(ParrotRuntime.Maneuver m) {
			return Math.pow(m.targetX() - m.originX(), 2) + Math.pow(m.targetY() - m.originY(), 2) + Math.pow(m.targetZ() - m.originZ(), 2);
		}
		void configure(double normalChance, double loudChance) {
			var b = originalConfig.behavior(); var c = originalConfig.cooldowns(); var limits = originalConfig.limits();
			ViewerParrotsConfig candidate = new ViewerParrotsConfig(originalConfig.schemaVersion(), originalConfig.maxActiveParrots(),
					originalConfig.deathMode(), originalConfig.nameFormat(), originalConfig.nameVisible(), originalConfig.permissions(),
					originalConfig.come(), originalConfig.follow(), originalConfig.dance(),
					new ViewerParrotsConfig.Cooldowns(c.come(), c.follow(), c.sit(), c.dance(), 60, c.respawn()), originalConfig.sound(),
					new ViewerParrotsConfig.Behavior(true, b.idleMinTicks(), b.idleMaxTicks(), b.dangerCheckTicks(), b.dangerRadius(),
							b.lazyMinTicks(), b.lazyMaxTicks(), normalChance, loudChance, b.traitWeights()),
					new ViewerParrotsConfig.Limits(limits.actionsPer20Ticks(), 1, limits.queueCapacity()), originalConfig.recovery(), originalConfig.storage());
			try { Files.writeString(context.configFile(), ConfigLoader.toJson(candidate)); }
			catch (IOException error) { throw new IllegalStateException(error); }
			check(FixtureCallbacks.finish(context.server(), context.reload(UUID.randomUUID(), actor)).succeeded(), "Test config reload must succeed");
		}
		void restoreConfig() {
			try { Files.writeString(context.configFile(), originalFile); }
			catch (IOException error) { throw new IllegalStateException(error); }
			FixtureCallbacks.finish(context.server(), context.reload(UUID.randomUUID(), actor));
		}
	}
}
