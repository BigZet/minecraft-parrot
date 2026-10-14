package io.github.viewerparrots.twitch;

/** Identity and content supplied by Twitch, before command selection. */
public record TwitchChatMessage(String channel, String userId, String displayName,
		String text, String messageId) {
}
