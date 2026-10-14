package io.github.viewerparrots.behavior;

import io.github.viewerparrots.config.ViewerParrotsConfig;
import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.domain.ParrotTrait;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BehaviorPolicyTest {
	private final ViewerParrotsConfig.Behavior config = ViewerParrotsConfig.defaults().behavior();

	@Test
	void idleJitterIncludesBothBoundsAndSeedReproducesIndependentSchedule() {
		assertEquals(600, new BehaviorPolicy(config, new FixedRandom(0, 0, false)).nextIdleDelay());
		assertEquals(2400, new BehaviorPolicy(config, new FixedRandom(1800, 0, false)).nextIdleDelay());
		BehaviorPolicy first = new BehaviorPolicy(config, new Random(7));
		BehaviorPolicy second = new BehaviorPolicy(config, new Random(7));
		int a = first.nextIdleDelay();
		int b = first.nextIdleDelay();
		assertEquals(a, second.nextIdleDelay());
		assertEquals(b, second.nextIdleDelay());
		assertNotEquals(a, b);
	}

	@Test
	void loudAndNormalUseTheirOwnProbabilityThresholds() {
		BehaviorPolicy normal = new BehaviorPolicy(config, new FixedRandom(0, 0.1, false));
		BehaviorPolicy loud = new BehaviorPolicy(config, new FixedRandom(0, 0.1, false));
		assertEquals(BehaviorPolicy.IdleAction.FLIGHT, normal.idleAction(ParrotTrait.NORMAL, BehaviorState.IDLE, false));
		assertEquals(BehaviorPolicy.IdleAction.SOUND, loud.idleAction(ParrotTrait.LOUD, BehaviorState.IDLE, false));
		assertEquals(BehaviorPolicy.IdleAction.FLIGHT, new BehaviorPolicy(config,
				new FixedRandom(0, 0.5, false)).idleAction(ParrotTrait.LOUD, BehaviorState.IDLE, false));
		assertEquals(BehaviorPolicy.IdleAction.SOUND, new BehaviorPolicy(config,
				new FixedRandom(0, 0.099, false)).idleAction(ParrotTrait.NORMAL, BehaviorState.IDLE, false));
	}

	@Test
	void nonsoundOpportunityChoosesLookAndFlightEquallyViaBoolean() {
		assertEquals(BehaviorPolicy.IdleAction.LOOK, new BehaviorPolicy(config,
				new FixedRandom(0, 0.9, true)).idleAction(ParrotTrait.NORMAL, BehaviorState.IDLE, false));
		assertEquals(BehaviorPolicy.IdleAction.FLIGHT, new BehaviorPolicy(config,
				new FixedRandom(0, 0.9, false)).idleAction(ParrotTrait.NORMAL, BehaviorState.IDLE, false));
	}

	@Test
	void disabledOrCommandBusyBehaviorConsumesNoRandomness() {
		Random forbidden = new Random() {
			@Override public double nextDouble() { throw new AssertionError("random read while disabled"); }
		};
		BehaviorPolicy active = new BehaviorPolicy(config, forbidden);
		assertEquals(BehaviorPolicy.IdleAction.NONE, active.idleAction(ParrotTrait.LOUD, BehaviorState.FOLLOWING, false));
		assertEquals(BehaviorPolicy.IdleAction.NONE, active.idleAction(ParrotTrait.LOUD, BehaviorState.IDLE, true));
		ViewerParrotsConfig.Behavior disabled = new ViewerParrotsConfig.Behavior(false,
				600, 2400, 20, 8, 20, 100, 0.1, 0.5, config.traitWeights());
		BehaviorPolicy inactive = new BehaviorPolicy(disabled, forbidden);
		assertEquals(BehaviorPolicy.IdleAction.NONE, inactive.idleAction(ParrotTrait.LOUD, BehaviorState.IDLE, false));
		assertFalse(inactive.canFlee(ParrotTrait.COWARD, BehaviorState.IDLE, false));
	}

	@Test
	void flightTargetsAreBoundedAndCowardOnlyFleesInIdle() {
		BehaviorPolicy policy = new BehaviorPolicy(config, new Random(5));
		for (int i = 0; i < 100; i++) {
			assertTrue(policy.flightOffset().lengthSquared() <= 9.0);
		}
		assertTrue(policy.canFlee(ParrotTrait.COWARD, BehaviorState.IDLE, false));
		assertFalse(policy.canFlee(ParrotTrait.COWARD, BehaviorState.FOLLOWING, false));
		assertFalse(policy.canFlee(ParrotTrait.NORMAL, BehaviorState.IDLE, false));
		for (BehaviorPolicy.Offset offset : policy.escapeOffsets(1, 0)) {
			assertTrue(offset.lengthSquared() <= 16.0);
			assertTrue(offset.x() > 0, "maneuver moves away from threat");
		}
	}

	@Test
	void navigationAndInertiaCannotMoveAnIdleManeuverPastItsBound() {
		var clipped = BehaviorPolicy.limitMovement(new BehaviorPolicy.Offset(-3.8, 0, 0),
				new BehaviorPolicy.Offset(-0.5, 0, 0), 4);
		assertTrue(Math.pow(-3.8 + clipped.x(), 2) <= 16, "Grid-centered path and inertia cannot overshoot four blocks");
		var diagonal = BehaviorPolicy.limitMovement(new BehaviorPolicy.Offset(2, 0, 0), new BehaviorPolicy.Offset(2, 2, 0), 3);
		assertTrue(Math.pow(2 + diagonal.x(), 2) + diagonal.y() * diagonal.y() <= 9, "Bound applies in three dimensions");
		var sliding = BehaviorPolicy.limitMovement(new BehaviorPolicy.Offset(3.9, 0.4, 0), new BehaviorPolicy.Offset(0.2, -0.4, 0), 4);
		assertTrue(Math.pow(3.9 + sliding.x(), 2) + 0.4 * 0.4 <= 16, "Collision may block the inward vertical component");
		assertEquals(new BehaviorPolicy.Offset(0.25, -0.1, 0), BehaviorPolicy.limitMovement(new BehaviorPolicy.Offset(0, 0, 0),
				new BehaviorPolicy.Offset(0.25, -0.1, 0), 3));
	}

	private static class FixedRandom extends Random {
		private final int number;
		private final double probability;
		private final boolean look;
		FixedRandom(int number, double probability, boolean look) {
			this.number = number; this.probability = probability; this.look = look;
		}
		@Override public int nextInt(int bound) { return number; }
		@Override public double nextDouble() { return probability; }
		@Override public boolean nextBoolean() { return look; }
	}
}
