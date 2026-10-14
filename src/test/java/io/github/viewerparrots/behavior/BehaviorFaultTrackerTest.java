package io.github.viewerparrots.behavior;

import io.github.viewerparrots.domain.ViewerId;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorFaultTrackerTest {
	@Test
	void faultSurvivesRuntimeReplacementButNeverLeaksToNewIncarnation() {
		BehaviorFaultTracker faults = new BehaviorFaultTracker();
		ViewerId id = ViewerId.of("viewer1");
		UUID first = new UUID(0, 1), second = new UUID(0, 2);
		faults.disable(id, first);
		assertTrue(faults.isDisabled(id, first));
		assertFalse(faults.isDisabled(id, second));
		faults.disable(id, second);
		faults.clear();
		assertFalse(faults.isDisabled(id, second));
	}
	@Test
	void logicalRemovalDropsFaultWhileChunkUnloadCanRetainIt() {
		BehaviorFaultTracker faults = new BehaviorFaultTracker();
		ViewerId id = ViewerId.of("viewer1"); UUID incarnation = new UUID(0, 1);
		faults.disable(id, incarnation);
		faults.forget(id);
		assertFalse(faults.isDisabled(id, incarnation));
	}
}
