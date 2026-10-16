package io.github.viewerparrots.speech;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Blocking synthesis belongs on the speech worker, never the Minecraft thread. */
public final class OpenRouterSpeechClient implements AutoCloseable {
	public static final int MAX_AUDIO_BYTES = 2 * 1024 * 1024;
	private static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/audio/speech");
	private static final Gson JSON = new Gson();
	private final String apiKey;
	private final HttpClient transport;

	public OpenRouterSpeechClient(String apiKey) {
		this(apiKey, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
				.followRedirects(HttpClient.Redirect.NEVER).build());
	}

	OpenRouterSpeechClient(String apiKey, HttpClient transport) {
		if (apiKey == null || apiKey.isBlank() || apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0) {
			throw new IllegalArgumentException("OpenRouter API key is missing or invalid.");
		}
		this.apiKey = apiKey.strip();
		this.transport = Objects.requireNonNull(transport, "transport");
	}

	public byte[] synthesize(String voiceId, String text) throws SpeechException {
        return synthesize(voiceId, text, "elevenlabs/eleven-v4-turbo", 60);
    }
    public byte[] synthesize(String voiceId, String text, String model, int timeoutSeconds) throws SpeechException {
		if (voiceId == null || voiceId.isBlank() || text == null || text.isBlank()) {
			throw new SpeechException("Speech requires a voice and nonempty text.");
		}
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.addProperty("input", text);
		body.addProperty("voice", voiceId);
		body.addProperty("response_format", "pcm");
		JsonObject elevenlabs = new JsonObject();
		elevenlabs.addProperty("output_format", "pcm_24000");
		JsonObject options = new JsonObject();
		options.add("elevenlabs", elevenlabs);
		JsonObject provider = new JsonObject();
		provider.add("options", options);
		body.add("provider", provider);
		CompletableFuture<HttpResponse<byte[]>> pending = null;
		try {
			HttpRequest request = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(timeoutSeconds))
					.header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
					.header("Accept", "audio/pcm")
					.POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body), StandardCharsets.UTF_8)).build();
			pending = transport.sendAsync(request, response -> new PcmBodySubscriber(response.statusCode()));
			// Waiting for the bounded subscriber covers the body too, including a stalled stream.
			return pending.get(timeoutSeconds, TimeUnit.SECONDS).body();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new SpeechException("Speech request was interrupted.");
		} catch (TimeoutException timeout) {
			throw new SpeechException("OpenRouter speech request timed out.");
		} catch (ExecutionException transportError) {
			if (transportError.getCause() instanceof SpeechException speechError) {
				throw speechError;
			}
			throw new SpeechException("OpenRouter speech request failed.");
		} catch (RuntimeException transportError) {
			// Provider bodies, text and credential-bearing transport exceptions never escape.
			throw new SpeechException("OpenRouter speech request failed.");
		} finally {
			if (pending != null && !pending.isDone()) {
				pending.cancel(true);
			}
		}
	}

	private static final class PcmBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
		private final CompletableFuture<byte[]> body = new CompletableFuture<>();
		private final ByteArrayOutputStream output = new ByteArrayOutputStream();
		private final int status;
		private Flow.Subscription subscription;

		private PcmBodySubscriber(int status) { this.status = status; }
		@Override public CompletionStage<byte[]> getBody() { return body; }

		@Override public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			if (status != 200) {
				fail("OpenRouter speech failed (HTTP " + status + ").");
			} else {
				subscription.request(1);
			}
		}

		@Override public void onNext(List<ByteBuffer> buffers) {
			if (body.isDone()) { return; }
			for (ByteBuffer buffer : buffers) {
				if (buffer.remaining() > MAX_AUDIO_BYTES - output.size()) {
					fail("Speech audio exceeds the size limit.");
					return;
				}
				byte[] data = new byte[buffer.remaining()];
				buffer.get(data);
				output.writeBytes(data);
			}
			subscription.request(1);
		}

		@Override public void onError(Throwable error) {
			body.completeExceptionally(new SpeechException("OpenRouter speech request failed."));
		}

		@Override public void onComplete() {
			if (output.size() == 0 || (output.size() & 1) != 0) {
				body.completeExceptionally(new SpeechException("OpenRouter returned invalid PCM audio."));
			} else {
				body.complete(output.toByteArray());
			}
		}

		private void fail(String message) {
			body.completeExceptionally(new SpeechException(message));
			subscription.cancel();
		}
	}

	/** Java 21 cancels pending HTTP work without waiting for it to finish. */
	@Override
	public void close() {
		transport.shutdownNow();
	}

	public static final class SpeechException extends Exception {
		public SpeechException(String message) { super(message); }
	}
}
