package io.github.viewerparrots.twitch;

import io.github.viewerparrots.settings.TwitchRole;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Parses only tagged user chat messages; other IRC traffic is ignored. */
public final class TwitchIrcParser {
	private TwitchIrcParser() {
	}

	public static Optional<TwitchChatMessage> parse(String line) {
		return parseLine(line).flatMap(message -> {
			if (!message.command().equals("PRIVMSG") || !message.hasTrailing()
					|| message.parameters().size() != 2) {
				return Optional.empty();
			}
			String channel = message.parameters().getFirst();
			String userId = message.tags().getOrDefault("user-id", "");
			if (!channel.matches("#[A-Za-z0-9_]+") || !userId.matches("[0-9]+")) {
				return Optional.empty();
			}
			String displayName = message.tags().getOrDefault("display-name", "");
			if (displayName.isEmpty()) {
				String prefix = message.prefix();
				int separator = prefix.indexOf('!');
				displayName = separator < 0 ? prefix : prefix.substring(0, separator);
				if (!displayName.matches("[A-Za-z0-9_]+")) {
					return Optional.empty();
				}
			}
			return Optional.of(new TwitchChatMessage(channel.substring(1).toLowerCase(Locale.ROOT),
					userId, displayName, message.parameters().get(1),
					message.tags().getOrDefault("id", ""), roles(message.tags())));
		});
	}

	private static Set<TwitchRole> roles(Map<String, String> tags) {
		Set<TwitchRole> roles = EnumSet.of(TwitchRole.EVERYONE);
		if ("1".equals(tags.get("mod"))) roles.add(TwitchRole.MODERATOR);
		if ("1".equals(tags.get("subscriber"))) roles.add(TwitchRole.SUBSCRIBER);
		for (String badge : tags.getOrDefault("badges", "").split(",")) {
			int separator = badge.indexOf('/');
			if (separator < 1 || !badge.substring(separator + 1).matches("[0-9]+")) continue;
			TwitchRole role = switch (badge.substring(0, separator)) {
				case "broadcaster" -> TwitchRole.BROADCASTER;
				case "moderator" -> TwitchRole.MODERATOR;
				case "vip" -> TwitchRole.VIP;
				case "subscriber", "founder" -> TwitchRole.SUBSCRIBER;
				default -> null;
			};
			if (role != null) roles.add(role);
		}
		return roles;
	}

	/** Shared IRC framing for chat, keepalive and connection status messages. */
	static Optional<Line> parseLine(String line) {
		if (line == null || line.isEmpty() || line.indexOf('\r') >= 0
				|| line.indexOf('\n') >= 0 || line.indexOf('\0') >= 0) {
			return Optional.empty();
		}
		Map<String, String> tags = new HashMap<>();
		int cursor = 0;
		if (line.charAt(cursor) == '@') {
			int end = line.indexOf(' ');
			if (end < 2) {
				return Optional.empty();
			}
			for (String tag : line.substring(1, end).split(";")) {
				int equals = tag.indexOf('=');
				String key = equals < 0 ? tag : tag.substring(0, equals);
				String value = equals < 0 ? "" : unescape(tag.substring(equals + 1));
				tags.put(key, value);
			}
			cursor = skipSpaces(line, end);
		}
		String prefix = "";
		if (cursor < line.length() && line.charAt(cursor) == ':') {
			int end = line.indexOf(' ', cursor);
			if (end <= cursor + 1) {
				return Optional.empty();
			}
			prefix = line.substring(cursor + 1, end);
			cursor = skipSpaces(line, end);
		}
		int commandEnd = line.indexOf(' ', cursor);
		if (commandEnd < 0) {
			commandEnd = line.length();
		}
		String command = line.substring(cursor, commandEnd).toUpperCase(Locale.ROOT);
		if (!command.matches("[A-Z]+|[0-9]{3}")) {
			return Optional.empty();
		}
		List<String> parameters = new ArrayList<>();
		cursor = commandEnd;
		boolean hasTrailing = false;
		while ((cursor = skipSpaces(line, cursor)) < line.length()) {
			if (line.charAt(cursor) == ':') {
				parameters.add(line.substring(cursor + 1));
				hasTrailing = true;
				break;
			}
			int end = line.indexOf(' ', cursor);
			if (end < 0) {
				end = line.length();
			}
			parameters.add(line.substring(cursor, end));
			cursor = end;
		}
		return Optional.of(new Line(prefix, command, List.copyOf(parameters), Map.copyOf(tags), hasTrailing));
	}

	private static int skipSpaces(String line, int cursor) {
		while (cursor < line.length() && line.charAt(cursor) == ' ') {
			cursor++;
		}
		return cursor;
	}

	private static String unescape(String value) {
		StringBuilder decoded = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char next = value.charAt(i);
			if (next != '\\') {
				decoded.append(next);
			} else if (++i < value.length()) {
				decoded.append(switch (value.charAt(i)) {
					case ':' -> ';';
					case 's' -> ' ';
					case 'r' -> '\r';
					case 'n' -> '\n';
					default -> value.charAt(i);
				});
			}
		}
		return decoded.toString();
	}

	record Line(String prefix, String command, List<String> parameters,
			Map<String, String> tags, boolean hasTrailing) {
	}
}
