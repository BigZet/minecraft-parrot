package io.github.viewerparrots.twitch;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Background, receive-only Twitch IRC connection. */
public final class TwitchIrcClient implements AutoCloseable {
	private static final String HOST = "irc.chat.twitch.tv";
	private static final int PORT = 6697;
	private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
	private static final int READ_TIMEOUT_MILLIS = 1_000;
	private static final long JOIN_TIMEOUT_NANOS = 30_000_000_000L;
	private static final long RETRY_INTERVAL_MILLIS = 5_000;
	private static final int MAX_LINE_LENGTH = 16_384;

	public enum Status {
		CONNECTING, CONNECTED, RECONNECTING, AUTH_FAILED, STOPPED
	}

	@FunctionalInterface
	interface ConnectionFactory {
		Socket connect() throws IOException;
	}

	private final String channel;
	private final String login;
	private final String oauthToken;
	private final Consumer<TwitchChatMessage> onMessage;
	private final Consumer<Status> onStatus;
	private final ConnectionFactory connectionFactory;
	private final AtomicBoolean closed = new AtomicBoolean();
	private volatile Thread worker;
	private Status lastStatus;

	public TwitchIrcClient(String channel, String login, String oauthToken,
			Consumer<TwitchChatMessage> onMessage, Consumer<Status> onStatus) {
		this(channel, login, oauthToken, onMessage, onStatus, TwitchIrcClient::connectTls);
	}

	TwitchIrcClient(String channel, String login, String oauthToken,
			Consumer<TwitchChatMessage> onMessage, Consumer<Status> onStatus,
			ConnectionFactory connectionFactory) {
		this.channel = normalizeName(channel, true);
		if (oauthToken == null || oauthToken.isBlank()) {
			this.login = "justinfan" + ThreadLocalRandom.current().nextInt(10_000, Integer.MAX_VALUE);
			this.oauthToken = "guest";
		} else {
			this.login = normalizeName(login, false);
			this.oauthToken = normalizeToken(oauthToken);
		}
		this.onMessage = Objects.requireNonNull(onMessage, "onMessage");
		this.onStatus = Objects.requireNonNull(onStatus, "onStatus");
		this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
	}

	public synchronized void start() {
		if (closed.get() || worker != null) {
			return;
		}
		Thread thread = new Thread(this::run, "viewer-parrots-twitch");
		thread.setDaemon(true);
		worker = thread;
		thread.start();
	}

	@Override
	public void close() {
		closed.set(true);
		Thread thread = worker;
		if (thread != null) {
			thread.interrupt();
		}
		// The worker owns socket closure. Closing TLS or joining a thread here
		// would put network waiting on the Minecraft game thread.
	}

	private void run() {
		boolean firstAttempt = true;
		boolean authFailed = false;
		try {
			while (!closed.get()) {
				publish(firstAttempt ? Status.CONNECTING : Status.RECONNECTING);
				try {
					authFailed = readSession();
				} catch (IOException | RuntimeException ignored) {
					// Status contains no raw IRC, credentials, or exception message.
				}
				if (authFailed) {
					publish(Status.AUTH_FAILED);
					return;
				}
				if (closed.get()) {
					return;
				}
				publish(Status.RECONNECTING);
				try {
					Thread.sleep(RETRY_INTERVAL_MILLIS);
				} catch (InterruptedException ignored) {
					if (closed.get()) {
						return;
					}
				}
				firstAttempt = false;
			}
		} finally {
			if (!authFailed || closed.get()) {
				publish(Status.STOPPED);
			}
		}
	}

