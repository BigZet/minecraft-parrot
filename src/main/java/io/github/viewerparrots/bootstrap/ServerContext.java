package io.github.viewerparrots.bootstrap;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.api.event.EventDispatcher;
import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.api.DefaultViewerParrotApi;
import io.github.viewerparrots.api.ViewerParrotApi;
import io.github.viewerparrots.behavior.BehaviorScheduler;
import io.github.viewerparrots.behavior.ParrotRuntime;
import io.github.viewerparrots.config.ConfigLoader;
import io.github.viewerparrots.config.ConfigValidator;
import io.github.viewerparrots.config.ViewerParrotsConfig;
import io.github.viewerparrots.entity.SafePositionFinder;
import io.github.viewerparrots.lifecycle.EntityRecoveryService;
import io.github.viewerparrots.ratelimit.CooldownService;
import io.github.viewerparrots.ratelimit.GlobalRateLimiter;
import io.github.viewerparrots.registry.LoadedEntityIndex;
import io.github.viewerparrots.registry.ViewerParrotRegistry;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.LifecycleState;
import io.github.viewerparrots.service.CommandPolicy;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.diagnostics.AuditLog;
import io.github.viewerparrots.service.ParrotCommandService;
import io.github.viewerparrots.service.ParrotShoulderService;
import io.github.viewerparrots.service.PermissionService;
import io.github.viewerparrots.service.SoundService;
import io.github.viewerparrots.service.StreamerService;
import io.github.viewerparrots.service.ViewerParrotService;
import io.github.viewerparrots.speech.ParrotSpeechService;
import io.github.viewerparrots.storage.JsonParrotRepository;
import io.github.viewerparrots.storage.ParrotRepository;
import io.github.viewerparrots.time.GameClock;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/**
 * Всё состояние мода для одного экземпляра {@link MinecraftServer} (раздел 3.1 ТЗ).
 *
 * <p>Глобального изменяемого синглтона между мирами нет: контекст создаётся при запуске
 * сервера и уничтожается при остановке. Иначе данные одного мира протекли бы в другой
 * при смене одиночного мира без перезапуска игры.
 */
public final class ServerContext {

	/** Готовность хранилища; определяет, разрешены ли изменения (раздел 8.3 ТЗ). */
	public enum StorageHealth {
		/** Реестр ещё читается: сущности без AI, identity не меняется. */
		LOADING,
		READY,
		/** Данные повреждены: изменения запрещены, AI собственных сущностей остановлен. */
		DEGRADED
	}

	private static ServerContext current;

	private final MinecraftServer server;
	private final GameClock clock;
	private final long startedNanos = AuditLog.started();
	private StorageWriter writer;
	private final ViewerParrotRegistry registry = new ViewerParrotRegistry();
	private final LoadedEntityIndex loadedIndex = new LoadedEntityIndex();
	private final SafePositionFinder safePositionFinder = new SafePositionFinder(new Random());

	private ViewerParrotsConfig config;
	private PermissionService permissions;
	private CooldownService cooldowns;
	private GlobalRateLimiter rateLimiter;
	private JsonParrotRepository repository;
	private StreamerService streamerService;
	private ViewerParrotService parrotService;
	private ParrotShoulderService shoulderService;
	private EntityRecoveryService recoveryService;
	private ParrotCommandService commandService;
	private SoundService soundService;
	private ParrotSpeechService speechService;
	private BehaviorScheduler behaviorScheduler;
	private DefaultViewerParrotApi api;

	private final EventDispatcher events = new EventDispatcher();

	/**
	 * Runtime-состояние по зрителям. Не сохраняется: после рестарта восстанавливается
	 * только намерение из реестра (раздел 4.2 ТЗ).
	 */
	private final Map<ViewerId, ParrotRuntime> runtimes = new HashMap<>();

	/** Отдельный генератор для поведения: раздел 10 требует внедряемой случайности. */
	private final Random behaviorRandom = new Random();

	private StorageHealth storageHealth = StorageHealth.LOADING;
	private String storageFaultReason;
	private final Set<ViewerId> knownUnloaded = new HashSet<>();
	private final Deque<ViewerId> namesToRefresh = new ArrayDeque<>();
	private UUID observedTarget;
	private boolean observedTargetOnline;
	private boolean targetObservationStarted;
	private long dirtySinceTick = -1;
	private volatile boolean stopping;
	private boolean reloadPending;
	private final Map<UUID, RegistryKey<World>> deferredEntities = new HashMap<>();

