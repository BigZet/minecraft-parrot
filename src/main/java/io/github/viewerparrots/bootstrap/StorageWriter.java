package io.github.viewerparrots.bootstrap;

import io.github.viewerparrots.ViewerParrotsMod;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Единственный выделенный поток файлового I/O (раздел 3.1 ТЗ).
 *
 * <p>На него передаются только неизменяемые снимки и DTO — никаких ссылок на {@code World},
 * {@code Entity} или изменяемый реестр. Результат всегда возвращается на server thread:
 * менять реестр и мир можно только там.
 */
public final class StorageWriter implements AutoCloseable {
	private final MinecraftServer server;
	private final ExecutorService executor;

	public StorageWriter(MinecraftServer server) {
		this.server = Objects.requireNonNull(server, "server");
		this.executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "viewerparrots-storage");
			thread.setDaemon(true);
			return thread;
		});
	}

	/**
	 * Выполняет операцию на потоке записи и возвращает результат на server thread.
	 *
	 * @param task работа с диском; не должна обращаться к миру или реестру
	 */
	public <T> CompletableFuture<T> submit(Callable<T> task) {
		Objects.requireNonNull(task, "task");
		CompletableFuture<T> onWriter = CompletableFuture.supplyAsync(() -> {
			try {
				return task.call();
			} catch (Exception e) {
				throw new java.util.concurrent.CompletionException(e);
			}
		}, this.executor);

		// MinecraftServer сам является Executor: продолжение выполнится в игровом потоке.
		return onWriter.thenApplyAsync(value -> value, this.server);
	}

	/**
	 * Штатная остановка: ждёт завершения записи не дольше отведённого времени.
	 *
	 * @return {@code true} если writer завершил всю работу; {@code false} — повод для ERROR
	 *         и сообщения о том, что сохранение не подтверждено (раздел 8.2 ТЗ)
	 */
	public boolean shutdownAndAwait(int timeoutSeconds) {
		this.executor.shutdown();
		try {
			if (this.executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
				return true;
			}
			ViewerParrotsMod.LOGGER.error(
					"Поток записи не завершился за {} с; сохранение не подтверждено", timeoutSeconds);
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
