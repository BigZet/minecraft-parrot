package io.github.viewerparrots.control;

import io.github.viewerparrots.api.*;
import io.github.viewerparrots.auth.LocalSecretStore;
import io.github.viewerparrots.auth.TwitchAuthService;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.domain.*;
import io.github.viewerparrots.settings.*;
import io.github.viewerparrots.speech.*;
import io.github.viewerparrots.twitch.TwitchChatBridge;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** Local asynchronous control facade. Snapshots and exports never contain credentials. */
public final class ControlPanelService {
    private static final ExecutorService FILES = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), task -> { var t = new Thread(task, "viewerparrots-settings"); t.setDaemon(true); return t; });
    private static volatile ModSettings current = ModSettings.defaults();
    private static volatile String settingsError = "";
    private static volatile String usageError = "";
    private static CompletableFuture<Void> ready = CompletableFuture.completedFuture(null);
    private static SettingsStore store;
    private static LocalSecretStore secrets;
    private static TwitchAuthService auth;
    private static Path directory;
    private static SpeechUsage usage;
    private static final java.util.Set<CompletableFuture<String>> previews = ConcurrentHashMap.newKeySet();
    private static volatile Thread previewThread;
    private static Consumer<byte[]> previewAudio = ignored -> { };
    private static final ThreadPoolExecutor PREVIEW = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> { var t = new Thread(task, "viewerparrots-preview"); t.setDaemon(true); return t; });
    private ControlPanelService() { }

    public static synchronized void initialize(Path configDirectory) {
        if (store != null) return;
        directory = configDirectory;
        store = new SettingsStore(directory); secrets = new LocalSecretStore(directory);
        auth = new TwitchAuthService(directory, token -> TwitchChatBridge.credentialsChanged());
        ready = io(() -> {
            try { current = store.loadOrCreate(); }
            catch (Exception invalid) { settingsError = "Не удалось прочитать viewerparrots-settings.json. Исправьте файл и нажмите «Перечитать»: " + invalid.getMessage(); }
            try { usage = new SpeechUsage(directory); }
            catch (Exception invalid) { usageError = "Учёт озвучки повреждён; платные запросы остановлены."; }
            auth.configure(current.twitch().clientId(), current.twitch().login());
            return null;
        });
    }
    public static ModSettings settings() { return current; }
    public static String settingsError() { return settingsError + (usageError.isBlank() ? "" : " " + usageError); }
    public static boolean configurationValid() { return settingsError.isBlank(); }
    public static TwitchAuthService auth() { return auth; }
    public static CompletableFuture<Void> ready() { return ready; }
    public static SpeechUsage usage() { return usage; }
    public static void setPreviewAudio(Consumer<byte[]> player) { previewAudio = Objects.requireNonNull(player); }
    public static String openRouterKey() throws Exception {
        String local = secrets.read("openrouter");
        return local.isBlank() ? OpenRouterToken.load(directory) : local;
    }

    public static CompletableFuture<ApplyResult> save(ModSettings draft) {
        return ready.thenCompose(ignored -> io(() -> {
            if (!settingsError.isBlank()) throw new IllegalStateException(settingsError);
            var validated = SettingsStore.parse(SettingsStore.toJson(draft));
            validateItems(validated);
            store.save(validated); current = validated;
            auth.configure(validated.twitch().clientId(), validated.twitch().login());
            return validated;
        })).thenCompose(loaded -> onWorld(context -> {
            boolean restart = context.applySettings(loaded.active());
            return new ApplyResult(restart ? "Сохранено. Часть настроек применится после перезахода в мир." : "Настройки сохранены и применены.", restart);
        }, new ApplyResult("Настройки сохранены.", false)));
    }
    public static CompletableFuture<ModSettings> reload() {
        return ready.thenCompose(ignored -> io(() -> {
            var loaded = store.loadOrCreate(); validateItems(loaded); current = loaded; settingsError = "";
            if (usage == null) try { usage = new SpeechUsage(directory); usageError = ""; } catch (Exception failure) { usageError = "Учёт озвучки повреждён; платные запросы остановлены."; }
            auth.configure(loaded.twitch().clientId(), loaded.twitch().login()); return loaded;
        })).thenCompose(loaded -> onWorld(context -> { context.applySettings(loaded.active()); return loaded; }, loaded));
    }
    private static void validateItems(ModSettings settings) {
        for (var profile : settings.profiles().values()) for (var category : profile.roulette().categories())
            for (var prize : category.items()) {
                var id = net.minecraft.util.Identifier.tryParse(prize.itemId());
                if (id == null || !net.minecraft.registry.Registries.ITEM.containsId(id)
                        || id.toString().equals("minecraft:air")) throw new IllegalArgumentException("Неизвестный предмет: " + prize.itemId());
            }
    }
    public static CompletableFuture<Path> exportProfile(String id) {
        var profile = current.profiles().get(id);
        if (profile == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Профиль не найден"));
        return io(() -> store.exportProfile(profile, id + ".json"));
    }
    public static CompletableFuture<ModSettings> importProfile(String filename) {
        return io(() -> {
            if (!filename.matches("[\\p{L}\\p{N}_. -]{1,100}\\.json") || filename.contains(".."))
                throw new IllegalArgumentException("Укажите имя .json из config/viewerparrots-profiles");
            var profile = store.importProfile(directory.resolve("viewerparrots-profiles").resolve(filename));
            var profiles = new LinkedHashMap<>(current.profiles());
            String name = profile.name(); int suffix = 2;
            while (containsName(profiles, name)) name = profile.name() + " " + suffix++;
            profile = new ModSettings.Profile(name, profile.general(), profile.commands(), profile.speech(), profile.roulette(), profile.gameplay(), profile.moderation());
            String id = UUID.randomUUID().toString(); profiles.put(id, profile);
            return new ModSettings(1, id, profiles, current.twitch());
        });
    }
    private static boolean containsName(Map<String, ModSettings.Profile> profiles, String name) {
        return profiles.values().stream().anyMatch(p -> p.name().equalsIgnoreCase(name));
    }
    public static CompletableFuture<String> saveOpenRouterKey(String key) {
        return io(() -> {
            if (key == null || !key.strip().matches("sk-or-[A-Za-z0-9_-]{16,256}")) throw new IllegalArgumentException("Некорректный ключ OpenRouter");
            secrets.write("openrouter", key.strip()); return key.strip();
        }).thenCompose(saved -> onWorld(context -> { context.speechService().replaceKey(saved); return "Ключ сохранён локально."; }, "Ключ сохранён локально."));
    }
    public static CompletableFuture<String> previewVoice(String id, String rawText) {
        var speech = current.active().speech();
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) return CompletableFuture.failedFuture(new IllegalArgumentException("Некорректный ID голоса"));
        String text = SpeechText.firstWords(rawText, speech.maxWords());
        var result = new CompletableFuture<String>();
        previews.add(result);
        try { PREVIEW.execute(() -> {
            if (result.isDone()) return;
            previewThread = Thread.currentThread();
            try (var client = new OpenRouterSpeechClient(openRouterKey())) {
                if (usage == null) throw new IllegalStateException("Учёт озвучки недоступен");
                if (!usage.record(SpeechText.wordCount(text), speech.hourlyRequests(), speech.hourlyWords())) {
                    result.complete("Достигнут часовой лимит озвучки."); return;
                }
                byte[] pcm = client.synthesize(id, text, speech.model(), speech.timeoutSeconds());
                if (!result.isDone()) previewAudio.accept(pcm); result.complete("Голос воспроизведён. Запрос учтён в лимитах.");
            } catch (Exception failure) { result.completeExceptionally(new IllegalStateException("Не удалось озвучить: " + failure.getClass().getSimpleName())); }
            finally { previewThread = null; previews.remove(result); }
        }); } catch (RejectedExecutionException full) { previews.remove(result); result.completeExceptionally(new IllegalStateException("Прослушивание уже выполняется")); }
        return result;
    }
    public static CompletableFuture<String> stopSpeech() {
        for (var job : previews) job.completeExceptionally(new IllegalStateException("Прослушивание остановлено"));
        previews.clear(); PREVIEW.getQueue().clear();
        Thread runningPreview = previewThread; if (runningPreview != null) runningPreview.interrupt();
        return onWorld(context -> { context.speechService().stopPending(); return "Очередь озвучки остановлена."; }, "Очередь озвучки остановлена.");
    }
    public static void setPaused(boolean paused) { TwitchChatBridge.setPaused(paused); }
    public static void setChatStopped(boolean stopped) { TwitchChatBridge.setChatStopped(stopped); }
    public static CompletableFuture<String> twitchConnect() { return ready.thenCompose(ignored -> TwitchChatBridge.connectFromSettings()); }
    public static CompletableFuture<String> twitchDisconnect() { TwitchChatBridge.disconnectNow(); return CompletableFuture.completedFuture("Twitch отключён."); }

    public static CompletableFuture<Dashboard> snapshot() {
        var counts = usage == null ? new SpeechUsage.Counts(0, 0) : usage.counts();
        return onWorld(context -> {
            List<Bird> birds = context.registry().allSorted().stream().map(record -> {
                var viewer = record.viewerId();
                var entity = context.loadedIndex().find(viewer).or(() -> context.shoulderService().find(viewer));
                Map<String, Long> cooldowns = new LinkedHashMap<>();
                context.cooldowns().remainingFor(viewer).forEach((action, ticks) -> cooldowns.put(action.name(), ticks));
                return new Bird(viewer.value(), record.viewer().displayName().value(), context.lifecycle(viewer).name(),
                        context.runtime(viewer).behavior().name(), context.speechService().voiceFor(viewer).orElse(""),
                        entity.map(b -> b.hatId()).orElse(""), context.shoulderService().contains(viewer) ? "На плече" : "", Map.copyOf(cooldowns));
            }).toList();
            return new Dashboard(true, TwitchChatBridge.status(), current.twitch().channel(),
                    context.streamerService().onlineTarget().map(p -> p.getName().getString()).orElse("Не назначен / не в мире"),
                    TwitchChatBridge.pending(), context.speechService().pendingCount(), context.rouletteService().pendingCount(),
                    birds, TwitchChatBridge.recent(), context.settingsRestartRequired(), counts.requests(), counts.words());
        }, new Dashboard(false, "Мир не открыт", current.twitch().channel(), "—", 0, 0, 0,
                List.of(), List.of(), false, counts.requests(), counts.words()));
    }
    public static CompletableFuture<String> resetCooldowns(String viewerId) {
        return onWorld(context -> { context.cooldowns().clear(ViewerId.of(viewerId)); return "Кулдауны сброшены."; }, "Откройте одиночный мир.");
    }
    public static CompletableFuture<String> execute(String viewerId, String action, String argument) {
        return worldFuture(context -> {
            if (context.server().isDedicated()) return CompletableFuture.completedFuture("Нужен одиночный мир.");
            var owner = context.server().getHostProfile();
            if (owner == null) return CompletableFuture.completedFuture("Владелец мира не найден.");
            var actor = new ActorContext(ActorContext.SourceType.INTERNAL_API, owner.getId(), 4, "local-control-panel");
            var viewer = viewerId == null || viewerId.isBlank() ? ViewerId.of("local:test") : ViewerId.of(viewerId);
            String normalized = action.toUpperCase(Locale.ROOT);
            if (normalized.equals("VOICE")) return context.speechService().setVoice(viewer, argument).thenApply(ignored -> "Голос изменён.");
            if (normalized.equals("HAT_SELECT")) {
                var bird = context.loadedIndex().find(viewer).or(() -> context.shoulderService().find(viewer));
                if (bird.isEmpty()) return CompletableFuture.completedFuture("Птица не загружена.");
                if (!argument.isEmpty() && !io.github.viewerparrots.cosmetic.ParrotHats.isKnown(argument)) return CompletableFuture.completedFuture("Неизвестная шляпа.");
                bird.get().setHatId(argument); context.shoulderService().update(bird.get());
                return CompletableFuture.completedFuture("Шляпа изменена.");
            }
            ParrotAction kind = ParrotAction.valueOf(normalized);
            ActionParameters parameters = switch (kind) {
                case CREATE, RENAME -> new ActionParameters.Name(DisplayName.of(argument.isBlank() ? "Тестовый попугай" : argument));
                case TRAIT -> new ActionParameters.Trait(ParrotTrait.valueOf(argument));
                case SETSTREAMER -> new ActionParameters.Target(owner.getId());
                case SAY -> ActionParameters.Speech.parse(argument);
                case NAME -> new ActionParameters.Nickname(argument.isBlank() ? null : DisplayName.of(argument));
                default -> ActionParameters.None.INSTANCE;
            };
            return context.api().submit(new CommandRequest(UUID.randomUUID(), viewer, kind, parameters), actor)
                    .thenApply(result -> result.code() == null ? "Выполнено: " + kind : result.code().name()
                            + (result.retryAfterTicks() > 0 ? " (" + String.format(Locale.ROOT, "%.1f", result.retryAfterTicks() / 20.0) + " с)" : ""));
        });
    }
    private static <T> CompletableFuture<T> onWorld(Function<ServerContext, T> work, T noWorld) {
        var context = ServerContext.current().orElse(null);
        if (context == null || context.server().isDedicated()) return CompletableFuture.completedFuture(noWorld);
        var result = new CompletableFuture<T>();
        context.server().execute(() -> {
            if (ServerContext.current().orElse(null) != context) { result.complete(noWorld); return; }
            try { result.complete(work.apply(context)); } catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result;
    }
    private static <T> CompletableFuture<T> worldFuture(Function<ServerContext, java.util.concurrent.CompletionStage<T>> work) {
        var context = ServerContext.current().orElse(null);
        if (context == null || context.server().isDedicated()) return CompletableFuture.failedFuture(new IllegalStateException("Откройте одиночный мир"));
        var result = new CompletableFuture<T>();
        context.server().execute(() -> {
            try {
                if (ServerContext.current().orElse(null) != context) throw new IllegalStateException("Мир закрыт");
                work.apply(context).whenComplete((value, error) -> { if (error == null) result.complete(value); else result.completeExceptionally(error); });
            } catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result;
    }
    @FunctionalInterface private interface Checked<T> { T get() throws Exception; }
    private static <T> CompletableFuture<T> io(Checked<T> work) {
        return CompletableFuture.supplyAsync(() -> { try { return work.get(); } catch (Exception e) { throw new CompletionException(e); } }, FILES);
    }
    public record ApplyResult(String message, boolean restartRequired) { }
    public record Dashboard(boolean worldOpen, String twitchStatus, String channel, String streamer,
                            int twitchPending, int speechPending, int roulettePending, List<Bird> birds,
                            List<RecentCommand> recent, boolean restartRequired, long hourlyRequests, long hourlyWords) { }
    public record Bird(String viewerId, String displayName, String state, String behavior, String voice,
                       String hat, String shoulder, Map<String, Long> cooldownTicks) { }
    public record RecentCommand(String viewer, String action, String result) { }
}
