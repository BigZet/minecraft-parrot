package io.github.viewerparrots.api;

import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.domain.LifecycleState;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** An immutable registry/runtime view with no world or entity references. */
public record ParrotSnapshot(ViewerParrotRecord record, LifecycleState lifecycle,
        BehaviorState behavior, UUID targetPlayerUuid, Map<ParrotAction, Long> cooldowns,
        boolean operationPending, boolean behaviorFault, UUID pendingCommandId, long movementDeadlineTick) {
    public ParrotSnapshot {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(lifecycle, "lifecycle");
        cooldowns = Map.copyOf(cooldowns);
    }
}
