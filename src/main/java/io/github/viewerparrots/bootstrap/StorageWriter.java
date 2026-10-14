package io.github.viewerparrots.bootstrap;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.diagnostics.AuditLog;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTask;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Единственный выделенный поток файлового I/O (раздел 3.1 ТЗ).
 *
 * <p>На него передаются только неизменяемые снимки и DTO — никаких ссылок на {@code World},
 * {@code Entity} или изменяемый реестр. Результат всегда возвращается на server thread:
 * менять реестр и мир можно только там.
 */
public final class StorageWriter implements AutoCloseable {
	private final Executor callbackExecutor;
	private final ThreadPoolExecutor executor;
	private final BoundedQueue queue;

	/** Only ThreadPoolExecutor.offer admits work; changing the startup limit keeps the same I/O thread. */
	private static final class BoundedQueue extends LinkedBlockingQueue<Runnable> {
		private volatile int capacity;
		BoundedQueue(int capacity) { setCapacity(capacity); }
		synchronized void setCapacity(int value) {
			if (value < 1 || value > 256) throw new IllegalArgumentException("queueCapacity must be in 1..256");
			this.capacity = value;
		}
		@Override public synchronized boolean offer(Runnable task) {
			return size() < this.capacity && super.offer(task);
		}
		@Override public int remainingCapacity() { return Math.max(0, this.capacity - size()); }
	}

	public StorageWriter(MinecraftServer server) {
		this(server, 256);
	}

	public StorageWriter(MinecraftServer server, int queueCapacity) {
		this(serverCallbacks(server), queueCapacity);
	}

	private static Executor serverCallbacks(MinecraftServer server) {
		Objects.requireNonNull(server, "server");
		// execute() runs inline when Minecraft is stopped. The bounded shutdown drain
		// explicitly runs this queue on the server thread, including its final callbacks.
		return callback -> server.send(new ServerTask(server.getTicks(), callback));
	}

	public StorageWriter(Executor callbackExecutor, int queueCapacity) {
		this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
		this.queue = new BoundedQueue(queueCapacity);
		this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
				this.queue, runnable -> {
			Thread thread = new Thread(runnable, "viewerparrots-storage");
			thread.setDaemon(true);
			return thread;
		}, new ThreadPoolExecutor.AbortPolicy());
	}

	/**
	 * Выполняет операцию на потоке записи и возвращает результат на server thread.
	 *
	 * @param task работа с диском; не должна обращаться к миру или реестру
	 */
	public <T> CompletableFuture<T> submit(Callable<T> task) {
		CompletableFuture<T> returned = new CompletableFuture<>();
		submitForShutdown(task).whenComplete((value, error) -> {
			try {
				this.callbackExecutor.execute(() -> {
					if (error == null) returned.complete(value);
					else returned.completeExceptionally(error);
				});
			} catch (RejectedExecutionException rejected) {
				returned.completeExceptionally(rejected);
			}
		});
		return returned;
	}

	/** Raw I/O completion is used only by the bounded server shutdown drain. */
	public <T> CompletableFuture<T> submitForShutdown(Callable<T> task) {
		Objects.requireNonNull(task, "task");
		CompletableFuture<T> result = new CompletableFuture<>();
		try {
			this.executor.execute(() -> {
				try { result.complete(task.call()); }
				catch (Throwable error) { result.completeExceptionally(error); }
			});
		} catch (RejectedExecutionException rejected) {
			result.completeExceptionally(rejected);
		}
		return result;
	}

	public int pendingTasks() {
		return this.executor.getActiveCount() + this.executor.getQueue().size();
	}

	public int queueDepth() {
		return this.executor.getQueue().size();
	}

	/** Called after asynchronous startup config loading; runtime reload still requires restart. */
	public void configureQueueCapacity(int capacity) { this.queue.setCapacity(capacity); }

	/**
	 * Штатная остановка: ждёт завершения записи не дольше отведённого времени.
	 *
	 * @return {@code true} если writer завершил всю работу; {@code false} — повод для ERROR
	 *         и сообщения о том, что сохранение не подтверждено (раздел 8.2 ТЗ)
	 */
	public boolean shutdownAndAwait(int timeoutSeconds) {
		return shutdownAndAwait(timeoutSeconds, TimeUnit.SECONDS);
	}

	public boolean shutdownAndAwait(long timeout, TimeUnit unit) {
		long started = AuditLog.started();
		this.executor.shutdown();
		try {
			if (this.executor.awaitTermination(Math.max(0L, timeout), unit)) {
				return true;
			}
			AuditLog.error("writer_shutdown", null, "FAILED", "SHUTDOWN_TIMEOUT", started,
					java.util.Map.of("pendingTasks", pendingTasks(), "queueDepth", queueDepth()), null);
			this.executor.shutdownNow();
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			this.executor.shutdownNow();
			return false;
		}
	}

	@Override
	public void close() {
		shutdownAndAwait(10);
	}
}
