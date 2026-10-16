package io.github.viewerparrots.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.config.ConfigLoader;
import io.github.viewerparrots.config.ConfigValidator;
import io.github.viewerparrots.config.ViewerParrotsConfig;
import io.github.viewerparrots.json.JsonSchemaException;
import io.github.viewerparrots.json.JsonView;
import io.github.viewerparrots.json.StrictJson;
import io.github.viewerparrots.roulette.RouletteSettings;
import io.github.viewerparrots.roulette.RouletteSettingsLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The only settings authority. Run file operations on the existing settings/storage worker. */
public final class SettingsStore {
    private static final String FILE_NAME = "viewerparrots-settings.json";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path directory;
    private final Path file;

    public SettingsStore(Path dir) {
        directory = Objects.requireNonNull(dir, "settings directory").toAbsolutePath().normalize();
        file = directory.resolve(FILE_NAME);
    }

    public synchronized ModSettings loadOrCreate() throws IOException {
        if (Files.exists(file)) return parse(Files.readString(file, StandardCharsets.UTF_8));
        ModSettings migrated = migrate();
        save(migrated);
        return migrated;
    }

    public synchronized void save(ModSettings settings) throws IOException {
        String json = toJson(Objects.requireNonNull(settings, "settings"));
        Files.createDirectories(directory);
        if (Files.exists(file)) {
            String previous = Files.readString(file, StandardCharsets.UTF_8);
            parse(previous);
            atomicWrite(directory.resolve(FILE_NAME + ".bak"), previous);
        }
        atomicWrite(file, json);
    }

    public static String toJson(ModSettings settings) {
        return JSON.toJson(Objects.requireNonNull(settings, "settings")) + "\n";
    }

    public static ModSettings parse(String text) {
        JsonView root = JsonView.ofRoot(StrictJson.parseObject(text));
        root.requireKnownFields("schemaVersion", "activeProfile", "profiles", "twitch");
        JsonView profilesView = root.requireObject("profiles");
        var profiles = new LinkedHashMap<String, ModSettings.Profile>();
        for (String id : profilesView.fieldNames()) profiles.put(id, profile(profilesView.requireObject(id)));
        JsonView twitch = root.requireObject("twitch");
        twitch.requireKnownFields("autoConnect", "channel", "login", "clientId", "guest");
        return new ModSettings(root.requireInt("schemaVersion"), root.requireString("activeProfile"), profiles,
                new ModSettings.Twitch(twitch.requireBoolean("autoConnect"), twitch.requireString("channel"),
                        twitch.requireString("login"), twitch.requireString("clientId"), twitch.requireBoolean("guest")));
    }

