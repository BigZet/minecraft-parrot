package io.github.viewerparrots.behavior;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.diagnostics.AuditLog;
import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.api.CommandStatus;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.domain.DesiredBehavior;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.entity.CageCompat;
import io.github.viewerparrots.entity.HostileThreatAdapter;
import io.github.viewerparrots.entity.ParrotEntityController;
import io.github.viewerparrots.entity.SafePositionFinder;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import io.github.viewerparrots.control.ControlPanelService;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Loaded entities only. A personality fault never disables explicit commands. */
public final class BehaviorScheduler {
	public static final int MAX_TELEPORTS_PER_TICK = BehaviorWorkBudget.MAX_TELEPORTS;
	public static final double FREE_FOLLOW_RADIUS = 12;
	public static final double FREE_FOLLOW_RETURN_RADIUS = 8;
	private final ServerContext context;
	private final BehaviorWorkBudget budget = new BehaviorWorkBudget();
	private final HostileThreatAdapter threats = new HostileThreatAdapter();
	private final BehaviorFaultTracker faults = new BehaviorFaultTracker();
	private long lastTickNanos;

	public BehaviorScheduler(ServerContext context) { this.context = Objects.requireNonNull(context); }
	/** Create/respawn callbacks share this budget immediately before the actual spawn. */
	public boolean tryAcquireTeleport() { return budget.tryTeleport(context.clock().currentTick()); }
	public void clear() { budget.clear(); faults.clear(); }
	public void forgetFault(ViewerId id) { faults.forget(id); }
	public void resetFaults() { faults.clear(); context.runtimes().values().forEach(ParrotRuntime::resetBehaviorErrors); }
	public BehaviorWorkBudget workBudget() { return budget; }
	public long lastTickNanos() { return lastTickNanos; }

	public void tick() {
		long started = System.nanoTime();
		try { tickLoaded(); } finally { lastTickNanos = System.nanoTime() - started; }
	}
	private void tickLoaded() {
		if (!context.allowsMutations()) return;
		long now = context.clock().currentTick();
		BehaviorPolicy policy = new BehaviorPolicy(context.config().behavior(), context.behaviorRandom());
		for (ViewerId id : budget.roundRobin(context.runtimes().keySet())) {
			ParrotRuntime runtime = context.runtimes().get(id);
			ViewerParrotEntity parrot = context.loadedIndex().find(id).orElse(null);
			if (runtime == null || parrot == null || parrot.isRemoved() || parrot.isAiDisabled()) continue;
			long started = AuditLog.started();
			try {
				io.github.viewerparrots.cage.CageJail.tick(context, id, parrot);
				if (rescueFromVoid(id, parrot) || tickExplicit(id, runtime, parrot, now)) continue;
			} catch (RuntimeException e) {
				UUID commandId = runtime.pendingAction().map(ParrotRuntime.PendingAction::commandId).orElseGet(() -> runtime.movementCommandId().orElse(null));
				AuditLog.Context identity = auditContext(commandId, id, "BEHAVIOR");
				context.commandService().cancelRuntime(id, ErrorCode.INTERNAL_ERROR);
				parrot.getNavigation().stop();
				parrot.setForcedDancing(false);
				AuditLog.error("explicit_behavior_failed",
						identity, "FAILED", "INTERNAL_ERROR", started, java.util.Map.of(), e);
				continue;
			}
			started = AuditLog.started();
			try {
				if (tickAutonomous(id, runtime, parrot, policy, now)) runtime.recordBehaviorSuccess();
			} catch (RuntimeException e) {
				runtime.cancelAutonomousMovement();
				parrot.getNavigation().stop();
				runtime.recordBehaviorError();
				if (runtime.isBehaviorDisabledByErrors()) context.registry().find(id).ifPresent(record -> faults.disable(id, record.incarnationId()));
				AuditLog.warn("personality_failed",
						auditContext(null, id, "IDLE"),
						runtime.isBehaviorDisabledByErrors() ? "DISABLED" : "ISOLATED",
						"BEHAVIOR_FAILURE", started,
						java.util.Map.of("consecutiveErrors", runtime.consecutiveBehaviorErrors(),
								"disabled", runtime.isBehaviorDisabledByErrors()), e);
			}
		}
	}

