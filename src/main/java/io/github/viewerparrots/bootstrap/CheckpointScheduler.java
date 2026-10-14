package io.github.viewerparrots.bootstrap;

import io.github.viewerparrots.diagnostics.AuditLog;
import io.github.viewerparrots.domain.Tombstone;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.storage.ParrotRepository;
import io.github.viewerparrots.registry.ViewerParrotRegistry;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Периодический durable checkpoint (раздел 8.2 ТЗ): каждые
 * {@code storage.checkpointIntervalTicks} при наличии изменений, а также по команде
 * {@code save} и при штатной остановке.
 *
 * <p>Снимок собирается из неизменяемых записей на server thread и только потом уходит
 * на writer: передавать туда живой реестр нельзя.
 *
 * <p>Одновременно выполняется не более одного checkpoint: иначе два снимка состязались
 * бы за один и тот же файл.
 */
public final class CheckpointScheduler {
	private static long lastCheckpointTick;
	private static final int SNAPSHOT_ITEMS_PER_TICK = 256;
	private static CheckpointJob inFlight;

	private static final class CheckpointJob {
		final ServerContext owner;
		final long startedNanos = AuditLog.started();
		final ViewerParrotRegistry.SnapshotCapture capture;
		final CompletableFuture<ParrotRepository.CheckpointResult> future = new CompletableFuture<>();
		boolean submitted;
		long lastAdvancedTick = Long.MIN_VALUE;
		CheckpointJob(ServerContext owner) {
			this.owner = owner;
			this.capture = owner.registry().beginSnapshot();
		}
	}

	private CheckpointScheduler() {
	}

	public static boolean isCheckpointPending() { return inFlight != null; }

	public static void tick(ServerContext context) {
		if (inFlight != null) {
			if (inFlight.owner == context) advance(inFlight, true);
			return;
		}
		if (!context.allowsMutations()) {
			return;
		}

		// Без изменений писать нечего: раздел 8.2 говорит «каждые 30 секунд при dirty».
		if (!context.registry().isDirty()) {
			return;
		}

		long interval = context.config().storage().checkpointIntervalTicks();
		long now = context.clock().currentTick();
		if (now - lastCheckpointTick < interval) {
			return;
		}
		lastCheckpointTick = now;
		request(context);
	}

	/** Явный checkpoint: команда {@code save} и остановка сервера. */
	public static CompletableFuture<ParrotRepository.CheckpointResult> request(ServerContext context) {
		if (!context.acceptingCommands()) {
			return CompletableFuture.failedFuture(new IllegalStateException("server context is stopping"));
		}
		if (inFlight != null) {
			return CompletableFuture.failedFuture(
					new IllegalStateException("checkpoint уже выполняется"));
		}
		CheckpointJob job = new CheckpointJob(context);
		inFlight = job;
		advance(job, true);
		return job.future;
	}

	private static void advance(CheckpointJob job, boolean enforceTickBudget) {
		if (job.submitted || inFlight != job) return;
		ServerContext context = job.owner;
		if (!context.allowsMutations()) {
			inFlight = null;
			job.future.completeExceptionally(new IllegalStateException("storage is not ready"));
			return;
		}
		long tick = context.clock().currentTick();
		if (enforceTickBudget && job.lastAdvancedTick == tick) return;
		job.lastAdvancedTick = tick;
		if (!job.capture.advance(SNAPSHOT_ITEMS_PER_TICK)) return;
		ViewerParrotRegistry.Snapshot snapshot = job.capture.snapshot();
		var repository = context.repository();
		job.submitted = true;
		context.writer()
				.submit(() -> repository.checkpointAtSequence(snapshot.records(), snapshot.tombstones(),
						snapshot.streamer(), snapshot.appliedSequence()))
				.whenComplete((result, error) -> {
					if (inFlight != job || ServerContext.current().orElse(null) != context) return;
					inFlight = null;
					if (error != null) {
						context.degrade("checkpoint не выполнен: " + error.getMessage());
						AuditLog.error("checkpoint", null, "FAILED", "STORAGE_UNAVAILABLE", job.startedNanos, java.util.Map.of(), error);
						job.future.completeExceptionally(error);
						return;
					}
					// Снимок записан и отражает реестр на момент сборки копий.
					context.registry().markClean(snapshot.epoch());
					if (!result.warnings().isEmpty()) {
						AuditLog.warn("checkpoint_warning", null, "SUCCEEDED", "CHECKPOINT_WARNING", job.startedNanos, java.util.Map.of("warnings", result.warnings()), null);
					}
					AuditLog.info("checkpoint", null, "SUCCEEDED", null, job.startedNanos, java.util.Map.of("sequence", result.checkpointSequence(), "records", snapshot.records().size(), "atomic", result.atomicReplace()), null);
					job.future.complete(result);
				});
	}

	/** Flush pending callbacks before capturing the final registry while the server is stopping. */
	public static boolean saveOnShutdown(ServerContext context) {
		long started = AuditLog.started();
		context.parrotService().beginShutdown();
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(
				context.config().storage().shutdownTimeoutSeconds());
		try {
			while (context.parrotService().operationCount() > 0 || context.streamerService().operationPending()
					|| context.writer().pendingTasks() > 0 || context.server().getTaskCount() > 0 || inFlight != null) {
				context.server().runTasks(() -> context.server().getTaskCount() == 0 || System.nanoTime() >= deadline);
				context.parrotService().tick();
				if (inFlight != null && inFlight.owner == context) advance(inFlight, false);
				if (System.nanoTime() >= deadline) {
					throw new java.util.concurrent.TimeoutException("pending storage callbacks did not drain");
				}
				java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
			}
			if (!context.allowsMutations()) return false;
			List<ViewerParrotRecord> records = context.registry().allSorted();
			List<Tombstone> tombstones = context.registry().tombstones();
			UUID streamer = context.registry().streamerPlayerUuid().orElse(null);
			long sequence = context.registry().appliedJournalSequence();
			ParrotRepository.CheckpointResult result = context.writer().submitForShutdown(() -> context.repository()
					.checkpointAtSequence(records, tombstones, streamer, sequence))
					.get(Math.max(1L, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
			if (!result.warnings().isEmpty()) {
				AuditLog.warn("shutdown_checkpoint_warning", null, "SUCCEEDED", "CHECKPOINT_WARNING", started, java.util.Map.of("warnings", result.warnings()), null);
			}
			AuditLog.info("shutdown_checkpoint", null, "SUCCEEDED", null, started, java.util.Map.of("sequence", result.checkpointSequence(), "records", records.size(), "atomic", result.atomicReplace()), null);
			return true;
		} catch (Exception failure) {
			if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
			AuditLog.error("shutdown_checkpoint", null, "FAILED", "STORAGE_UNAVAILABLE", started, java.util.Map.of(), failure);
			return false;
		}
	}

	/** Счётчики сбрасываются вместе с контекстом: другой мир — другая история. */
	public static void reset() {
		lastCheckpointTick = 0L;
		CheckpointJob previous = inFlight;
		inFlight = null;
		if (previous != null) previous.future.completeExceptionally(new IllegalStateException("server context stopped"));
	}
}
