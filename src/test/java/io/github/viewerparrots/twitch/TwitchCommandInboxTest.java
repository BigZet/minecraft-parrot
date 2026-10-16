package io.github.viewerparrots.twitch;

import io.github.viewerparrots.api.ActionParameters;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.settings.TwitchRole;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TwitchCommandInboxTest {
    private static TwitchChatMessage message(String user, String text, String id) {
        return new TwitchChatMessage("streamer", user, "Зритель", text, id);
    }

    @Test void derivesOwnershipFromSenderAndMapsOnlyViewerCommands() {
        var create = TwitchCommandInbox.command(message("123", "!parrot", "one"), "streamer").orElseThrow();
        assertEquals("twitch:123", create.viewerId().value());
        assertEquals(ParrotAction.CREATE, create.action());
        assertEquals("Зритель", ((ActionParameters.Name) create.parameters()).name().value());
        String[] commands = {"!come", "!follow", "!sit", "!dance", "!scream", "!love", "!exp"};
        ParrotAction[] actions = {ParrotAction.COME, ParrotAction.FOLLOW, ParrotAction.SIT, ParrotAction.DANCE, ParrotAction.SCREAM, ParrotAction.LOVE, ParrotAction.EXP};
        for (int i = 0; i < commands.length; i++) {
            var request = TwitchCommandInbox.command(message("456", commands[i], "action" + i), "streamer").orElseThrow();
            assertEquals("twitch:456", request.viewerId().value());
            assertEquals(actions[i], request.action());
            assertSame(ActionParameters.None.INSTANCE, request.parameters());
        }
    }

    @Test void ignoresForeignTargetsAdministrativeTextAndMissingIdentity() {
        for (String text : new String[]{"!sit twitch:999", "!spin twitch:999", "!hat twitch:999", "!dehat twitch:999", "!hat royal_crown", "!love twitch:999", "!exp twitch:999", "!parrot OtherName", "!remove", "/kill @e", "hello"})
            assertTrue(TwitchCommandInbox.command(message("123", text, "bad"), "streamer").isEmpty());
        assertTrue(TwitchCommandInbox.command(message("", "!parrot", "empty"), "streamer").isEmpty());
        assertTrue(TwitchCommandInbox.command(message("123", "!parrot", "foreign"), "other_channel").isEmpty());
    }

    @Test void boundsIngressDeduplicatesAndDropsWorkOnDisconnect() {
        var inbox = new TwitchCommandInbox("streamer", 2);
        assertFalse(inbox.offer(message("1", "!parrot", "before")));
        inbox.setAccepting(true);
        assertTrue(inbox.offer(message("1", "!parrot", "first")));
        assertFalse(inbox.offer(message("1", "!parrot", "first")));
        assertTrue(inbox.offer(message("2", "!sit", "second")));
        assertFalse(inbox.offer(message("3", "!follow", "overflow")));
        assertEquals(2, inbox.pending());
        assertEquals("twitch:1", inbox.poll().orElseThrow().viewerId().value());
        inbox.setAccepting(false);
        assertEquals(0, inbox.pending());
        assertTrue(inbox.poll().isEmpty());
        assertFalse(inbox.offer(message("3", "!come", "after")));
        inbox.setAccepting(true);
        assertTrue(inbox.poll().isEmpty());
    }

    @Test void queuesRolesWithTheirOwnRequestAndKeepsLegacyPollOnTheSameQueue() {
        var inbox = new TwitchCommandInbox("streamer", 2);
        inbox.setAccepting(true);
        var moderator = TwitchIrcParser.parse("@user-id=42;mod=1;id=moderator-message "
                + ":moderator!moderator@host PRIVMSG #streamer :!sit").orElseThrow();
        assertTrue(inbox.offer(moderator));
        assertTrue(inbox.offer(message("99", "!follow", "ordinary-message")));

        var entry = inbox.pollEntry().orElseThrow();

        assertEquals("twitch:42", entry.request().viewerId().value());
        assertEquals(ParrotAction.SIT, entry.request().action());
        assertEquals(Set.of(TwitchRole.EVERYONE, TwitchRole.MODERATOR), entry.roles());
        assertThrows(UnsupportedOperationException.class, () -> entry.roles().add(TwitchRole.BROADCASTER));
        assertEquals("twitch:99", inbox.poll().orElseThrow().viewerId().value());
        assertTrue(inbox.pollEntry().isEmpty());
    }

    @Test void speechUsesSenderIdentityAndPreservesBodyCase() {
        var request = TwitchCommandInbox.command(message("123", "!SaY twitch:999 Hello МИР", "speech"), "streamer").orElseThrow();
        assertEquals("twitch:123", request.viewerId().value());
        assertEquals("SAY", request.action().name());
        assertEquals("twitch:999 Hello МИР", ((ActionParameters.Speech) request.parameters()).text());
    }

    @Test void speechAcceptsUnicodeWhitespaceAfterCommand() {
        for (String separator : new String[]{" ", "\t", "\u2003", "\u00a0", "\u202f", "\u0085"}) {
            var request = TwitchCommandInbox.command(message("123", "!say" + separator + "Привет", "unicode"), "streamer").orElseThrow();
            assertEquals("SAY", request.action().name());
            assertEquals("Привет", ((ActionParameters.Speech) request.parameters()).text());
        }
    }

    @Test void ignoresEmptySpeechAndCommandPrefixLookalikes() {
        for (String text : new String[]{"!say", "!SAY   ", "!say\u2003\u00a0", "!saying Hello", "!sayHello"})
            assertTrue(TwitchCommandInbox.command(message("123", text, "empty-speech"), "streamer").isEmpty());
    }

    @Test void spinIsCaseInsensitiveAndAlwaysUsesItsSender() {
        var request = TwitchCommandInbox.command(message("456", "!SpIn", "spin"), "streamer").orElseThrow();
        assertEquals("SPIN", request.action().name());
        assertEquals("twitch:456", request.viewerId().value());
        assertSame(ActionParameters.None.INSTANCE, request.parameters());
    }

    @Test void hatCommandsTakeNoArgumentsAndAlwaysUseTheirSender() {
        for (String[] command : new String[][]{{"!HaT", "HAT"}, {"!DeHaT", "DEHAT"}}) {
            var request = TwitchCommandInbox.command(message("789", command[0], command[1]), "streamer").orElseThrow();
            assertEquals(command[1], request.action().name());
            assertEquals("twitch:789", request.viewerId().value());
            assertSame(ActionParameters.None.INSTANCE, request.parameters());
        }
    }

    @Test void freeViewAndShoulderCommandsCannotSelectAnotherViewer() {
        for (String name : new String[]{"free", "freefollow", "view", "deview", "arm", "dearm"}) {
            var request = TwitchCommandInbox.command(message("789", "!" + name.toUpperCase(java.util.Locale.ROOT), name), "streamer").orElseThrow();
            assertEquals(ParrotAction.valueOf(name.toUpperCase(java.util.Locale.ROOT)), request.action());
            assertEquals("twitch:789", request.viewerId().value());
            assertSame(ActionParameters.None.INSTANCE, request.parameters());
            assertTrue(TwitchCommandInbox.command(message("789", "!" + name + " twitch:999", "foreign-" + name), "streamer").isEmpty());
        }
    }

    @Test void rouletteUsesItsSenderAndRejectsArgumentsOrLookalikeCommands() {
        var request = TwitchCommandInbox.command(message("456", "  !RoUlEtTe  ", "roulette"), "streamer").orElseThrow();
        assertEquals(ParrotAction.ROULETTE, request.action());
        assertEquals("twitch:456", request.viewerId().value());
        assertSame(ActionParameters.None.INSTANCE, request.parameters());
        for (String text : new String[]{"!roulette twitch:999", "!roulette diamond", "!roulettes", "!rouletteNow"})
            assertTrue(TwitchCommandInbox.command(message("456", text, "bad-roulette"), "streamer").isEmpty());
    }
}
