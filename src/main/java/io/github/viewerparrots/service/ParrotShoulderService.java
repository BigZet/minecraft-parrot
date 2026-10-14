package io.github.viewerparrots.service;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.behavior.BehaviorStateMachine;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.domain.DesiredBehavior;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.entity.ModEntities;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import io.github.viewerparrots.mixin.PlayerShoulderAccess;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Vanilla's two shoulder slots hold the complete bird NBT, including its existing identity and hat. */
public final class ParrotShoulderService {
    private final ServerContext context;
    private final Map<ViewerId, UUID> mounted = new HashMap<>();
    private boolean evictLeft = true;

    private record Shoulder(ServerPlayerEntity player, boolean left, NbtCompound nbt, ViewerId viewer) { }

    public ParrotShoulderService(ServerContext context) { this.context = context; }

    public boolean contains(ViewerId viewer) { return locate(viewer).isPresent(); }

    public boolean isStored(ViewerParrotEntity parrot) {
        return parrot.marker().flatMap(marker -> locate(marker.viewerId()))
                .map(slot -> slot.nbt().containsUuid("UUID") && slot.nbt().getUuid("UUID").equals(parrot.getUuid()))
                .orElse(false);
    }

    /** An unspawned representation lets existing cosmetic/speech services reuse the real bird's identity. */
    public Optional<ViewerParrotEntity> find(ViewerId viewer) {
        return locate(viewer).flatMap(slot -> EntityType.getEntityFromNbt(slot.nbt().copy(), slot.player().getWorld())
                .filter(ViewerParrotEntity.class::isInstance).map(ViewerParrotEntity.class::cast)
                .map(parrot -> {
                    double yaw = slot.player().bodyYaw * Math.PI / 180.0;
                    double side = slot.left() ? 0.4 : -0.4;
                    parrot.setPosition(slot.player().getX() + Math.cos(yaw) * side,
                            slot.player().getY() + (slot.player().isInSneakingPose() ? 1.3 : 1.5),
                            slot.player().getZ() + Math.sin(yaw) * side);
                    return parrot;
                }));
    }

    /** Called before the command transition: unsuccessful placement leaves the current intent intact. */
    public ErrorCode arm(ViewerParrotEntity parrot, ServerPlayerEntity player) {
        ViewerId viewer = parrot.marker().map(marker -> marker.viewerId()).orElse(null);
        if (viewer == null) return ErrorCode.ENTITY_UNRESOLVED;
        if (contains(viewer)) return null;
        if (player.hasVehicle() || !player.isOnGround() || player.isTouchingWater()
                || player.inPowderSnow || player.isSpectator() || player.getAbilities().flying
                || parrot.getWorld() != player.getWorld()) return ErrorCode.INVALID_STATE;

        if (!player.getShoulderEntityLeft().isEmpty() && !player.getShoulderEntityRight().isEmpty()) {
            Shoulder other = shoulder(player, evictLeft);
            ErrorCode released = releaseSlot(other);
            if (released != null) return released;
            evictLeft = !evictLeft;
        }
        if (!parrot.mountOnStreamerShoulder(player)) return ErrorCode.INVALID_STATE;
        mounted.put(viewer, parrot.getUuid());
        context.markLoaded(viewer);
        return null;
    }

    /** Vanilla drop loads synchronously, so a movement command can immediately reacquire the physical bird. */
    public ErrorCode release(ViewerId viewer) {
        Shoulder slot = locate(viewer).orElse(null);
        return slot == null ? null : releaseSlot(slot);
    }

    private ErrorCode releaseSlot(Shoulder slot) {
        NbtCompound saved = slot.nbt().copy();
        set(slot, new NbtCompound());
        ((PlayerShoulderAccess) slot.player()).viewerparrots$dropShoulderEntity(saved);
        if (slot.viewer() != null) {
            var physical = context.loadedIndex().find(slot.viewer());
            if (physical.isEmpty() || !saved.containsUuid("UUID")
                    || !physical.get().getUuid().equals(saved.getUuid("UUID"))) {
                set(slot, saved);
                return ErrorCode.ENTITY_UNRESOLVED;
            }
            mounted.remove(slot.viewer());
            freeFollow(slot.viewer());
        }
        return null;
    }

    public void update(ViewerParrotEntity parrot) {
        parrot.marker().flatMap(marker -> locate(marker.viewerId())).ifPresent(slot -> {
            if (!slot.nbt().containsUuid("UUID") || !slot.nbt().getUuid("UUID").equals(parrot.getUuid())) return;
            NbtCompound updated = new NbtCompound();
            updated.putString("id", slot.nbt().getString("id"));
            parrot.writeNbt(updated);
            set(slot, updated);
        });
    }

