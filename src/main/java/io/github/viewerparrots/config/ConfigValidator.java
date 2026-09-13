package io.github.viewerparrots.config;

import io.github.viewerparrots.config.ConfigInvalidException.Violation;
import io.github.viewerparrots.domain.ParrotTrait;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Проверка диапазонов и взаимных ограничений конфига (раздел 11 ТЗ).
 *
 * <p>Собирает все нарушения и только потом бросает исключение: конфиг применяется целиком
 * либо не применяется вовсе, поэтому останавливаться на первой ошибке смысла нет.
 */
public final class ConfigValidator {
	private static final int MAX_DISTANCE = 128;
	private static final int MAX_ALLOWED_SOUNDS = 32;
	private static final Pattern SOUND_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

	private final Predicate<String> soundExists;

	/**
	 * @param soundExists проверка существования звука в реестре игры; домен сам этого
	 *                    сделать не может, поэтому проверка внедряется adapter-ом
	 */
	public ConfigValidator(Predicate<String> soundExists) {
		this.soundExists = soundExists == null ? id -> true : soundExists;
	}

	/** Валидатор без доступа к реестру игры: проверяет только форму идентификатора звука. */
	public static ConfigValidator withoutRegistry() {
		return new ConfigValidator(null);
	}

	/** @throws ConfigInvalidException если есть хотя бы одно нарушение */
	public void validate(ViewerParrotsConfig config) {
		List<Violation> violations = collect(config);
		if (!violations.isEmpty()) {
			throw new ConfigInvalidException(violations);
		}
	}

	public List<Violation> collect(ViewerParrotsConfig config) {
		List<Violation> violations = new ArrayList<>();

		if (config.schemaVersion() != ViewerParrotsConfig.CURRENT_SCHEMA_VERSION) {
			violations.add(new Violation("$.schemaVersion",
					"поддерживается только версия " + ViewerParrotsConfig.CURRENT_SCHEMA_VERSION
							+ ", получено " + config.schemaVersion()));
		}
		range(violations, "$.maxActiveParrots", config.maxActiveParrots(), 1, 200);

		if (config.nameFormat().isBlank()) {
			violations.add(new Violation("$.nameFormat", "не может быть пустым"));
		}

		validatePermissions(violations, config.permissions());
		validateCome(violations, config.come());
		validateFollow(violations, config.follow());
		atLeast(violations, "$.dance.durationTicks", config.dance().durationTicks(), 1);
		validateCooldowns(violations, config.cooldowns());
		validateSound(violations, config.sound());
		validateBehavior(violations, config.behavior());
		validateLimits(violations, config.limits());
		validateRecovery(violations, config.recovery());
		validateStorage(violations, config.storage());

		return List.copyOf(violations);
	}

	private void validatePermissions(List<Violation> violations, ViewerParrotsConfig.Permissions permissions) {
		range(violations, "$.permissions.controlLevel", permissions.controlLevel(), 0, 4);
		range(violations, "$.permissions.adminLevel", permissions.adminLevel(), 0, 4);
		if (permissions.adminLevel() < permissions.controlLevel()) {
			violations.add(new Violation("$.permissions.adminLevel",
					"не может быть ниже controlLevel (" + permissions.controlLevel() + ")"));
		}
	}

	private void validateCome(List<Violation> violations, ViewerParrotsConfig.Come come) {
		range(violations, "$.come.maxPathfindDistance", come.maxPathfindDistance(), 1, MAX_DISTANCE);
		range(violations, "$.come.arrivalRadius", come.arrivalRadius(), 1, MAX_DISTANCE);
		atLeast(violations, "$.come.timeoutTicks", come.timeoutTicks(), 1);
		if (come.arrivalRadius() > come.maxPathfindDistance()) {
			violations.add(new Violation("$.come.arrivalRadius",
					"не может превышать maxPathfindDistance (" + come.maxPathfindDistance() + ")"));
		}
	}

	private void validateFollow(List<Violation> violations, ViewerParrotsConfig.Follow follow) {
		range(violations, "$.follow.startDistance", follow.startDistance(), 1, MAX_DISTANCE);
		range(violations, "$.follow.stopDistance", follow.stopDistance(), 1, MAX_DISTANCE);
		atLeast(violations, "$.follow.repathTicks", follow.repathTicks(), 1);
		if (follow.stopDistance() >= follow.startDistance()) {
			// Иначе попугай начинал бы и прекращал движение на одной дистанции и дёргался.
			violations.add(new Violation("$.follow.stopDistance",
					"должна быть строго меньше startDistance (" + follow.startDistance() + ")"));
		}
	}

	private void validateCooldowns(List<Violation> violations, ViewerParrotsConfig.Cooldowns cooldowns) {
		nonNegative(violations, "$.cooldowns.come", cooldowns.come());
		nonNegative(violations, "$.cooldowns.follow", cooldowns.follow());
		nonNegative(violations, "$.cooldowns.sit", cooldowns.sit());
		nonNegative(violations, "$.cooldowns.dance", cooldowns.dance());
		nonNegative(violations, "$.cooldowns.scream", cooldowns.scream());
		nonNegative(violations, "$.cooldowns.respawn", cooldowns.respawn());
	}

