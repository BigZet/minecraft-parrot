package io.github.viewerparrots.entity;

import io.github.viewerparrots.behavior.ViewTrackingPolicy;
import net.minecraft.entity.ai.control.BodyControl;
import net.minecraft.entity.ai.control.LookControl;
import net.minecraft.util.math.MathHelper;

/** Vanilla body control for flight; standing view mode preserves the head/body separation. */
final class ViewingBodyControl extends BodyControl {
    private final ViewerParrotEntity parrot;

    ViewingBodyControl(ViewerParrotEntity parrot) {
        super(parrot);
        this.parrot = parrot;
    }

    @Override public void tick() {
        if (parrot.isViewingStreamer() && !parrot.isSpinning() && parrot.getWorld().isClient()) {
            // The LOOK goal runs only on the server. Preserve its separately synchronized body yaw;
            // vanilla's idle client controller would otherwise align the whole body with the head.
            parrot.bodyYaw = parrot.getYaw();
            parrot.headYaw = MathHelper.clampAngle(parrot.headYaw, parrot.bodyYaw, ViewTrackingPolicy.MAX_HEAD_YAW);
            return;
        }
        double dx = parrot.getX() - parrot.prevX;
        double dz = parrot.getZ() - parrot.prevZ;
        LookControl look = parrot.getLookControl();
        if (!parrot.isViewingStreamer() || parrot.isSpinning() || dx * dx + dz * dz > 2.5000003E-7
                || !look.isLookingAtSpecificPosition()) { super.tick(); return; }
        double targetX = look.getLookX() - parrot.getX();
        double targetZ = look.getLookZ() - parrot.getZ();
        if (targetX * targetX + targetZ * targetZ < 1.0E-10) return;
        float targetYaw = (float) (MathHelper.atan2(targetZ, targetX) * 180 / Math.PI) - 90;
        parrot.bodyYaw = ViewTrackingPolicy.bodyYaw(parrot.bodyYaw, parrot.headYaw, targetYaw);
        parrot.setYaw(parrot.bodyYaw);
        parrot.headYaw = MathHelper.clampAngle(parrot.headYaw, parrot.bodyYaw, ViewTrackingPolicy.MAX_HEAD_YAW);
    }
}
