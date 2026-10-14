package io.github.viewerparrots.behavior.goal;

import io.github.viewerparrots.behavior.ParrotRuntime;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.entity.SafePositionFinder;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import net.minecraft.entity.ai.goal.Goal;

import java.util.EnumSet;

/** Removes residual navigation thrust between idle opportunities and while a LAZY command waits. */
public final class IdleHoldGoal extends Goal {
	private final ViewerParrotEntity parrot;
	public IdleHoldGoal(ViewerParrotEntity parrot) {
		this.parrot = parrot;
		setControls(EnumSet.of(Control.MOVE, Control.JUMP));
	}
	@Override public boolean canStart() {
		ServerContext context = ServerContext.current().orElse(null);
		if (context == null) return false;
		var id = context.loadedIndex().findViewerId(parrot.getUuid()).orElse(null);
		ParrotRuntime runtime = id == null ? null : context.runtimes().get(id);
		return runtime != null && (runtime.pendingAction().isPresent()
				|| runtime.behavior() == BehaviorState.IDLE && runtime.maneuver().isEmpty());
	}
	@Override public boolean shouldContinue() { return canStart(); }
	@Override public boolean shouldRunEveryTick() { return true; }
	@Override public void tick() {
		// GoalSelector may tick a previously running goal before its next eligibility pass.
		if (!canStart()) return;
		parrot.getNavigation().stop();
		parrot.setMovementSpeed(0);
		boolean support = SafePositionFinder.hasSupportBelow(parrot.getWorld(), parrot.getBlockPos(), SafePositionFinder.SUPPORT_SCAN_DEPTH);
		parrot.setVelocity(parrot.getVelocity().multiply(0, support ? 1 : 0, 0));
		if (!support) parrot.fallDistance = 0;
	}
}
