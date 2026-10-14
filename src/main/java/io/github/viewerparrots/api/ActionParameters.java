package io.github.viewerparrots.api;

import io.github.viewerparrots.domain.DisplayName;
import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.speech.SpeechText;
import java.util.Objects;
import java.util.UUID;

/** Typed values for the only actions requiring parameters; arbitrary executable JSON is never accepted. */
public sealed interface ActionParameters permits ActionParameters.None, ActionParameters.Name,
        ActionParameters.Trait, ActionParameters.Target, ActionParameters.Page, ActionParameters.Speech {
    boolean supports(ParrotAction action);

    enum None implements ActionParameters {
        INSTANCE;
        @Override public boolean supports(ParrotAction action) {
            return action != ParrotAction.CREATE && action != ParrotAction.RENAME
                    && action != ParrotAction.TRAIT && action != ParrotAction.SETSTREAMER && action != ParrotAction.SAY;
        }
    }
    record Name(DisplayName name) implements ActionParameters {
        public Name { Objects.requireNonNull(name, "name"); }
        @Override public boolean supports(ParrotAction action) { return action == ParrotAction.CREATE || action == ParrotAction.RENAME; }
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
    record Speech(String text) implements ActionParameters {
        public Speech { text = SpeechText.firstThirtyWords(text); }
        @Override public boolean supports(ParrotAction action) { return action == ParrotAction.SAY; }
    }
}
