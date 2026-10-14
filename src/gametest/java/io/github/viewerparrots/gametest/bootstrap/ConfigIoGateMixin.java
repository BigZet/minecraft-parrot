package io.github.viewerparrots.gametest.bootstrap;

import io.github.viewerparrots.config.ConfigLoader;
import io.github.viewerparrots.config.ViewerParrotsConfig;
import io.github.viewerparrots.gametest.BootstrapIoGameTests;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.nio.file.Path;

@Mixin(value = ConfigLoader.class, remap = false)
public abstract class ConfigIoGateMixin {
    @Inject(method = "loadOrCreate", at = @At("HEAD"))
    private void beforeRead(Path path, CallbackInfoReturnable<ViewerParrotsConfig> result) { BootstrapIoGameTests.beforeConfigIo(); }
}
