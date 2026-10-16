package io.github.viewerparrots.behavior;

/** Stationary birds first use their neck, then turn their body just enough to keep watching. */
public final class ViewTrackingPolicy {
    public static final float MAX_HEAD_YAW = 75;
    public static final float MAX_HEAD_PITCH = 40;
    private static final float BODY_TURN_PER_TICK = 10;

    private ViewTrackingPolicy() { }

    public static float bodyYaw(float bodyYaw, float headYaw, float targetYaw) {
        return bodyYaw(bodyYaw, headYaw, targetYaw, MAX_HEAD_YAW, MAX_HEAD_YAW);
    }

    public static float bodyYaw(float bodyYaw, float headYaw, float targetYaw, float headMaxAngle, float headTurnThreshold) {
        float threshold = Math.max(0, Math.min(headMaxAngle, headTurnThreshold));
        float targetDelta = wrap(targetYaw - bodyYaw);
        float headDelta = wrap(headYaw - bodyYaw);
        if (Math.abs(targetDelta) <= threshold || Math.abs(headDelta) < threshold - 0.1f
                || threshold > 0 && Math.signum(targetDelta) != Math.signum(headDelta)) return bodyYaw;
        float turn = targetDelta - Math.copySign(threshold, targetDelta);
        return bodyYaw + Math.max(-BODY_TURN_PER_TICK, Math.min(BODY_TURN_PER_TICK, turn));
    }

    private static float wrap(float degrees) {
        float result = degrees % 360;
        if (result >= 180) result -= 360;
        if (result < -180) result += 360;
        return result;
    }
}
