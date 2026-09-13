package io.github.viewerparrots.lifecycle;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.domain.WorldLocation;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Ограниченный поиск потерянного экземпляра (раздел 9.3 ТЗ).
 *
 * <p>Сначала проверяется loaded index во всех измерениях. Если сущности там нет, разрешена
 * временная загрузка ровно одного чанка последней известной позиции — без генерации
 * территории: чанк, которого нет на диске, создавать нельзя.
 *
 * <p>Ограничения жёсткие и сознательные: не более {@value #MAX_CONCURRENT_JOBS} заданий
 * глобально, один чанк на задание, дедлайн из конфигурации. Ticket освобождается при любом
 * исходе — иначе чанк остался бы загруженным навсегда.
 *
 * <p>Итог «не найдено» — локальное свидетельство, а не доказательство отсутствия во всём
 * мире. Поэтому запись переводится в {@code MISSING} с причиной
 * {@code NOT_FOUND_IN_KNOWN_LOCATION}, а не удаляется.
 */
public final class EntityRecoveryService {
	public static final int MAX_CONCURRENT_JOBS = 2;
	public static final String NOT_FOUND_REASON = "NOT_FOUND_IN_KNOWN_LOCATION";

	/** Собственный тип тикета с радиусом 0: держим один чанк, а не область вокруг. */
	private static final ChunkTicketType<ChunkPos> RECOVERY_TICKET =
			ChunkTicketType.create("viewerparrots_recovery", Comparator.comparingLong(ChunkPos::toLong));

	private final ServerContext context;
	private final Map<ViewerId, Job> jobs = new LinkedHashMap<>();

	public EntityRecoveryService(ServerContext context) {
		this.context = Objects.requireNonNull(context, "context");
	}

	/** Исход попытки запуска поиска. */
	public enum StartResult {
		/** Сущность нашлась сразу в загруженном мире. */
		FOUND_IMMEDIATELY,
		/** Задание поставлено, результат придёт в пределах дедлайна. */
		STARTED,
		/** Искать нечего: запись не ожидает физического экземпляра. */
		NOTHING_TO_FIND,
		/** Нет позиции последнего пребывания или её измерение неизвестно. */
		NO_KNOWN_LOCATION,
		/** Все слоты заняты: раздел 9.3 разрешает максимум два задания. */
		BUSY,
		/** Для этого зрителя задание уже выполняется. */
		ALREADY_RUNNING
	}

	private static final class Job {
		private final ServerWorld world;
		private final ChunkPos chunkPos;
		private final UUID expectedEntityUuid;
		private final long deadlineTick;

		private Job(ServerWorld world, ChunkPos chunkPos, UUID expectedEntityUuid, long deadlineTick) {
			this.world = world;
			this.chunkPos = chunkPos;
			this.expectedEntityUuid = expectedEntityUuid;
			this.deadlineTick = deadlineTick;
		}
	}

	public StartResult start(ViewerId viewerId) {
		Objects.requireNonNull(viewerId, "viewerId");

		if (this.jobs.containsKey(viewerId)) {
			return StartResult.ALREADY_RUNNING;
		}

		Optional<ViewerParrotRecord> maybeRecord = this.context.registry().find(viewerId);
		if (maybeRecord.isEmpty()) {
			return StartResult.NOTHING_TO_FIND;
		}
		ViewerParrotRecord record = maybeRecord.get();

		Optional<UUID> expected = record.expectedEntityUuid();
		if (expected.isEmpty()) {
			return StartResult.NOTHING_TO_FIND;
		}

		if (bindIfLoadedAnywhere(viewerId, expected.get())) {
			return StartResult.FOUND_IMMEDIATELY;
		}

		Optional<WorldLocation> location = record.location();
		if (location.isEmpty()) {
			markMissing(viewerId, "нет последней известной позиции");
			return StartResult.NO_KNOWN_LOCATION;
		}

		Optional<ServerWorld> world = resolveWorld(location.get().dimension());
		if (world.isEmpty()) {
			// Удалённое или неизвестное измерение: Overworld подставлять нельзя.
			markMissing(viewerId, "измерение " + location.get().dimension() + " недоступно");
			return StartResult.NO_KNOWN_LOCATION;
		}

		int maxJobs = Math.min(MAX_CONCURRENT_JOBS, this.context.config().recovery().maxConcurrentJobs());
		if (this.jobs.size() >= maxJobs) {
			return StartResult.BUSY;
		}

		ChunkPos chunkPos = new ChunkPos(
				(int) Math.floor(location.get().x()) >> 4,
				(int) Math.floor(location.get().z()) >> 4);

		long deadline = this.context.clock().currentTick()
				+ this.context.config().recovery().timeoutTicks();

		// Радиус 0: держим ровно один чанк. Генерация территории при этом не запускается —
		// отсутствующий на диске чанк просто не станет доступным до дедлайна.
		world.get().getChunkManager().addTicket(RECOVERY_TICKET, chunkPos, 0, chunkPos);
		this.jobs.put(viewerId, new Job(world.get(), chunkPos, expected.get(), deadline));

		ViewerParrotsMod.LOGGER.debug("recovery start viewerId={} dimension={} chunk={} deadline={}",
				viewerId, location.get().dimension(), chunkPos, deadline);
		return StartResult.STARTED;
	}

	/** Вызывается каждый тик сервера: проверяет загрузку и дедлайны. */
	public void tick() {
		if (this.jobs.isEmpty()) {
			return;
		}

		long now = this.context.clock().currentTick();
		var iterator = this.jobs.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<ViewerId, Job> entry = iterator.next();
			ViewerId viewerId = entry.getKey();
			Job job = entry.getValue();

			if (bindIfLoadedAnywhere(viewerId, job.expectedEntityUuid)) {
				release(job);
				iterator.remove();
				ViewerParrotsMod.LOGGER.debug("recovery found viewerId={}", viewerId);
				continue;
			}

			if (now >= job.deadlineTick) {
				release(job);
				iterator.remove();
				// Поиск завершён без обнаружения: это и есть переход в MISSING.
				markMissing(viewerId, NOT_FOUND_REASON);
				ViewerParrotsMod.LOGGER.warn("recovery exhausted viewerId={} reason={}",
						viewerId, NOT_FOUND_REASON);
			}
		}
	}

	/** Освобождает все тикеты: вызывается при остановке сервера. */
	public void cancelAll() {
		for (Job job : this.jobs.values()) {
			release(job);
		}
		this.jobs.clear();
	}

	public boolean isRunning(ViewerId viewerId) {
		return this.jobs.containsKey(viewerId);
	}

	public int activeJobs() {
		return this.jobs.size();
	}

	private boolean bindIfLoadedAnywhere(ViewerId viewerId, UUID expectedEntityUuid) {
		if (this.context.loadedIndex().isLoaded(viewerId)) {
			return true;
		}
		for (ServerWorld world : this.context.server().getWorlds()) {
			if (world.getEntity(expectedEntityUuid) instanceof ViewerParrotEntity found) {
				this.context.loadedIndex().bind(viewerId, found);
				return true;
			}
		}
		return false;
	}

	private void markMissing(ViewerId viewerId, String reason) {
		this.context.registry().find(viewerId).ifPresent(record -> {
			this.context.registry().replace(record.asMissing(this.context.clock().now()));
			ViewerParrotsMod.LOGGER.warn("missing viewerId={} generation={} reason={}",
					viewerId, record.generation(), reason);
		});
	}

	private void release(Job job) {
		// Ticket освобождается при любом исходе, иначе чанк останется загруженным навсегда.
		job.world.getChunkManager().removeTicket(RECOVERY_TICKET, job.chunkPos, 0, job.chunkPos);
	}

	private Optional<ServerWorld> resolveWorld(String dimensionId) {
		Identifier identifier = Identifier.tryParse(dimensionId);
		if (identifier == null) {
			return Optional.empty();
		}
		RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, identifier);
		return Optional.ofNullable(this.context.server().getWorld(key));
	}
}
