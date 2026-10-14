package io.github.viewerparrots.network;

/** The login compatibility query has exactly one signed, big-endian 32-bit version. */
public final class CompatibilityProtocol {
	public static final int VERSION = 1;

	private CompatibilityProtocol() { }

	public static Result validate(boolean understood, int payloadLength, int version) {
		if (!understood) return Result.MISSING_MOD;
		if (payloadLength != Integer.BYTES) return Result.INVALID_PAYLOAD;
		return version == VERSION ? Result.ACCEPTED : Result.PROTOCOL_MISMATCH;
	}

	public enum Result {
		ACCEPTED(""),
		MISSING_MOD("[Viewer Parrots] This server requires Fabric, Fabric API and Viewer Parrots for Minecraft 1.21.1."
				+ "\nУстановите Fabric, Fabric API и Viewer Parrots для Minecraft 1.21.1."),
		INVALID_PAYLOAD("[Viewer Parrots] Invalid compatibility response. Install the server's Viewer Parrots version, Fabric and Fabric API for Minecraft 1.21.1."
				+ "\nУстановите такую же версию Viewer Parrots, как на сервере, Fabric и Fabric API для Minecraft 1.21.1."),
		PROTOCOL_MISMATCH("[Viewer Parrots] Incompatible mod protocol. Install the server's Viewer Parrots version, Fabric and Fabric API for Minecraft 1.21.1."
				+ "\nУстановите такую же версию Viewer Parrots, как на сервере, Fabric и Fabric API для Minecraft 1.21.1.");

		private final String message;

		Result(String message) {
			this.message = message;
		}

		public String message() {
			return this.message;
		}
	}
}
