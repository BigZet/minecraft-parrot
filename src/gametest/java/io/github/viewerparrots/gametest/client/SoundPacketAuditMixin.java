package io.github.viewerparrots.gametest.client;

import io.github.viewerparrots.gametest.AcceptanceClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts only the sounds used by the acceptance configuration, once per received packet. */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class SoundPacketAuditMixin {
    @Inject(method = "onPlaySound", at = @At("TAIL"))
    private void viewerparrots$recordSound(PlaySoundS2CPacket packet, CallbackInfo callback) {
        String id = packet.getSound().value().getId().toString();
        if (id.equals("minecraft:entity.zombie.ambient") || id.equals("minecraft:entity.skeleton.ambient")
                || id.equals("minecraft:entity.creeper.primed")) {
            AcceptanceClient.LOGGER.info("QA_SOUND sound={} x={} y={} z={}", id, packet.getX(), packet.getY(), packet.getZ());
        }
    }
}