	private boolean tickExplicit(ViewerId id, ParrotRuntime runtime, ViewerParrotEntity parrot, long now) {
		if (parrot.isSpinning()) return true;
		if (runPendingLazyAction(id, runtime, parrot, now)) return true;
		if (runtime.pendingAction().isPresent()) return true;
		if (runtime.isDanceExpired(now)) finishDance(id, runtime, parrot);
		if (CageCompat.isCaged(parrot) && holdInCage(id, runtime, parrot)) return true;
		if ((runtime.behavior() == BehaviorState.IDLE && runtime.desiredBehavior() == DesiredBehavior.FOLLOWING
				|| runtime.behavior() == BehaviorState.FREE && runtime.desiredBehavior() == DesiredBehavior.FREEFOLLOW)
				&& context.streamerService().onlineTarget().isPresent()) {
			parrot.getNavigation().stop();
			applyEvent(id, runtime, BehaviorStateMachine.Event.TARGET_AVAILABLE);
		}
		if (runtime.behavior() == BehaviorState.RETURNING) { tickReturning(id, runtime, parrot, now); return true; }
		if (runtime.behavior() == BehaviorState.FOLLOWING) { tickFollowing(id, runtime, parrot, now); return true; }
		if (runtime.behavior() == BehaviorState.FREEFOLLOW) { tickFreeFollowing(id, runtime, parrot, now); return true; }
		return runtime.behavior() != BehaviorState.IDLE || runtime.pendingAction().isPresent();
	}

	/** В клетке птица не летит к стримеру: «приди» завершается ошибкой, следование ждёт выхода из клетки. */
	private boolean holdInCage(ViewerId id, ParrotRuntime runtime, ViewerParrotEntity parrot) {
		BehaviorState behavior = runtime.behavior();
		if (behavior == BehaviorState.RETURNING) { finishMovement(id, runtime, parrot, ErrorCode.PARROT_CAGED); return true; }
		if (behavior == BehaviorState.FOLLOWING) { parrot.getNavigation().stop(); return true; }
		if (behavior == BehaviorState.FREEFOLLOW) {
			// Свободный полёт внутри клетки остаётся, догонять стримера птица не пытается.
			if (runtime.isFreeFollowCatchingUp()) parrot.getNavigation().stop();
			runtime.setFreeFollowCatchingUp(false);
			return true;
		}
		return false;
	}

	private boolean rescueFromVoid(ViewerId id, ViewerParrotEntity parrot) {
		if (!context.config().deathMode().blocksOrdinaryDamage() || parrot.getY() >= parrot.getWorld().getBottomY()) return false;
		long started = AuditLog.started();
		parrot.setVelocity(Vec3d.ZERO);
		parrot.fallDistance = 0;
		Optional<ServerPlayerEntity> target = context.streamerService().onlineTarget();
		if (target.isPresent() && tryAcquireTeleport() && teleportNear(parrot, target.get())) {
			AuditLog.warn("void_rescue",
					auditContext(null, id, "TELEPORT"),
					"SUCCEEDED", "VOID_RESCUE", started, java.util.Map.of(), null);
		}
		return true;
	}

	private AuditLog.Context auditContext(UUID commandId, ViewerId id, String action) {
		return context.registry().find(id).map(record -> AuditLog.Context.of(commandId, record, action))
				.orElseGet(() -> AuditLog.Context.viewer(commandId, id, action));
	}

	private boolean runPendingLazyAction(ViewerId id, ParrotRuntime runtime, ViewerParrotEntity parrot, long now) {
		ParrotRuntime.PendingAction pending = runtime.pendingAction().orElse(null);
		if (pending == null || now < pending.executeAtTick()) return false;
		runtime.clearPendingAction();
		ErrorCode failure = null;
		if (context.registry().find(id).map(r -> r.generation() != pending.generationAtSchedule()).orElse(true)) failure = ErrorCode.ENTITY_UNRESOLVED;
		else if (context.streamerService().onlineTarget().isEmpty()) failure = ErrorCode.TARGET_UNAVAILABLE;
		BehaviorStateMachine.Result transition = BehaviorStateMachine.apply(runtime.behavior(), runtime.desiredBehavior(), pending.event());
		if (transition instanceof BehaviorStateMachine.Rejected rejected) failure = rejected.code();
		if (failure != null) {
			context.commandService().cancelDeferred(pending.commandId(), id, failure);
			return true;
		}
		ParrotAction action = pending.event() == BehaviorStateMachine.Event.COMMAND_COME ? ParrotAction.COME : ParrotAction.FOLLOW;
		CommandResult result = context.commandService().execute(pending.commandId(), id, runtime, parrot,
				(BehaviorStateMachine.Accepted) transition, action);
		if (result.status() != CommandStatus.ACCEPTED) context.commandService().completeDeferred(pending.commandId(), id, result);
		return true;
	}

