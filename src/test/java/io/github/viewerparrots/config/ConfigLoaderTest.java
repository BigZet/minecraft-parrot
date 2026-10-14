package io.github.viewerparrots.config;

import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.json.JsonSchemaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Раздел 11 ТЗ: round-trip конфига, неизвестные поля, диапазоны, CONFIG_RESTART_REQUIRED. */
class ConfigLoaderTest {

	private final ConfigLoader loader = new ConfigLoader(ConfigValidator.withoutRegistry());

	@Test
	void queueCapacityAcceptsOneAndTheSpecifiedHardMaximum() {
		for (int capacity : new int[]{1, 256}) {
			ViewerParrotsConfig defaults = ViewerParrotsConfig.defaults();
			ViewerParrotsConfig candidate = withLimits(defaults,
					new ViewerParrotsConfig.Limits(20, 4, capacity));
			assertEquals(candidate, this.loader.parse(ConfigLoader.toJson(candidate)));
		}
	}

	@Test
	void queueCapacityAboveTheHardMaximumIsRejectedWithItsJsonPath() {
		assertAll(java.util.stream.IntStream.of(257, 1024).<org.junit.jupiter.api.function.Executable>mapToObj(capacity -> () -> {
			ViewerParrotsConfig defaults = ViewerParrotsConfig.defaults();
			ViewerParrotsConfig candidate = withLimits(defaults,
					new ViewerParrotsConfig.Limits(20, 4, capacity));
			ConfigInvalidException error = assertThrows(ConfigInvalidException.class,
					() -> this.loader.parse(ConfigLoader.toJson(candidate)), "queueCapacity " + capacity);
			assertTrue(error.getMessage().contains("$.limits.queueCapacity"));
		}));
	}

	@Test
	void newlyCreatedDefaultsMustAlsoPassRegistryValidation(@TempDir Path dir) {
		ConfigLoader strict = new ConfigLoader(new ConfigValidator(id -> false));
		Path file = dir.resolve("viewerparrots.json");
		assertThrows(ConfigInvalidException.class, () -> strict.loadOrCreate(file));
		org.junit.jupiter.api.Assertions.assertFalse(Files.exists(file));
	}

	@Test
	void dangerSearchRadiusIsBoundedLikeOtherDistances() {
		ViewerParrotsConfig current = ViewerParrotsConfig.defaults();
		var b = current.behavior();
		ViewerParrotsConfig candidate = withBehavior(current, new ViewerParrotsConfig.Behavior(
				b.enabled(), b.idleMinTicks(), b.idleMaxTicks(), b.dangerCheckTicks(), 129,
				b.lazyMinTicks(), b.lazyMaxTicks(), b.normalSoundChance(), b.loudSoundChance(), b.traitWeights()));
		ConfigInvalidException error = assertThrows(ConfigInvalidException.class,
				() -> ConfigValidator.withoutRegistry().validate(candidate));
		assertTrue(error.getMessage().contains("$.behavior.dangerRadius"));
	}

	@Test
	@DisplayName("значения по умолчанию проходят собственную валидацию")
	void defaultsAreValid() {
		ConfigValidator.withoutRegistry().validate(ViewerParrotsConfig.defaults());
	}

	@Test
	@DisplayName("round-trip сериализации не теряет и не меняет значений")
	void roundTripPreservesEverything() {
		ViewerParrotsConfig defaults = ViewerParrotsConfig.defaults();
		assertEquals(defaults, this.loader.parse(ConfigLoader.toJson(defaults)));
	}

	@Test
	@DisplayName("отсутствующий файл создаётся из defaults")
	void missingFileIsCreatedFromDefaults(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("nested").resolve("viewerparrots.json");

		ViewerParrotsConfig config = this.loader.loadOrCreate(file);

		assertAll(
				() -> assertEquals(ViewerParrotsConfig.defaults(), config),
				() -> assertTrue(Files.exists(file)),
				() -> assertEquals(config, this.loader.loadOrCreate(file)));
	}

