package io.github.viewerparrots.network;

import io.github.viewerparrots.ViewerParrotsMod;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Rejects incompatible clients in LOGIN, before custom entity registries are sent. */
public final class ClientCompatibility {
	public static final Identifier CHANNEL = ViewerParrotsMod.id("compatibility");

	private ClientCompatibility() { }

	public static void register() {
		ServerLoginNetworking.registerGlobalReceiver(CHANNEL,
				(server, handler, understood, payload, synchronizer, sender) -> {
					// Fabric supplies an empty buffer for an unhandled vanilla query. Never
					// read it, and reject malformed lengths before decoding the fixed int.
					int length = understood ? payload.readableBytes() : 0;
					int version = understood && length == Integer.BYTES ? payload.readInt() : 0;
					var result = CompatibilityProtocol.validate(understood, length, version);
					if (result != CompatibilityProtocol.Result.ACCEPTED) {
						// Reception is on Netty. Keep LOGIN blocked until the server thread
						// has disconnected this connection with a vanilla-readable literal.
						synchronizer.waitFor(server.submit(() -> handler.disconnect(Text.literal(result.message()))));
					}
				});
		ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) ->
				sender.sendPacket(CHANNEL, PacketByteBufs.create().writeInt(CompatibilityProtocol.VERSION)));
	}
}