	private ServerContext(MinecraftServer server) {
		this.server = Objects.requireNonNull(server, "server");
		this.clock = new ServerGameClock(server);
	}

	/**
	 * Создаёт контекст, читает конфиг и реестр.
	 *
	 * <p>Ошибка конфигурации или повреждение данных не прерывают запуск сервера, но
	 * переводят мод в {@link StorageHealth#DEGRADED}: раздел 11 требует понятной
	 * диагностики вместо тихого старта с пустыми данными.
	 */
	public static ServerContext start(MinecraftServer server) {
		AuditLog.clear();
		ServerContext context = new ServerContext(server);
		current = context;
		CheckpointScheduler.reset();
		context.initialize();
		return context;
	}

	public static Optional<ServerContext> current() {
		return Optional.ofNullable(current);
	}

	public static void stop(MinecraftServer server) {
		if (current == null || current.server != server) {
			return;
		}
		ServerContext stopped = current;
		stopped.shutdown();
		if (current == stopped) {
			CheckpointScheduler.reset();
			current = null;
		}
	}

	private void initialize() {
		// Diagnostics and permission checks remain available while all mutations are gated by LOADING.
		this.config = ViewerParrotsConfig.defaults();
		this.permissions = new PermissionService(
				this.config.permissions().controlLevel(), this.config.permissions().adminLevel());
		this.cooldowns = new CooldownService(this.clock);
		this.rateLimiter = new GlobalRateLimiter(this.clock,
				this.config.limits().actionsPer20Ticks(), this.config.limits().soundsPer20Ticks());
		this.writer = new StorageWriter(this.server, this.config.limits().queueCapacity());

		Path dataDirectory = this.server.getSavePath(WorldSavePath.ROOT).resolve("data")
				.resolve(ViewerParrotsMod.MOD_ID);
		this.repository = new JsonParrotRepository(dataDirectory);

		this.streamerService = new StreamerService(this);
		this.parrotService = new ViewerParrotService(this);
		this.shoulderService = new ParrotShoulderService(this);
		this.recoveryService = new EntityRecoveryService(this);
		this.soundService = new SoundService(this.config, this.behaviorRandom);
		this.speechService = new ParrotSpeechService(this, FabricLoader.getInstance().getConfigDir());
		this.commandService = new ParrotCommandService(this);
		this.behaviorScheduler = new BehaviorScheduler(this);
		this.api = new DefaultViewerParrotApi(this);

		if (!this.soundService.hasUsableSounds()) {
			// Не отказ: команды продолжат работать, но scream будет молчать,
			// и администратор должен об этом узнать.
			AuditLog.warn("sound_whitelist_empty", null, "DISABLED", "NO_USABLE_SOUNDS", this.startedNanos, Map.of(), null);
		}

		Path configPath = configFile();
		ConfigLoader loader = configReader();
		JsonParrotRepository files = this.repository;
		boolean[] configurationLoaded = {false};
		this.writer.submit(() -> loader.loadOrCreate(configPath)).thenCompose(candidate -> {
			if (!isActiveContext()) return CompletableFuture.failedFuture(new CancellationException("context stopped during configuration read"));
			installConfiguration(candidate, false);
			this.writer.configureQueueCapacity(candidate.limits().queueCapacity());
			configurationLoaded[0] = true;
			return this.writer.submit(files::load);
		}).thenAccept(result -> {
			if (!isActiveContext()) throw new CancellationException("context stopped during registry read");
			loadRegistry(result);
			FabricLifecycleAdapter.revalidateDeferred(this);
		}).whenComplete((ignored, error) -> {
			if (error == null || !isActiveContext()) return;
			String stage = configurationLoaded[0] ? "реестр не загружен" : "конфигурация недоступна";
			degrade(stage + ": " + error.getMessage());
			AuditLog.error("bootstrap_failed", null, "DEGRADED", "STORAGE_UNAVAILABLE", this.startedNanos, Map.of("stage", stage), error);
		});
	}

	private boolean isActiveContext() { return current == this && !this.stopping; }

	/** Copy only immutable sound identifiers; the I/O task never consults a Minecraft registry. */
	private ConfigLoader configReader() {
		Set<String> sounds = Set.copyOf(Registries.SOUND_EVENT.getIds().stream().map(Object::toString).toList());
		return new ConfigLoader(new ConfigValidator(sounds::contains));
	}

