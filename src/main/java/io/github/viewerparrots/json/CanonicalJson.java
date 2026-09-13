package io.github.viewerparrots.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Канонизация JSON и контрольная сумма записей журнала (раздел 8.2 ТЗ).
 *
 * <p>Правила зафиксированы здесь и проверяются тестом, потому что checksum обязана
 * совпадать между запусками и версиями:
 * <ul>
 *   <li>ключи объектов сортируются по code point строки, а не по локали;</li>
 *   <li>незначащие пробелы отсутствуют;</li>
 *   <li>строки кодируются в UTF-8;</li>
 *   <li>поле {@code checksum} исключается из подсчёта — иначе оно зависело бы от себя.</li>
 * </ul>
 */
public final class CanonicalJson {
	public static final String CHECKSUM_FIELD = "checksum";

	private CanonicalJson() {
	}

	/** Каноническое текстовое представление без поля checksum на верхнем уровне. */
	public static String canonicalize(JsonObject object) {
		JsonObject copy = object.deepCopy();
		copy.remove(CHECKSUM_FIELD);
		StringBuilder builder = new StringBuilder();
		write(copy, builder);
		return builder.toString();
	}

	/** SHA-256 канонизированного представления, в нижнем регистре hex. */
	public static String checksum(JsonObject object) {
		byte[] bytes = canonicalize(object).getBytes(StandardCharsets.UTF_8);
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 обязателен для любой реализации Java.
			throw new IllegalStateException("SHA-256 недоступен", e);
		}
	}

	public static boolean matches(JsonObject object, String expectedChecksum) {
		return checksum(object).equals(expectedChecksum);
	}

	private static void write(JsonElement element, StringBuilder out) {
		if (element.isJsonNull()) {
			out.append("null");
			return;
		}
		if (element.isJsonObject()) {
			writeObject(element.getAsJsonObject(), out);
			return;
		}
		if (element.isJsonArray()) {
			writeArray(element.getAsJsonArray(), out);
			return;
		}
		writePrimitive(element.getAsJsonPrimitive(), out);
	}

	private static void writeObject(JsonObject object, StringBuilder out) {
		List<String> keys = new ArrayList<>(object.keySet());
		// compareTo сравнивает по UTF-16 code unit; для сортировки по code point
		// суррогатные пары нужно учитывать явно.
		keys.sort(CanonicalJson::compareByCodePoint);

		out.append('{');
		boolean first = true;
		for (String key : keys) {
			if (!first) {
				out.append(',');
			}
			first = false;
			writeString(key, out);
			out.append(':');
			write(object.get(key), out);
		}
		out.append('}');
	}

	private static void writeArray(JsonArray array, StringBuilder out) {
		out.append('[');
		for (int i = 0; i < array.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			write(array.get(i), out);
		}
		out.append(']');
	}

	private static void writePrimitive(JsonPrimitive primitive, StringBuilder out) {
		if (primitive.isString()) {
			writeString(primitive.getAsString(), out);
			return;
		}
		if (primitive.isBoolean()) {
			out.append(primitive.getAsBoolean());
			return;
		}
		// Число печатается ровно так, как было разобрано: округление изменило бы checksum.
		out.append(primitive.getAsBigDecimal().toString());
	}

	private static void writeString(String value, StringBuilder out) {
		out.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				case '\b' -> out.append("\\b");
				case '\f' -> out.append("\\f");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
				}
			}
		}
		out.append('"');
	}

	static int compareByCodePoint(String left, String right) {
		int leftIndex = 0;
		int rightIndex = 0;
		while (leftIndex < left.length() && rightIndex < right.length()) {
			int leftCp = left.codePointAt(leftIndex);
			int rightCp = right.codePointAt(rightIndex);
			if (leftCp != rightCp) {
				return Integer.compare(leftCp, rightCp);
			}
			leftIndex += Character.charCount(leftCp);
			rightIndex += Character.charCount(rightCp);
		}
		return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
	}
}
