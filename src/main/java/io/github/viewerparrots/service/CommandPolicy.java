package io.github.viewerparrots.service;

import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.domain.LifecycleState;
import io.github.viewerparrots.domain.PhysicalStatus;
import io.github.viewerparrots.domain.ParrotTrait;

/** Pure command predicates shared by the adapter and diagnostic views. */
public final class CommandPolicy {
    private CommandPolicy() {}

    public static boolean shouldDelay(boolean behaviorEnabled, ParrotTrait trait, ParrotAction action) {
        return behaviorEnabled && trait == ParrotTrait.LAZY
                && (action == ParrotAction.COME || action == ParrotAction.FOLLOW);
    }

    /** Movement commands bring the same registered bird out of vanilla shoulder NBT. */
    public static boolean releasesShoulder(ParrotAction action) {
        return switch (action) {
            case COME, FOLLOW, SIT, DANCE, SPIN, FREE, FREEFOLLOW, DEARM -> true;
            default -> false;
        };
    }

    public static boolean preservesBehavior(ParrotAction action) {
        return switch (action) {
            case HAT, DEHAT, COLOR, NAME, VIEW, DEVIEW, SPIN, SCREAM, LOVE, EXP, ROULETTE -> true;
            default -> false;
        };
    }

    public static ParrotAction cooldownKey(ParrotAction action) {
        return switch (action) {
            case DEHAT -> ParrotAction.HAT;
            case DEVIEW -> ParrotAction.VIEW;
            case DEARM -> ParrotAction.ARM;
            default -> action;
        };
    }

    public static LifecycleState lifecycle(PhysicalStatus status, boolean loaded, boolean knownUnloaded) {
        if (status == PhysicalStatus.DEAD) return LifecycleState.DEAD;
        if (status == PhysicalStatus.MISSING && !loaded) return LifecycleState.MISSING;
        if (loaded) return LifecycleState.LOADED;
        return knownUnloaded ? LifecycleState.UNLOADED : LifecycleState.UNRESOLVED;
    }
}
