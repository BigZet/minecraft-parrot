package io.github.viewerparrots.client;

import io.github.viewerparrots.network.ClientCompatibility;
import io.github.viewerparrots.network.CompatibilityProtocol;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;

import java.util.concurrent.CompletableFuture;

/** Client-only responder; the server decides whether the protocol is compatible. */
public final class ClientCompatibilityReceiver {
	private ClientCompatibilityReceiver() { }

	public static void register() {
		ClientLoginNetworking.registerGlobalReceiver(ClientCompatibility.CHANNEL,
				(client, handler, payload, callbacks) -> {
					if (payload.readableBytes() != Integer.BYTES) return CompletableFuture.completedFuture(null);
					// Reply with our own version even if the query advertises another one;
					// the server can then return the specific protocol mismatch reason.
					return CompletableFuture.completedFuture(PacketByteBufs.create().writeInt(CompatibilityProtocol.VERSION));
				});
	}
}
