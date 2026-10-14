package io.github.viewerparrots.speech;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.api.event.ParrotEvent;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.domain.LifecycleState;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.network.SpeechAudioPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** World-owned voice assignments and a small background synthesis queue. */
public final class ParrotSpeechService implements AutoCloseable {
    private final ServerContext context;
    private final Path voiceFile;
    private final Path configDirectory;
    private final VoiceAssignments voices = new VoiceAssignments(VoiceAssignments.DEFAULT_VOICES, new Random());
    private final ThreadPoolExecutor synthesis = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), task -> {
                Thread thread = new Thread(task, "viewerparrots-speech");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private record PendingSpeech(UUID commandId, CompletableFuture<CommandResult> completion) { }
    private final Map<ViewerId, PendingSpeech> pending = new HashMap<>();
    private OpenRouterSpeechClient client;
    private CompletableFuture<Void> voiceWrite = CompletableFuture.completedFuture(null);
    private boolean voicesReady;
    private volatile boolean closed;

    private static final class Loaded {
        final Map<UUID, String> assignments;
        final String token;
        Loaded(Map<UUID, String> assignments, String token) {
            this.assignments = assignments;
            this.token = token;
        }
    }

    public ParrotSpeechService(ServerContext context, Path configDirectory) {
        this.context = context;
        this.configDirectory = configDirectory;
        this.voiceFile = context.server().getSavePath(WorldSavePath.ROOT).resolve("data")
                .resolve(ViewerParrotsMod.MOD_ID).resolve("voices.json");
        context.events().subscribe(event -> {
            if (voicesReady && (event.type() == ParrotEvent.Type.PARROT_CREATED
                    || event.type() == ParrotEvent.Type.PARROT_REMOVED)) synchronizeVoices();
            if (event.type() == ParrotEvent.Type.PARROT_REMOVED || event.type() == ParrotEvent.Type.PARROT_DIED
                    || event.type() == ParrotEvent.Type.PARROT_UNLOADED) {
                PendingSpeech job = pending.remove(event.viewerId());
                if (job != null) job.completion().complete(CommandResult.failed(job.commandId(), ErrorCode.ENTITY_UNRESOLVED, null));
            }
        });
    }

    public void onRegistryReady() {
        context.writer().submit(() -> new Loaded(VoiceAssignments.read(voiceFile), OpenRouterToken.load(configDirectory)))
                .whenComplete((loaded, error) -> {
                    if (closed || !context.acceptingCommands()) return;
                    if (error != null) {
                        ViewerParrotsMod.LOGGER.warn("Speech: unable to load voice assignments or local OpenRouter key.");
                        return;
                    }
                    try {
                        voices.restore(loaded.assignments);
                        if (!loaded.token.isBlank()) client = new OpenRouterSpeechClient(loaded.token);
                        voicesReady = true;
                        synchronizeVoices();
                    } catch (RuntimeException invalidData) {
                        ViewerParrotsMod.LOGGER.warn("Speech: check the saved voice assignments.");
                    }
                });
    }

    private void synchronizeVoices() {
        Map<UUID, String> before = voices.snapshot();
        var records = context.registry().allSorted();
        voices.reconcile(records.stream().map(ViewerParrotRecord::incarnationId).collect(Collectors.toSet()));
        records.forEach(record -> voices.assign(record.incarnationId()));
        Map<UUID, String> snapshot = voices.snapshot();
        if (!snapshot.equals(before)) {
            voiceWrite = context.writer().submit(() -> {
                VoiceAssignments.write(voiceFile, snapshot);
                return (Void) null;
            });
            voiceWrite.whenComplete((ignored, error) -> {
                if (error != null && !closed) ViewerParrotsMod.LOGGER.warn("Speech: voice assignments could not be saved.");
            });
        }
    }

    public Optional<String> voiceFor(ViewerId viewer) {
        return context.registry().find(viewer).map(record -> voices.snapshot().get(record.incarnationId()));
    }

    public boolean configured() { return voicesReady && client != null && !closed; }
    public int pendingCount() { return pending.size(); }

    public CompletableFuture<CommandResult> say(UUID commandId, ViewerId viewer, String rawText) {
        if (!context.server().isOnThread()) throw new IllegalStateException("speech submission must use the server thread");
        if (!configured()) return reject(commandId, ErrorCode.SPEECH_NOT_CONFIGURED);
        var maybeRecord = context.registry().find(viewer);
        if (maybeRecord.isEmpty()) return reject(commandId, ErrorCode.VIEWER_NOT_FOUND);
        if (!context.allowsMutations()) return reject(commandId, ErrorCode.STORAGE_UNAVAILABLE);
        if (context.lifecycle(viewer) != LifecycleState.LOADED) return reject(commandId, ErrorCode.ENTITY_UNRESOLVED);
        if (pending.containsKey(viewer)) return reject(commandId, ErrorCode.OPERATION_PENDING);
        if (pending.size() >= 17) return reject(commandId, ErrorCode.SERVER_BUSY);
        var entity = context.loadedIndex().find(viewer).or(() -> context.shoulderService().find(viewer)).orElse(null);
        if (entity == null || !entity.isAlive()) return reject(commandId, ErrorCode.PARROT_ENTITY_MISSING);
        var listener = context.server().getPlayerManager().getPlayerList().stream()
                .filter(player -> player.getWorld() == entity.getWorld() && ServerPlayNetworking.canSend(player, SpeechAudioPayload.ID))
                .findFirst();
        if (listener.isEmpty()) return reject(commandId, ErrorCode.TARGET_UNAVAILABLE);
        long remaining = context.cooldowns().remainingTicks(viewer, ParrotAction.SAY);
        if (remaining > 0) return CompletableFuture.completedFuture(CommandResult.retryLater(commandId, ErrorCode.COMMAND_ON_COOLDOWN, remaining));
        var decision = context.rateLimiter().check(ParrotAction.SAY);
        if (!decision.allowed()) return CompletableFuture.completedFuture(CommandResult.retryLater(commandId, ErrorCode.GLOBAL_RATE_LIMITED, decision.retryAfterTicks()));

        String text = SpeechText.firstThirtyWords(rawText);
        ViewerParrotRecord identity = maybeRecord.get();
        String voice = voices.assign(identity.incarnationId());
        UUID entityId = entity.getUuid();
        UUID listenerId = listener.get().getUuid();
        CompletableFuture<CommandResult> completed = new CompletableFuture<>();
        pending.put(viewer, new PendingSpeech(commandId, completed));
        context.rateLimiter().tryAcquire(ParrotAction.SAY);
        context.cooldowns().consume(viewer, ParrotAction.SAY, ParrotAction.SAY_COOLDOWN_TICKS);
        publish(identity, commandId, ParrotEvent.Type.COMMAND_ACCEPTED, null);
        voiceWrite.whenComplete((ignored, saveError) -> {
            if (closed || completed.isDone()) return;
            if (saveError != null) {
                finish(identity, commandId, completed, CommandResult.failed(commandId, ErrorCode.STORAGE_UNAVAILABLE, null));
                return;
            }
            try {
                synthesis.execute(() -> {
                    if (closed || completed.isDone()) return;
                    byte[] pcm = null;
                    try { pcm = client.synthesize(voice, text); }
                    catch (Exception failure) {
                        if (!closed) ViewerParrotsMod.LOGGER.warn("Speech: OpenRouter synthesis failed ({})", failure.getClass().getSimpleName());
                    }
                    if (closed) return;
                    byte[] audio = pcm;
                    context.server().execute(() -> {
                        if (closed || completed.isDone()) return;
                        if (audio == null) {
                            finish(identity, commandId, completed, CommandResult.failed(commandId, ErrorCode.SPEECH_FAILED, null));
                            return;
                        }
                        var current = context.registry().find(viewer);
                        var bird = context.loadedIndex().find(viewer).or(() -> context.shoulderService().find(viewer)).orElse(null);
                        var player = context.server().getPlayerManager().getPlayer(listenerId);
                        if (!context.acceptingCommands() || current.isEmpty()
                                || !current.get().incarnationId().equals(identity.incarnationId())
                                || current.get().generation() != identity.generation()
                                || bird == null || !bird.isAlive() || !bird.getUuid().equals(entityId)
                                || player == null || player.getWorld() != bird.getWorld()) {
                            finish(identity, commandId, completed, CommandResult.failed(commandId, ErrorCode.ENTITY_UNRESOLVED, null));
                            return;
                        }
                        SpeechAudioPayload.send(player, commandId, entityId, audio);
                        finish(identity, commandId, completed, CommandResult.succeeded(commandId, "viewerparrots.say.ok", CommandResult.details("viewerId", viewer.value())));
                    });
                });
            } catch (RejectedExecutionException full) {
                finish(identity, commandId, completed, CommandResult.rejected(commandId, ErrorCode.SERVER_BUSY));
            }
        });
        return completed;
    }

    private static CompletableFuture<CommandResult> reject(UUID commandId, ErrorCode code) {
        return CompletableFuture.completedFuture(CommandResult.rejected(commandId, code));
    }

    private void finish(ViewerParrotRecord identity, UUID commandId, CompletableFuture<CommandResult> completed, CommandResult result) {
        pending.computeIfPresent(identity.viewerId(), (viewer, job) -> job.completion() == completed ? null : job);
        publish(identity, commandId, ParrotEvent.Type.COMMAND_COMPLETED, result.code() == null ? null : result.code().name());
        completed.complete(result);
    }

    private void publish(ViewerParrotRecord identity, UUID commandId, ParrotEvent.Type type, String reason) {
        context.events().publish(ParrotEvent.of(type, identity.viewerId(), identity.incarnationId(), identity.generation(),
                context.clock().now(), context.clock().currentTick(), commandId, null, "SAY", reason));
    }

    @Override public void close() {
        closed = true;
        synthesis.shutdownNow();
        if (client != null) client.close();
        for (PendingSpeech job : Map.copyOf(pending).values()) {
            // API shutdown also clears its completion cache; no old-world callback survives.
            job.completion().complete(CommandResult.failed(job.commandId(), ErrorCode.INVALID_STATE, null));
        }
        pending.clear();
    }
}
