package io.github.viewerparrots.cosmetic;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParrotHatsTest {
	@Test
	void neverRepeatsTheCurrentHat() {
		Random random = new Random(42);
		for (String current : ParrotHats.IDS) {
			Set<String> alternatives = new HashSet<>();
			for (int i = 0; i < 200; i++) {
				String selected = ParrotHats.randomDifferent(current, random);
				assertNotEquals(current, selected);
				assertTrue(ParrotHats.isKnown(selected));
				alternatives.add(selected);
			}
			assertEquals(7, alternatives.size());
		}
	}

	@Test
	void anEmptyOrUnknownCurrentHatCanChooseEveryDesign() {
		Set<String> expected = Set.of("royal_crown", "top_hat", "pirate_hat", "mushroom_hat",
				"wizard_hat", "propeller_cap", "cowboy_hat", "ufo_hat");
		for (String current : new String[]{"", "removed_hat", null}) {
			Random random = new Random(42);
			Set<String> selected = new HashSet<>();
			for (int i = 0; i < 200; i++) {
				selected.add(ParrotHats.randomDifferent(current, random));
			}
			assertEquals(expected, selected);
		}
	}

	@Test
	void normalizesKnownIdsAndClearsInvalidValues() {
		assertEquals("royal_crown", ParrotHats.normalize("  ROYAL_CROWN\t"));
		assertEquals("ufo_hat", ParrotHats.normalize("ufo_hat"));
		assertEquals("", ParrotHats.normalize("removed_hat"));
		assertEquals("", ParrotHats.normalize(""));
		assertEquals("", ParrotHats.normalize("\t\n"));
		assertEquals("", ParrotHats.normalize(null));
		assertTrue(ParrotHats.isKnown("wizard_hat"));
		assertFalse(ParrotHats.isKnown("removed_hat"));
		assertFalse(ParrotHats.isKnown(null));
	}

	@Test
	void normalizedCurrentHatIsAlsoExcluded() {
		Random random = new Random(42);
		for (int i = 0; i < 200; i++) {
			String selected = ParrotHats.randomDifferent("  ROYAL_CROWN  ", random);
			assertNotEquals("royal_crown", selected);
			assertTrue(ParrotHats.isKnown(selected));
		}
	}
}
