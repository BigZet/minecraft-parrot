package io.github.viewerparrots.speech;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceAssignmentsTest {
	@TempDir
	Path directory;

	@Test
	void usesAllTwelveVoicesBeforeSharingThenKeepsUsageBalanced() {
		VoiceAssignments voices = new VoiceAssignments(VoiceAssignments.DEFAULT_VOICES, new Random(42));
		Set<String> firstTwelve = new HashSet<>();
		for (int i = 0; i < 12; i++) {
			firstTwelve.add(voices.assign(new UUID(0, i)));
		}
		assertEquals(12, firstTwelve.size());
		for (int i = 12; i < 36; i++) {
			voices.assign(new UUID(0, i));
		}
		for (String voice : firstTwelve) {
			assertEquals(3, voices.snapshot().values().stream().filter(voice::equals).count());
		}
	}

	@Test
	void randomlyChoosesAmongUnusedVoicesInsteadOfFollowingPoolOrder() {
		VoiceAssignments voices = new VoiceAssignments(List.of("A", "B", "C"), new Random(0));
		assertEquals("A", voices.assign(new UUID(0, 1)));
		assertEquals("C", voices.assign(new UUID(0, 2)));
		assertEquals("B", voices.assign(new UUID(0, 3)));
	}

	@Test
	void preservesAnIncarnationAndReleasesRemovedBirds() {
		UUID first = new UUID(0, 1);
		UUID second = new UUID(0, 2);
		VoiceAssignments voices = new VoiceAssignments(List.of("A", "B"), new Random(42));
		String firstVoice = voices.assign(first);
		String secondVoice = voices.assign(second);
		assertEquals(firstVoice, voices.assign(first));
		voices.reconcile(Set.of(second));
		assertFalse(voices.snapshot().containsKey(first));
		assertEquals(secondVoice, voices.assign(second));
		assertEquals(firstVoice, voices.assign(new UUID(0, 3)));
	}

	@Test
	void persistsAssignmentsAndRestoresTheirUsage() throws Exception {
		UUID first = new UUID(0, 1);
		Path path = directory.resolve("world/data/viewerparrots-voices.json");
		VoiceAssignments.write(path, Map.of(first, "A"));
		VoiceAssignments voices = new VoiceAssignments(List.of("A", "B"), new Random(42));
		voices.restore(VoiceAssignments.read(path));
		assertEquals("A", voices.assign(first));
		assertEquals("B", voices.assign(new UUID(0, 2)));
		assertThrows(UnsupportedOperationException.class, () -> voices.snapshot().clear());
		VoiceAssignments.write(path, voices.snapshot());
		assertEquals(voices.snapshot(), VoiceAssignments.read(path));
		try (var files = Files.list(path.getParent())) {
			assertEquals(1, files.count());
		}
	}

	@Test
	void missingSidecarStartsEmptyAndMalformedSidecarsFail() throws Exception {
		Path path = directory.resolve("voices.json");
		assertTrue(VoiceAssignments.read(path).isEmpty());
		Files.writeString(path, "{\"not-a-uuid\":\"A\"}");
		assertThrows(IOException.class, () -> VoiceAssignments.read(path));
		Files.writeString(path, "{\"00000000-0000-0000-0000-000000000001\":5}");
		assertThrows(IOException.class, () -> VoiceAssignments.read(path));
	}

	@Test
	void rejectsUnknownRestoredVoicesWithoutReplacingCurrentAssignments() {
		UUID first = new UUID(0, 1);
		VoiceAssignments voices = new VoiceAssignments(List.of("A"), new Random(42));
		voices.assign(first);
		assertThrows(IllegalArgumentException.class, () -> voices.restore(Map.of(first, "unknown")));
		assertEquals(Map.of(first, "A"), voices.snapshot());
		assertThrows(IllegalArgumentException.class, () -> new VoiceAssignments(List.of(), new Random()));
		assertThrows(IllegalArgumentException.class, () -> new VoiceAssignments(List.of("A", "A"), new Random()));
	}
}
