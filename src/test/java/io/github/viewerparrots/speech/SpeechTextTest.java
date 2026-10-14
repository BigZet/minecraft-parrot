package io.github.viewerparrots.speech;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpeechTextTest {
	@Test
	void normalizesUnicodeWhitespaceWithoutChangingWords() {
		assertEquals("Привет, World! 🌻", SpeechText.firstThirtyWords("\u00a0Привет,\tWorld!\u2003🌻\r\n"));
	}

	@Test
	void keepsOnlyTheFirstThirtyWords() {
		String thirty = "1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30";
		assertEquals(thirty, SpeechText.firstThirtyWords(thirty + " 31 32"));
	}

	@Test
	void rejectsEmptyTextAndNull() {
		assertThrows(IllegalArgumentException.class, () -> SpeechText.firstThirtyWords("\u00a0\t\r\n"));
		assertThrows(NullPointerException.class, () -> SpeechText.firstThirtyWords(null));
	}
}
