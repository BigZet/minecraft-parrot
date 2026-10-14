package io.github.viewerparrots.twitch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwitchChatSettingsTest {

	@Test
	void firstLaunchCreatesDisabledSettingsWithoutCreatingASecret(@TempDir Path directory) throws IOException {
		Path configDirectory = directory.resolve("config");

		TwitchChatSettings settings = TwitchChatSettings.loadOrCreate(configDirectory);

		assertAll(
				() -> assertFalse(settings.autoConnect()),
				() -> assertEquals("", settings.channel()),
				() -> assertEquals("", settings.login()),
				() -> assertEquals("", settings.oauthToken()),
				() -> assertTrue(Files.exists(configDirectory.resolve("viewerparrots-twitch.json"))),
				() -> assertFalse(Files.exists(configDirectory.resolve("viewerparrots-twitch-token.txt"))),
				() -> assertEquals(settings, TwitchChatSettings.loadOrCreate(configDirectory)),
				() -> assertThrows(IllegalArgumentException.class, settings::requireConfigured));
	}

	@Test
	void loadsLocalTokenAndNormalizesConnectionNames(@TempDir Path directory) throws IOException {
		Files.writeString(directory.resolve("viewerparrots-twitch.json"), """
				{"autoConnect":true,"channel":" #Streamer_42 ","login":" Parrot_Bot "}
				""");
		Files.writeString(directory.resolve("viewerparrots-twitch-token.txt"), " oauth:local_test_secret\n");

		TwitchChatSettings settings = TwitchChatSettings.loadOrCreate(directory);

		assertAll(
				() -> assertTrue(settings.autoConnect()),
				() -> assertEquals("streamer_42", settings.channel()),
				() -> assertEquals("parrot_bot", settings.login()),
				() -> assertEquals("local_test_secret", settings.oauthToken()),
				() -> assertDoesNotThrow(settings::requireConfigured),
				() -> assertFalse(Files.readString(directory.resolve("viewerparrots-twitch.json"))
						.contains("local_test_secret")));
	}

	@Test
	void channelOnlySettingsCanConnectAsAGuestWithoutAToken(@TempDir Path directory) throws IOException {
		Files.writeString(directory.resolve("viewerparrots-twitch.json"), """
				{"autoConnect":false,"channel":"streamer"}
				""");

		TwitchChatSettings settings = TwitchChatSettings.loadOrCreate(directory);

		assertAll(
				() -> assertEquals("", settings.login()),
				() -> assertEquals("", settings.oauthToken()),
				() -> assertDoesNotThrow(settings::requireConfigured));
	}

	@Test
	void connectionRejectsNamesThatCouldAlterAnIrcCommand() {
		for (String unsafe : new String[]{"streamer other", "streamer\r\nJOIN #other", "##streamer",
				"abcdefghijklmnopqrstuvwxyz", "streamer-name"}) {
			assertAll(
					() -> assertThrows(IllegalArgumentException.class,
							() -> new TwitchChatSettings(false, unsafe, "bot", "secret").requireConfigured()),
					() -> assertThrows(IllegalArgumentException.class,
							() -> new TwitchChatSettings(false, "streamer", unsafe, "secret").requireConfigured()));
		}
	}

	@Test
	void connectionRejectsTokenWhitespaceAndControlsWithoutRevealingTheToken() {
		for (String unsafe : new String[]{"test_secret other", "test_secret\r\nJOIN #other", "test_secret\tother",
				"test_secret\u0000other"}) {
			TwitchChatSettings settings = new TwitchChatSettings(false, "streamer", "bot", unsafe);

			IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
					settings::requireConfigured);

			assertFalse(error.getMessage().contains("test_secret"));
			assertFalse(settings.toString().contains("test_secret"));
		}
	}

	@Test
	void recordStringAlwaysRedactsItsSecret() {
		TwitchChatSettings settings = new TwitchChatSettings(true, "streamer", "bot", "local_test_secret");

		assertFalse(settings.toString().contains("local_test_secret"));
		assertDoesNotThrow(settings::requireConfigured);
	}
}