    /** Standalone exports contain one profile, with no global Twitch account or credential fields. */
    public synchronized Path exportProfile(ModSettings.Profile profile, String filename) throws IOException {
        Objects.requireNonNull(profile, "profile");
        if (filename == null || !filename.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}")) {
            throw new IllegalArgumentException("Export filename must be a simple filename without directories");
        }
        if (filename.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?")) {
            throw new IllegalArgumentException("This export filename is reserved by Windows");
        }
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".json")) filename += ".json";
        Path target = directory.resolve("viewerparrots-profiles").resolve(filename);
        atomicWrite(target, JSON.toJson(profile) + "\n");
        return target;
    }

    public synchronized ModSettings.Profile importProfile(Path path) throws IOException {
        return profile(JsonView.ofRoot(StrictJson.parseObject(Files.readString(path, StandardCharsets.UTF_8))));
    }

    private ModSettings migrate() throws IOException {
        ModSettings defaults = ModSettings.defaults();
        var old = defaults.active();
        Path generalPath = directory.resolve("viewerparrots.json");
        Path roulettePath = directory.resolve(RouletteSettingsLoader.FILE_NAME);
        Path twitchPath = directory.resolve("viewerparrots-twitch.json");
        ViewerParrotsConfig general = Files.exists(generalPath)
                ? new ConfigLoader(ConfigValidator.withoutRegistry()).parse(Files.readString(generalPath, StandardCharsets.UTF_8))
                : old.general();
        RouletteSettings roulette = Files.exists(roulettePath)
                ? new RouletteSettingsLoader(id -> true).parse(Files.readString(roulettePath, StandardCharsets.UTF_8))
                : old.roulette();
        ModSettings.Twitch twitch = defaults.twitch();
        if (Files.exists(twitchPath)) {
            JsonView legacy = JsonView.ofRoot(StrictJson.parseObject(Files.readString(twitchPath, StandardCharsets.UTF_8)));
            legacy.requireKnownFields("autoConnect", "channel", "login");
            String login = legacy.optionalString("login").orElse("");
            twitch = new ModSettings.Twitch(legacy.has("autoConnect") && legacy.requireBoolean("autoConnect"),
                    legacy.optionalString("channel").orElse(""), login, "", login.isBlank());
        }
        var profile = new ModSettings.Profile("Текущие настройки", general, Map.of(), old.speech(), roulette,
                old.gameplay(), old.moderation());
        return new ModSettings(1, "default", Map.of("default", profile), twitch);
    }

    private static Map<String, String> tags(JsonView speech, String field, Map<String, String> defaults) {
        var object = speech.optionalObject(field);
        if (object.isEmpty()) return defaults;
        var tags = new LinkedHashMap<String, String>();
        for (String key : object.get().fieldNames()) tags.put(key, object.get().requireString(key));
        return tags;
    }

    private static ModSettings.Profile profile(JsonView view) {
        view.requireKnownFields("name", "general", "commands", "speech", "roulette", "gameplay", "moderation");
        ViewerParrotsConfig general = new ConfigLoader(ConfigValidator.withoutRegistry())
                .parse(view.requireObject("general").raw().toString());
        RouletteSettings roulette = new RouletteSettingsLoader(id -> true)
                .parse(view.requireObject("roulette").raw().toString());
        var commands = new EnumMap<ParrotAction, ModSettings.CommandRule>(ParrotAction.class);
        JsonView commandViews = view.requireObject("commands");
        for (String key : commandViews.fieldNames()) {
            JsonView rule = commandViews.requireObject(key);
            rule.requireKnownFields("enabled", "roles", "cooldownTicks", "roleCooldownTicks");
            var roleCooldowns = new EnumMap<TwitchRole, Integer>(TwitchRole.class);
            JsonView cooldownView = rule.requireObject("roleCooldownTicks");
            for (String role : cooldownView.fieldNames()) {
                roleCooldowns.put(enumValue(TwitchRole.class, role, cooldownView.child(role)), cooldownView.requireInt(role));
            }
            commands.put(enumValue(ParrotAction.class, key, commandViews.child(key)), new ModSettings.CommandRule(
                    rule.requireBoolean("enabled"), roles(rule, "roles"), rule.requireInt("cooldownTicks"), roleCooldowns));
        }
        JsonView speech = view.requireObject("speech");
        speech.requireKnownFields("enabled", "model", "maxWords", "timeoutSeconds", "queueCapacity", "hourlyRequests", "hourlyWords", "voices",
                "styleTags", "traitTags");
        var voices = new ArrayList<ModSettings.Voice>();
        for (JsonView voice : speech.requireObjectArray("voices")) {
            voice.requireKnownFields("id", "name", "enabled");
            voices.add(new ModSettings.Voice(voice.requireString("id"), voice.requireString("name"), voice.requireBoolean("enabled")));
        }
        var speechSettings = new ModSettings.Speech(speech.requireBoolean("enabled"), speech.requireString("model"),
                speech.requireInt("maxWords"), speech.requireInt("timeoutSeconds"), speech.requireInt("queueCapacity"),
                speech.requireInt("hourlyRequests"), speech.requireInt("hourlyWords"), voices,
                // Файлы версии 1.1.0 не знают об интонациях: без полей действуют теги по умолчанию.
                tags(speech, "styleTags", io.github.viewerparrots.speech.SpeechStyle.DEFAULT_STYLE_TAGS),
                tags(speech, "traitTags", io.github.viewerparrots.speech.SpeechStyle.DEFAULT_TRAIT_TAGS));
        JsonView gameplay = view.requireObject("gameplay");
        gameplay.requireKnownFields("spinDurationTicks", "spinDegreesPerTick", "freefollowRadius", "freefollowReturnDistance", "headMaxAngle", "headTurnThreshold");
        var gameplaySettings = new ModSettings.Gameplay(gameplay.requireInt("spinDurationTicks"), gameplay.requireDouble("spinDegreesPerTick"),
                gameplay.requireDouble("freefollowRadius"), gameplay.requireDouble("freefollowReturnDistance"),
                (float) gameplay.requireDouble("headMaxAngle"), (float) gameplay.requireDouble("headTurnThreshold"));
        JsonView moderation = view.requireObject("moderation");
        moderation.requireKnownFields("twitchQueueCapacity", "commandsPerTick", "blockedViewers", "mutedViewers");
        var moderationSettings = new ModSettings.Moderation(moderation.requireInt("twitchQueueCapacity"),
                moderation.requireInt("commandsPerTick"), Set.copyOf(moderation.requireStringArray("blockedViewers")),
                Set.copyOf(moderation.requireStringArray("mutedViewers")));
        return new ModSettings.Profile(view.requireString("name"), general, commands, speechSettings,
                roulette, gameplaySettings, moderationSettings);
    }

    private static Set<TwitchRole> roles(JsonView view, String field) {
        var roles = new HashSet<TwitchRole>();
        for (String role : view.requireStringArray(field)) roles.add(enumValue(TwitchRole.class, role, view.child(field)));
        return Set.copyOf(roles);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String path) {
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException unknown) { throw new JsonSchemaException(path, "неизвестное значение " + value); }
    }

    private static void atomicWrite(Path target, String text) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), target.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
