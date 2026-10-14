package io.github.viewerparrots.behavior;

import io.github.viewerparrots.config.ViewerParrotsConfig;
import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.domain.ParrotTrait;

import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Pure personality decisions. The adapter owns world queries and all side effects. */
public final class BehaviorPolicy {
	public static final int MANEUVER_TICKS = 40;
	public static final int MAX_HOSTILE_CANDIDATES = 16;
	public enum IdleAction { NONE, SOUND, LOOK, FLIGHT }
	public record Offset(double x, double y, double z) {
		public double lengthSquared() { return x * x + y * y + z * z; }
	}
	private final ViewerParrotsConfig.Behavior config;
	private final RandomGenerator random;

	public BehaviorPolicy(ViewerParrotsConfig.Behavior config, RandomGenerator random) {
		this.config = Objects.requireNonNull(config);
		this.random = Objects.requireNonNull(random);
	}

	public int nextIdleDelay() {
		return config.idleMinTicks() + random.nextInt(config.idleMaxTicks() - config.idleMinTicks() + 1);
	}

	public IdleAction idleAction(ParrotTrait trait, BehaviorState state, boolean disabledByErrors) {
		if (!config.enabled() || disabledByErrors || state != BehaviorState.IDLE) return IdleAction.NONE;
		double probability = trait == ParrotTrait.LOUD ? config.loudSoundChance() : config.normalSoundChance();
		if (random.nextDouble() < probability) return IdleAction.SOUND;
		return random.nextBoolean() ? IdleAction.LOOK : IdleAction.FLIGHT;
	}

	public boolean canFlee(ParrotTrait trait, BehaviorState state, boolean disabledByErrors) {
		return config.enabled() && !disabledByErrors && trait == ParrotTrait.COWARD && state == BehaviorState.IDLE;
	}

	/** Target displacement is bounded in three dimensions, including vertical motion. */
	public Offset flightOffset() {
		double angle = random.nextDouble() * Math.PI * 2;
		double vertical = random.nextDouble() * 1.5 - 0.75;
		double distance = 1 + random.nextDouble() * 2;
		double horizontal = Math.sqrt(Math.max(0, distance * distance - vertical * vertical));
		return new Offset(Math.cos(angle) * horizontal, vertical, Math.sin(angle) * horizontal);
	}

	/** Three bounded escape directions; blocked directions never trigger a wider search. */
	public List<Offset> escapeOffsets(double awayX, double awayZ) {
		double angle = awayX == 0 && awayZ == 0 ? random.nextDouble() * Math.PI * 2 : Math.atan2(awayZ, awayX);
		return List.of(escapeAt(angle), escapeAt(angle + Math.PI / 4), escapeAt(angle - Math.PI / 4));
	}
	private static Offset escapeAt(double angle) {
		return new Offset(Math.cos(angle) * 3.8, 0.5, Math.sin(angle) * 3.8);
	}

	/** Constrain the next physical move, since navigation nodes and residual velocity can overshoot a target. */
	public static Offset limitMovement(Offset fromOrigin, Offset movement, double maxDistance) {
		double boundSquared = maxDistance * maxDistance;
		if (worstDisplacementSquared(fromOrigin, movement, 1) <= boundSquared) return movement;
		// Collision resolution can shorten each axis separately. Find a safe fraction for every such result.
		double low = 0, high = 1;
		for (int attempt = 0; attempt < 28; attempt++) {
			double fraction = (low + high) / 2;
			if (worstDisplacementSquared(fromOrigin, movement, fraction) <= boundSquared) low = fraction;
			else high = fraction;
		}
		return new Offset(movement.x() * low, movement.y() * low, movement.z() * low);
	}
	private static double worstDisplacementSquared(Offset from, Offset movement, double fraction) {
		double x = Math.max(Math.abs(from.x()), Math.abs(from.x() + movement.x() * fraction));
		double y = Math.max(Math.abs(from.y()), Math.abs(from.y() + movement.y() * fraction));
		double z = Math.max(Math.abs(from.z()), Math.abs(from.z() + movement.z() * fraction));
		return x * x + y * y + z * z;
	}
}
