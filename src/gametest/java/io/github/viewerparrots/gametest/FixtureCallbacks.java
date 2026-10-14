package io.github.viewerparrots.gametest;

import net.minecraft.server.MinecraftServer;
import net.minecraft.test.TestContext;
import io.github.viewerparrots.bootstrap.ServerContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Test-only setup pump for existing fixture configuration. It preserves fixture
 * tick deadlines while production reload performs its I/O asynchronously. Actual
 * nonblocking callback behavior is verified by the separate bootstrap GameTest.
 */
final class FixtureCallbacks {
    private FixtureCallbacks() {}
    static boolean ready(TestContext test, Runnable retry) {
        ServerContext context = ServerContext.current().orElseThrow();
        if (context.storageHealth() == ServerContext.StorageHealth.LOADING) {
            test.waitAndRun(1, retry);
            return false;
        }
        test.assertTrue(context.allowsMutations(), "Fixture requires a healthy initialized registry");
        return true;
    }
    static <T> T finish(MinecraftServer server, CompletableFuture<T> result) {
        if (!server.isOnThread()) throw new IllegalStateException("fixture setup requires server thread");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!result.isDone()) {
            server.runTasks(() -> result.isDone() || System.nanoTime() >= deadline);
            if (System.nanoTime() >= deadline) throw new IllegalStateException("fixture I/O callback timed out");
            if (!result.isDone()) LockSupport.parkNanos(1_000_000L);
        }
        return result.join();
    }
}