	void deferEntityValidation(UUID entityUuid, RegistryKey<World> dimension) { this.deferredEntities.put(entityUuid, dimension); }
	void forgetDeferredEntity(UUID entityUuid, RegistryKey<World> dimension) { this.deferredEntities.remove(entityUuid, dimension); }
	Map<UUID, RegistryKey<World>> takeDeferredEntities() {
		Map<UUID, RegistryKey<World>> pending = Map.copyOf(this.deferredEntities);
		this.deferredEntities.clear();
		return pending;
	}

	/**
	 * Runtime-состояние зрителя; создаётся по требованию.
	 *
	 * <p>При первом обращении намерение восстанавливается из реестра: временные
	 * состояния после рестарта не возвращаются (раздел 4.2 ТЗ).
	 */
	public ParrotRuntime runtime(ViewerId viewerId) {
		return this.runtimes.computeIfAbsent(viewerId, id -> {
			ParrotRuntime created = new ParrotRuntime(id);
			this.registry.find(id).ifPresent(record -> created.restoreDesired(record.desiredBehavior()));
			return created;
		});
	}

	public void forgetRuntime(ViewerId viewerId) {
		if (this.commandService != null) this.commandService.cancelRuntime(viewerId, ErrorCode.INVALID_STATE);
		this.runtimes.remove(viewerId);
	}

	public LifecycleState lifecycle(ViewerId viewerId) {
		if (this.shoulderService != null && this.shoulderService.contains(viewerId)) return LifecycleState.LOADED;
		return this.registry.find(viewerId).map(record -> CommandPolicy.lifecycle(record.physicalStatus(),
				this.loadedIndex.isLoaded(viewerId), this.knownUnloaded.contains(viewerId)))
				.orElseGet(() -> this.registry.tombstonesFor(viewerId).isEmpty()
						? LifecycleState.UNRESOLVED : LifecycleState.REMOVED);
	}

	public void markLoaded(ViewerId viewerId) { this.knownUnloaded.remove(viewerId); }
	public void markUnloaded(ViewerId viewerId) { this.knownUnloaded.add(viewerId); }
	public void markUnresolved(ViewerId viewerId) { this.knownUnloaded.remove(viewerId); }
	public Path configFile() { return FabricLoader.getInstance().getConfigDir().resolve("viewerparrots.json"); }

	/** Prepare everything before publishing the new immutable configuration. */
	public CompletableFuture<CommandResult> reload(UUID commandId, ActorContext actor) {
		long started = AuditLog.started();
		if (!this.permissions.isAllowed(actor, ParrotAction.RELOAD)) return CompletableFuture.completedFuture(CommandResult.rejected(commandId, ErrorCode.PERMISSION_DENIED));
		if (!isActiveContext()) return CompletableFuture.completedFuture(CommandResult.rejected(commandId, ErrorCode.INVALID_STATE));
		if (this.storageHealth == StorageHealth.LOADING) return CompletableFuture.completedFuture(CommandResult.rejected(commandId, ErrorCode.STORAGE_UNAVAILABLE));
		if (this.reloadPending) return CompletableFuture.completedFuture(CommandResult.rejected(commandId, ErrorCode.OPERATION_PENDING));
		if (this.writer.queueDepth() >= this.config.limits().queueCapacity()) return CompletableFuture.completedFuture(CommandResult.rejected(commandId, ErrorCode.SERVER_BUSY));
		Path path = configFile();
		ConfigLoader loader = configReader();
		this.reloadPending = true;
		return this.writer.submit(() -> loader.loadOrCreate(path)).handle((candidate, error) -> {
			this.reloadPending = false;
			if (!isActiveContext()) return CommandResult.rejected(commandId, ErrorCode.INVALID_STATE);
			if (error != null) {
				AuditLog.warn("config_reload", AuditLog.Context.viewer(commandId, null, "RELOAD"), "REJECTED", "CONFIG_INVALID", started, Map.of(), error);
				return CommandResult.rejected(commandId, ErrorCode.CONFIG_INVALID, CommandResult.details("detail", AuditLog.safeText(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())));
			}
			if (candidate.requiresRestartComparedTo(this.config)) return CommandResult.rejected(commandId, ErrorCode.CONFIG_RESTART_REQUIRED);
			installConfiguration(candidate, true);
			AuditLog.info("config_reload", AuditLog.Context.viewer(commandId, null, "RELOAD"), "SUCCEEDED", null, started, Map.of("deathMode", candidate.deathMode(), "behaviorEnabled", candidate.behavior().enabled()), null);
			return CommandResult.succeeded(commandId, "viewerparrots.reload.ok", Map.of());
		});
	}

