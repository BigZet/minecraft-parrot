package io.github.viewerparrots.lifecycle;

import io.github.viewerparrots.diagnostics.AuditLog;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.domain.WorldLocation;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import io.github.viewerparrots.entity.VanillaVariantMapper;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.api.event.ParrotEvent;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtString;
import net.minecraft.nbt.scanner.NbtScanQuery;
import net.minecraft.nbt.scanner.SelectiveNbtCollector;
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
import java.util.concurrent.CompletableFuture;

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
	private static final ChunkTicketType<UUID> RECOVERY_TICKET =
			ChunkTicketType.create("viewerparrots_recovery", Comparator.<UUID>naturalOrder());

	private final ServerContext context;
	private final Map<ViewerId, Job> jobs = new LinkedHashMap<>();
	private final Map<ViewerId, ErrorCode> lastFailures = new LinkedHashMap<>();

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
		private final UUID ticketKey = UUID.randomUUID();
		private final long deadlineTick;
		private final UUID incarnationId;
		private final long generation;
		private final long startedNanos = AuditLog.started();
		private final AuditLog.Context auditContext;
		private final SelectiveNbtCollector collector;
		private final CompletableFuture<Void> scan;
		private boolean ticketAdded;

		private Job(ServerWorld world, ChunkPos chunkPos, ViewerParrotRecord record, long deadlineTick) {
			this.world = world;
			this.chunkPos = chunkPos;
			this.expectedEntityUuid = record.expectedEntityUuid().orElseThrow();
			this.deadlineTick = deadlineTick;
			this.incarnationId = record.incarnationId();
			this.generation = record.generation();
			this.auditContext = AuditLog.Context.of(null, record, "RECOVERY");
			this.collector = new SelectiveNbtCollector(new NbtScanQuery(NbtString.TYPE, "Status"));
			this.scan = world.getChunkManager().getChunkIoWorker().scanChunk(chunkPos, this.collector);
		}
	}

	public StartResult start(ViewerId viewerId) {
		Objects.requireNonNull(viewerId, "viewerId");
		if (this.context.shoulderService().contains(viewerId)) return StartResult.FOUND_IMMEDIATELY;

		if (this.jobs.containsKey(viewerId)) {
			return StartResult.ALREADY_RUNNING;
		}
		this.lastFailures.remove(viewerId);

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
			this.lastFailures.put(viewerId, ErrorCode.DIMENSION_UNAVAILABLE);
			this.context.markUnresolved(viewerId);
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
		Job job = new Job(world.get(), chunkPos, record, deadline);
		this.jobs.put(viewerId, job);
		AuditLog.debug("recovery_started", job.auditContext, "STARTED", null, job.startedNanos,
				Map.of("dimension", location.get().dimension(), "chunkX", chunkPos.x, "chunkZ", chunkPos.z, "deadlineTick", deadline), null);
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
			boolean stillCurrent = this.context.registry().find(viewerId).map(record ->
					record.incarnationId().equals(job.incarnationId) && record.generation() == job.generation
					&& record.expectedEntityUuid().filter(job.expectedEntityUuid::equals).isPresent()).orElse(false);
			if (!stillCurrent || !this.context.allowsMutations()) {
				release(job);
				iterator.remove();
				continue;
			}

			if (bindIfLoadedAnywhere(viewerId, job.expectedEntityUuid)) {
				release(job);
				iterator.remove();
				AuditLog.info("recovery_completed", job.auditContext, "FOUND", null, job.startedNanos, Map.of(), null);
				continue;
			}

			if (!job.ticketAdded && job.scan.isDone()) {
				try {
					job.scan.getNow(null);
					if (!(job.collector.getRoot() instanceof NbtCompound chunkNbt)
							|| !(chunkNbt.getString("Status").equals("minecraft:full")
							|| chunkNbt.getString("Status").equals("full"))) {
						iterator.remove();
						markMissing(viewerId, NOT_FOUND_REASON);
						continue;
					}
					job.world.getChunkManager().addTicket(RECOVERY_TICKET, job.chunkPos, 0, job.ticketKey);
					job.ticketAdded = true;
				} catch (RuntimeException error) {
					iterator.remove();
					this.lastFailures.put(viewerId, ErrorCode.RECOVERY_TIMEOUT);
					this.context.markUnresolved(viewerId);
					AuditLog.warn("recovery_completed", job.auditContext, "UNRESOLVED", "RECOVERY_READ_FAILED", job.startedNanos, Map.of(), error);
					continue;
				}
			}

			// This overload checks ServerEntityManager's LOADED status, not block availability.
			if (job.ticketAdded && job.world.isChunkLoaded(job.chunkPos.toLong())) {
				release(job);
				iterator.remove();
				markMissing(viewerId, NOT_FOUND_REASON);
				continue;
			}

			if (now >= job.deadlineTick) {
				release(job);
				iterator.remove();
				this.lastFailures.put(viewerId, ErrorCode.RECOVERY_TIMEOUT);
				this.context.markUnresolved(viewerId);
				AuditLog.warn("recovery_completed", job.auditContext, "UNRESOLVED", "RECOVERY_TIMEOUT", job.startedNanos, Map.of(), null);
			}
		}
	}

	/** Освобождает все тикеты: вызывается при остановке сервера. */
	public void cancelAll() {
		for (Job job : this.jobs.values()) {
			release(job);
		}
		this.jobs.clear();
		this.lastFailures.clear();
	}

	public void cancel(ViewerId viewerId) {
		Job job = this.jobs.remove(viewerId);
		if (job != null) release(job);
		this.lastFailures.remove(viewerId);
	}

	public Optional<ErrorCode> lastFailure(ViewerId viewerId) {
		return Optional.ofNullable(this.lastFailures.get(viewerId));
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
				if (!GenerationValidator.decide(found.marker(), found.getUuid(), this.context.registry(),
						this.context.isRegistryReady()).isBind()) continue;
				this.context.loadedIndex().bind(viewerId, found);
				this.context.markLoaded(viewerId);
				this.context.registry().find(viewerId).ifPresent(record -> {
					this.context.parrotService().controller().applyName(found, record);
					found.setVariant(VanillaVariantMapper.toVanilla(record.variant()));
					this.context.runtime(viewerId).restoreDesired(record.desiredBehavior());
					found.setAiDisabled(false);
				});
				this.context.parrotService().restorePresentOnBind(viewerId);
				return true;
			}
		}
		return false;
	}

	private void markMissing(ViewerId viewerId, String reason) {
		long started = AuditLog.started();
		this.context.registry().find(viewerId).ifPresent(record -> {
			this.context.registry().replace(record.asMissing(this.context.clock().now()));
			this.lastFailures.put(viewerId, ErrorCode.PARROT_ENTITY_MISSING);
			this.context.events().publish(ParrotEvent.of(ParrotEvent.Type.PARROT_MISSING, viewerId,
					record.incarnationId(), record.generation(), this.context.clock().now(),
					this.context.clock().currentTick(), null, null, "MISSING", reason));
			AuditLog.warn("parrot_missing", AuditLog.Context.of(null, record, "RECOVERY"), "MISSING", NOT_FOUND_REASON, started, Map.of("detail", reason), null);
		});
	}

	private void release(Job job) {
		// Ticket освобождается при любом исходе, иначе чанк останется загруженным навсегда.
		if (job.ticketAdded) job.world.getChunkManager().removeTicket(RECOVERY_TICKET, job.chunkPos, 0, job.ticketKey);
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
