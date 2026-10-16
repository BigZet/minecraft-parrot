package io.github.viewerparrots.twitch;

import io.github.viewerparrots.settings.TwitchRole;
import java.util.Set;

/** Identity and content supplied by Twitch, before command selection. */
public record TwitchChatMessage(String channel, String userId, String displayName,
		String text, String messageId, Set<TwitchRole> roles) {
	public TwitchChatMessage {
		roles = roles == null || roles.isEmpty() ? Set.of(TwitchRole.EVERYONE) : Set.copyOf(roles);
	}

	public TwitchChatMessage(String channel, String userId, String displayName, String text, String messageId) {
		this(channel, userId, displayName, text, messageId, Set.of(TwitchRole.EVERYONE));
	}
}
