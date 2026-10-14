package io.github.viewerparrots.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StorageWriterTest {
    @Test
    void hardQueueLimitCannotBeRaisedThroughPublicConstructionOrReconfiguration() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new StorageWriter(Runnable::run, 257)),
                () -> assertThrows(IllegalArgumentException.class, () -> new StorageWriter(Runnable::run, 1024)));
        try (StorageWriter writer = new StorageWriter(Runnable::run, 1)) {
            assertDoesNotThrow(() -> writer.configureQueueCapacity(256));
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> writer.configureQueueCapacity(257)),
                    () -> assertThrows(IllegalArgumentException.class, () -> writer.configureQueueCapacity(1024)));
            assertDoesNotThrow(() -> writer.configureQueueCapacity(1));
        }
    }

    @Test
    void startupCapacityChangeKeepsTheSameWriterAndBoundsLaterAdmission() throws Exception {
        StorageWriter writer = new StorageWriter(Runnable::run, 4);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            Thread original = writer.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
            writer.configureQueueCapacity(1);
            CompletableFuture<Thread> active = writer.submit(() -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout");
                return Thread.currentThread();
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var queued = writer.submit(Thread::currentThread);
            var rejected = writer.submit(() -> 3);
            var error = assertThrows(java.util.concurrent.ExecutionException.class, () -> rejected.get(5, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, error.getCause());
            assertEquals(1, writer.queueDepth());
            release.countDown();
            assertSame(original, active.get(5, TimeUnit.SECONDS));
            assertSame(original, queued.get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); writer.close(); }
    }

    @Test
    void failureAndSuccessCallbacksUseTheServerExecutor() throws Exception {
        var callbacks = Executors.newSingleThreadExecutor(task -> new Thread(task, "test-server"));
        StorageWriter writer = new StorageWriter(callbacks, 4);
        try {
            CountDownLatch callbacksMayRun = new CountDownLatch(1);
            callbacks.execute(() -> {
                try { callbacksMayRun.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            AtomicReference<String> successThread = new AtomicReference<>();
            AtomicReference<String> failureThread = new AtomicReference<>();
            var succeeded = writer.submit(() -> 42).thenAccept(value -> successThread.set(Thread.currentThread().getName()));
            var failed = writer.submit(() -> { throw new IOException("disk unavailable"); })
                    .handle((value, error) -> { failureThread.set(Thread.currentThread().getName()); return null; });
            callbacksMayRun.countDown();
            succeeded.get(5, TimeUnit.SECONDS);
            failed.get(5, TimeUnit.SECONDS);
            assertEquals("test-server", successThread.get());
            assertEquals("test-server", failureThread.get());
        } finally {
            writer.close();
            callbacks.shutdownNow();
        }
    }

    @Test
    void fullQueueRejectsWithoutGrowingOrLosingQueuedTasks() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StorageWriter writer = new StorageWriter(Runnable::run, 1);
        try {
            CompletableFuture<Integer> first = writer.submit(() -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout");
                return 1;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Integer> queued = writer.submit(() -> 2);
            var rejected = writer.submit(() -> 3);
            var error = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> rejected.get(5, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, error.getCause());
            assertEquals(1, writer.queueDepth());
            release.countDown();
            assertEquals(1, first.get(5, TimeUnit.SECONDS));
            assertEquals(2, queued.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            writer.close();
        }
    }
}
