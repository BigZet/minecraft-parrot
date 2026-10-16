package io.github.viewerparrots.twitch;

import io.github.viewerparrots.settings.TwitchRole;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwitchIrcParserTest {
	@Test
	void readsStableUserIdAndKeepsChatTextSeparateFromIrcSyntax() {
		String line = "@badges=;display-name=Зритель;id=chat-123;user-id=987654321 "
				+ ":viewer!viewer@viewer.tmi.twitch.tv PRIVMSG #Streamer :!parrot  :hello";

		assertEquals(new TwitchChatMessage("streamer", "987654321", "Зритель",
				"!parrot  :hello", "chat-123"), TwitchIrcParser.parse(line).orElseThrow());
	}

	@Test
	void unescapesIrcv3TagsWithoutChangingMessageText() {
		String line = "@display-name=One\\sTwo\\:Three\\\\Four\\r\\n\\q;id=msg\\:42;user-id=42 "
				+ ":viewer!viewer@viewer.tmi.twitch.tv PRIVMSG #streamer :!dance\\s";

		assertEquals(new TwitchChatMessage("streamer", "42", "One Two;Three\\Four\r\nq",
				"!dance\\s", "msg;42"), TwitchIrcParser.parse(line).orElseThrow());
	}

	@Test
	void fallsBackToLoginWhenDisplayNameIsEmptyOrAbsent() {
		for (String tags : new String[] {"@user-id=42", "@display-name=;user-id=42"}) {
			assertEquals(new TwitchChatMessage("streamer", "42", "viewer", "!sit", ""),
					TwitchIrcParser.parse(tags + " :viewer!viewer@viewer.tmi.twitch.tv "
							+ "PRIVMSG #streamer :!sit").orElseThrow());
		}
	}

	@Test
	void collectsEveryRecognizedBadgeAndFallsBackToOrdinaryViewer() {
		var privileged = TwitchIrcParser.parse("@user-id=42;badges=broadcaster/1,moderator/1,vip/1,subscriber/24 "
				+ ":viewer!viewer@host PRIVMSG #streamer :!parrot").orElseThrow();

		assertEquals(Set.of(TwitchRole.EVERYONE, TwitchRole.BROADCASTER, TwitchRole.MODERATOR,
				TwitchRole.VIP, TwitchRole.SUBSCRIBER), privileged.roles());
		for (String badges : new String[]{"", "partner/1,glhf-pledge/1", "notmoderator/1,vipish/1",
				"broadcaster,moderator/,subscriber/no-version,vip/1/extra"}) {
			var ordinary = TwitchIrcParser.parse("@user-id=42;badges=" + badges
					+ " :viewer!viewer@host PRIVMSG #streamer :!parrot broadcaster/1 mod=1 subscriber=1").orElseThrow();
			assertEquals(Set.of(TwitchRole.EVERYONE), ordinary.roles());
		}
	}

	@Test
	void recognizesLegacyModeratorAndSubscriberFlagsOnlyWhenExactlyOne() {
		var flagged = TwitchIrcParser.parse("@user-id=42;mod=1;subscriber=1 "
				+ ":viewer!viewer@host PRIVMSG #streamer :!sit").orElseThrow();
		assertEquals(Set.of(TwitchRole.EVERYONE, TwitchRole.MODERATOR, TwitchRole.SUBSCRIBER), flagged.roles());
		for (String flags : new String[]{"mod=0;subscriber=0", "mod=true;subscriber=yes", "mod=10;subscriber=-1"}) {
			var ordinary = TwitchIrcParser.parse("@user-id=42;" + flags
					+ " :viewer!viewer@host PRIVMSG #streamer :!sit").orElseThrow();
			assertEquals(Set.of(TwitchRole.EVERYONE), ordinary.roles());
		}
	}

	@Test
	void founderBadgeCountsAsSubscriptionWithoutTheSubscriberFlag() {
		var founder = TwitchIrcParser.parse("@user-id=42;badges=founder/0;subscriber=0 "
				+ ":viewer!viewer@host PRIVMSG #streamer :!sit").orElseThrow();

		assertEquals(Set.of(TwitchRole.EVERYONE, TwitchRole.SUBSCRIBER), founder.roles());
	}

	@Test
	void chatMessageCopiesRoleMetadataSoCallersCannotChangeItAfterIngress() {
		Set<TwitchRole> supplied = EnumSet.of(TwitchRole.EVERYONE, TwitchRole.VIP);
		var message = new TwitchChatMessage("streamer", "42", "Viewer", "!sit", "roles", supplied);
		supplied.add(TwitchRole.BROADCASTER);

		assertEquals(Set.of(TwitchRole.EVERYONE, TwitchRole.VIP), message.roles());
		assertThrows(UnsupportedOperationException.class, () -> message.roles().add(TwitchRole.MODERATOR));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"@display-name=viewer :viewer!viewer@host PRIVMSG #streamer :!parrot",
			"@display-name=viewer;user-id= :viewer!viewer@host PRIVMSG #streamer :!parrot",
			"@display-name=viewer;user-id=viewer :viewer!viewer@host PRIVMSG #streamer :!parrot",
			"@display-name=viewer;user-id=-42 :viewer!viewer@host PRIVMSG #streamer :!parrot",
			"@display-name=viewer;user-id=42 :viewer!viewer@host PRIVMSG streamer :!parrot",
			"@user-id=42 PRIVMSG #streamer :!parrot",
			"@display-name=viewer;user-id=42 :viewer!viewer@host USERNOTICE #streamer :!parrot",
			"PING :tmi.twitch.tv",
			"@display-name=viewer;user-id=42 :viewer!viewer@host PRIVMSG #streamer !parrot",
			"@display-name=viewer;user-id=42 :viewer!viewer@host PRIVMSG #streamer :!parrot\r\nPING :host"
	})
	void rejectsMessagesThatCannotIdentifyOneChatSender(String line) {
		assertTrue(TwitchIrcParser.parse(line).isEmpty());
	}
}
