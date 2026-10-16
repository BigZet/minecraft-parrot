package io.github.viewerparrots.settings;

import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.config.ConfigValidator;
import io.github.viewerparrots.config.ViewerParrotsConfig;
import io.github.viewerparrots.roulette.RouletteSettings;
import io.github.viewerparrots.speech.VoiceAssignments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Public, credential-free snapshots shared by the control panel and server settings service. */
public record ModSettings(int schemaVersion, String activeProfile, Map<String, Profile> profiles, Twitch twitch) {
    private static final Set<ParrotAction> CHAT_ACTIONS = Set.of(ParrotAction.CREATE, ParrotAction.COME,
            ParrotAction.FOLLOW, ParrotAction.FREE, ParrotAction.FREEFOLLOW, ParrotAction.VIEW,
            ParrotAction.DEVIEW, ParrotAction.ARM, ParrotAction.DEARM, ParrotAction.SIT,
            ParrotAction.DANCE, ParrotAction.SPIN, ParrotAction.SCREAM, ParrotAction.SAY,
            ParrotAction.LOVE, ParrotAction.HAT, ParrotAction.DEHAT, ParrotAction.EXP, ParrotAction.ROULETTE);

    public ModSettings {
        if (schemaVersion != 1) throw new IllegalArgumentException("schemaVersion must be 1");
        Objects.requireNonNull(activeProfile, "activeProfile");
        Objects.requireNonNull(profiles, "profiles");
        range("profiles count", profiles.size(), 1, 32);
        var copy = new LinkedHashMap<String, Profile>();
        var names = new HashSet<String>();
        profiles.forEach((id, profile) -> {
            if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
                throw new IllegalArgumentException("Profile ID must contain lowercase letters, digits, - or _: " + id);
            }
            Objects.requireNonNull(profile, "profile");
            if (!names.add(profile.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Profile names must be unique: " + profile.name());
            }
            copy.put(id, profile);
        });
        profiles = Collections.unmodifiableMap(copy);
        if (!profiles.containsKey(activeProfile)) throw new IllegalArgumentException("Active profile does not exist: " + activeProfile);
        Objects.requireNonNull(twitch, "twitch");
    }

    public Profile active() { return profiles.get(activeProfile); }

    public ModSettings withActiveProfile(String id) { return new ModSettings(schemaVersion, id, profiles, twitch); }

    public static ModSettings defaults() {
        return new ModSettings(1, "default", Map.of("default", Profile.defaults()), new Twitch(false, "", "", "", true));
    }

    public record Profile(String name, ViewerParrotsConfig general, Map<ParrotAction, CommandRule> commands,
                          Speech speech, RouletteSettings roulette, Gameplay gameplay, Moderation moderation) {
        public Profile {
            name = label("profile name", name, 64);
            Objects.requireNonNull(general, "general");
            ConfigValidator.withoutRegistry().validate(general);
            validateGeneralBounds(general);
            Objects.requireNonNull(speech, "speech");
            Objects.requireNonNull(roulette, "roulette");
            Objects.requireNonNull(gameplay, "gameplay");
            Objects.requireNonNull(moderation, "moderation");
            var complete = new EnumMap<ParrotAction, CommandRule>(ParrotAction.class);
            complete.putAll(defaultCommands(general, roulette));
            Objects.requireNonNull(commands, "commands").forEach((action, rule) -> {
                if (!CHAT_ACTIONS.contains(action)) throw new IllegalArgumentException("Not a Twitch chat action: " + action);
                complete.put(action, Objects.requireNonNull(rule, "command rule"));
            });
            commands = Collections.unmodifiableMap(complete);
        }

        public static Profile defaults() {
            var general = ViewerParrotsConfig.defaults();
            var roulette = RouletteSettings.defaults();
            var voices = new ArrayList<Voice>();
            for (int i = 0; i < VoiceAssignments.DEFAULT_VOICES.size(); i++) {
                voices.add(new Voice(VoiceAssignments.DEFAULT_VOICES.get(i), "Голос " + (i + 1), true));
            }
            return new Profile("По умолчанию", general, Map.of(),
                    new Speech(true, "elevenlabs/eleven-v4-turbo", 30, 60, 16, 0, 0, voices), roulette,
                    new Gameplay(60, 180.0, 12.0, 8.0, 75.0f, 75.0f),
                    new Moderation(256, 4, Set.of(), Set.of()));
        }
    }

