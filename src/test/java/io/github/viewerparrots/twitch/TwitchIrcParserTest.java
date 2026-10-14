package io.github.viewerparrots.twitch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
