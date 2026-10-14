package io.github.viewerparrots.entity;

import io.github.viewerparrots.behavior.ViewTrackingPolicy;
import net.minecraft.entity.ai.control.LookControl;
import net.minecraft.util.math.MathHelper;

import java.util.Optional;

/** Retains vanilla interpolation while bounding the neck in both axes. */
final class ViewingLookControl extends LookControl {
    private final ViewerParrotEntity parrot;

    ViewingLookControl(ViewerParrotEntity parrot) {
        super(parrot);
        this.parrot = parrot;
    }

    @Override protected boolean shouldStayHorizontal() {
        return !parrot.isViewingStreamer() && super.shouldStayHorizontal();
    }

    @Override protected Optional<Float> getTargetPitch() {
        return super.getTargetPitch().map(pitch -> parrot.isViewingStreamer()
                ? MathHelper.clamp(pitch, -ViewTrackingPolicy.MAX_HEAD_PITCH, ViewTrackingPolicy.MAX_HEAD_PITCH) : pitch);
    }

    @Override protected void clampHeadYaw() {
        if (!parrot.isViewingStreamer()) { super.clampHeadYaw(); return; }
        parrot.headYaw = MathHelper.clampAngle(parrot.headYaw, parrot.bodyYaw, ViewTrackingPolicy.MAX_HEAD_YAW);
    }
}
