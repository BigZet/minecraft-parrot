package io.github.viewerparrots.api;

import io.github.viewerparrots.domain.DisplayName;
import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.speech.SpeechText;
import java.util.Objects;
import java.util.UUID;

/** Typed values for the only actions requiring parameters; arbitrary executable JSON is never accepted. */
public sealed interface ActionParameters permits ActionParameters.None, ActionParameters.Name,
        ActionParameters.Trait, ActionParameters.Target, ActionParameters.Page, ActionParameters.Speech, ActionParameters.Color,
        ActionParameters.Nickname, ActionParameters.Emote, ActionParameters.Opponent {
    boolean supports(ParrotAction action);

    enum None implements ActionParameters {
        INSTANCE;
        @Override public boolean supports(ParrotAction action) {
            return action != ParrotAction.CREATE && action != ParrotAction.RENAME
                    && action != ParrotAction.TRAIT && action != ParrotAction.SETSTREAMER && action != ParrotAction.SAY
                    && action != ParrotAction.FIGHT;
        }
    }
    record Name(DisplayName name) implements ActionParameters {
        public Name { Objects.requireNonNull(name, "name"); }
        @Override public boolean supports(ParrotAction action) { return action == ParrotAction.CREATE || action == ParrotAction.RENAME; }
    }
    /** Явный окрас для {@code !color синий}; {@code !color} без аргумента идёт с {@link None} и выбирает другой случайный. */
    record Color(io.github.viewerparrots.domain.ParrotVariant variant) implements ActionParameters {
        public Color { Objects.requireNonNull(variant, "variant"); }
        @Override public boolean supports(ParrotAction action) { return action == ParrotAction.COLOR; }
    }
    /** Кличка для {@code !name Кеша}; {@code null} ({@code !name} без текста) возвращает имя зрителя. */
    record Nickname(DisplayName nickname) implements ActionParameters {
        @Override public boolean supports(ParrotAction action) { return action == ParrotAction.NAME; }
    }
    /** Явная эмоция для {@code !emote поклон}; {@code !emote} без аргумента идёт с {@link None} и выбирает случайную. */
    record Emote(String id) implements ActionParameters {
        public Emote {
            if (!io.github.viewerparrots.cosmetic.ParrotEmotes.isKnown(id)) throw new IllegalArgumentException("Unknown parrot emote");
        }
        @Override public boolean supports(ParrotAction action) { return action == ParrotAction.EMOTE; }
    }
    /** Соперник для {@code !fight @ник}: имя или {@code twitch:id}; разрешается службой дуэлей. */
    record Opponent(String name) implements ActionParameters {
        public Opponent {
            name = Objects.requireNonNull(name, "name").strip();
            if (name.startsWith("@")) name = name.substring(1).strip();
            if (name.isEmpty() || name.length() > 64 || name.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Opponent name must be 1-64 printable characters");
            }
        }
        @Override public boolean supports(ParrotAction action) { return action == ParrotAction.FIGHT; }
    }
    record Trait(ParrotTrait trait) implements ActionParameters {
        public Trait { Objects.requireNonNull(trait, "trait"); }
        @Override public boolean supports(ParrotAction action) { return action == ParrotAction.TRAIT; }
    }
    record Target(UUID playerUuid) implements ActionParameters {
        public Target { Objects.requireNonNull(playerUuid, "playerUuid"); }
        @Override public boolean supports(ParrotAction action) { return action == ParrotAction.SETSTREAMER; }
    }
    record Page(int page) implements ActionParameters {
        public Page { if (page < 1) throw new IllegalArgumentException("page must be at least one"); }
        @Override public boolean supports(ParrotAction action) { return action == ParrotAction.LIST; }
    }
    /** {@code style} — ключ интонации из {@code !say [стиль] текст} или {@code null}; проверяется службой речи. */
    record Speech(String text, String style) implements ActionParameters {
        public Speech {
            text = SpeechText.firstWords(text, 200);
            if (style != null) {
                style = io.github.viewerparrots.speech.SpeechStyle.normalizeKey(style);
                if (style.length() > io.github.viewerparrots.speech.SpeechStyle.MAX_STYLE_LENGTH) {
                    style = style.substring(0, io.github.viewerparrots.speech.SpeechStyle.MAX_STYLE_LENGTH);
                }
            }
        }
        public Speech(String text) { this(text, null); }
        /** Разбирает необязательный {@code [стиль]} в начале реплики. */
        public static Speech parse(String raw) {
            var parsed = io.github.viewerparrots.speech.SpeechStyle.parse(raw);
            return new Speech(parsed.text(), parsed.style());
        }
        @Override public boolean supports(ParrotAction action) { return action == ParrotAction.SAY; }
    }
}