	/** @return true only when Twitch explicitly rejects the credentials. */
	private boolean readSession() throws IOException {
		Socket socket = connectionFactory.connect();
		try {
			if (closed.get()) {
				return false;
			}
			socket.setSoTimeout(READ_TIMEOUT_MILLIS);
			BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
			send(output, "CAP REQ :twitch.tv/tags twitch.tv/commands");
			send(output, "PASS " + oauthToken);
			send(output, "NICK " + login);

			boolean authenticated = false;
			boolean joined = false;
			boolean connected = false;
			long joinDeadline = System.nanoTime() + JOIN_TIMEOUT_NANOS;
			StringBuilder incoming = new StringBuilder();
			while (!closed.get()) {
				if (!connected && System.nanoTime() - joinDeadline >= 0) {
					throw new IOException("Twitch channel join timed out");
				}
				int next;
				try {
					next = input.read();
				} catch (SocketTimeoutException ignored) {
					continue;
				}
				if (next < 0) {
					return false;
				}
				if (next != '\n') {
					if (incoming.length() >= MAX_LINE_LENGTH) {
						throw new IOException("Twitch IRC line exceeded the input limit");
					}
					incoming.append((char) next);
					continue;
				}
				if (!incoming.isEmpty() && incoming.charAt(incoming.length() - 1) == '\r') {
					incoming.setLength(incoming.length() - 1);
				}
				String rawLine = incoming.toString();
				incoming.setLength(0);
				TwitchIrcParser.Line message = TwitchIrcParser.parseLine(rawLine).orElse(null);
				if (message == null || closed.get()) {
					continue;
				}
				List<String> parameters = message.parameters();
				if (message.command().equals("PING") && !parameters.isEmpty()) {
					send(output, "PONG :" + parameters.getLast());
				} else if (authenticationFailed(message)) {
					return true;
				} else if (message.command().equals("RECONNECT")) {
					return false;
				} else if (!authenticated && (message.command().equals("GLOBALUSERSTATE")
						|| (message.command().equals("001") && !parameters.isEmpty()
						&& parameters.getFirst().equalsIgnoreCase(login)))) {
					authenticated = true;
					send(output, "JOIN #" + channel);
				}
				if (confirmsChannelJoin(message)) {
					joined = true;
				}
				if (authenticated && joined && !connected) {
					connected = true;
					publish(Status.CONNECTED);
				}
				if (connected && !closed.get()) {
					TwitchIrcParser.parse(rawLine).filter(chat -> chat.channel().equals(channel)).ifPresent(chat -> {
						if (!closed.get()) {
							try {
								onMessage.accept(chat);
							} catch (RuntimeException ignored) {
								// A rejected command must not interrupt the IRC connection.
							}
						}
					});
				}
			}
			return false;
		} finally {
			try {
				socket.close();
			} catch (IOException ignored) {
				// A close failure must not replace an authentication rejection.
			}
		}
	}

	private boolean confirmsChannelJoin(TwitchIrcParser.Line message) {
		List<String> parameters = message.parameters();
		if (message.command().equals("ROOMSTATE") || message.command().equals("USERSTATE")) {
			return !parameters.isEmpty() && parameters.getFirst().equalsIgnoreCase("#" + channel);
		}
		if (message.command().equals("366")) {
			return parameters.size() >= 2 && parameters.getFirst().equalsIgnoreCase(login)
					&& parameters.get(1).equalsIgnoreCase("#" + channel);
		}
		return message.command().equals("JOIN") && message.prefix().split("!", 2)[0].equalsIgnoreCase(login)
				&& !parameters.isEmpty() && parameters.getFirst().equalsIgnoreCase("#" + channel);
	}

	private static boolean authenticationFailed(TwitchIrcParser.Line message) {
		if (!message.command().equals("NOTICE") || message.parameters().isEmpty()) {
			return false;
		}
		String text = message.parameters().getLast();
		return text.equalsIgnoreCase("Login authentication failed")
				|| text.equalsIgnoreCase("Improperly formatted auth");
	}

	private void send(BufferedWriter output, String command) throws IOException {
		if (!closed.get()) {
			output.write(command);
			output.write("\r\n");
			output.flush();
		}
	}

	private void publish(Status status) {
		if ((closed.get() && status != Status.STOPPED) || status == lastStatus) {
			return;
		}
		lastStatus = status;
		try {
			onStatus.accept(status);
		} catch (RuntimeException ignored) {
			// No consumer exception or message is logged from the IRC worker.
		}
	}

	private static String normalizeName(String value, boolean channel) {
		if (value == null) {
			throw new IllegalArgumentException("Twitch channel and login must be set");
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		if (channel && normalized.startsWith("#")) {
			normalized = normalized.substring(1);
		}
		if (!normalized.matches("[a-z0-9_]+")) {
			throw new IllegalArgumentException("Twitch channel or login has invalid format");
		}
		return normalized;
	}

	private static String normalizeToken(String value) {
		if (value == null) {
			throw new IllegalArgumentException("Twitch token must be set");
		}
		String token = value.trim();
		if (token.startsWith("oauth:")) {
			token = token.substring(6);
		}
		if (!token.matches("[A-Za-z0-9_-]+")) {
			throw new IllegalArgumentException("Twitch token has invalid format");
		}
		return "oauth:" + token;
	}

	private static Socket connectTls() throws IOException {
		Socket tcp = new Socket();
		try {
			tcp.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MILLIS);
			SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
					.createSocket(tcp, HOST, PORT, true);
			SSLParameters parameters = tls.getSSLParameters();
			parameters.setEndpointIdentificationAlgorithm("HTTPS");
			tls.setSSLParameters(parameters);
			tls.setSoTimeout(CONNECT_TIMEOUT_MILLIS);
			tls.startHandshake();
			return tls;
		} catch (IOException | RuntimeException failure) {
			try {
				tcp.close();
			} catch (IOException ignored) {
			}
			throw failure;
		}
	}
}