    public record Twitch(boolean autoConnect, String channel, String login, String clientId, boolean guest) {
        public Twitch {
            channel = Objects.requireNonNull(channel, "channel").strip().toLowerCase(Locale.ROOT);
            if (channel.startsWith("#")) channel = channel.substring(1);
            login = Objects.requireNonNull(login, "login").strip().toLowerCase(Locale.ROOT);
            clientId = Objects.requireNonNull(clientId, "clientId").strip();
            if (!channel.matches("[a-z0-9_]{0,25}")) throw new IllegalArgumentException("Twitch channel must be a channel login");
            if (!login.matches("[a-z0-9_]{0,25}")) throw new IllegalArgumentException("Twitch login must be a login name");
            if (!clientId.matches("[a-zA-Z0-9]{0,64}")) throw new IllegalArgumentException("Twitch clientId must be a public application ID");
            if (autoConnect && channel.isEmpty()) throw new IllegalArgumentException("Twitch channel is required for autoConnect");
        }
    }

    public record CommandRule(boolean enabled, Set<TwitchRole> roles, int cooldownTicks,
                              Map<TwitchRole, Integer> roleCooldownTicks) {
        public CommandRule {
            roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
            range("command cooldownTicks", cooldownTicks, 0, 72_000);
            var copy = new EnumMap<TwitchRole, Integer>(TwitchRole.class);
            Objects.requireNonNull(roleCooldownTicks, "roleCooldownTicks").forEach((role, cooldown) -> {
                Objects.requireNonNull(role, "role");
                range("role cooldownTicks", Objects.requireNonNull(cooldown, "role cooldown"), 0, 72_000);
                copy.put(role, cooldown);
            });
            roleCooldownTicks = Collections.unmodifiableMap(copy);
        }
    }

    public record Speech(boolean enabled, String model, int maxWords, int timeoutSeconds, int queueCapacity,
                         int hourlyRequests, int hourlyWords, List<Voice> voices) {
        public Speech {
            model = Objects.requireNonNull(model, "model").strip();
            if (!model.matches("[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.:-]+") || model.length() > 128) {
                throw new IllegalArgumentException("Speech model must be an OpenRouter model ID");
            }
            range("speech maxWords", maxWords, 1, 200);
            range("speech timeoutSeconds", timeoutSeconds, 5, 180);
            range("speech queueCapacity", queueCapacity, 1, 64);
            range("speech hourlyRequests", hourlyRequests, 0, 100_000);
            range("speech hourlyWords", hourlyWords, 0, 10_000_000);
            voices = List.copyOf(Objects.requireNonNull(voices, "voices"));
            range("speech voices count", voices.size(), 1, 128);
            var ids = new HashSet<String>();
            for (Voice voice : voices) {
                if (!ids.add(voice.id())) throw new IllegalArgumentException("Voice IDs must be unique: " + voice.id());
            }
            if (enabled && voices.stream().noneMatch(Voice::enabled)) {
                throw new IllegalArgumentException("Enable at least one speech voice");
            }
        }
    }

    public record Voice(String id, String name, boolean enabled) {
        public Voice {
            id = Objects.requireNonNull(id, "voice id").strip();
            if (!id.matches("[a-zA-Z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid voice ID");
            name = label("voice name", name, 80);
        }
    }

    public record Gameplay(int spinDurationTicks, double spinDegreesPerTick, double freefollowRadius,
                           double freefollowReturnDistance, float headMaxAngle, float headTurnThreshold) {
        public Gameplay {
            range("spinDurationTicks", spinDurationTicks, 1, 1200);
            decimal("spinDegreesPerTick", spinDegreesPerTick, 1, 3600);
            decimal("freefollowRadius", freefollowRadius, 1, 128);
            decimal("freefollowReturnDistance", freefollowReturnDistance, 0.5, 128);
            if (freefollowReturnDistance >= freefollowRadius) throw new IllegalArgumentException("freefollowReturnDistance must be below freefollowRadius");
            decimal("headMaxAngle", headMaxAngle, 1, 180);
            decimal("headTurnThreshold", headTurnThreshold, 0, headMaxAngle);
        }
    }

