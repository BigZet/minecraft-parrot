package io.github.viewerparrots.gametest.bootstrap;

import io.github.viewerparrots.gametest.BootstrapIoGameTests;
import io.github.viewerparrots.storage.JsonParrotRepository;
import io.github.viewerparrots.storage.ParrotRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = JsonParrotRepository.class, remap = false)
public abstract class RegistryIoGateMixin {
    @Inject(method = "load", at = @At("HEAD"))
    private void beforeRead(CallbackInfoReturnable<ParrotRepository.LoadResult> result) { BootstrapIoGameTests.beforeRegistryIo(); }
}
