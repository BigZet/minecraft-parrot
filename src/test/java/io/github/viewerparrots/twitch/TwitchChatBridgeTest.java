package io.github.viewerparrots.twitch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwitchChatBridgeTest {

	@Test
	void dedicatedServerAlwaysReadsChatAsGuest() {
		assertTrue(TwitchChatBridge.guestOnly(true, false));
		assertTrue(TwitchChatBridge.guestOnly(true, true));
	}

	@Test
	void singleplayerKeepsTheAccountSetting() {
		assertFalse(TwitchChatBridge.guestOnly(false, false));
		assertTrue(TwitchChatBridge.guestOnly(false, true));
	}
}
