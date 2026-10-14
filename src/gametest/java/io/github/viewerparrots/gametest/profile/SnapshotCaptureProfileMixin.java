package io.github.viewerparrots.gametest.profile;

import io.github.viewerparrots.gametest.RegistryBootAcceptance;
import io.github.viewerparrots.registry.ViewerParrotRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ViewerParrotRegistry.SnapshotCapture.class, remap = false)
public abstract class SnapshotCaptureProfileMixin {
    @Inject(method = "advance", at = @At("RETURN"))
    private void afterAdvance(int budget, CallbackInfoReturnable<Boolean> cir) {
        RegistryBootAcceptance.afterCaptureAdvance(((ViewerParrotRegistry.SnapshotCapture) (Object) this).lastStepCopies(), cir.getReturnValue());
    }
}