	private void installConfiguration(ViewerParrotsConfig candidate, boolean reload) {
			PermissionService nextPermissions = new PermissionService(candidate.permissions().controlLevel(), candidate.permissions().adminLevel());
			SoundService nextSounds = new SoundService(candidate, this.behaviorRandom);
			// These operations cannot fail after validation. Existing runtime deadlines/cooldowns stay intact.
			this.rateLimiter.reconfigure(candidate.limits().actionsPer20Ticks(), candidate.limits().soundsPer20Ticks());
			this.config = candidate;
			this.permissions = nextPermissions;
			this.soundService = nextSounds;
			this.parrotService.onConfigReload();
			if (!reload) {
				AuditLog.info("config_loaded", null, "READY", null, this.startedNanos, Map.of(
						"maxActiveParrots", candidate.maxActiveParrots(), "deathMode", candidate.deathMode(),
						"behaviorEnabled", candidate.behavior().enabled(), "danceTicks", candidate.dance().durationTicks(),
						"controlLevel", candidate.permissions().controlLevel(), "adminLevel", candidate.permissions().adminLevel(),
						"queueCapacity", candidate.limits().queueCapacity(), "usableSounds", nextSounds.usableSoundCount()), null);
				return;
			}
			this.namesToRefresh.clear();
			this.namesToRefresh.addAll(this.loadedIndex.loadedViewerIds());
			this.behaviorScheduler.resetFaults();
			for (ParrotRuntime runtime : this.runtimes.values()) {
				runtime.resetBehaviorErrors();
				if (!candidate.behavior().enabled()) {
					this.commandService.cancelLazy(runtime.viewerId(), ErrorCode.INVALID_STATE);
					runtime.cancelAutonomousMovement();
					this.loadedIndex.find(runtime.viewerId()).ifPresent(parrot -> parrot.getNavigation().stop());
				}
			}
	}

	public void refreshNames() {
		if (this.registry.isDirty()) {
			if (this.dirtySinceTick < 0) this.dirtySinceTick = this.clock.currentTick();
		} else this.dirtySinceTick = -1;
		for (int i = 0; i < 10 && !this.namesToRefresh.isEmpty(); i++) {
			ViewerId viewerId = this.namesToRefresh.removeFirst();
			this.loadedIndex.find(viewerId).ifPresent(parrot -> this.registry.find(viewerId)
					.ifPresent(record -> this.parrotService.controller().applyName(parrot, record)));
		}
	}

	public long dirtyAgeTicks() {
		return this.dirtySinceTick < 0 || !this.registry.isDirty() ? 0 : this.clock.currentTick() - this.dirtySinceTick;
	}

	/** Constant-time target lookup; only loaded runtime entries are visited on an actual change. */
	public void monitorTarget() {
		UUID target = this.streamerService.configuredUuid().orElse(null);
		boolean online = this.streamerService.onlineTarget().isPresent();
		if (this.targetObservationStarted && (!Objects.equals(target, this.observedTarget)
				|| (this.observedTargetOnline && !online))) {
			for (ViewerId viewerId : Set.copyOf(this.runtimes.keySet())) {
				ParrotRuntime runtime = this.runtimes.get(viewerId);
				if (runtime.pendingAction().isPresent() || runtime.movementCommandId().isPresent()) {
					this.commandService.cancelRuntime(viewerId, ErrorCode.TARGET_UNAVAILABLE);
				}
				runtime.cancelAutonomousMovement();
				runtime.setTargetPlayerUuid(target);
				this.loadedIndex.find(viewerId).ifPresent(parrot -> parrot.getNavigation().stop());
			}
		}
		this.observedTarget = target;
		this.observedTargetOnline = online;
		this.targetObservationStarted = true;
	}

	public Map<ViewerId, ParrotRuntime> runtimes() {
		return this.runtimes;
	}

	private void loadRegistry(ParrotRepository.LoadResult result) {
			this.registry.replaceAll(result.records(), result.tombstones(), result.streamerPlayerUuid());
			this.registry.initializeAppliedJournalSequence(result.lastJournalSequence());

			if (!result.warnings().isEmpty()) {
				AuditLog.warn("storage_recovered", null, "RECOVERED", "STORAGE_RECOVERY_WARNING", this.startedNanos, Map.of("warnings", result.warnings()), null);
			}
			if (!result.danglingPrepares().isEmpty()) {
				AuditLog.warn("dangling_prepares", null, "RECOVERED", "DANGLING_PREPARE", this.startedNanos, Map.of("count", result.danglingPrepares().size()), null);
			}

			this.storageHealth = StorageHealth.READY;
			this.speechService.onRegistryReady();
			AuditLog.info("registry_loaded", null, "READY", null, this.startedNanos, Map.of("records", result.records().size(), "tombstones", result.tombstones().size(), "checkpoint", result.checkpointSequence(), "journalSequence", result.lastJournalSequence()), null);
	}