	private void finishDance(ViewerId id, ParrotRuntime runtime, ViewerParrotEntity parrot) {
		UUID command = runtime.danceCommandId().orElse(null);
		parrot.setForcedDancing(false);
		parrot.getNavigation().stop();
		applyEvent(id, runtime, BehaviorStateMachine.Event.DANCE_EXPIRED);
		if (command != null) context.commandService().finishDance(command, id);
	}

	private void tickReturning(ViewerId id, ParrotRuntime runtime, ViewerParrotEntity parrot, long now) {
		ServerPlayerEntity target = context.streamerService().onlineTarget().orElse(null);
		if (target == null) { finishMovement(id, runtime, parrot, ErrorCode.TARGET_UNAVAILABLE); return; }
		boolean sameWorld = sameWorld(parrot, target);
		double distance = sameWorld ? parrot.squaredDistanceTo(target) : Double.POSITIVE_INFINITY;
		double arrival = context.config().come().arrivalRadius();
		if (distance <= arrival * arrival) { finishMovement(id, runtime, parrot, null); return; }
		boolean far = distance > Math.pow(context.config().come().maxPathfindDistance(), 2);
		boolean timedOut = runtime.isMovementExpired(now);
		if (far || timedOut) {
			if (!sameWorld && !context.config().come().crossDimensionTeleport()) {
				finishMovement(id, runtime, parrot, ErrorCode.DIMENSION_UNAVAILABLE); return;
			}
			boolean allowed = far ? context.config().come().teleportIfFar() : context.config().come().teleportOnPathFailure();
			if (!allowed) { finishMovement(id, runtime, parrot, ErrorCode.PATH_NOT_FOUND); return; }
			if (!tryAcquireTeleport()) return;
			finishMovement(id, runtime, parrot, teleportNear(parrot, target) ? null : ErrorCode.NO_SAFE_POSITION);
			return;
		}
		moveToward(runtime, parrot, target, now);
	}

	private void finishMovement(ViewerId id, ParrotRuntime runtime, ViewerParrotEntity parrot, ErrorCode error) {
		UUID commandId = runtime.movementCommandId().orElse(null);
		parrot.getNavigation().stop();
		applyEvent(id, runtime, error == null ? BehaviorStateMachine.Event.ARRIVAL_REACHED : BehaviorStateMachine.Event.MOVEMENT_TIMEOUT);
		if (commandId != null) {
			if (error == ErrorCode.TARGET_UNAVAILABLE) context.commandService().cancelDeferred(commandId, id, error);
			else context.commandService().finishMovement(commandId, id, error);
		}
	}

	private void tickFollowing(ViewerId id, ParrotRuntime runtime, ViewerParrotEntity parrot, long now) {
		ServerPlayerEntity target = context.streamerService().onlineTarget().orElse(null);
		if (target == null) { parrot.getNavigation().stop(); applyEvent(id, runtime, BehaviorStateMachine.Event.TARGET_LOST); return; }
		boolean sameWorld = sameWorld(parrot, target);
		double distance = sameWorld ? parrot.squaredDistanceTo(target) : Double.POSITIVE_INFINITY;
		double stop = context.config().follow().stopDistance();
		if (distance <= stop * stop) { parrot.getNavigation().stop(); return; }
		if (distance > Math.pow(context.config().come().maxPathfindDistance(), 2)) {
			if (!sameWorld && !context.config().come().crossDimensionTeleport()) { parrot.getNavigation().stop(); return; }
			if (context.config().come().teleportIfFar() && runtime.canRepath(now) && tryAcquireTeleport()) {
				runtime.markRepathed(now, repathTicks());
				teleportNear(parrot, target);
			}
			return;
		}
		double start = context.config().follow().startDistance();
		if (distance >= start * start) moveToward(runtime, parrot, target, now);
	}

	private void moveToward(ParrotRuntime runtime, ViewerParrotEntity parrot, ServerPlayerEntity target, long now) {
		if (!runtime.canRepath(now) || !budget.tryPath(runtime.viewerId(), now)) return;
		runtime.markRepathed(now, repathTicks());
		parrot.getNavigation().startMovingTo(target, 1.0);
	}

