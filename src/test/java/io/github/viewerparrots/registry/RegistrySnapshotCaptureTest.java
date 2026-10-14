package io.github.viewerparrots.registry;

import io.github.viewerparrots.domain.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RegistrySnapshotCaptureTest {
    private static ViewerParrotRecord record(int index) {
        return ViewerParrotRecord.created(new Viewer(ViewerId.of("capture-" + index),
                DisplayName.of("Viewer " + index), Instant.EPOCH), new UUID(1, index),
                new UUID(2, index), ParrotVariant.BLUE, ParrotTrait.NORMAL, null, Instant.EPOCH);
    }

    @Test
    void largeSnapshotCopiesAtMostTheRequestedBudgetAndFreezesItsContents() {
        ViewerParrotRegistry registry = new ViewerParrotRegistry();
        for (int i = 0; i < 10000; i++) registry.put(record(i));
        for (int i = 0; i < 1000; i++) registry.addTombstone(Tombstone.of(record(10000 + i), Instant.EPOCH));
        var capture = registry.beginSnapshot();
        int steps = 0;
        do {
            steps++;
            capture.advance(256);
            assertTrue(capture.lastStepCopies() <= 256);
        } while (!capture.complete());
        assertEquals(43, steps);
        var snapshot = capture.snapshot();
        assertEquals(registry.allSorted(), snapshot.records());
        assertEquals(new HashSet<>(registry.tombstones()), new HashSet<>(snapshot.tombstones()));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.records().clear());
        registry.remove(ViewerId.of("capture-0"), Instant.EPOCH);
        assertEquals(10000, snapshot.records().size());
        assertEquals(1000, snapshot.tombstones().size());
        registry.markClean(snapshot.epoch());
        assertTrue(registry.isDirty(), "A later mutation must remain dirty");
    }

    @Test
    void mutationBetweenPortionsPreservesTheOriginalGeneration() {
        ViewerParrotRegistry registry = new ViewerParrotRegistry();
        for (int i = 0; i < 5; i++) registry.put(record(i));
        var capture = registry.beginSnapshot();
        List<ViewerParrotRecord> original = registry.allSorted();
        long originalEpoch = registry.changeEpoch();
        assertFalse(capture.advance(2));
        registry.remove(ViewerId.of("capture-0"), Instant.EPOCH);
        registry.setStreamerPlayerUuid(new UUID(3, 4));
        registry.journalApplied(7);
        while (!capture.advance(2)) assertTrue(capture.lastStepCopies() <= 2);
        var snapshot = capture.snapshot();
        assertEquals(original, snapshot.records());
        assertTrue(snapshot.tombstones().isEmpty());
        assertNull(snapshot.streamer());
        assertEquals(0, snapshot.appliedSequence());
        assertEquals(originalEpoch, snapshot.epoch());
    }

    @Test
    void journalSequenceChangeKeepsTheCapturedSequence() {
        ViewerParrotRegistry registry = new ViewerParrotRegistry();
        registry.put(record(0)); registry.put(record(1));
        var capture = registry.beginSnapshot();
        assertThrows(IllegalStateException.class, capture::snapshot);
        assertFalse(capture.advance(1));
        registry.journalApplied(9);
        assertTrue(capture.advance(1));
        assertEquals(0, capture.snapshot().appliedSequence());
        assertThrows(IllegalArgumentException.class, () -> registry.beginSnapshot().advance(0));
    }

    @Test
    void continuousMutationsCannotStarveATenThousandRecordSnapshot() {
        ViewerParrotRegistry registry = new ViewerParrotRegistry();
        for (int i = 0; i < 10000; i++) registry.put(record(i));
        List<ViewerParrotRecord> original = registry.allSorted();
        var capture = registry.beginSnapshot();
        for (int portion = 0; portion < 40; portion++) {
            var previous = registry.find(ViewerId.of("capture-0")).orElseThrow();
            registry.replace(previous.withDisplayName(DisplayName.of("Changed " + portion), Instant.EPOCH));
            capture.advance(256);
        }
        assertTrue(capture.complete(), "Checkpoint must progress under ordinary concurrent mutations");
        assertEquals(original, capture.snapshot().records());
        registry.markClean(capture.snapshot().epoch());
        assertTrue(registry.isDirty());
    }

    @Test
    void replacingRegistryWithEmptyDoesNotInvalidateAnExistingFrozenSnapshot() {
        ViewerParrotRegistry registry = new ViewerParrotRegistry();
        registry.put(record(0)); registry.put(record(1));
        var original = registry.allSorted();
        long before = registry.changeEpoch();
        var capture = registry.beginSnapshot();
        assertFalse(capture.advance(1));
        registry.replaceAll(List.of(), List.of(), null);
        assertTrue(capture.advance(1));
        assertEquals(original, capture.snapshot().records());
        assertTrue(registry.changeEpoch() > before);
    }

    @Test
    void mixedInsertReplaceAndRemovalSnapshotsAgreeWithCanonicalIndexes() {
        ViewerParrotRegistry registry = new ViewerParrotRegistry();
        Random random = new Random(17);
        for (int operation = 0; operation < 2000; operation++) {
            int number = random.nextInt(300);
            ViewerId id = ViewerId.of("capture-" + number);
            var existing = registry.find(id);
            if (existing.isEmpty()) registry.put(record(number));
            else if (random.nextBoolean()) registry.remove(id, Instant.EPOCH);
            else registry.replace(existing.orElseThrow().withDisplayName(DisplayName.of("Changed " + operation), Instant.EPOCH));
            if (operation % 20 == 0) {
                var capture = registry.beginSnapshot();
                while (!capture.advance(7)) assertTrue(capture.lastStepCopies() <= 7);
                assertEquals(registry.allSorted(), capture.snapshot().records());
                assertEquals(new HashSet<>(registry.tombstones()), new HashSet<>(capture.snapshot().tombstones()));
            }
        }
    }
}