	private void validateSound(List<Violation> violations, ViewerParrotsConfig.Sound sound) {
		probability(violations, "$.sound.volume", sound.volume());
		doubleRange(violations, "$.sound.minPitch", sound.minPitch(), 0.5, 2.0);
		doubleRange(violations, "$.sound.maxPitch", sound.maxPitch(), 0.5, 2.0);
		if (sound.minPitch() > sound.maxPitch()) {
			violations.add(new Violation("$.sound.minPitch",
					"не может превышать maxPitch (" + sound.maxPitch() + ")"));
		}

		List<String> allowed = sound.allowedSounds();
		if (allowed.isEmpty()) {
			violations.add(new Violation("$.sound.allowedSounds", "список не может быть пустым"));
		}
		if (allowed.size() > MAX_ALLOWED_SOUNDS) {
			violations.add(new Violation("$.sound.allowedSounds",
					"не более " + MAX_ALLOWED_SOUNDS + " элементов, получено " + allowed.size()));
		}
		for (int i = 0; i < allowed.size(); i++) {
			String id = allowed.get(i);
			String path = "$.sound.allowedSounds[" + i + "]";
			if (!SOUND_ID.matcher(id).matches()) {
				violations.add(new Violation(path, "некорректный идентификатор звука: " + id));
			} else if (!this.soundExists.test(id)) {
				violations.add(new Violation(path, "звук отсутствует в реестре игры: " + id));
			}
		}
	}

	private void validateBehavior(List<Violation> violations, ViewerParrotsConfig.Behavior behavior) {
		atLeast(violations, "$.behavior.idleMinTicks", behavior.idleMinTicks(), 1);
		atLeast(violations, "$.behavior.idleMaxTicks", behavior.idleMaxTicks(), 1);
		atLeast(violations, "$.behavior.dangerCheckTicks", behavior.dangerCheckTicks(), 1);
		atLeast(violations, "$.behavior.dangerRadius", behavior.dangerRadius(), 1);
		atLeast(violations, "$.behavior.lazyMinTicks", behavior.lazyMinTicks(), 1);
		atLeast(violations, "$.behavior.lazyMaxTicks", behavior.lazyMaxTicks(), 1);

		if (behavior.idleMinTicks() > behavior.idleMaxTicks()) {
			violations.add(new Violation("$.behavior.idleMinTicks",
					"не может превышать idleMaxTicks (" + behavior.idleMaxTicks() + ")"));
		}
		if (behavior.lazyMinTicks() > behavior.lazyMaxTicks()) {
			violations.add(new Violation("$.behavior.lazyMinTicks",
					"не может превышать lazyMaxTicks (" + behavior.lazyMaxTicks() + ")"));
		}

		probability(violations, "$.behavior.normalSoundChance", behavior.normalSoundChance());
		probability(violations, "$.behavior.loudSoundChance", behavior.loudSoundChance());

		validateTraitWeights(violations, behavior.traitWeights());
	}

	private void validateTraitWeights(List<Violation> violations, Map<ParrotTrait, Integer> weights) {
		int sum = 0;
		for (ParrotTrait trait : ParrotTrait.values()) {
			Integer weight = weights.get(trait);
			String path = "$.behavior.traitWeights." + trait.name();
			if (weight == null) {
				violations.add(new Violation(path, "вес не задан"));
				continue;
			}
			if (weight < 0) {
				violations.add(new Violation(path, "вес не может быть отрицательным: " + weight));
				continue;
			}
			if (!trait.isActiveInMvp() && weight != 0) {
				// HERO и THIEF хранятся и отображаются, но случайно не выдаются (раздел 1.3).
				violations.add(new Violation(path,
						"в MVP вес обязан быть 0, поскольку trait не имеет эффекта; получено " + weight));
				continue;
			}
			sum += weight;
		}
		if (sum <= 0) {
			violations.add(new Violation("$.behavior.traitWeights",
					"сумма весов должна быть больше нуля"));
		}
	}

	private void validateLimits(List<Violation> violations, ViewerParrotsConfig.Limits limits) {
		atLeast(violations, "$.limits.actionsPer20Ticks", limits.actionsPer20Ticks(), 1);
		atLeast(violations, "$.limits.soundsPer20Ticks", limits.soundsPer20Ticks(), 1);
		range(violations, "$.limits.queueCapacity", limits.queueCapacity(), 1, 1024);
	}

	private void validateRecovery(List<Violation> violations, ViewerParrotsConfig.Recovery recovery) {
		range(violations, "$.recovery.maxConcurrentJobs", recovery.maxConcurrentJobs(), 1, 2);
		atLeast(violations, "$.recovery.timeoutTicks", recovery.timeoutTicks(), 1);
	}

	private void validateStorage(List<Violation> violations, ViewerParrotsConfig.Storage storage) {
		atLeast(violations, "$.storage.checkpointIntervalTicks", storage.checkpointIntervalTicks(), 1);
		atLeast(violations, "$.storage.shutdownTimeoutSeconds", storage.shutdownTimeoutSeconds(), 1);
	}

	private static void range(List<Violation> violations, String path, int value, int min, int max) {
		if (value < min || value > max) {
			violations.add(new Violation(path, "должно быть в диапазоне " + min + ".." + max + ", получено " + value));
		}
	}

	private static void doubleRange(List<Violation> violations, String path, double value, double min, double max) {
		if (!(value >= min) || !(value <= max)) {
			violations.add(new Violation(path, "должно быть в диапазоне " + min + ".." + max + ", получено " + value));
		}
	}

	private static void probability(List<Violation> violations, String path, double value) {
		doubleRange(violations, path, value, 0.0, 1.0);
	}

	private static void atLeast(List<Violation> violations, String path, int value, int min) {
		if (value < min) {
			violations.add(new Violation(path, "должно быть не меньше " + min + ", получено " + value));
		}
	}

	private static void nonNegative(List<Violation> violations, String path, int value) {
		if (value < 0) {
			violations.add(new Violation(path, "не может быть отрицательным, получено " + value));
		}
	}
}