    /** The vanilla release event precedes clearing its slot; recognize it before normal binding restores intent. */
    public void onPhysicalLoad(ViewerParrotEntity parrot) {
        parrot.marker().ifPresent(marker -> {
            if (mounted.remove(marker.viewerId()) != null || isStored(parrot)) freeFollow(marker.viewerId());
        });
    }

    public void tick() {
        if (!context.isRegistryReady()) return;
        Set<ViewerId> present = new HashSet<>();
        for (ServerPlayerEntity player : context.server().getPlayerManager().getPlayerList()) {
            for (boolean left : new boolean[] {true, false}) {
                Shoulder slot = shoulder(player, left);
                if (!isViewerType(slot.nbt())) continue;
                if (slot.viewer() == null) {
                    set(slot, new NbtCompound());
                    continue;
                }
                var physical = context.loadedIndex().find(slot.viewer());
                if (physical.isPresent() && !physical.get().isRemoved()) {
                    // A saved shoulder copy must never coexist with an already authorized physical bird.
                    set(slot, new NbtCompound());
                    continue;
                }
                if (!present.add(slot.viewer())) {
                    set(slot, new NbtCompound());
                    continue;
                }
                mounted.put(slot.viewer(), slot.nbt().getUuid("UUID"));
                context.markLoaded(slot.viewer());
                var runtime = context.runtime(slot.viewer());
                if (runtime.behavior() != BehaviorState.SHOULDER) {
                    runtime.apply(new BehaviorStateMachine.Accepted(BehaviorState.SHOULDER,
                            DesiredBehavior.FREEFOLLOW, Set.of()), context.clock().currentTick());
                }
            }
        }
        for (ViewerId id : Set.copyOf(mounted.keySet())) {
            if (present.contains(id)) continue;
            mounted.remove(id);
            if (context.loadedIndex().isLoaded(id)) freeFollow(id);
            else context.markUnloaded(id);
        }
    }

    private void freeFollow(ViewerId viewer) {
        var runtime = context.runtime(viewer);
        runtime.restoreDesired(DesiredBehavior.FREEFOLLOW);
        runtime.setTargetPlayerUuid(context.streamerService().configuredUuid().orElse(null));
        context.loadedIndex().find(viewer).ifPresent(parrot -> {
            parrot.setForcedDancing(false);
            parrot.stopSpin();
            parrot.setSitting(false);
            parrot.setInSittingPose(false);
        });
        context.registry().find(viewer).filter(record -> record.desiredBehavior() != DesiredBehavior.FREEFOLLOW)
                .ifPresent(record -> context.registry().replace(record.withDesiredBehavior(
                        DesiredBehavior.FREEFOLLOW, context.clock().now())));
    }

    private Optional<Shoulder> locate(ViewerId viewer) {
        for (ServerPlayerEntity player : context.server().getPlayerManager().getPlayerList()) {
            Shoulder left = shoulder(player, true);
            if (viewer.equals(left.viewer())) return Optional.of(left);
            Shoulder right = shoulder(player, false);
            if (viewer.equals(right.viewer())) return Optional.of(right);
        }
        return Optional.empty();
    }

    private Shoulder shoulder(ServerPlayerEntity player, boolean left) {
        NbtCompound nbt = left ? player.getShoulderEntityLeft() : player.getShoulderEntityRight();
        return new Shoulder(player, left, nbt, validViewer(nbt));
    }

    private ViewerId validViewer(NbtCompound nbt) {
        if (!isViewerType(nbt) || !nbt.containsUuid("UUID")) return null;
        NbtCompound marker = nbt.getCompound(ViewerParrotsMod.MOD_ID);
        if (!marker.containsUuid("incarnationId")) return null;
        try {
            ViewerId viewer = ViewerId.of(marker.getString("viewerId"));
            return context.registry().find(viewer).filter(record ->
                    record.incarnationId().equals(marker.getUuid("incarnationId"))
                            && record.generation() == marker.getLong("generation")
                            && record.expectedEntityUuid().filter(nbt.getUuid("UUID")::equals).isPresent())
                    .map(record -> viewer).orElse(null);
        } catch (RuntimeException invalid) { return null; }
    }

    private static boolean isViewerType(NbtCompound nbt) {
        return EntityType.get(nbt.getString("id")).orElse(null) == ModEntities.VIEWER_PARROT;
    }

    private static void set(Shoulder slot, NbtCompound nbt) {
        PlayerShoulderAccess access = (PlayerShoulderAccess) slot.player();
        if (slot.left()) access.viewerparrots$setShoulderLeft(nbt);
        else access.viewerparrots$setShoulderRight(nbt);
    }
}
