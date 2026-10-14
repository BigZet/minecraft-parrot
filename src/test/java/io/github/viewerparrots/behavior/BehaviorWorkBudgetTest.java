package io.github.viewerparrots.behavior;

import io.github.viewerparrots.domain.ViewerId;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorWorkBudgetTest {
	@Test
	void sameViewerCannotCalculateAnotherPathBeforeTwentyTicks() {
		BehaviorWorkBudget budget = new BehaviorWorkBudget();
		assertTrue(budget.tryPath(ViewerId.of("alice"), 100));
		for (long tick = 101; tick < 120; tick++) {
			assertFalse(budget.tryPath(ViewerId.of("alice"), tick), "tick " + tick);
		}
		assertTrue(budget.tryPath(ViewerId.of("alice"), 120));
	}

	@Test
	void throttledViewerDoesNotSpendAnotherViewersGlobalSlot() {
		BehaviorWorkBudget budget = new BehaviorWorkBudget();
		assertTrue(budget.tryPath(ViewerId.of("alice"), 100));
		assertFalse(budget.tryPath(ViewerId.of("alice"), 101));
		assertEquals(0, budget.pathsUsed());
		assertTrue(budget.tryPath(ViewerId.of("bob"), 101));
		assertTrue(budget.tryPath(ViewerId.of("carol"), 101));
		assertFalse(budget.tryPath(ViewerId.of("dave"), 101));
		assertEquals(2, budget.pathsUsed());
	}

	@Test
	void globalBudgetRejectionDoesNotStartViewerCooldown() {
		BehaviorWorkBudget budget = new BehaviorWorkBudget();
		assertTrue(budget.tryPath(ViewerId.of("alice"), 100));
		assertTrue(budget.tryPath(ViewerId.of("bob"), 100));
		assertFalse(budget.tryPath(ViewerId.of("carol"), 100));
		assertTrue(budget.tryPath(ViewerId.of("carol"), 101));
	}

	@Test
	void runtimeResetPreservesViewerPathDeadline() {
		BehaviorWorkBudget budget = new BehaviorWorkBudget();
		ParrotRuntime runtime = new ParrotRuntime(ViewerId.of("alice"));
		assertTrue(budget.tryPath(runtime.viewerId(), 100));
		runtime.markRepathed(100, 20);
		runtime.resetTransientState();
		assertTrue(runtime.canRepath(101));
		assertFalse(budget.tryPath(runtime.viewerId(), 101));
		assertTrue(budget.tryPath(runtime.viewerId(), 120));
	}

	@Test
	void replacingRuntimePreservesViewerPathDeadline() {
		BehaviorWorkBudget budget = new BehaviorWorkBudget();
		ParrotRuntime original = new ParrotRuntime(ViewerId.of("alice"));
		assertTrue(budget.tryPath(original.viewerId(), 100));
		ParrotRuntime replacement = new ParrotRuntime(ViewerId.of("alice"));
		assertFalse(budget.tryPath(replacement.viewerId(), 101));
		assertTrue(budget.tryPath(replacement.viewerId(), 120));
	}

	@Test
	void viewerDeadlinesRetainAtMostTwentyTicksOfAcceptedWork() {
		BehaviorWorkBudget budget = new BehaviorWorkBudget();
		for (long tick = 0; tick < 200; tick++) {
			assertTrue(budget.tryPath(ViewerId.of("first:" + tick), tick));
			assertTrue(budget.tryPath(ViewerId.of("second:" + tick), tick));
			assertFalse(budget.tryPath(ViewerId.of("rejected:" + tick), tick));
			assertEquals((int) Math.min(40, (tick + 1) * 2), budget.trackedPathViewers(), "tick " + tick);
		}
		assertTrue(budget.tryDanger(219));
		assertEquals(0, budget.trackedPathViewers());
	}

	@Test
	void deadlinePruningAlsoRunsOnTeleportTicks() {
		BehaviorWorkBudget budget = new BehaviorWorkBudget();
		assertTrue(budget.tryPath(ViewerId.of("alice"), 100));
		assertTrue(budget.tryTeleport(119));
		assertEquals(1, budget.trackedPathViewers());
		assertTrue(budget.tryTeleport(120));
		assertEquals(0, budget.trackedPathViewers());
	}

	@Test
	void legacyPathAdmissionSharesTheGlobalViewerBudget() {
		BehaviorWorkBudget budget = new BehaviorWorkBudget();
		assertTrue(budget.tryPath(100));
		assertTrue(budget.tryPath(ViewerId.of("alice"), 100));
		assertFalse(budget.tryPath(ViewerId.of("bob"), 100));
		assertTrue(budget.tryPath(ViewerId.of("bob"), 101));
	}

	@Test
	void shutdownClearResetsWorkAndViewerDeadlines() {
		BehaviorWorkBudget budget = new BehaviorWorkBudget();
		assertTrue(budget.tryPath(ViewerId.of("alice"), 100));
		budget.clear();
		assertEquals(0, budget.pathsUsed());
		assertEquals(0, budget.trackedPathViewers());
		assertTrue(budget.tryPath(ViewerId.of("alice"), 100));
	}

	@Test
	void pathsDangerAndTeleportHaveIndependentTickBudgets() {
		BehaviorWorkBudget budget = new BehaviorWorkBudget();
		assertTrue(budget.tryPath(1)); assertTrue(budget.tryPath(1)); assertFalse(budget.tryPath(1));
		for (int i = 0; i < 5; i++) assertTrue(budget.tryDanger(1));
		assertFalse(budget.tryDanger(1));
		assertTrue(budget.tryTeleport(1)); assertTrue(budget.tryTeleport(1)); assertFalse(budget.tryTeleport(1));
		assertTrue(budget.tryPath(2)); assertTrue(budget.tryDanger(2)); assertTrue(budget.tryTeleport(2));
	}

	@Test
	void roundRobinServesEveryViewerAndDropsRemovedWork() {
		BehaviorWorkBudget budget = new BehaviorWorkBudget();
		List<Integer> ids = List.of(1, 2, 3, 4, 5, 6);
		List<Integer> served = new ArrayList<>();
		for (int tick = 0; tick < 6; tick++) {
			for (Integer id : budget.roundRobin(ids)) {
				if (budget.tryPath(tick)) served.add(id);
			}
		}
		assertTrue(served.containsAll(ids));
		assertEquals(List.of(2, 4), budget.roundRobin(List.of(2, 4)).stream().sorted().toList());
		assertTrue(budget.roundRobin(List.of()).isEmpty());
	}
}
