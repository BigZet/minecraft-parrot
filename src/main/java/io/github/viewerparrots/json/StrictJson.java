package io.github.viewerparrots.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;

/**
 * Разбор JSON без молчаливых допущений (разделы 8.3 и 11 ТЗ).
 *
 * <p>Стандартный {@code JsonParser} для этих файлов не годится: он принимает дубликаты
 * ключей, оставляя последний, и в lenient-режиме глотает мусор после документа. Здесь оба
 * случая — ошибка с путём, потому что молча сменить trait или viewerId нельзя.
 *
 * <p>Не-finite числа отвергаются: в строгом режиме {@code NaN} и {@code Infinity} вообще
 * не являются валидным JSON, а координаты обязаны быть конечными.
 */
public final class StrictJson {

	private StrictJson() {
	}

	public static JsonObject parseObject(String text) {
		JsonElement root = parse(text);
		if (!root.isJsonObject()) {
			throw new JsonSchemaException("$", "ожидался объект, получено " + describe(root));
		}
		return root.getAsJsonObject();
	}

	public static JsonElement parse(String text) {
		if (text == null) {
			throw new JsonSchemaException("$", "пустое содержимое");
		}
		try (JsonReader reader = new JsonReader(new StringReader(text))) {
			// Minecraft 1.21.1 несёт Gson 2.10.1, где enum Strictness ещё не появился.
			reader.setLenient(false);
			JsonElement value = read(reader, "$");
			if (reader.peek() != JsonToken.END_DOCUMENT) {
				throw new JsonSchemaException("$", "после документа есть лишнее содержимое");
			}
			return value;
		} catch (MalformedJsonException e) {
			throw new JsonSchemaException("$", "некорректный JSON: " + e.getMessage(), e);
		} catch (IOException e) {
			throw new JsonSchemaException("$", "не удалось прочитать JSON: " + e.getMessage(), e);
		}
	}

	private static JsonElement read(JsonReader reader, String path) throws IOException {
		JsonToken token = reader.peek();
		return switch (token) {
			case BEGIN_OBJECT -> readObject(reader, path);
			case BEGIN_ARRAY -> readArray(reader, path);
			case STRING -> new JsonPrimitive(reader.nextString());
			case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
			case NUMBER -> readNumber(reader, path);
			case NULL -> {
				reader.nextNull();
				yield JsonNull.INSTANCE;
			}
			default -> throw new JsonSchemaException(path, "неожиданный элемент " + token);
		};
	}

	private static JsonObject readObject(JsonReader reader, String path) throws IOException {
		JsonObject object = new JsonObject();
		reader.beginObject();
		while (reader.hasNext()) {
			String name = reader.nextName();
			String childPath = path + "." + name;
			if (object.has(name)) {
				// Последний-выигрывает недопустим: дубликат мог бы тихо подменить identity.
				throw new JsonSchemaException(childPath, "дублирующийся ключ");
			}
			object.add(name, read(reader, childPath));
		}
		reader.endObject();
		return object;
	}

	private static JsonArray readArray(JsonReader reader, String path) throws IOException {
		JsonArray array = new JsonArray();
		reader.beginArray();
		int index = 0;
		while (reader.hasNext()) {
			array.add(read(reader, path + "[" + index + "]"));
			index++;
		}
		reader.endArray();
		return array;
	}

	private static JsonElement readNumber(JsonReader reader, String path) throws IOException {
		String raw = reader.nextString();
		BigDecimal value;
		try {
			value = new BigDecimal(raw);
		} catch (NumberFormatException e) {
			throw new JsonSchemaException(path, "не является числом: " + raw, e);
		}
		double asDouble = value.doubleValue();
		if (!Double.isFinite(asDouble)) {
			throw new JsonSchemaException(path, "число не является конечным: " + raw);
		}
		return new JsonPrimitive(value);
	}

	private static String describe(JsonElement element) {
		if (element.isJsonArray()) {
			return "массив";
		}
		if (element.isJsonNull()) {
			return "null";
		}
		if (element.isJsonPrimitive()) {
			return "скалярное значение";
		}
		return "объект";
	}
}
