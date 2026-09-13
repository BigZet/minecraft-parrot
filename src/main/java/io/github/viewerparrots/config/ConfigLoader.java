package io.github.viewerparrots.config;

import com.google.gson.JsonObject;
import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.json.JsonSchemaException;
import io.github.viewerparrots.json.JsonView;
import io.github.viewerparrots.json.StrictJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Чтение и запись {@code config/viewerparrots.json} (раздел 11 ТЗ).
 *
 * <p>Отсутствующий файл создаётся из defaults. Некорректный файл никогда не
 * перезаписывается молча: разбор завершается ошибкой с JSON path, и решение остаётся
 * за администратором.
 */
public final class ConfigLoader {
	private final ConfigValidator validator;

	public ConfigLoader(ConfigValidator validator) {
		this.validator = Objects.requireNonNull(validator, "validator");
	}

	/**
	 * Загружает конфиг, создавая файл из defaults если его нет.
	 *
	 * @throws JsonSchemaException   при синтаксической ошибке или неизвестном поле
	 * @throws ConfigInvalidException при нарушении диапазонов
	 */
	public ViewerParrotsConfig loadOrCreate(Path file) throws IOException {
		Objects.requireNonNull(file, "file");
		if (!Files.exists(file)) {
			ViewerParrotsConfig defaults = ViewerParrotsConfig.defaults();
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.writeString(file, toJson(defaults), StandardCharsets.UTF_8);
			return defaults;
		}
		return parse(Files.readString(file, StandardCharsets.UTF_8));
	}

	public ViewerParrotsConfig parse(String json) {
		JsonView root = JsonView.ofRoot(StrictJson.parseObject(json));
		root.requireKnownFields("schemaVersion", "maxActiveParrots", "deathMode", "nameFormat",
				"nameVisible", "permissions", "come", "follow", "dance", "cooldowns", "sound",
				"behavior", "limits", "recovery", "storage");

		ViewerParrotsConfig config = new ViewerParrotsConfig(
				root.requireInt("schemaVersion"),
				root.requireInt("maxActiveParrots"),
				root.requireEnum("deathMode", DeathMode.class),
				root.requireString("nameFormat"),
				root.requireBoolean("nameVisible"),
				parsePermissions(root.requireObject("permissions")),
				parseCome(root.requireObject("come")),
				parseFollow(root.requireObject("follow")),
				parseDance(root.requireObject("dance")),
				parseCooldowns(root.requireObject("cooldowns")),
				parseSound(root.requireObject("sound")),
				parseBehavior(root.requireObject("behavior")),
				parseLimits(root.requireObject("limits")),
				parseRecovery(root.requireObject("recovery")),
				parseStorage(root.requireObject("storage")));

		this.validator.validate(config);
		return config;
	}

	private static ViewerParrotsConfig.Permissions parsePermissions(JsonView view) {
		view.requireKnownFields("controlLevel", "adminLevel");
		return new ViewerParrotsConfig.Permissions(
				view.requireInt("controlLevel"), view.requireInt("adminLevel"));
	}

	private static ViewerParrotsConfig.Come parseCome(JsonView view) {
		view.requireKnownFields("maxPathfindDistance", "arrivalRadius", "timeoutTicks",
				"teleportIfFar", "teleportOnPathFailure", "crossDimensionTeleport");
		return new ViewerParrotsConfig.Come(
				view.requireInt("maxPathfindDistance"),
				view.requireInt("arrivalRadius"),
				view.requireInt("timeoutTicks"),
				view.requireBoolean("teleportIfFar"),
				view.requireBoolean("teleportOnPathFailure"),
				view.requireBoolean("crossDimensionTeleport"));
	}

	private static ViewerParrotsConfig.Follow parseFollow(JsonView view) {
		view.requireKnownFields("startDistance", "stopDistance", "repathTicks");
		return new ViewerParrotsConfig.Follow(
				view.requireInt("startDistance"),
				view.requireInt("stopDistance"),
				view.requireInt("repathTicks"));
	}

	private static ViewerParrotsConfig.Dance parseDance(JsonView view) {
		view.requireKnownFields("durationTicks");
		return new ViewerParrotsConfig.Dance(view.requireInt("durationTicks"));
	}

