package io.github.viewerparrots.roulette;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouletteQueueTest {
    @Test void keepsOneActiveAndRejectsBothActiveAndWaitingDuplicates() {
        var queue = new RouletteQueue<Integer>();
        assertTrue(queue.offer("a", 1, 2));
        assertFalse(queue.offer("a", 9, 2));
        assertEquals(1, queue.startNext().orElseThrow().value());
        assertTrue(queue.startNext().isEmpty());
        assertFalse(queue.offer("a", 9, 2));
        assertTrue(queue.offer("b", 2, 2));
        assertTrue(queue.offer("c", 3, 2));
        assertFalse(queue.offer("d", 4, 2));
        queue.complete();
        assertEquals(2, queue.startNext().orElseThrow().value());
        queue.complete();
        assertEquals(3, queue.startNext().orElseThrow().value());
    }
    @Test void cancellationAndWorldResetReleaseTheirSlots() {
        var queue = new RouletteQueue<Integer>();
        queue.offer("a", 1, 2);
        queue.offer("b", 2, 2);
        queue.startNext();
        assertEquals(1, queue.remove("a").orElseThrow().value());
        assertEquals(2, queue.startNext().orElseThrow().value());
        queue.offer("c", 3, 2);
        assertEquals(3, queue.remove("c").orElseThrow().value());
        assertFalse(queue.contains("c"));
        assertEquals(2, queue.clear().getFirst().value());
        assertEquals(0, queue.waitingCount());
        assertFalse(queue.contains("b"));
        assertTrue(queue.offer("b", 4, 1));
    }
}
