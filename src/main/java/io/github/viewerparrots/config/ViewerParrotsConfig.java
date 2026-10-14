package io.github.viewerparrots.config;

import io.github.viewerparrots.domain.ParrotTrait;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Полный runtime-конфиг мода (раздел 11 ТЗ). Неизменяемый: reload создаёт новый экземпляр
 * целиком, потому что частичное применение запрещено.
 *
 * <p>Проверки диапазонов живут не здесь, а в {@link ConfigValidator}: он собирает все
 * нарушения сразу, чтобы администратор увидел их одним списком, а не по одному за перезапуск.
 */
public record ViewerParrotsConfig(
		int schemaVersion,
		int maxActiveParrots,
		DeathMode deathMode,
		String nameFormat,
		boolean nameVisible,
		Permissions permissions,
		Come come,
		Follow follow,
		Dance dance,
		Cooldowns cooldowns,
		Sound sound,
		Behavior behavior,
		Limits limits,
		Recovery recovery,
		Storage storage
) {
	public static final int CURRENT_SCHEMA_VERSION = 1;
	public static final List<String> PARROT_CALL_SOUND_IDS = List.of(
			"viewerparrots:parrot.call_01",
			"viewerparrots:parrot.call_12",
			"viewerparrots:parrot.call_14",
			"viewerparrots:parrot.call_15",
			"viewerparrots:parrot.call_27");

	public ViewerParrotsConfig {
		Objects.requireNonNull(deathMode, "deathMode");
		Objects.requireNonNull(nameFormat, "nameFormat");
		Objects.requireNonNull(permissions, "permissions");
		Objects.requireNonNull(come, "come");
		Objects.requireNonNull(follow, "follow");
		Objects.requireNonNull(dance, "dance");
		Objects.requireNonNull(cooldowns, "cooldowns");
		Objects.requireNonNull(sound, "sound");
		Objects.requireNonNull(behavior, "behavior");
		Objects.requireNonNull(limits, "limits");
		Objects.requireNonNull(recovery, "recovery");
		Objects.requireNonNull(storage, "storage");
	}

	public record Permissions(int controlLevel, int adminLevel) {
	}

	public record Come(int maxPathfindDistance, int arrivalRadius, int timeoutTicks,
			boolean teleportIfFar, boolean teleportOnPathFailure, boolean crossDimensionTeleport) {
	}

	public record Follow(int startDistance, int stopDistance, int repathTicks) {
	}

	public record Dance(int durationTicks) {
	}

	public record Cooldowns(int come, int follow, int sit, int dance, int scream, int respawn) {
	}

	public record Sound(double volume, double minPitch, double maxPitch, List<String> allowedSounds) {
		public Sound {
			allowedSounds = List.copyOf(allowedSounds);
		}
	}

	public record Behavior(boolean enabled, int idleMinTicks, int idleMaxTicks, int dangerCheckTicks,
			int dangerRadius, int lazyMinTicks, int lazyMaxTicks, double normalSoundChance,
			double loudSoundChance, Map<ParrotTrait, Integer> traitWeights) {
		public Behavior {
			traitWeights = Map.copyOf(traitWeights);
		}
	}

	public record Limits(int actionsPer20Ticks, int soundsPer20Ticks, int queueCapacity) {
	}

	public record Recovery(int maxConcurrentJobs, int timeoutTicks) {
	}

	public record Storage(int checkpointIntervalTicks, int shutdownTimeoutSeconds) {
	}

	/**
	 * Значения по умолчанию из раздела 11 ТЗ. Отсутствующий файл конфигурации создаётся
	 * именно из них.
	 */
	public static ViewerParrotsConfig defaults() {
		Map<ParrotTrait, Integer> weights = new LinkedHashMap<>();
		weights.put(ParrotTrait.NORMAL, 40);
		weights.put(ParrotTrait.COWARD, 20);
		weights.put(ParrotTrait.LAZY, 20);
		weights.put(ParrotTrait.LOUD, 20);
		// HERO и THIEF в MVP не выдаются случайно, поэтому их вес обязан быть нулевым.
		weights.put(ParrotTrait.HERO, 0);
		weights.put(ParrotTrait.THIEF, 0);

		return new ViewerParrotsConfig(
				CURRENT_SCHEMA_VERSION,
				50,
				DeathMode.IMMORTAL,
				"{viewer_name}",
				true,
				new Permissions(2, 3),
				new Come(64, 3, 200, true, true, true),
				new Follow(6, 3, 20),
				// 200 тиков вместо 100 из раздела 11: при проверке в игре пять секунд
				// оказались слишком коротким танцем. Совпадает с cooldown, поэтому
				// повторить танец можно ровно в момент его окончания.
				new Dance(200),
				new Cooldowns(100, 20, 0, 200, 300, 200),
				new Sound(0.8, 1.0, 1.0, PARROT_CALL_SOUND_IDS),
				new Behavior(true, 600, 2400, 20, 8, 20, 100, 0.1, 0.5, weights),
				new Limits(20, 4, 256),
				new Recovery(2, 100),
				new Storage(600, 10));
	}

	/**
	 * Параметры, которые нельзя применить без рестарта (раздел 11 ТЗ): под них уже
	 * выделены очереди и пулы.
	 *
	 * @return {@code true} если reload должен быть отвергнут с {@code CONFIG_RESTART_REQUIRED}
	 */
	public boolean requiresRestartComparedTo(ViewerParrotsConfig other) {
		return this.limits.queueCapacity() != other.limits.queueCapacity()
				|| this.recovery.maxConcurrentJobs() != other.recovery.maxConcurrentJobs()
				|| !this.storage.equals(other.storage);
	}
}