	private static ViewerParrotsConfig.Cooldowns parseCooldowns(JsonView view) {
		view.requireKnownFields("come", "follow", "sit", "dance", "scream", "respawn");
		return new ViewerParrotsConfig.Cooldowns(
				view.requireInt("come"),
				view.requireInt("follow"),
				view.requireInt("sit"),
				view.requireInt("dance"),
				view.requireInt("scream"),
				view.requireInt("respawn"));
	}

	private static ViewerParrotsConfig.Sound parseSound(JsonView view) {
		view.requireKnownFields("volume", "minPitch", "maxPitch", "allowedSounds");
		return new ViewerParrotsConfig.Sound(
				view.requireDouble("volume"),
				view.requireDouble("minPitch"),
				view.requireDouble("maxPitch"),
				view.requireStringArray("allowedSounds"));
	}

	private static ViewerParrotsConfig.Behavior parseBehavior(JsonView view) {
		view.requireKnownFields("enabled", "idleMinTicks", "idleMaxTicks", "dangerCheckTicks",
				"dangerRadius", "lazyMinTicks", "lazyMaxTicks", "normalSoundChance",
				"loudSoundChance", "traitWeights");

		JsonView weightsView = view.requireObject("traitWeights");
		String[] traitNames = new String[ParrotTrait.values().length];
		for (int i = 0; i < ParrotTrait.values().length; i++) {
			traitNames[i] = ParrotTrait.values()[i].name();
		}
		weightsView.requireKnownFields(traitNames);

		Map<ParrotTrait, Integer> weights = new LinkedHashMap<>();
		for (ParrotTrait trait : ParrotTrait.values()) {
			if (weightsView.has(trait.name())) {
				weights.put(trait, weightsView.requireInt(trait.name()));
			}
		}

		return new ViewerParrotsConfig.Behavior(
				view.requireBoolean("enabled"),
				view.requireInt("idleMinTicks"),
				view.requireInt("idleMaxTicks"),
				view.requireInt("dangerCheckTicks"),
				view.requireInt("dangerRadius"),
				view.requireInt("lazyMinTicks"),
				view.requireInt("lazyMaxTicks"),
				view.requireDouble("normalSoundChance"),
				view.requireDouble("loudSoundChance"),
				weights);
	}

	private static ViewerParrotsConfig.Limits parseLimits(JsonView view) {
		view.requireKnownFields("actionsPer20Ticks", "soundsPer20Ticks", "queueCapacity");
		return new ViewerParrotsConfig.Limits(
				view.requireInt("actionsPer20Ticks"),
				view.requireInt("soundsPer20Ticks"),
				view.requireInt("queueCapacity"));
	}

	private static ViewerParrotsConfig.Recovery parseRecovery(JsonView view) {
		view.requireKnownFields("maxConcurrentJobs", "timeoutTicks");
		return new ViewerParrotsConfig.Recovery(
				view.requireInt("maxConcurrentJobs"), view.requireInt("timeoutTicks"));
	}

	private static ViewerParrotsConfig.Storage parseStorage(JsonView view) {
		view.requireKnownFields("checkpointIntervalTicks", "shutdownTimeoutSeconds");
		return new ViewerParrotsConfig.Storage(
				view.requireInt("checkpointIntervalTicks"), view.requireInt("shutdownTimeoutSeconds"));
	}