	/** Wander locally; only catch up when the streamer leaves the bird's relaxed neighborhood. */
	private void tickFreeFollowing(ViewerId id, ParrotRuntime runtime, ViewerParrotEntity parrot, long now) {
		ServerPlayerEntity target = context.streamerService().onlineTarget().orElse(null);
		if (target == null) {
			parrot.getNavigation().stop();
			applyEvent(id, runtime, BehaviorStateMachine.Event.TARGET_LOST);
			return;
		}
		boolean sameWorld = sameWorld(parrot, target);
		double distance = sameWorld ? parrot.squaredDistanceTo(target) : Double.POSITIVE_INFINITY;
		var gameplay = ControlPanelService.settings().active().gameplay();
		if (distance <= gameplay.freefollowReturnDistance() * gameplay.freefollowReturnDistance()) {
			if (runtime.isFreeFollowCatchingUp()) parrot.getNavigation().stop();
			runtime.setFreeFollowCatchingUp(false);
			return;
		}
		if (!runtime.isFreeFollowCatchingUp() && distance <= gameplay.freefollowRadius() * gameplay.freefollowRadius()) return;
		runtime.setFreeFollowCatchingUp(true);
		if (distance > Math.pow(context.config().come().maxPathfindDistance(), 2)) {
			if (!sameWorld && !context.config().come().crossDimensionTeleport()) {
				parrot.getNavigation().stop();
				return;
			}
			if (context.config().come().teleportIfFar() && runtime.canRepath(now) && tryAcquireTeleport()) {
				runtime.markRepathed(now, repathTicks());
				if (teleportNear(parrot, target)) runtime.setFreeFollowCatchingUp(false);
			}
			return;
		}
		moveToward(runtime, parrot, target, now);
	}
	private int repathTicks() { return Math.max(20, context.config().follow().repathTicks()); }
	private static boolean sameWorld(ViewerParrotEntity parrot, ServerPlayerEntity target) {
		return parrot.getWorld().getRegistryKey().equals(target.getServerWorld().getRegistryKey());
	}

	private boolean tickAutonomous(ViewerId id, ParrotRuntime runtime, ViewerParrotEntity parrot, BehaviorPolicy policy, long now) {
		var record = context.registry().find(id).orElse(null);
		if (record == null) return false;
		if (runtime.isBehaviorDisabledByErrors()) faults.disable(id, record.incarnationId());
		if (faults.isDisabled(id, record.incarnationId())) runtime.restoreBehaviorFault();
		if (!context.config().behavior().enabled() || runtime.isBehaviorDisabledByErrors()) {
			if (runtime.maneuver().isPresent()) { runtime.cancelAutonomousMovement(); parrot.getNavigation().stop(); }
			return false;
		}
		if (runtime.maneuver().isPresent()) {
			ParrotRuntime.Maneuver m = runtime.maneuver().get();
			Vec3d origin = new Vec3d(m.originX(), m.originY(), m.originZ());
			if (now >= m.endsAtTick() || parrot.getNavigation().isIdle() || parrot.getPos().squaredDistanceTo(origin) >= m.maxDistance() * m.maxDistance()) {
				runtime.cancelAutonomousMovement(); parrot.getNavigation().stop(); parrot.setVelocity(Vec3d.ZERO);
			}
			return false;
		}
		if (runtime.nextIdleTick() == 0) runtime.scheduleIdle(now + policy.nextIdleDelay());
		boolean attempted = false;
		if (policy.canFlee(record.trait(), runtime.behavior(), false)) {
			int interval = Math.max(20, context.config().behavior().dangerCheckTicks());
			if (runtime.nextDangerTick() == 0) runtime.scheduleDanger(now + context.behaviorRandom().nextInt(interval) + 1);
			if (now >= runtime.nextDangerTick() && budget.tryDanger(now)) {
				runtime.scheduleDanger(now + interval);
				attempted = true;
				threats.nearest(parrot, context.config().behavior().dangerRadius()).ifPresent(threat -> {
					for (BehaviorPolicy.Offset offset : policy.escapeOffsets(parrot.getX() - threat.getX(), parrot.getZ() - threat.getZ())) {
						if (startManeuver(runtime, parrot, offset, 4, now)) break;
					}
				});
				if (runtime.maneuver().isPresent()) return true;
			}
		}
		if (now < runtime.nextIdleTick()) return attempted;
		runtime.scheduleIdle(now + policy.nextIdleDelay());
		switch (policy.idleAction(record.trait(), runtime.behavior(), false)) {
			case SOUND -> {
				if (context.soundService().hasUsableSounds() && !context.cooldowns().isOnCooldown(id, ParrotAction.SCREAM) && context.rateLimiter().tryAcquireIdleSound()) {
					if (context.soundService().playScream(parrot)) context.cooldowns().consume(id, ParrotAction.SCREAM, context.config().cooldowns().scream());
				}
			}
			case LOOK -> {
				if (parrot.isViewingStreamer()) break;
				var player = parrot.getWorld().getClosestPlayer(parrot, 8);
				if (player != null) parrot.getLookControl().lookAt(player, 30, 30);
			}
			case FLIGHT -> startManeuver(runtime, parrot, policy.flightOffset(), 3, now);
			case NONE -> { }
		}
		return true;
	}

