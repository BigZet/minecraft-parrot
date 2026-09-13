package io.github.viewerparrots.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Типизированный доступ к разобранному объекту с отслеживанием пути.
 *
 * <p>Неизвестное поле — ошибка, а не игнорирование: раздел 11 ТЗ требует, чтобы опечатка
 * в конфиге не пропадала молча. Поэтому у каждого объекта вызывается
 * {@link #requireKnownFields(String...)}.
 */
public final class JsonView {
	private final JsonObject object;
	private final String path;

	public JsonView(JsonObject object, String path) {
		this.object = Objects.requireNonNull(object, "object");
		this.path = Objects.requireNonNull(path, "path");
	}

	public static JsonView ofRoot(JsonObject object) {
		return new JsonView(object, "$");
	}

	public String path() {
		return this.path;
	}

	public Set<String> fieldNames() {
		return this.object.keySet();
	}

	public boolean has(String field) {
		return this.object.has(field) && !this.object.get(field).isJsonNull();
	}

	/** @throws JsonSchemaException если встретилось поле, отсутствующее в списке известных */
	public void requireKnownFields(String... known) {
		Set<String> allowed = Set.of(known);
		for (String actual : this.object.keySet()) {
			if (!allowed.contains(actual)) {
				throw new JsonSchemaException(child(actual), "неизвестное поле");
			}
		}
	}

	public JsonView requireObject(String field) {
		JsonElement element = require(field);
		if (!element.isJsonObject()) {
			throw new JsonSchemaException(child(field), "ожидался объект");
		}
		return new JsonView(element.getAsJsonObject(), child(field));
	}

	public Optional<JsonView> optionalObject(String field) {
		return has(field) ? Optional.of(requireObject(field)) : Optional.empty();
	}

	public String requireString(String field) {
		JsonElement element = require(field);
		if (!isString(element)) {
			throw new JsonSchemaException(child(field), "ожидалась строка");
		}
		return element.getAsString();
	}

	public Optional<String> optionalString(String field) {
		return has(field) ? Optional.of(requireString(field)) : Optional.empty();
	}

	public boolean requireBoolean(String field) {
		JsonElement element = require(field);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
			throw new JsonSchemaException(child(field), "ожидалось true или false");
		}
		return element.getAsBoolean();
	}

	public long requireLong(String field) {
		JsonElement element = require(field);
		if (!isNumber(element)) {
			throw new JsonSchemaException(child(field), "ожидалось число");
		}
		java.math.BigDecimal value = element.getAsBigDecimal();
		try {
			return value.longValueExact();
		} catch (ArithmeticException e) {
			throw new JsonSchemaException(child(field), "ожидалось целое число, получено " + value, e);
		}
	}

	public int requireInt(String field) {
		long value = requireLong(field);
		if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
			throw new JsonSchemaException(child(field), "значение вне диапазона int: " + value);
		}
		return (int) value;
	}

	public double requireDouble(String field) {
		JsonElement element = require(field);
		if (!isNumber(element)) {
			throw new JsonSchemaException(child(field), "ожидалось число");
		}
		double value = element.getAsDouble();
		if (!Double.isFinite(value)) {
			throw new JsonSchemaException(child(field), "число не является конечным");
		}
		return value;
	}

	public UUID requireUuid(String field) {
		String raw = requireString(field);
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException e) {
			throw new JsonSchemaException(child(field), "некорректный UUID: " + raw, e);
		}
	}

	public Optional<UUID> optionalUuid(String field) {
		return has(field) ? Optional.of(requireUuid(field)) : Optional.empty();
	}

	public Instant requireInstant(String field) {
		String raw = requireString(field);
		try {
			return Instant.parse(raw);
		} catch (DateTimeParseException e) {
			throw new JsonSchemaException(child(field), "некорректная метка времени ISO-8601: " + raw, e);
		}
	}

	public Optional<Instant> optionalInstant(String field) {
		return has(field) ? Optional.of(requireInstant(field)) : Optional.empty();
	}

	/** Неизвестная константа — ошибка: молча сменить trait или variant нельзя (раздел 8.3 ТЗ). */
	public <E extends Enum<E>> E requireEnum(String field, Class<E> type) {
		String raw = requireString(field);
		try {
			return Enum.valueOf(type, raw);
		} catch (IllegalArgumentException e) {
			throw new JsonSchemaException(child(field),
					"неизвестное значение " + raw + "; допустимы " + names(type), e);
		}
	}

	public List<String> requireStringArray(String field) {
		JsonElement element = require(field);
		if (!element.isJsonArray()) {
			throw new JsonSchemaException(child(field), "ожидался массив строк");
		}
		JsonArray array = element.getAsJsonArray();
		List<String> result = new ArrayList<>(array.size());
		for (int i = 0; i < array.size(); i++) {
			JsonElement item = array.get(i);
			if (!isString(item)) {
				throw new JsonSchemaException(child(field) + "[" + i + "]", "ожидалась строка");
			}
			result.add(item.getAsString());
		}
		return List.copyOf(result);
	}

	public List<JsonView> requireObjectArray(String field) {
		JsonElement element = require(field);
		if (!element.isJsonArray()) {
			throw new JsonSchemaException(child(field), "ожидался массив объектов");
		}
		JsonArray array = element.getAsJsonArray();
		List<JsonView> result = new ArrayList<>(array.size());
		for (int i = 0; i < array.size(); i++) {
			JsonElement item = array.get(i);
			String itemPath = child(field) + "[" + i + "]";
			if (!item.isJsonObject()) {
				throw new JsonSchemaException(itemPath, "ожидался объект");
			}
			result.add(new JsonView(item.getAsJsonObject(), itemPath));
		}
		return List.copyOf(result);
	}

	public String child(String field) {
		return this.path + "." + field;
	}

	public JsonObject raw() {
		return this.object;
	}

	private JsonElement require(String field) {
		JsonElement element = this.object.get(field);
		if (element == null) {
			throw new JsonSchemaException(child(field), "обязательное поле отсутствует");
		}
		if (element.isJsonNull()) {
			throw new JsonSchemaException(child(field), "поле не может быть null");
		}
		return element;
	}

	private static boolean isString(JsonElement element) {
		return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
	}

	private static boolean isNumber(JsonElement element) {
		if (!element.isJsonPrimitive()) {
			return false;
		}
		JsonPrimitive primitive = element.getAsJsonPrimitive();
		return primitive.isNumber();
	}

	private static <E extends Enum<E>> String names(Class<E> type) {
		StringBuilder builder = new StringBuilder();
		for (E constant : type.getEnumConstants()) {
			if (builder.length() > 0) {
				builder.append(", ");
			}
			builder.append(constant.name());
		}
		return builder.toString().toUpperCase(Locale.ROOT);
	}
}
