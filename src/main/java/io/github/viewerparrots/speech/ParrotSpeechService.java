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
    private ThreadPoolExecutor synthesis = createWorker(16);
    private int queueCapacity = 16;
    private final java.util.List<OpenRouterSpeechClient> retiredClients = new java.util.ArrayList<>();
    private final java.util.Map<UUID, java.util.concurrent.Future<?>> running = new HashMap<>();
    private static ThreadPoolExecutor createWorker(int capacity) { return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(capacity), task -> {
                Thread thread = new Thread(task, "viewerparrots-speech");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy()); }
    private record PendingSpeech(UUID commandId, CompletableFuture<CommandResult> completion) { }
    private final Map<ViewerId, PendingSpeech> pending = new HashMap<>();
    private OpenRouterSpeechClient client;
    private CompletableFuture<Void> voiceWrite = CompletableFuture.completedFuture(null);
    private boolean voicesReady;
    private volatile boolean closed;

    /** One daemon thread for key loading: a stuck PowerShell never holds the writer, and a closed world ignores its late result. */
    private static final java.util.concurrent.ExecutorService KEY_LOADER = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "viewerparrots-key");
        thread.setDaemon(true);
        return thread;
    });

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
        // voices.json belongs to the storage writer, as before.
        context.writer().submit(() -> VoiceAssignments.read(voiceFile)).whenComplete((assignments, error) -> {
            if (closed || !context.acceptingCommands()) return;
            if (error != null) {
                ViewerParrotsMod.LOGGER.warn("Speech: unable to load voice assignments.");
                return;
            }
            try {
                voices.restore(assignments);
                voicesReady = true;
                synchronizeVoices();
            } catch (RuntimeException invalidData) {
                ViewerParrotsMod.LOGGER.warn("Speech: check the saved voice assignments.");
            }
        });
        // The key may spawn PowerShell for the DPAPI fallback and wait seconds for it: never on the storage writer
        // or the game thread. Until it arrives, configured() stays false and !say answers SPEECH_NOT_CONFIGURED.
        CompletableFuture.supplyAsync(() -> {
            try {
                return io.github.viewerparrots.control.ControlPanelService.openRouterKey();
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, KEY_LOADER).whenComplete((token, error) -> context.server().execute(() -> {
            if (closed || !context.acceptingCommands()) return;
            if (error != null) {
                ViewerParrotsMod.LOGGER.warn("Speech: unable to load the local OpenRouter key.");
                return;
            }
            if (!token.isBlank() && client == null) client = new OpenRouterSpeechClient(token);
        }));
    }

    public void initializeSettings(io.github.viewerparrots.settings.ModSettings.Speech speech) {
        synthesis.shutdownNow(); queueCapacity = speech.queueCapacity(); synthesis = createWorker(queueCapacity);
        applySettings(speech);
    }
    public void applySettings(io.github.viewerparrots.settings.ModSettings.Speech speech) {
        var enabled = speech.voices().stream().filter(io.github.viewerparrots.settings.ModSettings.Voice::enabled)
                .map(io.github.viewerparrots.settings.ModSettings.Voice::id).toList();
        if (!enabled.isEmpty()) voices.updatePool(enabled);
        if (voicesReady) synchronizeVoices();
    }
    public void replaceKey(String key) {
        var previous = client; client = new OpenRouterSpeechClient(key);
        if (previous != null) retiredClients.add(previous);
    }
    public CompletableFuture<Void> setVoice(ViewerId viewer, String voice) {
        if (!voicesReady) return CompletableFuture.failedFuture(new IllegalStateException("Голоса ещё загружаются"));
        if (io.github.viewerparrots.control.ControlPanelService.settings().active().speech().voices().stream().noneMatch(v -> v.id().equals(voice)))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Неизвестный голос"));
        var record = context.registry().find(viewer).orElseThrow(() -> new IllegalArgumentException("Птица не найдена"));
        voices.setVoice(record.incarnationId(), voice);
        var snapshot = voices.snapshot();
        voiceWrite = context.writer().submit(() -> { VoiceAssignments.write(voiceFile, snapshot); return (Void) null; });
        return voiceWrite;
    }
    public void stopPending() {
        var jobs = Map.copyOf(pending); pending.clear();
        for (var task : running.values()) task.cancel(true);
        running.clear(); synthesis.getQueue().clear();
        for (var job : jobs.values()) job.completion().complete(CommandResult.failed(job.commandId(), ErrorCode.INVALID_STATE, null));
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
        return say(commandId, io.github.viewerparrots.api.ActorContext.console(4), viewer, io.github.viewerparrots.api.ActionParameters.Speech.parse(rawText));
    }
    public CompletableFuture<CommandResult> say(UUID commandId, io.github.viewerparrots.api.ActorContext actor, ViewerId viewer,
                                                io.github.viewerparrots.api.ActionParameters.Speech speech) {
        if (!context.server().isOnThread()) throw new IllegalStateException("speech submission must use the server thread");
        var access = io.github.viewerparrots.settings.CommandAccess.evaluate(actor, ParrotAction.SAY, viewer);
        if (!access.allowed()) return reject(commandId, io.github.viewerparrots.settings.CommandAccess.error(access));
        var speechSettings = io.github.viewerparrots.control.ControlPanelService.settings().active().speech();
        if (!speechSettings.enabled() || !configured()) return reject(commandId, ErrorCode.SPEECH_NOT_CONFIGURED);
        var acceptedClient = client;
        var maybeRecord = context.registry().find(viewer);
        if (maybeRecord.isEmpty()) return reject(commandId, ErrorCode.VIEWER_NOT_FOUND);
        if (!context.allowsMutations()) return reject(commandId, ErrorCode.STORAGE_UNAVAILABLE);
        if (context.lifecycle(viewer) != LifecycleState.LOADED) return reject(commandId, ErrorCode.ENTITY_UNRESOLVED);
        if (pending.containsKey(viewer)) return reject(commandId, ErrorCode.OPERATION_PENDING);
        if (pending.size() >= queueCapacity + 1) return reject(commandId, ErrorCode.SERVER_BUSY);
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

        ViewerParrotRecord identity = maybeRecord.get();
        // Явный [стиль] перекрывает тег характера; текст зрителя теряет свои скобки и не несёт тегов.
        String tag = speech.style() == null ? SpeechStyle.traitTag(speechSettings.traitTags(), identity.trait())
                : SpeechStyle.tagFor(speechSettings.styleTags(), speech.style());
        if (tag == null) return reject(commandId, ErrorCode.SPEECH_UNKNOWN_STYLE);
        String text;
        try {
            text = SpeechText.firstWords(SpeechStyle.sanitize(speech.text()), speechSettings.maxWords());
        } catch (IllegalArgumentException empty) {
            return reject(commandId, ErrorCode.INVALID_ARGUMENT);
        }
        String spoken = SpeechStyle.compose(tag, text);
        String voice = voices.assign(identity.incarnationId());
        UUID entityId = entity.getUuid();
        UUID listenerId = listener.get().getUuid();
        CompletableFuture<CommandResult> completed = new CompletableFuture<>();
        pending.put(viewer, new PendingSpeech(commandId, completed));
        context.rateLimiter().tryAcquire(ParrotAction.SAY);
        context.cooldowns().consume(viewer, ParrotAction.SAY, access.cooldownTicks());
        publish(identity, commandId, ParrotEvent.Type.COMMAND_ACCEPTED, null);
        voiceWrite.whenComplete((ignored, saveError) -> {
            if (closed || completed.isDone()) return;
            if (saveError != null) {
                finish(identity, commandId, completed, CommandResult.failed(commandId, ErrorCode.STORAGE_UNAVAILABLE, null));
                return;
            }
            try {
                var task = synthesis.submit(() -> {
                    if (closed || completed.isDone()) return;
                    byte[] pcm = null;
                    ErrorCode failureCode = ErrorCode.SPEECH_FAILED;
                    try {
                        var usage = io.github.viewerparrots.control.ControlPanelService.usage();
                        if (usage == null) throw new IllegalStateException("Usage unavailable");
                        if (usage.record(SpeechText.wordCount(text), speechSettings.hourlyRequests(), speechSettings.hourlyWords()))
                            pcm = acceptedClient.synthesize(voice, spoken, speechSettings.model(), speechSettings.timeoutSeconds());
                        else failureCode = ErrorCode.SPEECH_LIMIT_REACHED;
                    }
                    catch (Exception failure) {
                        if (!closed) ViewerParrotsMod.LOGGER.warn("Speech: OpenRouter synthesis failed ({})", failure.getClass().getSimpleName());
                    }
                    if (closed) return;
                    byte[] audio = pcm;
                    ErrorCode finalFailure = failureCode;
                    context.server().execute(() -> {
                        if (closed || completed.isDone()) return;
                        if (audio == null) {
                            finish(identity, commandId, completed, CommandResult.failed(commandId, finalFailure, null));
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
                running.put(commandId, task);
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
        running.remove(commandId);
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
        retiredClients.forEach(OpenRouterSpeechClient::close);
        for (PendingSpeech job : Map.copyOf(pending).values()) {
            // API shutdown also clears its completion cache; no old-world callback survives.
            job.completion().complete(CommandResult.failed(job.commandId(), ErrorCode.INVALID_STATE, null));
        }
        pending.clear();
    }
}
