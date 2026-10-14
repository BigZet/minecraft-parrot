package io.github.viewerparrots.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilityProtocolTest {

	@Test
	void matchingProtocolIsAccepted() {
		assertEquals(CompatibilityProtocol.Result.ACCEPTED,
				CompatibilityProtocol.validate(true, Integer.BYTES, CompatibilityProtocol.VERSION));
	}

	@Test
	void unhandledQueryIsMissingModEvenIfPayloadLooksValid() {
		assertEquals(CompatibilityProtocol.Result.MISSING_MOD,
				CompatibilityProtocol.validate(false, Integer.BYTES, CompatibilityProtocol.VERSION));
	}

	@Test
	void emptyTruncatedOrTrailingPayloadIsRejected() {
		for (int size : new int[]{0, 1, 2, 3, 5, 1024}) {
			assertEquals(CompatibilityProtocol.Result.INVALID_PAYLOAD,
					CompatibilityProtocol.validate(true, size, CompatibilityProtocol.VERSION));
		}
	}

	@Test
	void differentOrInvalidVersionIsRejected() {
		for (int version : new int[]{Integer.MIN_VALUE, -1, 0, CompatibilityProtocol.VERSION + 1, Integer.MAX_VALUE}) {
			assertEquals(CompatibilityProtocol.Result.PROTOCOL_MISMATCH,
					CompatibilityProtocol.validate(true, Integer.BYTES, version));
		}
	}

	@Test
	void rejectionCanBeReadWithoutClientTranslations() {
		for (var result : CompatibilityProtocol.Result.values()) {
			if (result == CompatibilityProtocol.Result.ACCEPTED) continue;
			assertTrue(result.message().contains("Viewer Parrots"));
			assertTrue(result.message().contains("Fabric"));
			assertTrue(result.message().contains("Minecraft 1.21.1"));
			assertTrue(result.message().contains("Установите"));
		}
	}
}
