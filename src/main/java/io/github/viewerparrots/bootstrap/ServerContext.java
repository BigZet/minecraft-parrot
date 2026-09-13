package io.github.viewerparrots.bootstrap;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.api.event.EventDispatcher;
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
import io.github.viewerparrots.service.ParrotCommandService;
import io.github.viewerparrots.service.PermissionService;
import io.github.viewerparrots.service.SoundService;
import io.github.viewerparrots.service.StreamerService;
import io.github.viewerparrots.service.ViewerParrotService;
import io.github.viewerparrots.storage.JsonParrotRepository;
import io.github.viewerparrots.storage.ParrotRepository;
import io.github.viewerparrots.time.GameClock;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

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
	private final StorageWriter writer;
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
	private EntityRecoveryService recoveryService;
	private ParrotCommandService commandService;
	private SoundService soundService;
	private BehaviorScheduler behaviorScheduler;

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

	private ServerContext(MinecraftServer server) {
		this.server = Objects.requireNonNull(server, "server");
		this.clock = new ServerGameClock(server);
		this.writer = new StorageWriter(server);
	}

	/**
	 * Создаёт контекст, читает конфиг и реестр.
	 *
	 * <p>Ошибка конфигурации или повреждение данных не прерывают запуск сервера, но
	 * переводят мод в {@link StorageHealth#DEGRADED}: раздел 11 требует понятной
	 * диагностики вместо тихого старта с пустыми данными.
	 */
	public static ServerContext start(MinecraftServer server) {
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
		if (current == null) {
			return;
		}
		current.shutdown();
		CheckpointScheduler.reset();
		current = null;
	}

	private void initialize() {
		Path configFile = FabricLoader.getInstance().getConfigDir().resolve("viewerparrots.json");
		try {
			this.config = new ConfigLoader(ConfigValidator.withoutRegistry()).loadOrCreate(configFile);
		} catch (IOException | RuntimeException e) {
			this.config = ViewerParrotsConfig.defaults();
			degrade("конфигурация недоступна: " + e.getMessage());
			ViewerParrotsMod.LOGGER.error("Конфигурация {} не принята; функциональность мода заблокирована",
					configFile, e);
		}

		this.permissions = new PermissionService(
				this.config.permissions().controlLevel(), this.config.permissions().adminLevel());
		this.cooldowns = new CooldownService(this.clock);
		this.rateLimiter = new GlobalRateLimiter(this.clock,
				this.config.limits().actionsPer20Ticks(), this.config.limits().soundsPer20Ticks());

		Path dataDirectory = this.server.getSavePath(WorldSavePath.ROOT).resolve("data")
				.resolve(ViewerParrotsMod.MOD_ID);
		this.repository = new JsonParrotRepository(dataDirectory);

		if (this.storageHealth != StorageHealth.DEGRADED) {
			loadRegistry();
		}

		this.streamerService = new StreamerService(this.server, this.registry);
		this.parrotService = new ViewerParrotService(this);
		this.recoveryService = new EntityRecoveryService(this);
		this.soundService = new SoundService(this.config, this.behaviorRandom);
		this.commandService = new ParrotCommandService(this);
		this.behaviorScheduler = new BehaviorScheduler(this);

		if (!this.soundService.hasUsableSounds()) {
			// Не отказ: команды продолжат работать, но scream будет молчать,
			// и администратор должен об этом узнать.
			ViewerParrotsMod.LOGGER.warn(
					"Ни один звук из белого списка не найден в реестре; scream не будет слышен");
		}
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
		this.runtimes.remove(viewerId);
	}

	public Map<ViewerId, ParrotRuntime> runtimes() {
		return this.runtimes;
	}

	private void loadRegistry() {
		try {
			ParrotRepository.LoadResult result = this.repository.load();
			this.registry.replaceAll(result.records(), result.tombstones(), result.streamerPlayerUuid());

			for (String warning : result.warnings()) {
				ViewerParrotsMod.LOGGER.warn("Хранилище: {}", warning);
			}
			if (!result.danglingPrepares().isEmpty()) {
				ViewerParrotsMod.LOGGER.warn(
						"Незавершённых PREPARE после аварии: {}; подготовленные сущности будут удалены",
						result.danglingPrepares().size());
			}

			this.storageHealth = StorageHealth.READY;
			ViewerParrotsMod.LOGGER.info(
					"Реестр загружен: записей {}, надгробий {}, checkpoint {}, журнал до {}",
					result.records().size(), result.tombstones().size(),
					result.checkpointSequence(), result.lastJournalSequence());
		} catch (IOException | RuntimeException e) {
			degrade("реестр не загружен: " + e.getMessage());
			ViewerParrotsMod.LOGGER.error(
					"Реестр не загружен. Изменения запрещены, поведение собственных сущностей остановлено. "
							+ "Восстановите согласованную копию мира и registry.json", e);
		}
	}

	/** Перевод в режим отказа: изменения запрещены до перезапуска. */
	public void degrade(String reason) {
		this.storageHealth = StorageHealth.DEGRADED;
		this.storageFaultReason = reason;
	}

	private void shutdown() {
		this.recoveryService.cancelAll();
		// Приём команд прекращается вместе с контекстом; ждём writer в пределах таймаута.
		boolean drained = this.writer.shutdownAndAwait(this.config.storage().shutdownTimeoutSeconds());
		if (!drained) {
			ViewerParrotsMod.LOGGER.error("Сохранение при остановке не подтверждено");
		}
		try {
			this.repository.close();
		} catch (IOException e) {
			ViewerParrotsMod.LOGGER.error("Не удалось закрыть журнал", e);
		}
		this.loadedIndex.clear();
	}

	public boolean allowsMutations() {
		return this.storageHealth == StorageHealth.READY;
	}

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

	public ParrotCommandService commandService() {
		return this.commandService;
	}

	public BehaviorScheduler behaviorScheduler() {
		return this.behaviorScheduler;
	}

	public SoundService soundService() {
		return this.soundService;
	}

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
