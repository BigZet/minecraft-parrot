package io.github.viewerparrots.api;

import io.github.viewerparrots.domain.DisplayName;
import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.domain.ViewerId;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CommandRequestTest {
    @Test void parametersAreTypedAndValidatedAgainstTheirAction() {
        assertTrue(new ActionParameters.Name(DisplayName.of("Имя")).supports(ParrotAction.CREATE));
        assertTrue(new ActionParameters.Name(DisplayName.of("Имя")).supports(ParrotAction.RENAME));
        assertFalse(new ActionParameters.Name(DisplayName.of("Имя")).supports(ParrotAction.TRAIT));
        assertTrue(new ActionParameters.Trait(ParrotTrait.HERO).supports(ParrotAction.TRAIT));
        assertFalse(ActionParameters.None.INSTANCE.supports(ParrotAction.CREATE));
        assertTrue(ActionParameters.None.INSTANCE.supports(ParrotAction.COME));
    }
    @Test void viewerScopedActionsRequireAViewerWhileGlobalActionsDoNot() {
        assertThrows(IllegalArgumentException.class, () -> new CommandRequest(UUID.randomUUID(), null,
                ParrotAction.SIT, ActionParameters.None.INSTANCE));
        assertDoesNotThrow(() -> new CommandRequest(UUID.randomUUID(), null, ParrotAction.RELOAD, ActionParameters.None.INSTANCE));
        assertEquals(ViewerId.of("viewer"), new CommandRequest(UUID.randomUUID(), ViewerId.of("viewer"),
                ParrotAction.SIT, ActionParameters.None.INSTANCE).viewerId());
    }

    @Test void speechParametersNormalizeLimitAndOnlySupportSay() {
        String thirty = "1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30";
        var speech = new ActionParameters.Speech("\u00a0" + thirty + "\t31 32");
        assertEquals(thirty, speech.text());
        assertTrue(speech.supports(ParrotAction.SAY));
        assertFalse(speech.supports(ParrotAction.SCREAM));
        assertFalse(ActionParameters.None.INSTANCE.supports(ParrotAction.SAY));
        assertThrows(IllegalArgumentException.class, () -> new ActionParameters.Speech("\u00a0\t"));
    }

    @Test void speechCannotBeSubmittedWithoutItsViewer() {
        var speech = new ActionParameters.Speech("Hello");
        assertThrows(IllegalArgumentException.class, () -> new CommandRequest(UUID.randomUUID(), null,
                ParrotAction.SAY, speech));
        assertEquals(ViewerId.of("twitch:123"), new CommandRequest(UUID.randomUUID(), ViewerId.of("twitch:123"),
                ParrotAction.SAY, speech).viewerId());
    }

    @Test void spinAcceptsNoParametersAndRequiresItsViewer() {
        var action = ParrotAction.valueOf("SPIN");
        assertTrue(ActionParameters.None.INSTANCE.supports(action));
        assertThrows(IllegalArgumentException.class, () -> new CommandRequest(UUID.randomUUID(), null,
                action, ActionParameters.None.INSTANCE));
        assertEquals(ViewerId.of("twitch:456"), new CommandRequest(UUID.randomUUID(), ViewerId.of("twitch:456"),
                action, ActionParameters.None.INSTANCE).viewerId());
    }

    @Test void hatActionsAcceptNoParametersAndRequireTheirViewer() {
        for (String name : new String[]{"HAT", "DEHAT"}) {
            var action = ParrotAction.valueOf(name);
            assertTrue(ActionParameters.None.INSTANCE.supports(action));
            assertThrows(IllegalArgumentException.class, () -> new CommandRequest(UUID.randomUUID(), null,
                    action, ActionParameters.None.INSTANCE));
            assertEquals(ViewerId.of("twitch:789"), new CommandRequest(UUID.randomUUID(), ViewerId.of("twitch:789"),
                    action, ActionParameters.None.INSTANCE).viewerId());
        }
    }

    @Test void freeViewAndShoulderActionsRequireOwnViewerAndNoArguments() {
        for (ParrotAction action : new ParrotAction[]{ParrotAction.FREE, ParrotAction.FREEFOLLOW,
                ParrotAction.VIEW, ParrotAction.DEVIEW, ParrotAction.ARM, ParrotAction.DEARM}) {
            assertTrue(ActionParameters.None.INSTANCE.supports(action));
            assertThrows(IllegalArgumentException.class, () -> new CommandRequest(UUID.randomUUID(), null,
                    action, ActionParameters.None.INSTANCE));
        }
    }
}
