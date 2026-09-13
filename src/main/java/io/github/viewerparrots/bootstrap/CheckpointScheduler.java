package io.github.viewerparrots.bootstrap;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.domain.Tombstone;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.storage.ParrotRepository;

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
	private static boolean inFlight;

	private CheckpointScheduler() {
	}

	public static void tick(ServerContext context) {
		if (!context.allowsMutations() || inFlight) {
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
		if (inFlight) {
			return CompletableFuture.failedFuture(
					new IllegalStateException("checkpoint уже выполняется"));
		}
		inFlight = true;

		// Неизменяемые копии: writer не должен видеть живой реестр.
		List<ViewerParrotRecord> records = context.registry().allSorted();
		List<Tombstone> tombstones = context.registry().tombstones();
		UUID streamer = context.registry().streamerPlayerUuid().orElse(null);

		return context.writer()
				.submit(() -> context.repository().checkpoint(records, tombstones, streamer))
				.whenComplete((result, error) -> {
					inFlight = false;
					if (error != null) {
						context.degrade("checkpoint не выполнен: " + error.getMessage());
						ViewerParrotsMod.LOGGER.error("Checkpoint не выполнен", error);
						return;
					}
					for (String warning : result.warnings()) {
						ViewerParrotsMod.LOGGER.warn("Checkpoint: {}", warning);
					}
					ViewerParrotsMod.LOGGER.info("Checkpoint {} записан, записей {}, атомарно {}",
							result.checkpointSequence(), records.size(), result.atomicReplace());
				});
	}

	/** Счётчики сбрасываются вместе с контекстом: другой мир — другая история. */
	public static void reset() {
		lastCheckpointTick = 0L;
		inFlight = false;
	}
}
