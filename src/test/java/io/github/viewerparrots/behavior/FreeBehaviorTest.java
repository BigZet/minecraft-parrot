package io.github.viewerparrots.behavior;

import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.domain.DesiredBehavior;
import io.github.viewerparrots.domain.ViewerId;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FreeBehaviorTest {

	private static BehaviorStateMachine.Accepted transition(BehaviorState from,
			DesiredBehavior desired, String event) {
		return assertInstanceOf(BehaviorStateMachine.Accepted.class,
				BehaviorStateMachine.apply(from, desired, BehaviorStateMachine.Event.valueOf(event)));
	}

	@Test
	void newlyCreatedAndUnconfiguredBirdsStartFree() {
		ParrotRuntime runtime = new ParrotRuntime(ViewerId.of("viewer1"));
		assertEquals("FREE", runtime.behavior().name());
		assertEquals("FREE", runtime.desiredBehavior().name());
		var created = transition(BehaviorState.SITTING, DesiredBehavior.SITTING, "CREATED_OR_RESPAWNED");
		assertEquals("FREE", created.behavior().name());
		assertEquals("FREE", created.desiredBehavior().name());
	}

	@Test
	void legacyIdleIntentRestoresNaturalFreeBehavior() {
		ParrotRuntime runtime = new ParrotRuntime(ViewerId.of("viewer1"));
		runtime.restoreDesired(DesiredBehavior.IDLE);
		assertEquals("FREE", runtime.behavior().name());
		assertEquals("FREE", runtime.desiredBehavior().name());
	}

	@Test
	void freeCommandsCancelTemporaryAndLazyActions() {
		for (String event : new String[]{"COMMAND_FREE", "COMMAND_FREEFOLLOW"}) {
			ParrotRuntime runtime = new ParrotRuntime(ViewerId.of("viewer1"));
			runtime.apply(transition(runtime.behavior(), runtime.desiredBehavior(), "COMMAND_DANCE"), 1);
			runtime.startDance(UUID.randomUUID(), 100);
			runtime.startMovement(UUID.randomUUID(), 200);
			runtime.setPendingAction(new ParrotRuntime.PendingAction(UUID.randomUUID(),
					BehaviorStateMachine.Event.COMMAND_FOLLOW, 70, 1));
			runtime.apply(transition(runtime.behavior(), runtime.desiredBehavior(), event), 2);
			assertEquals(event.substring("COMMAND_".length()), runtime.behavior().name());
			assertEquals(runtime.behavior().name(), runtime.desiredBehavior().name());
			assertEquals(0, runtime.movementDeadlineTick());
			assertTrue(runtime.danceCommandId().isEmpty());
			assertTrue(runtime.movementCommandId().isEmpty());
			assertTrue(runtime.pendingAction().isEmpty());
		}
	}

	@Test
	void temporaryCommandsReturnToBothFreeIntents() {
		for (String name : new String[]{"FREE", "FREEFOLLOW"}) {
			DesiredBehavior desired = DesiredBehavior.valueOf(name);
			assertEquals(desired.toBehaviorState(), transition(BehaviorState.RETURNING,
					desired, "ARRIVAL_REACHED").behavior());
			assertEquals(desired.toBehaviorState(), transition(BehaviorState.DANCING,
					desired, "DANCE_EXPIRED").behavior());
		}
	}

	@Test
	void shoulderIsTransientAndAlwaysReleasesToFreeFollow() {
		var mounted = transition(BehaviorState.DANCING, DesiredBehavior.SITTING, "COMMAND_ARM");
		assertEquals("SHOULDER", mounted.behavior().name());
		assertTrue(mounted.behavior().isTransient());
		assertEquals("FREEFOLLOW", mounted.desiredBehavior().name());
		assertTrue(mounted.cancellations().containsAll(Set.of(BehaviorStateMachine.Cancellation.MOVEMENT,
				BehaviorStateMachine.Cancellation.DANCE, BehaviorStateMachine.Cancellation.PENDING_LAZY_ACTION)));
		assertEquals("FREEFOLLOW", transition(mounted.behavior(), mounted.desiredBehavior(), "COMMAND_DEARM").behavior().name());
		assertEquals("FREEFOLLOW", transition(mounted.behavior(), mounted.desiredBehavior(), "DEACTIVATED").behavior().name());
		assertThrows(IllegalArgumentException.class, () -> DesiredBehavior.fromStable(mounted.behavior()));
	}

	@Test
	void viewingIsIndependentOfMovementIncludingShoulderAndDance() {
		for (BehaviorState from : BehaviorState.values()) {
			for (String event : new String[]{"COMMAND_VIEW", "COMMAND_DEVIEW"}) {
				var changed = transition(from, DesiredBehavior.SITTING, event);
				assertEquals(from, changed.behavior());
				assertEquals(DesiredBehavior.SITTING, changed.desiredBehavior());
				assertTrue(changed.cancellations().isEmpty());
			}
		}
	}

	@Test
	void freeFollowWithoutStreamerWandersAndResumesWhenStreamerReturns() {
		var paused = transition(BehaviorState.valueOf("FREEFOLLOW"), DesiredBehavior.valueOf("FREEFOLLOW"), "TARGET_LOST");
		assertEquals("FREE", paused.behavior().name());
		assertEquals("FREEFOLLOW", paused.desiredBehavior().name());
		assertEquals("FREEFOLLOW", transition(paused.behavior(), paused.desiredBehavior(), "TARGET_AVAILABLE").behavior().name());
	}
}
