package io.github.viewerparrots.twitch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class TwitchIrcClientTest {
	@Test
	void blankCredentialsJoinAsGuestAndRetainTheChatSenderId() throws Exception {
		LinkedBlockingQueue<TwitchIrcClient.Status> statuses = new LinkedBlockingQueue<>();
		LinkedBlockingQueue<TwitchChatMessage> messages = new LinkedBlockingQueue<>();
		try (ServerSocket server = localServer();
			 TwitchIrcClient client = assertDoesNotThrow(() -> new TwitchIrcClient("numnerd", "", "",
					 messages::offer, statuses::offer,
					 () -> new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())))) {
			server.setSoTimeout(2_000);
			client.start();
			try (Socket peer = server.accept()) {
				peer.setSoTimeout(2_000);
				BufferedReader input = reader(peer);
				BufferedWriter output = writer(peer);
				assertEquals(TwitchIrcClient.Status.CONNECTING, statuses.poll(2, TimeUnit.SECONDS));
				assertEquals("CAP REQ :twitch.tv/tags twitch.tv/commands", input.readLine());
				assertEquals("PASS guest", input.readLine());
				String nicknameCommand = input.readLine();
				assertTrue(nicknameCommand.matches("NICK justinfan[0-9]+"));
				String guestLogin = nicknameCommand.substring(5);

				send(output, ":tmi.twitch.tv 001 " + guestLogin + " :Welcome, GLHF!");
				assertEquals("JOIN #numnerd", input.readLine());
				send(output, "@room-id=123 :tmi.twitch.tv ROOMSTATE #numnerd");
				assertEquals(TwitchIrcClient.Status.CONNECTED, statuses.poll(2, TimeUnit.SECONDS));
				send(output, "@display-name=Viewer;user-id=42;id=guest-message "
						+ ":viewer!viewer@host PRIVMSG #numnerd :!parrot");
				assertEquals(new TwitchChatMessage("numnerd", "42", "Viewer", "!parrot", "guest-message"),
						messages.poll(2, TimeUnit.SECONDS));
			}
		}
	}

	@Test
	void authenticatesJoinsAndReceivesOnlyTheSelectedChannelOnWorker() throws Exception {
		LinkedBlockingQueue<TwitchIrcClient.Status> statuses = new LinkedBlockingQueue<>();
		LinkedBlockingQueue<TwitchChatMessage> messages = new LinkedBlockingQueue<>();
		try (ServerSocket server = localServer();
			 TwitchIrcClient client = localClient(server, messages, statuses)) {
			server.setSoTimeout(2_000);
			client.start();
			try (Socket peer = server.accept()) {
				peer.setSoTimeout(2_000);
				BufferedReader input = reader(peer);
				BufferedWriter output = writer(peer);
				assertEquals(TwitchIrcClient.Status.CONNECTING, statuses.poll(2, TimeUnit.SECONDS));
				assertEquals("CAP REQ :twitch.tv/tags twitch.tv/commands", input.readLine());
				assertEquals("PASS oauth:local-test-placeholder", input.readLine());
				assertEquals("NICK testbot", input.readLine());

				send(output, "PING :keepalive-before-auth");
				assertEquals("PONG :keepalive-before-auth", input.readLine());
				assertNull(statuses.poll());

				send(output, ":tmi.twitch.tv 001 testbot :Welcome, GLHF!");
				assertEquals("JOIN #streamer", input.readLine());
				assertNull(statuses.poll());
				send(output, "@room-id=123 :tmi.twitch.tv ROOMSTATE #streamer");
				assertEquals(TwitchIrcClient.Status.CONNECTED, statuses.poll(2, TimeUnit.SECONDS));

				send(output, "@display-name=Viewer;user-id=42;id=wrong-channel "
						+ ":viewer!viewer@host PRIVMSG #other :!parrot");
				send(output, "@display-name=Viewer;user-id=42;id=correct-channel "
						+ ":viewer!viewer@host PRIVMSG #streamer :!parrot");
				assertEquals(new TwitchChatMessage("streamer", "42", "Viewer", "!parrot", "correct-channel"),
						messages.poll(2, TimeUnit.SECONDS));
				assertNull(messages.poll());

				assertTimeout(Duration.ofMillis(100), client::close);
				assertEquals(TwitchIrcClient.Status.STOPPED, statuses.poll(2, TimeUnit.SECONDS));
			}
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"Login authentication failed", "Improperly formatted auth"})
	void stopsInsteadOfRetryingRejectedCredentials(String notice) throws Exception {
		LinkedBlockingQueue<TwitchIrcClient.Status> statuses = new LinkedBlockingQueue<>();
		try (ServerSocket server = localServer();
			 TwitchIrcClient client = localClient(server, new LinkedBlockingQueue<>(), statuses)) {
			server.setSoTimeout(2_000);
			client.start();
			try (Socket peer = server.accept()) {
				peer.setSoTimeout(2_000);
				BufferedReader input = reader(peer);
				for (int i = 0; i < 3; i++) {
					input.readLine();
				}
				assertEquals(TwitchIrcClient.Status.CONNECTING, statuses.poll(2, TimeUnit.SECONDS));
				send(writer(peer), ":tmi.twitch.tv NOTICE * :" + notice);
				assertEquals(TwitchIrcClient.Status.AUTH_FAILED, statuses.poll(2, TimeUnit.SECONDS));
				assertEquals(-1, input.read());
				assertFalse(statuses.contains(TwitchIrcClient.Status.RECONNECTING));
			}
		}
	}

	private static ServerSocket localServer() throws Exception {
		return new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
	}

	private static TwitchIrcClient localClient(ServerSocket server,
			LinkedBlockingQueue<TwitchChatMessage> messages,
			LinkedBlockingQueue<TwitchIrcClient.Status> statuses) {
		return new TwitchIrcClient("#Streamer", "TestBot", "local-test-placeholder",
				messages::offer, statuses::offer,
				() -> new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort()));
	}

	private static BufferedReader reader(Socket peer) throws Exception {
		return new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
	}

	private static BufferedWriter writer(Socket peer) throws Exception {
		return new BufferedWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8));
	}

	private static void send(BufferedWriter output, String line) throws Exception {
		output.write(line + "\r\n");
		output.flush();
	}
}
