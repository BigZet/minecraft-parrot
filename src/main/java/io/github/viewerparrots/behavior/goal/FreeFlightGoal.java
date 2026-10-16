package io.github.viewerparrots.behavior.goal;

import io.github.viewerparrots.behavior.ParrotRuntime;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import io.github.viewerparrots.control.ControlPanelService;
import net.minecraft.block.LeavesBlock;
import net.minecraft.entity.ai.FuzzyTargeting;
import net.minecraft.entity.ai.goal.FlyGoal;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Vanilla flight opportunities and tree perches, gated by the viewer's stable free intent. */
public final class FreeFlightGoal extends FlyGoal {
	private final ViewerParrotEntity parrot;

	public FreeFlightGoal(ViewerParrotEntity parrot, double speed) {
		super(parrot, speed);
		this.parrot = parrot;
	}

	@Override
	public boolean canStart() {
		if (!parrot.isFreeFlightEnabled() || !super.canStart()) return false;
		ServerContext context = ServerContext.current().orElse(null);
		ParrotRuntime runtime = runtime(context);
		if (runtime == null) return false;
		long now = context.clock().currentTick();
		if (!runtime.canRepath(now) || !context.behaviorScheduler().workBudget().tryPath(runtime.viewerId(), now)) return false;
		runtime.markRepathed(now, 20);
		return true;
	}

	@Override
	public boolean shouldContinue() { return parrot.isFreeFlightEnabled() && super.shouldContinue(); }

	@Override
	public void stop() {
		// A command or catch-up route may have replaced this goal's path before GoalSelector cleans it up.
		if (parrot.isFreeFlightEnabled()) super.stop();
	}

	@Override
	protected Vec3d getWanderTarget() {
		Vec3d perch = findTreePerch();
		if (perch != null) return perch;
		// Landing opportunities alternate with airborne routes, with vanilla's 120-tick chance between trips.
		Vec3d destination = parrot.isTouchingWater() || parrot.getRandom().nextBoolean()
				? FuzzyTargeting.find(parrot, 8, 4) : super.getWanderTarget();
		if (isWithinNeighborhood(destination)) return destination;
		for (int attempt = 0; attempt < 2; attempt++) {
			destination = super.getWanderTarget();
			if (isWithinNeighborhood(destination)) return destination;
		}
		return null;
	}

	/** Mirrors vanilla's nearby tree search without requesting unloaded chunks. */
	private Vec3d findTreePerch() {
		BlockPos current = parrot.getBlockPos();
		for (BlockPos candidate : BlockPos.iterate(MathHelper.floor(parrot.getX() - 3), MathHelper.floor(parrot.getY() - 6),
				MathHelper.floor(parrot.getZ() - 3), MathHelper.floor(parrot.getX() + 3), MathHelper.floor(parrot.getY() + 6),
				MathHelper.floor(parrot.getZ() + 3))) {
			if (candidate.equals(current) || !parrot.getWorld().isChunkLoaded(candidate)) continue;
			Vec3d position = Vec3d.ofBottomCenter(candidate);
			if (!isWithinNeighborhood(position)) continue;
			var support = parrot.getWorld().getBlockState(candidate.down());
			if ((support.getBlock() instanceof LeavesBlock || support.isIn(BlockTags.LOGS))
					&& parrot.getWorld().isAir(candidate) && parrot.getWorld().isAir(candidate.up())) return position;
		}
		return null;
	}

	private boolean isWithinNeighborhood(Vec3d destination) {
		if (destination == null) return false;
		ServerContext context = ServerContext.current().orElse(null);
		ParrotRuntime runtime = runtime(context);
		if (runtime == null) return false;
		if (runtime.behavior() != BehaviorState.FREEFOLLOW) return true;
		ServerPlayerEntity target = context.streamerService().onlineTarget().orElse(null);
		double radius = ControlPanelService.settings().active().gameplay().freefollowRadius();
		return target == null || (parrot.getWorld().getRegistryKey().equals(target.getWorld().getRegistryKey())
				&& destination.squaredDistanceTo(target.getPos()) <= radius * radius);
	}

	private ParrotRuntime runtime(ServerContext context) {
		if (context == null) return null;
		var id = context.loadedIndex().findViewerId(parrot.getUuid()).orElse(null);
		return id == null ? null : context.runtimes().get(id);
	}
}
