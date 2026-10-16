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
	void usesEveryVoiceBeforeSharingThenKeepsUsageBalanced() {
		VoiceAssignments voices = new VoiceAssignments(VoiceAssignments.DEFAULT_VOICES, new Random(42));
		int poolSize = VoiceAssignments.DEFAULT_VOICES.size();
		Set<String> firstAssignments = new HashSet<>();
		for (int i = 0; i < poolSize; i++) {
			firstAssignments.add(voices.assign(new UUID(0, i)));
		}
		assertEquals(Set.copyOf(VoiceAssignments.DEFAULT_VOICES), firstAssignments);
		for (int i = poolSize; i < poolSize * 3; i++) {
			voices.assign(new UUID(0, i));
		}
		for (String voice : firstAssignments) {
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
	void restoresExistingBirdVoiceEvenWhenItIsOutsideTheCurrentProfilePool() {
		UUID first = new UUID(0, 1);
		VoiceAssignments voices = new VoiceAssignments(List.of("A"), new Random(42));
		voices.restore(Map.of(first, "retiredVoice"));
		assertEquals("retiredVoice", voices.assign(first));
		assertEquals("A", voices.assign(new UUID(0, 2)));
		assertThrows(IllegalArgumentException.class, () -> voices.restore(Map.of(first, " ")));
		assertEquals("retiredVoice", voices.assign(first));
		assertThrows(IllegalArgumentException.class, () -> new VoiceAssignments(List.of(), new Random()));
		assertThrows(IllegalArgumentException.class, () -> new VoiceAssignments(List.of("A", "A"), new Random()));
	}

	@Test
	void excludingAVoiceOnlyChangesFutureAssignments() {
		UUID first = new UUID(0, 1);
		VoiceAssignments voices = new VoiceAssignments(List.of("A"), new Random(42));
		assertEquals("A", voices.assign(first));
		voices.updatePool(List.of("B", "C"));
		assertEquals("A", voices.assign(first));
		String second = voices.assign(new UUID(0, 2));
		String third = voices.assign(new UUID(0, 3));
		assertTrue(Set.of("B", "C").contains(second));
		assertTrue(Set.of("B", "C").contains(third));
		assertFalse(second.equals(third));
	}

	@Test
	void aNewlyEnabledVoiceReceivesAssignmentsUntilItsUsageCatchesUp() {
		VoiceAssignments voices = new VoiceAssignments(List.of("A", "B"), new Random(42));
		voices.restore(Map.of(new UUID(0, 1), "A", new UUID(0, 2), "A", new UUID(0, 3), "B"));
		voices.updatePool(List.of("B", "C"));
		assertEquals("C", voices.assign(new UUID(0, 4)));
		assertEquals("A", voices.assign(new UUID(0, 1)));
		assertThrows(IllegalArgumentException.class, () -> voices.updatePool(List.of("B", "B")));
	}

	@Test
	void explicitVoiceSelectionAndRetiredAssignmentsSurviveSaving() throws Exception {
		UUID first = new UUID(0, 1);
		VoiceAssignments voices = new VoiceAssignments(List.of("A", "B"), new Random(42));
		voices.setVoice(first, "B");
		assertEquals("B", voices.assign(first));
		voices.updatePool(List.of("A"));
		Path path = directory.resolve("voices.json");
		VoiceAssignments.write(path, voices.snapshot());
		VoiceAssignments restored = new VoiceAssignments(List.of("A"), new Random(42));
		restored.restore(VoiceAssignments.read(path));
		assertEquals("B", restored.assign(first));
		assertEquals("A", restored.assign(new UUID(0, 2)));
	}
}