	/** Перевод в режим отказа: изменения запрещены до перезапуска. */
	public void degrade(String reason) {
		if (!this.server.isOnThread()) { this.server.execute(() -> degrade(reason)); return; }
		this.storageHealth = StorageHealth.DEGRADED;
		this.storageFaultReason = AuditLog.safeText(reason);
		AuditLog.error("storage_degraded", null, "DEGRADED", "STORAGE_UNAVAILABLE", AuditLog.started(), Map.of("detail", this.storageFaultReason), null);
		for (ViewerId viewerId : this.loadedIndex.loadedViewerIds()) {
			if (this.commandService != null) this.commandService.cancelRuntime(viewerId, ErrorCode.STORAGE_UNAVAILABLE);
			this.loadedIndex.find(viewerId).ifPresent(parrot -> {
				parrot.getNavigation().stop();
				parrot.setForcedDancing(false);
				parrot.setAiDisabled(true);
			});
		}
	}

	private void shutdown() {
		if (this.stopping) return;
		long started = AuditLog.started();
		this.stopping = true;
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(this.config.storage().shutdownTimeoutSeconds());
		this.recoveryService.cancelAll();
		this.speechService.close();
		CheckpointScheduler.saveOnShutdown(this);
		this.commandService.clear();
		this.api.clear();
		this.behaviorScheduler.clear();
		// Приём команд прекращается вместе с контекстом; ждём writer в пределах таймаута.
		JsonParrotRepository files = this.repository;
		try {
			this.writer.submitForShutdown(() -> { files.close(); return null; })
					.get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
		} catch (Exception error) {
			if (error instanceof InterruptedException) Thread.currentThread().interrupt();
			AuditLog.error("journal_close", null, "FAILED", "STORAGE_UNAVAILABLE", started, Map.of(), error);
		}
		boolean drained = this.writer.shutdownAndAwait(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
		if (!drained) {
			AuditLog.error("server_shutdown", null, "FAILED", "SHUTDOWN_TIMEOUT", started, Map.of(), null);
		}
		this.loadedIndex.clear();
		this.runtimes.clear();
		this.knownUnloaded.clear();
		this.namesToRefresh.clear();
		this.events.clear();
		this.deferredEntities.clear();
		AuditLog.clear();
	}

	public boolean allowsMutations() {
		return this.storageHealth == StorageHealth.READY;
	}

	/** New commands stop at shutdown entry; already accepted durable work can still drain while READY. */
	public boolean acceptingCommands() { return isActiveContext(); }

	public boolean isRegistryReady() {
		return this.storageHealth == StorageHealth.READY;
	}

	public MinecraftServer server() {
		return this.server;
	}

	public GameClock clock() {
		return this.clock;
	}

	public StorageWriter writer() {
		return this.writer;
	}

	public ViewerParrotRegistry registry() {
		return this.registry;
	}

	public LoadedEntityIndex loadedIndex() {
		return this.loadedIndex;
	}

	public SafePositionFinder safePositionFinder() {
		return this.safePositionFinder;
	}

	public ViewerParrotsConfig config() {
		return this.config;
	}

	public PermissionService permissions() {
		return this.permissions;
	}

	public CooldownService cooldowns() {
		return this.cooldowns;
	}

	public GlobalRateLimiter rateLimiter() {
		return this.rateLimiter;
	}

	public JsonParrotRepository repository() {
		return this.repository;
	}

	public StreamerService streamerService() {
		return this.streamerService;
	}

	public ViewerParrotService parrotService() {
		return this.parrotService;
	}

	public EntityRecoveryService recoveryService() {
		return this.recoveryService;
	}

	public ParrotShoulderService shoulderService() { return this.shoulderService; }

	public ParrotCommandService commandService() {
		return this.commandService;
	}

	public ViewerParrotApi api() { return this.api; }

	public BehaviorScheduler behaviorScheduler() {
		return this.behaviorScheduler;
	}

	public SoundService soundService() {
		return this.soundService;
	}

	public ParrotSpeechService speechService() { return this.speechService; }

	public EventDispatcher events() {
		return this.events;
	}

	public Random behaviorRandom() {
		return this.behaviorRandom;
	}

	public StorageHealth storageHealth() {
		return this.storageHealth;
	}

	public Optional<String> storageFaultReason() {
		return Optional.ofNullable(this.storageFaultReason);
	}
}