	private boolean startManeuver(ParrotRuntime runtime, ViewerParrotEntity parrot, BehaviorPolicy.Offset offset, double bound, long now) {
		Vec3d destination = parrot.getPos().add(offset.x(), offset.y(), offset.z());
		if (runtime.behavior() != BehaviorState.IDLE || !runtime.canRepath(now) || !context.safePositionFinder().isSafe((ServerWorld) parrot.getWorld(), destination)) return false;
		if (!budget.tryPath(runtime.viewerId(), now)) return false;
		runtime.markRepathed(now, repathTicks());
		if (!parrot.getNavigation().startMovingTo(destination.x, destination.y, destination.z, 1.0)) return false;
		runtime.startManeuver(new ParrotRuntime.Maneuver(parrot.getX(), parrot.getY(), parrot.getZ(), destination.x, destination.y, destination.z, bound, now + BehaviorPolicy.MANEUVER_TICKS));
		return true;
	}

	/** At most 32 local loaded-block candidates, within the actual COME arrival radius. */
	private boolean teleportNear(ViewerParrotEntity parrot, ServerPlayerEntity target) {
		ServerWorld world = target.getServerWorld();
		if (!sameWorld(parrot, target) && !context.config().come().crossDimensionTeleport()) return false;
		double radius = Math.min(3, context.config().come().arrivalRadius());
		Vec3d safe = null;
		for (int attempt = 0; attempt < SafePositionFinder.MAX_CANDIDATES; attempt++) {
			double angle = context.behaviorRandom().nextDouble() * Math.PI * 2;
			double vertical = Math.min(radius / 2, 1) * (context.behaviorRandom().nextInt(3) - 1);
			double horizontal = Math.sqrt(radius * radius - vertical * vertical) * (0.5 + context.behaviorRandom().nextDouble() * 0.45);
			Vec3d candidate = target.getPos().add(Math.cos(angle) * horizontal, vertical, Math.sin(angle) * horizontal);
			if (context.safePositionFinder().isSafe(world, candidate)) { safe = candidate; break; }
		}
		if (safe == null && context.safePositionFinder().isSafe(world, target.getPos())) safe = target.getPos();
		if (safe == null) return false;
		context.loadedIndex().findViewerId(parrot.getUuid()).map(context.runtimes()::get)
				.ifPresent(ParrotRuntime::cancelAutonomousMovement);
		parrot.getNavigation().stop();
		return parrot.teleport(world, safe.x, safe.y, safe.z, Set.<PositionFlag>of(), parrot.getYaw(), parrot.getPitch());
	}

	private void applyEvent(ViewerId id, ParrotRuntime runtime, BehaviorStateMachine.Event event) {
		String old = runtime.behavior().name();
		if (BehaviorStateMachine.apply(runtime.behavior(), runtime.desiredBehavior(), event) instanceof BehaviorStateMachine.Accepted accepted) {
			runtime.apply(accepted, context.clock().currentTick());
			context.commandService().behaviorChanged(id, old, runtime.behavior().name(), event.name());
		}
	}
	public static void refreshLocationHint(ServerContext context, ViewerId id, ViewerParrotEntity parrot) {
		context.registry().find(id).ifPresent(record -> context.registry().replace(record.withLocation(ParrotEntityController.locationOf(parrot), context.clock().now())));
	}
	public static boolean hasSupport(ViewerParrotEntity parrot) {
		return SafePositionFinder.hasSupportBelow(parrot.getWorld(), parrot.getBlockPos(), SafePositionFinder.SUPPORT_SCAN_DEPTH);
	}
}