    public record Moderation(int twitchQueueCapacity, int commandsPerTick, Set<String> blockedViewers, Set<String> mutedViewers) {
        public Moderation {
            range("twitchQueueCapacity", twitchQueueCapacity, 1, 4096);
            range("commandsPerTick", commandsPerTick, 1, 64);
            blockedViewers = viewers(blockedViewers);
            mutedViewers = viewers(mutedViewers);
        }
    }

    private static Set<String> viewers(Set<String> viewers) {
        range("moderation viewers count", Objects.requireNonNull(viewers, "viewers").size(), 0, 10_000);
        var normalized = new HashSet<String>();
        for (String viewer : viewers) {
            String value = Objects.requireNonNull(viewer, "viewer").strip().toLowerCase(Locale.ROOT);
            if (!value.matches("[a-z0-9_:-]{1,64}")) throw new IllegalArgumentException("Invalid viewer ID or login: " + value);
            normalized.add(value);
        }
        return Set.copyOf(normalized);
    }

    private static Map<ParrotAction, CommandRule> defaultCommands(ViewerParrotsConfig config, RouletteSettings roulette) {
        var rules = new EnumMap<ParrotAction, CommandRule>(ParrotAction.class);
        for (ParrotAction action : CHAT_ACTIONS) {
            int ticks = switch (action) {
                case COME -> config.cooldowns().come();
                case FOLLOW -> config.cooldowns().follow();
                case SIT -> config.cooldowns().sit();
                case DANCE -> config.cooldowns().dance();
                case SCREAM -> config.cooldowns().scream();
                case SAY -> ParrotAction.SAY_COOLDOWN_TICKS;
                case SPIN -> ParrotAction.SPIN_COOLDOWN_TICKS;
                case HAT, DEHAT -> ParrotAction.HAT_COOLDOWN_TICKS;
                case EXP -> ParrotAction.EXP_COOLDOWN_TICKS;
                case FREE, FREEFOLLOW, VIEW, DEVIEW, ARM, DEARM -> ParrotAction.SIMPLE_CONTROL_COOLDOWN_TICKS;
                case LOVE -> 40;
                case ROULETTE -> roulette.cooldownTicks();
                default -> 0;
            };
            rules.put(action, new CommandRule(true, Set.of(TwitchRole.EVERYONE), ticks, Map.of()));
        }
        return rules;
    }

    private static void validateGeneralBounds(ViewerParrotsConfig c) {
        label("nameFormat", c.nameFormat(), 256);
        range("dance durationTicks", c.dance().durationTicks(), 1, 72_000);
        range("come timeoutTicks", c.come().timeoutTicks(), 1, 72_000);
        range("follow repathTicks", c.follow().repathTicks(), 1, 72_000);
        range("actionsPer20Ticks", c.limits().actionsPer20Ticks(), 1, 1000);
        range("soundsPer20Ticks", c.limits().soundsPer20Ticks(), 1, 1000);
        for (int cooldown : new int[] {c.cooldowns().come(), c.cooldowns().follow(), c.cooldowns().sit(),
                c.cooldowns().dance(), c.cooldowns().scream(), c.cooldowns().respawn()}) range("general cooldown", cooldown, 0, 72_000);
        for (int ticks : new int[] {c.behavior().idleMinTicks(), c.behavior().idleMaxTicks(), c.behavior().dangerCheckTicks(),
                c.behavior().lazyMinTicks(), c.behavior().lazyMaxTicks(), c.recovery().timeoutTicks()}) range("behavior/recovery ticks", ticks, 1, 72_000);
        range("checkpointIntervalTicks", c.storage().checkpointIntervalTicks(), 1, 720_000);
        range("shutdownTimeoutSeconds", c.storage().shutdownTimeoutSeconds(), 1, 120);
        long total = 0;
        for (int weight : c.behavior().traitWeights().values()) {
            range("trait weight", weight, 0, 1_000_000);
            total += weight;
        }
        if (total > 1_000_000) throw new IllegalArgumentException("Trait weights total must be at most 1000000");
    }

    private static String label(String field, String value, int max) {
        value = Objects.requireNonNull(value, field).strip();
        if (value.isEmpty() || value.length() > max || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must contain 1.." + max + " printable characters");
        }
        return value;
    }

    private static void range(String field, int value, int min, int max) {
        if (value < min || value > max) throw new IllegalArgumentException(field + " must be in " + min + ".." + max);
    }

    private static void decimal(String field, double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException(field + " must be finite and in " + min + ".." + max);
    }
}
