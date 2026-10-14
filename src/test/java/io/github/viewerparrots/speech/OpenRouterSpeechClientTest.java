package io.github.viewerparrots.speech;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenRouterSpeechClientTest {
	@Test
	void requestsV4TurboWithTheSelectedVoiceAndRawTwentyFourKhzPcm() throws Exception {
		FakeClient transport = new FakeClient(200, new byte[]{1, 2, 3, 4});
		try (OpenRouterSpeechClient client = new OpenRouterSpeechClient("local-placeholder", transport)) {
			assertArrayEquals(new byte[]{1, 2, 3, 4}, client.synthesize("selected-voice", "Hello, \"world\"!"));
			HttpRequest request = transport.request;
			assertEquals(URI.create("https://openrouter.ai/api/v1/audio/speech"), request.uri());
			assertEquals("POST", request.method());
			assertEquals("Bearer local-placeholder", request.headers().firstValue("Authorization").orElseThrow());
			assertEquals(Duration.ofSeconds(60), request.timeout().orElseThrow());
			JsonObject body = JsonParser.parseString(bodyText(request)).getAsJsonObject();
			assertEquals("elevenlabs/eleven-v4-turbo", body.get("model").getAsString());
			assertEquals("selected-voice", body.get("voice").getAsString());
			assertEquals("Hello, \"world\"!", body.get("input").getAsString());
			assertEquals("pcm", body.get("response_format").getAsString());
			assertEquals("pcm_24000", body.getAsJsonObject("provider").getAsJsonObject("options")
					.getAsJsonObject("elevenlabs").get("output_format").getAsString());
		}
		assertTrue(transport.shutdown);
		assertTrue(transport.stream.closed);
	}

	@Test
	void rejectsProviderErrorsWithoutExposingTheirBodyOrCredentials() {
		FakeClient transport = new FakeClient(401, "private-provider-message local-placeholder".getBytes(StandardCharsets.UTF_8));
		try (OpenRouterSpeechClient client = new OpenRouterSpeechClient("local-placeholder", transport)) {
			OpenRouterSpeechClient.SpeechException error = assertThrows(OpenRouterSpeechClient.SpeechException.class,
					() -> client.synthesize("voice", "Hello"));
			assertTrue(error.getMessage().contains("401"));
			assertFalse(error.getMessage().contains("private-provider-message"));
			assertFalse(error.getMessage().contains("local-placeholder"));
			assertNull(error.getCause());
			assertEquals(1, transport.calls);
			assertEquals(0, transport.stream.bytesRead);
			assertTrue(transport.stream.closed);
		}
	}

	@Test
	void rejectsEmptyOddAndOversizedPcmWithoutReadingBeyondTheCap() {
		for (int length : new int[]{0, 3, OpenRouterSpeechClient.MAX_AUDIO_BYTES + 10}) {
			FakeClient transport = new FakeClient(200, new byte[length]);
			try (OpenRouterSpeechClient client = new OpenRouterSpeechClient("local-placeholder", transport)) {
				assertThrows(OpenRouterSpeechClient.SpeechException.class, () -> client.synthesize("voice", "Hello"));
				assertTrue(transport.stream.bytesRead <= OpenRouterSpeechClient.MAX_AUDIO_BYTES + 8192);
				assertTrue(transport.stream.closed);
			}
		}
	}

	@Test
	void sanitizesTransportErrorsAndKeepsTheInterruptedFlag() {
		FakeClient transport = new FakeClient(200, new byte[]{1, 2});
		transport.failure = new IOException("local-placeholder private-provider-message");
		try (OpenRouterSpeechClient client = new OpenRouterSpeechClient("local-placeholder", transport)) {
			OpenRouterSpeechClient.SpeechException error = assertThrows(OpenRouterSpeechClient.SpeechException.class,
					() -> client.synthesize("voice", "Hello"));
			assertFalse(error.toString().contains("local-placeholder"));
			assertNull(error.getCause());
			transport.interruptWait = true;
			try {
				assertThrows(OpenRouterSpeechClient.SpeechException.class, () -> client.synthesize("voice", "Hello"));
				assertTrue(Thread.currentThread().isInterrupted());
			} finally {
				Thread.interrupted();
			}
		}
	}

	@Test
	void cancelsAResponseThatDoesNotFinishWithinSixtySeconds() {
		FakeClient transport = new FakeClient(200, new byte[]{1, 2});
		transport.stalled = true;
		try (OpenRouterSpeechClient client = new OpenRouterSpeechClient("local-placeholder", transport)) {
			OpenRouterSpeechClient.SpeechException error = assertThrows(OpenRouterSpeechClient.SpeechException.class,
					() -> client.synthesize("voice", "Hello"));
			assertTrue(error.getMessage().contains("timed out"));
			assertEquals(60, transport.waitSeconds);
			assertTrue(transport.pending.isCancelled());
		}
	}

	private static String bodyText(HttpRequest request) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		CompletableFuture<Void> complete = new CompletableFuture<>();
		request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
			public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
			public void onNext(ByteBuffer bytes) { byte[] data = new byte[bytes.remaining()]; bytes.get(data); output.writeBytes(data); }
			public void onError(Throwable error) { complete.completeExceptionally(error); }
			public void onComplete() { complete.complete(null); }
		});
		complete.join();
		return output.toString(StandardCharsets.UTF_8);
	}

	private static final class TrackingStream extends InputStream {
		private final ByteArrayInputStream delegate;
		private int bytesRead;
		private boolean closed;
		TrackingStream(byte[] data) { delegate = new ByteArrayInputStream(data); }
		@Override public int read() {
			int value = delegate.read();
			if (value >= 0) { bytesRead++; }
			return value;
		}
		@Override public synchronized int read(byte[] data, int offset, int length) {
			int read = delegate.read(data, offset, length);
			if (read > 0) { bytesRead += read; }
			return read;
		}
		@Override public void close() { closed = true; }
	}

	private static final class FakeClient extends HttpClient {
		private final int status;
		private final TrackingStream stream;
		private HttpRequest request;
		private Exception failure;
		private int calls;
		private boolean shutdown;
		private boolean stalled;
		private boolean interruptWait;
		private long waitSeconds;
		private CompletableFuture<?> pending;
		FakeClient(int status, byte[] body) { this.status = status; stream = new TrackingStream(body); }
		public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
			throw new UnsupportedOperationException();
		}

		private <T> HttpResponse<T> response(HttpRequest request, T data) {
			return new HttpResponse<>() {
				public int statusCode() { return status; }
				public HttpRequest request() { return request; }
				public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
				public HttpHeaders headers() { return HttpHeaders.of(Map.of("Content-Type", List.of("audio/pcm")), (k, v) -> true); }
				public T body() { return data; }
				public Optional<SSLSession> sslSession() { return Optional.empty(); }
				public URI uri() { return request.uri(); }
				public Version version() { return Version.HTTP_2; }
			};
		}
		@Override public void shutdownNow() { shutdown = true; }
		@Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
		@Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
		@Override public Redirect followRedirects() { return Redirect.NEVER; }
		@Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
		@Override public SSLContext sslContext() { throw new UnsupportedOperationException(); }
		@Override public SSLParameters sslParameters() { return new SSLParameters(); }
		@Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
		@Override public Version version() { return Version.HTTP_2; }
		@Override public Optional<Executor> executor() { return Optional.empty(); }
		@Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
			this.request = request;
			calls++;
			if (stalled || interruptWait) {
				CompletableFuture<HttpResponse<T>> future = new CompletableFuture<>() {
					@Override public HttpResponse<T> get(long timeout, TimeUnit unit) throws TimeoutException, InterruptedException {
						waitSeconds = unit.toSeconds(timeout);
						if (interruptWait) { throw new InterruptedException("local-placeholder"); }
						throw new TimeoutException();
					}
				};
				pending = future;
				return future;
			}
			if (failure != null) { return CompletableFuture.failedFuture(failure); }
			HttpResponse.BodySubscriber<T> subscriber = handler.apply(new HttpResponse.ResponseInfo() {
				public int statusCode() { return status; }
				public HttpHeaders headers() { return response(request, null).headers(); }
				public Version version() { return Version.HTTP_2; }
			});
			subscriber.onSubscribe(new Flow.Subscription() {
				public void request(long count) { }
				public void cancel() { stream.close(); }
			});
			byte[] frame = new byte[8192];
			int length;
			while (!stream.closed && (length = stream.read(frame, 0, frame.length)) >= 0) {
				subscriber.onNext(List.of(ByteBuffer.wrap(java.util.Arrays.copyOf(frame, length))));
			}
			if (!stream.closed) { subscriber.onComplete(); stream.close(); }
			return subscriber.getBody().toCompletableFuture().thenApply(data -> response(request, data));
		}
		@Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) { throw new UnsupportedOperationException(); }
	}
}
