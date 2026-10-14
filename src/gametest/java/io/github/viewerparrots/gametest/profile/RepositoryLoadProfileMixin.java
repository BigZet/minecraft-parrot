package io.github.viewerparrots.gametest.profile;

import io.github.viewerparrots.gametest.RegistryBootAcceptance;
import io.github.viewerparrots.storage.JsonParrotRepository;
import io.github.viewerparrots.storage.ParrotRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = JsonParrotRepository.class, remap = false)
public abstract class RepositoryLoadProfileMixin {
    @Inject(method = "load", at = @At("HEAD"))
    private void beforeRead(CallbackInfoReturnable<ParrotRepository.LoadResult> ci) {
        RegistryBootAcceptance.beforeRepositoryLoad();
    }
}
