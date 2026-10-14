package io.github.viewerparrots.service;

import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.domain.LifecycleState;
import io.github.viewerparrots.domain.PhysicalStatus;
import io.github.viewerparrots.domain.ParrotTrait;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandPolicyTest {
    @Test void disabledBehaviorSkipsLazyButStillAllowsExplicitMovement() {
        assertFalse(CommandPolicy.shouldDelay(false, ParrotTrait.LAZY, ParrotAction.COME));
        assertFalse(CommandPolicy.shouldDelay(false, ParrotTrait.LAZY, ParrotAction.FOLLOW));
        assertTrue(CommandPolicy.shouldDelay(true, ParrotTrait.LAZY, ParrotAction.COME));
        assertFalse(CommandPolicy.shouldDelay(true, ParrotTrait.LAZY, ParrotAction.SIT));
    }

    @Test void lifecyclePreservesDeadMissingAndUnresolvedInsteadOfAssumingUnloaded() {
        assertEquals(LifecycleState.DEAD, CommandPolicy.lifecycle(PhysicalStatus.DEAD, false, false));
        assertEquals(LifecycleState.MISSING, CommandPolicy.lifecycle(PhysicalStatus.MISSING, false, false));
        assertEquals(LifecycleState.UNRESOLVED, CommandPolicy.lifecycle(PhysicalStatus.PRESENT, false, false));
        assertEquals(LifecycleState.UNLOADED, CommandPolicy.lifecycle(PhysicalStatus.PRESENT, false, true));
        assertEquals(LifecycleState.LOADED, CommandPolicy.lifecycle(PhysicalStatus.PRESENT, true, false));
    }

    @Test void movementLeavesShoulderWhileCosmeticsAndViewingKeepIt() {
        for (ParrotAction action : new ParrotAction[]{ParrotAction.COME, ParrotAction.FOLLOW, ParrotAction.SIT,
                ParrotAction.DANCE, ParrotAction.SPIN, ParrotAction.FREE, ParrotAction.FREEFOLLOW, ParrotAction.DEARM})
            assertTrue(CommandPolicy.releasesShoulder(action), action.name());
        for (ParrotAction action : new ParrotAction[]{ParrotAction.HAT, ParrotAction.DEHAT, ParrotAction.VIEW,
                ParrotAction.DEVIEW, ParrotAction.SCREAM, ParrotAction.LOVE, ParrotAction.EXP}) {
            assertFalse(CommandPolicy.releasesShoulder(action), action.name());
            assertTrue(CommandPolicy.preservesBehavior(action), action.name());
        }
    }

    @Test void pairedCommandsShareCooldownAndFreeIsImmediateForLazy() {
        assertEquals(CommandPolicy.cooldownKey(ParrotAction.VIEW), CommandPolicy.cooldownKey(ParrotAction.DEVIEW));
        assertEquals(CommandPolicy.cooldownKey(ParrotAction.ARM), CommandPolicy.cooldownKey(ParrotAction.DEARM));
        assertFalse(CommandPolicy.shouldDelay(true, ParrotTrait.LAZY, ParrotAction.FREE));
        assertFalse(CommandPolicy.shouldDelay(true, ParrotTrait.LAZY, ParrotAction.FREEFOLLOW));
    }
}