	/** Сериализация с отступами: файл читают и правят люди. */
	public static String toJson(ViewerParrotsConfig config) {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", config.schemaVersion());
		root.addProperty("maxActiveParrots", config.maxActiveParrots());
		root.addProperty("deathMode", config.deathMode().name());
		root.addProperty("nameFormat", config.nameFormat());
		root.addProperty("nameVisible", config.nameVisible());

		JsonObject permissions = new JsonObject();
		permissions.addProperty("controlLevel", config.permissions().controlLevel());
		permissions.addProperty("adminLevel", config.permissions().adminLevel());
		root.add("permissions", permissions);

		JsonObject come = new JsonObject();
		come.addProperty("maxPathfindDistance", config.come().maxPathfindDistance());
		come.addProperty("arrivalRadius", config.come().arrivalRadius());
		come.addProperty("timeoutTicks", config.come().timeoutTicks());
		come.addProperty("teleportIfFar", config.come().teleportIfFar());
		come.addProperty("teleportOnPathFailure", config.come().teleportOnPathFailure());
		come.addProperty("crossDimensionTeleport", config.come().crossDimensionTeleport());
		root.add("come", come);

		JsonObject follow = new JsonObject();
		follow.addProperty("startDistance", config.follow().startDistance());
		follow.addProperty("stopDistance", config.follow().stopDistance());
		follow.addProperty("repathTicks", config.follow().repathTicks());
		root.add("follow", follow);

		JsonObject dance = new JsonObject();
		dance.addProperty("durationTicks", config.dance().durationTicks());
		root.add("dance", dance);

		JsonObject cooldowns = new JsonObject();
		cooldowns.addProperty("come", config.cooldowns().come());
		cooldowns.addProperty("follow", config.cooldowns().follow());
		cooldowns.addProperty("sit", config.cooldowns().sit());
		cooldowns.addProperty("dance", config.cooldowns().dance());
		cooldowns.addProperty("scream", config.cooldowns().scream());
		cooldowns.addProperty("respawn", config.cooldowns().respawn());
		root.add("cooldowns", cooldowns);

		JsonObject sound = new JsonObject();
		sound.addProperty("volume", config.sound().volume());
		sound.addProperty("minPitch", config.sound().minPitch());
		sound.addProperty("maxPitch", config.sound().maxPitch());
		com.google.gson.JsonArray allowed = new com.google.gson.JsonArray();
		for (String id : config.sound().allowedSounds()) {
			allowed.add(id);
		}
		sound.add("allowedSounds", allowed);
		root.add("sound", sound);

		JsonObject behavior = new JsonObject();
		behavior.addProperty("enabled", config.behavior().enabled());
		behavior.addProperty("idleMinTicks", config.behavior().idleMinTicks());
		behavior.addProperty("idleMaxTicks", config.behavior().idleMaxTicks());
		behavior.addProperty("dangerCheckTicks", config.behavior().dangerCheckTicks());
		behavior.addProperty("dangerRadius", config.behavior().dangerRadius());
		behavior.addProperty("lazyMinTicks", config.behavior().lazyMinTicks());
		behavior.addProperty("lazyMaxTicks", config.behavior().lazyMaxTicks());
		behavior.addProperty("normalSoundChance", config.behavior().normalSoundChance());
		behavior.addProperty("loudSoundChance", config.behavior().loudSoundChance());
		JsonObject weights = new JsonObject();
		for (ParrotTrait trait : ParrotTrait.values()) {
			Integer weight = config.behavior().traitWeights().get(trait);
			weights.addProperty(trait.name(), weight == null ? 0 : weight);
		}
		behavior.add("traitWeights", weights);
		root.add("behavior", behavior);

		JsonObject limits = new JsonObject();
		limits.addProperty("actionsPer20Ticks", config.limits().actionsPer20Ticks());
		limits.addProperty("soundsPer20Ticks", config.limits().soundsPer20Ticks());
		limits.addProperty("queueCapacity", config.limits().queueCapacity());
		root.add("limits", limits);

		JsonObject recovery = new JsonObject();
		recovery.addProperty("maxConcurrentJobs", config.recovery().maxConcurrentJobs());
		recovery.addProperty("timeoutTicks", config.recovery().timeoutTicks());
		root.add("recovery", recovery);

		JsonObject storage = new JsonObject();
		storage.addProperty("checkpointIntervalTicks", config.storage().checkpointIntervalTicks());
		storage.addProperty("shutdownTimeoutSeconds", config.storage().shutdownTimeoutSeconds());
		root.add("storage", storage);

		return new com.google.gson.GsonBuilder()
				.setPrettyPrinting()
				.disableHtmlEscaping()
				.create()
				.toJson(root);
	}

	/** Список звуков в порядке конфига, для удобства adapter-ов. */
	public static List<String> allowedSounds(ViewerParrotsConfig config) {
		return config.sound().allowedSounds();
	}
}
