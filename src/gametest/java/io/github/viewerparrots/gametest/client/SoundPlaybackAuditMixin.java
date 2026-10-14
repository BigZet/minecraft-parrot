package io.github.viewerparrots.gametest.client;

import io.github.viewerparrots.gametest.AcceptanceClient;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Confirms one positional instance reaches the actual client sound engine. QA only. */
@Mixin(SoundSystem.class)
public abstract class SoundPlaybackAuditMixin {
    @Shadow public abstract boolean isPlaying(SoundInstance sound);

    @Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;)V", at = @At("RETURN"))
    private void viewerparrots$recordPlayback(SoundInstance sound, CallbackInfo callback) {
        String id = sound.getId().toString();
        if (id.equals("minecraft:entity.zombie.ambient") || id.equals("minecraft:entity.skeleton.ambient")
                || id.equals("minecraft:entity.creeper.primed")) {
            AcceptanceClient.LOGGER.info("QA_PLAY sound={} x={} y={} z={} relative={} volume={} active={}",
                    id, sound.getX(), sound.getY(), sound.getZ(), sound.isRelative(), sound.getVolume(), isPlaying(sound));
        }
    }
}
