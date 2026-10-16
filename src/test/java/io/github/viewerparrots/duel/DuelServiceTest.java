package io.github.viewerparrots.duel;

import io.github.viewerparrots.api.ActionParameters;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.behavior.BehaviorStateMachine;
import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.domain.DesiredBehavior;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DuelServiceTest {
    @Test void opponentNamesLoseTheAtSignAndCase() {
        assertEquals("gosha", DuelService.normalizeOpponent(" @Gosha "));
        assertEquals("twitch:42", DuelService.normalizeOpponent("twitch:42"));
        assertEquals("", DuelService.normalizeOpponent("@"));
        assertEquals("", DuelService.normalizeOpponent(null));
    }

    @Test void opponentParameterRejectsEmptyAndControlCharacters() {
        assertEquals("Gosha", new ActionParameters.Opponent("@Gosha").name());
        assertThrows(IllegalArgumentException.class, () -> new ActionParameters.Opponent("@"));
        assertThrows(IllegalArgumentException.class, () -> new ActionParameters.Opponent("a\u0007b"));
        assertThrows(IllegalArgumentException.class, () -> new ActionParameters.Opponent("x".repeat(65)));
        assertFalse(ActionParameters.None.INSTANCE.supports(ParrotAction.FIGHT));
        assertTrue(ActionParameters.None.INSTANCE.supports(ParrotAction.ACCEPT));
    }

    @Test void duelEventsNeverChangeTheBirdsBehavior() {
        for (var event : new BehaviorStateMachine.Event[]{BehaviorStateMachine.Event.COMMAND_FIGHT,
                BehaviorStateMachine.Event.COMMAND_ACCEPT, BehaviorStateMachine.Event.COMMAND_DECLINE}) {
            for (BehaviorState state : BehaviorState.values()) {
                var result = assertInstanceOf(BehaviorStateMachine.Accepted.class,
                        BehaviorStateMachine.apply(state, DesiredBehavior.FREE, event));
                assertEquals(state, result.behavior());
                assertTrue(result.cancellations().isEmpty());
            }
        }
    }
}
