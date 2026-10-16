package io.github.viewerparrots.api;

import io.github.viewerparrots.domain.ViewerId;
import java.util.Objects;
import java.util.UUID;

public record CommandRequest(UUID commandId, ViewerId viewerId, ParrotAction action, ActionParameters parameters) {
    public CommandRequest {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(parameters, "parameters");
        boolean scoped = switch (action) {
            case CREATE, REMOVE, RESPAWN, RENAME, TRAIT, RECONCILE, INFO, COME, FOLLOW, FREE, FREEFOLLOW, VIEW, DEVIEW, ARM, DEARM, SIT, DANCE, SPIN, SCREAM, SAY, LOVE, HAT, DEHAT, COLOR, NAME, EMOTE, EXP, ROULETTE -> true;
            default -> false;
        };
        if (scoped && viewerId == null) throw new IllegalArgumentException("viewerId is required for " + action);
    }
}
