package io.github.viewerparrots.gametest.profile;

import io.github.viewerparrots.domain.Tombstone;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.gametest.RegistryBootAcceptance;
import io.github.viewerparrots.registry.ViewerParrotRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.UUID;

@Mixin(value = ViewerParrotRegistry.class, remap = false)
public abstract class RegistryApplyProfileMixin {
    @Inject(method = "replaceAll", at = @At("RETURN"))
    private void afterApply(List<ViewerParrotRecord> records, List<Tombstone> tombstones, UUID streamer, CallbackInfo ci) {
        RegistryBootAcceptance.afterRegistryApply(records.size());
    }
}
