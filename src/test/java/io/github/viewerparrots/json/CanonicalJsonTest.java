package io.github.viewerparrots.json;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Раздел 8.2 ТЗ прямо требует зафиксировать тестом правила сортировки ключей и UTF-8,
 * потому что от них зависит воспроизводимость контрольных сумм журнала.
 */
class CanonicalJsonTest {

	@Test
	@DisplayName("порядок ключей в исходном объекте не влияет на канонический вид")
	void keyOrderDoesNotMatter() {
		JsonObject first = StrictJson.parseObject("{\"b\":1,\"a\":2,\"c\":3}");
		JsonObject second = StrictJson.parseObject("{\"c\":3,\"a\":2,\"b\":1}");

		assertAll(
				() -> assertEquals("{\"a\":2,\"b\":1,\"c\":3}", CanonicalJson.canonicalize(first)),
				() -> assertEquals(CanonicalJson.canonicalize(first), CanonicalJson.canonicalize(second)),
				() -> assertEquals(CanonicalJson.checksum(first), CanonicalJson.checksum(second)));
	}

	@Test
	@DisplayName("вложенные объекты канонизируются рекурсивно")
	void nestedObjectsAreSortedToo() {
		JsonObject object = StrictJson.parseObject("{\"outer\":{\"z\":1,\"a\":{\"y\":2,\"b\":3}}}");
		assertEquals("{\"outer\":{\"a\":{\"b\":3,\"y\":2},\"z\":1}}", CanonicalJson.canonicalize(object));
	}

	@Test
	@DisplayName("порядок элементов массива значим и не сортируется")
	void arrayOrderIsPreserved() {
		JsonObject first = StrictJson.parseObject("{\"list\":[3,1,2]}");
		JsonObject second = StrictJson.parseObject("{\"list\":[1,2,3]}");

		assertAll(
				() -> assertEquals("{\"list\":[3,1,2]}", CanonicalJson.canonicalize(first)),
				() -> assertNotEquals(CanonicalJson.checksum(first), CanonicalJson.checksum(second)));
	}

	@Test
	@DisplayName("поле checksum исключается из подсчёта")
	void checksumFieldIsExcluded() {
		JsonObject withoutChecksum = StrictJson.parseObject("{\"a\":1}");
		String checksum = CanonicalJson.checksum(withoutChecksum);

		JsonObject withChecksum = StrictJson.parseObject("{\"a\":1,\"checksum\":\"" + checksum + "\"}");

		assertAll(
				() -> assertEquals(checksum, CanonicalJson.checksum(withChecksum)),
				() -> assertTrue(CanonicalJson.matches(withChecksum, checksum)));
	}

	@Test
	@DisplayName("контрольная сумма стабильна и считается по UTF-8")
	void checksumIsStableAndUtf8() {
		JsonObject cyrillic = StrictJson.parseObject("{\"displayName\":\"Дмитрий\"}");

		// Значение зафиксировано намеренно: изменение правил канонизации обязано ломать тест.
		String expected = sha256Hex("{\"displayName\":\"Дмитрий\"}");

		assertAll(
				() -> assertEquals("{\"displayName\":\"Дмитрий\"}", CanonicalJson.canonicalize(cyrillic)),
				() -> assertEquals(expected, CanonicalJson.checksum(cyrillic)),
				() -> assertEquals(64, CanonicalJson.checksum(cyrillic).length()));
	}

	@Test
	@DisplayName("незначащие пробелы не влияют на сумму")
	void whitespaceIsIgnored() {
		JsonObject spaced = StrictJson.parseObject("{\n  \"a\" : 1 ,\n  \"b\" : 2\n}");
		JsonObject compact = StrictJson.parseObject("{\"a\":1,\"b\":2}");

		assertEquals(CanonicalJson.checksum(compact), CanonicalJson.checksum(spaced));
	}

	@Test
	@DisplayName("ключи сортируются по code point, а не по UTF-16 code unit")
	void keysAreSortedByCodePoint() {
		// U+1F99C (эмодзи) больше U+FF01, но в UTF-16 его суррогат D83E меньше FF01.
		String emoji = "🦜";
		String fullWidthBang = "！";

		assertTrue(CanonicalJson.compareByCodePoint(fullWidthBang, emoji) < 0,
				"по code point U+FF01 должен идти раньше U+1F99C");
		assertTrue(fullWidthBang.compareTo(emoji) > 0,
				"а обычный compareTo даёт обратный порядок — ради этого и нужна своя сортировка");
	}

	@Test
	@DisplayName("управляющие символы экранируются")
	void controlCharactersAreEscaped() {
		JsonObject object = StrictJson.parseObject("{\"a\":\"line\\nbreak\\u0001\"}");
		assertEquals("{\"a\":\"line\\nbreak\\u0001\"}", CanonicalJson.canonicalize(object));
	}

	private static String sha256Hex(String value) {
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			return java.util.HexFormat.of().formatHex(
					digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
