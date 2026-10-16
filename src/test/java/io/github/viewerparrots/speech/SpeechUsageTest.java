package io.github.viewerparrots.speech;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SpeechUsageTest {
    @TempDir Path directory;

    @Test void requestAndWordLimitsRejectWithoutChargingTheRejectedAttempt() throws Exception {
        AtomicLong now = new AtomicLong(1000);
        SpeechUsage usage = new SpeechUsage(directory, now::get);
        assertTrue(usage.record(12, 2, 30));
        assertTrue(usage.record(18, 2, 30));
        assertFalse(usage.record(1, 2, 30));
        assertEquals(new SpeechUsage.Counts(2, 30), usage.counts());
        SpeechUsage restored = new SpeechUsage(directory, now::get);
        assertEquals(usage.counts(), restored.counts());
        assertFalse(restored.record(1, 0, 30));
    }

    @Test void zeroLimitsAreDisabledAndUsageStillAccumulates() throws Exception {
        SpeechUsage usage = new SpeechUsage(directory, () -> 1000L);
        assertTrue(usage.record(200, 0, 0));
        assertTrue(usage.record(200, 0, 0));
        assertEquals(new SpeechUsage.Counts(2, 400), usage.counts());
    }

    @Test void oldUsageExpiresExactlyAtTheRollingHourBoundary() throws Exception {
        AtomicLong now = new AtomicLong(1000);
        SpeechUsage usage = new SpeechUsage(directory, now::get);
        assertTrue(usage.record(30, 1, 30));
        now.set(4599);
        assertFalse(usage.record(1, 1, 30));
        now.set(4600);
        assertTrue(usage.record(10, 1, 30));
        assertEquals(new SpeechUsage.Counts(1, 10), usage.counts());
    }

    @Test void corruptedUsageFileIsPreservedAndCannotResetSpendingSilently() throws Exception {
        Path file = directory.resolve("viewerparrots-usage.json");
        Files.writeString(file, "{ broken");
        assertThrows(IOException.class, () -> new SpeechUsage(directory));
        assertEquals("{ broken", Files.readString(file));
    }
}