	@Test
	@DisplayName("некорректный файл не перезаписывается молча")
	void brokenFileIsNotOverwritten(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("viewerparrots.json");
		String broken = "{ \"schemaVersion\": 1, ";
		Files.writeString(file, broken, StandardCharsets.UTF_8);

		assertThrows(JsonSchemaException.class, () -> this.loader.loadOrCreate(file));
		assertEquals(broken, Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("неизвестное поле — ошибка с JSON path, а не игнорирование")
	void unknownFieldIsRejectedWithPath() {
		String json = ConfigLoader.toJson(ViewerParrotsConfig.defaults())
				.replace("\"maxActiveParrots\"", "\"maxActiveParots\"");

		JsonSchemaException error = assertThrows(JsonSchemaException.class, () -> this.loader.parse(json));

		assertAll(
				() -> assertEquals("$.maxActiveParots", error.path()),
				() -> assertTrue(error.getMessage().contains("неизвестное поле")));
	}

	@Test
	@DisplayName("дублирующийся ключ отвергается, а не берётся последним")
	void duplicateKeyIsRejected() {
		String json = """
				{"schemaVersion": 1, "schemaVersion": 2}
				""";

		JsonSchemaException error = assertThrows(JsonSchemaException.class, () -> this.loader.parse(json));
		assertTrue(error.getMessage().contains("дублирующийся ключ"));
	}

	@Test
	@DisplayName("пропущенное обязательное поле называется по имени")
	void missingFieldIsNamed() {
		String json = ConfigLoader.toJson(ViewerParrotsConfig.defaults())
				.replaceAll("(?s)\\s*\"nameVisible\": (true|false),", "");

		JsonSchemaException error = assertThrows(JsonSchemaException.class, () -> this.loader.parse(json));
		assertEquals("$.nameVisible", error.path());
	}

	@Test
	@DisplayName("неизвестная константа enum отвергается")
	void unknownEnumConstantIsRejected() {
		String json = ConfigLoader.toJson(ViewerParrotsConfig.defaults())
				.replace("\"IMMORTAL\"", "\"INVINCIBLE\"");

		JsonSchemaException error = assertThrows(JsonSchemaException.class, () -> this.loader.parse(json));
		assertEquals("$.deathMode", error.path());
	}

	@Test
	@DisplayName("все нарушения диапазонов сообщаются одним списком")
	void allRangeViolationsAreReportedTogether() {
		ViewerParrotsConfig defaults = ViewerParrotsConfig.defaults();
		ViewerParrotsConfig broken = new ViewerParrotsConfig(
				defaults.schemaVersion(),
				0,
				defaults.deathMode(),
				defaults.nameFormat(),
				defaults.nameVisible(),
				new ViewerParrotsConfig.Permissions(3, 2),
				defaults.come(),
				new ViewerParrotsConfig.Follow(3, 6, 20),
				defaults.dance(),
				defaults.cooldowns(),
				defaults.sound(),
				defaults.behavior(),
				defaults.limits(),
				defaults.recovery(),
				defaults.storage());

		ConfigInvalidException error = assertThrows(ConfigInvalidException.class,
				() -> ConfigValidator.withoutRegistry().validate(broken));

		assertAll(
				() -> assertEquals(3, error.violations().size(), error.getMessage()),
				() -> assertTrue(error.getMessage().contains("$.maxActiveParrots")),
				() -> assertTrue(error.getMessage().contains("$.permissions.adminLevel")),
				() -> assertTrue(error.getMessage().contains("$.follow.stopDistance")));
	}

	@Test
	@DisplayName("HERO и THIEF обязаны иметь нулевой вес в MVP")
	void inactiveTraitsMustHaveZeroWeight() {
		ViewerParrotsConfig defaults = ViewerParrotsConfig.defaults();
		var weights = new java.util.LinkedHashMap<>(defaults.behavior().traitWeights());
		weights.put(ParrotTrait.HERO, 10);

		ViewerParrotsConfig broken = withBehavior(defaults, new ViewerParrotsConfig.Behavior(
				true, 600, 2400, 20, 8, 20, 100, 0.1, 0.5, weights));

		ConfigInvalidException error = assertThrows(ConfigInvalidException.class,
				() -> ConfigValidator.withoutRegistry().validate(broken));
		assertTrue(error.getMessage().contains("$.behavior.traitWeights.HERO"));
	}

	@Test
	@DisplayName("нулевая сумма весов отвергается")
	void zeroWeightSumIsRejected() {
		ViewerParrotsConfig defaults = ViewerParrotsConfig.defaults();
		var weights = new java.util.LinkedHashMap<ParrotTrait, Integer>();
		for (ParrotTrait trait : ParrotTrait.values()) {
			weights.put(trait, 0);
		}

		ViewerParrotsConfig broken = withBehavior(defaults, new ViewerParrotsConfig.Behavior(
				true, 600, 2400, 20, 8, 20, 100, 0.1, 0.5, weights));

		ConfigInvalidException error = assertThrows(ConfigInvalidException.class,
				() -> ConfigValidator.withoutRegistry().validate(broken));
		assertTrue(error.getMessage().contains("сумма весов"));
	}

	@Test
	@DisplayName("несуществующий звук отвергается проверкой реестра")
	void unknownSoundIsRejectedByRegistryCheck() {
		ConfigValidator strict = new ConfigValidator("minecraft:entity.zombie.ambient"::equals);

		ConfigInvalidException error = assertThrows(ConfigInvalidException.class,
				() -> strict.validate(ViewerParrotsConfig.defaults()));

		assertAll(
				() -> assertTrue(error.getMessage().contains("$.sound.allowedSounds[1]")),
				() -> assertTrue(error.getMessage().contains("отсутствует в реестре")));
	}

	@Test
	@DisplayName("пустой список звуков отвергается")
	void emptySoundListIsRejected() {
		ViewerParrotsConfig defaults = ViewerParrotsConfig.defaults();
		ViewerParrotsConfig broken = withSound(defaults,
				new ViewerParrotsConfig.Sound(0.8, 0.9, 1.1, java.util.List.of()));

		ConfigInvalidException error = assertThrows(ConfigInvalidException.class,
				() -> ConfigValidator.withoutRegistry().validate(broken));
		assertTrue(error.getMessage().contains("$.sound.allowedSounds"));
	}

	@Test
	@DisplayName("pitch вне 0.5..2 отвергается")
	void pitchOutsideRangeIsRejected() {
		ViewerParrotsConfig defaults = ViewerParrotsConfig.defaults();
		ViewerParrotsConfig broken = withSound(defaults, new ViewerParrotsConfig.Sound(
				0.8, 0.1, 3.0, defaults.sound().allowedSounds()));

		ConfigInvalidException error = assertThrows(ConfigInvalidException.class,
				() -> ConfigValidator.withoutRegistry().validate(broken));

		assertAll(
				() -> assertTrue(error.getMessage().contains("$.sound.minPitch")),
				() -> assertTrue(error.getMessage().contains("$.sound.maxPitch")));
	}

	@Test
	@DisplayName("изменение queueCapacity, recovery jobs и storage требует рестарта")
	void restartRequiredChangesAreDetected() {
		ViewerParrotsConfig current = ViewerParrotsConfig.defaults();

		ViewerParrotsConfig queueChanged = withLimits(current,
				new ViewerParrotsConfig.Limits(20, 4, 128));
		ViewerParrotsConfig recoveryChanged = new ViewerParrotsConfig(
				current.schemaVersion(), current.maxActiveParrots(), current.deathMode(),
				current.nameFormat(), current.nameVisible(), current.permissions(), current.come(),
				current.follow(), current.dance(), current.cooldowns(), current.sound(),
				current.behavior(), current.limits(), new ViewerParrotsConfig.Recovery(1, 100),
				current.storage());
		ViewerParrotsConfig storageChanged = new ViewerParrotsConfig(
				current.schemaVersion(), current.maxActiveParrots(), current.deathMode(),
				current.nameFormat(), current.nameVisible(), current.permissions(), current.come(),
				current.follow(), current.dance(), current.cooldowns(), current.sound(),
				current.behavior(), current.limits(), current.recovery(),
				new ViewerParrotsConfig.Storage(1200, 10));

		// Изменение, применимое на лету, рестарта требовать не должно.
		ViewerParrotsConfig danceChanged = new ViewerParrotsConfig(
				current.schemaVersion(), current.maxActiveParrots(), current.deathMode(),
				current.nameFormat(), current.nameVisible(), current.permissions(), current.come(),
				current.follow(), new ViewerParrotsConfig.Dance(60), current.cooldowns(),
				current.sound(), current.behavior(), current.limits(), current.recovery(),
				current.storage());

		assertAll(
				() -> assertTrue(queueChanged.requiresRestartComparedTo(current)),
				() -> assertTrue(recoveryChanged.requiresRestartComparedTo(current)),
				() -> assertTrue(storageChanged.requiresRestartComparedTo(current)),
				() -> org.junit.jupiter.api.Assertions.assertFalse(
						danceChanged.requiresRestartComparedTo(current)));
	}

	@Test
	@DisplayName("будущая schemaVersion отвергается")
	void futureSchemaVersionIsRejected() {
		ViewerParrotsConfig defaults = ViewerParrotsConfig.defaults();
		ViewerParrotsConfig future = new ViewerParrotsConfig(
				2, defaults.maxActiveParrots(), defaults.deathMode(), defaults.nameFormat(),
				defaults.nameVisible(), defaults.permissions(), defaults.come(), defaults.follow(),
				defaults.dance(), defaults.cooldowns(), defaults.sound(), defaults.behavior(),
				defaults.limits(), defaults.recovery(), defaults.storage());

		ConfigInvalidException error = assertThrows(ConfigInvalidException.class,
				() -> ConfigValidator.withoutRegistry().validate(future));
		assertTrue(error.getMessage().contains("$.schemaVersion"));
	}

	private static ViewerParrotsConfig withBehavior(ViewerParrotsConfig base,
			ViewerParrotsConfig.Behavior behavior) {
		return new ViewerParrotsConfig(base.schemaVersion(), base.maxActiveParrots(), base.deathMode(),
				base.nameFormat(), base.nameVisible(), base.permissions(), base.come(), base.follow(),
				base.dance(), base.cooldowns(), base.sound(), behavior, base.limits(), base.recovery(),
				base.storage());
	}

	private static ViewerParrotsConfig withSound(ViewerParrotsConfig base,
			ViewerParrotsConfig.Sound sound) {
		return new ViewerParrotsConfig(base.schemaVersion(), base.maxActiveParrots(), base.deathMode(),
				base.nameFormat(), base.nameVisible(), base.permissions(), base.come(), base.follow(),
				base.dance(), base.cooldowns(), sound, base.behavior(), base.limits(), base.recovery(),
				base.storage());
	}

	private static ViewerParrotsConfig withLimits(ViewerParrotsConfig base,
			ViewerParrotsConfig.Limits limits) {
		return new ViewerParrotsConfig(base.schemaVersion(), base.maxActiveParrots(), base.deathMode(),
				base.nameFormat(), base.nameVisible(), base.permissions(), base.come(), base.follow(),
				base.dance(), base.cooldowns(), base.sound(), base.behavior(), limits, base.recovery(),
				base.storage());
	}
}
