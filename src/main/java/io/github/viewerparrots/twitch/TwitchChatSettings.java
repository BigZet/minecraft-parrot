package io.github.viewerparrots.twitch;

import io.github.viewerparrots.json.JsonSchemaException;
import io.github.viewerparrots.json.JsonView;
import io.github.viewerparrots.json.StrictJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Local connection settings; an optional OAuth token lives in a separate, ignored file. */
public record TwitchChatSettings(boolean autoConnect, String channel, String login, String oauthToken) {
	private static final String SETTINGS_FILE = "viewerparrots-twitch.json";
	private static final String TOKEN_FILE = "viewerparrots-twitch-token.txt";
	private static final String DEFAULT_SETTINGS = """
			{
			  "autoConnect": false,
			  "channel": "",
			  "login": ""
			}
			""";

	public TwitchChatSettings {
		channel = trimmed(channel).toLowerCase(Locale.ROOT);
		if (channel.startsWith("#")) {
			channel = channel.substring(1);
		}
		login = trimmed(login).toLowerCase(Locale.ROOT);
		oauthToken = trimmed(oauthToken);
		if (oauthToken.startsWith("oauth:")) {
			oauthToken = oauthToken.substring("oauth:".length()).trim();
		}
	}

	/** Creates safe disabled defaults on first launch; connection validation is deferred. */
	public static TwitchChatSettings loadOrCreate(Path configDirectory) throws IOException {
		Objects.requireNonNull(configDirectory, "configDirectory");
		Path settingsFile = configDirectory.resolve(SETTINGS_FILE);
		if (!Files.exists(settingsFile)) {
			Files.createDirectories(configDirectory);
			Files.writeString(settingsFile, DEFAULT_SETTINGS, StandardCharsets.UTF_8);
		}

		JsonView settings;
		boolean autoConnect;
		String channel;
		String login;
		try {
			settings = JsonView.ofRoot(StrictJson.parseObject(Files.readString(settingsFile, StandardCharsets.UTF_8)));
			settings.requireKnownFields("autoConnect", "channel", "login");
			autoConnect = settings.has("autoConnect") && settings.requireBoolean("autoConnect");
			channel = settings.optionalString("channel").orElse("");
			login = settings.optionalString("login").orElse("");
		} catch (JsonSchemaException error) {
			// Parser diagnostics can contain user data; expose only the settings filename.
			throw new IOException("Invalid Twitch settings in " + SETTINGS_FILE
					+ "; expected autoConnect, channel and login. Store the OAuth token in " + TOKEN_FILE + ".");
		}

		Path tokenFile = configDirectory.resolve(TOKEN_FILE);
		String token = Files.exists(tokenFile) ? Files.readString(tokenFile, StandardCharsets.UTF_8) : "";
		return new TwitchChatSettings(autoConnect, channel, login, token);
	}

	/** Validates a guest channel or authenticated credentials before opening a connection. */
	public void requireConfigured() {
		if (!channel.matches("[a-z0-9_]{1,25}")) {
			throw new IllegalArgumentException("Set a valid Twitch channel in " + SETTINGS_FILE + ".");
		}
		if (oauthToken.isEmpty()) {
			return;
		}
		if (!login.matches("[a-z0-9_]{1,25}")) {
			throw new IllegalArgumentException("Set a valid Twitch bot login in " + SETTINGS_FILE + ".");
		}
		if (oauthToken.codePoints().anyMatch(character -> Character.isWhitespace(character)
				|| Character.isSpaceChar(character) || Character.isISOControl(character))) {
			throw new IllegalArgumentException("The Twitch OAuth token must contain no whitespace or control characters.");
		}
	}

	@Override
	public String toString() {
		return "TwitchChatSettings[autoConnect=" + autoConnect + ", channel=" + channel
				+ ", login=" + login + ", oauthToken=[redacted]]";
	}

	private static String trimmed(String value) {
		return value == null ? "" : value.trim();
	}
}
