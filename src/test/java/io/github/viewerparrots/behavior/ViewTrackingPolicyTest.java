package io.github.viewerparrots.behavior;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ViewTrackingPolicyTest {
    @Test void bodyStaysStillWhileTheHeadCanReachTheTarget() {
        assertEquals(0, ViewTrackingPolicy.bodyYaw(0, 30, 60), 0.001);
        assertEquals(0, ViewTrackingPolicy.bodyYaw(0, 75, 75), 0.001);
    }

    @Test void headTurnsBeforeBodyStartsFollowing() {
        assertEquals(0, ViewTrackingPolicy.bodyYaw(0, 60, 150), 0.001);
        assertEquals(10, ViewTrackingPolicy.bodyYaw(0, 75, 150), 0.001);
        assertEquals(15, ViewTrackingPolicy.bodyYaw(10, 85, 90), 0.001);
    }

    @Test void turningBackDoesNotRotateTheBodyUntilTheHeadReachesTheOtherLimit() {
        assertEquals(0, ViewTrackingPolicy.bodyYaw(0, 75, -150), 0.001);
        assertEquals(-10, ViewTrackingPolicy.bodyYaw(0, -75, -150), 0.001);
    }

    @Test void anglesWrapAcrossNorthWithoutAnUnnecessaryFullTurn() {
        assertEquals(170, ViewTrackingPolicy.bodyYaw(170, -175, -170), 0.001);
        assertEquals(180, ViewTrackingPolicy.bodyYaw(170, -115, -70), 0.001);
    }

    @Test void configurableThresholdCanTurnTheBodyEarlierThanTheNeckLimit() {
        assertEquals(0, ViewTrackingPolicy.bodyYaw(0, 20, 60, 90, 30), 0.001);
        assertEquals(10, ViewTrackingPolicy.bodyYaw(0, 30, 60, 90, 30), 0.001);
        assertEquals(5, ViewTrackingPolicy.bodyYaw(0, 30, 35, 90, 30), 0.001);
    }

    @Test void zeroThresholdFollowsTheTargetImmediatelyWithoutRequiringAnInitialHeadTurn() {
        assertEquals(10, ViewTrackingPolicy.bodyYaw(0, 0, 60, 75, 0), 0.001);
        assertEquals(-10, ViewTrackingPolicy.bodyYaw(0, 0, -60, 75, 0), 0.001);
        assertEquals(0, ViewTrackingPolicy.bodyYaw(0, 0, 0, 75, 0), 0.001);
    }
}
