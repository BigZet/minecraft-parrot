package io.github.viewerparrots.cosmetic;

import io.github.viewerparrots.domain.ParrotVariant;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ParrotColorsTest {
    @Test void namesAreAcceptedInRussianEnglishAndEnumFormIgnoringCaseAndYo() {
        assertEquals(Optional.of(ParrotVariant.GREEN), ParrotColors.parse("Зелёный"));
        assertEquals(Optional.of(ParrotVariant.GREEN), ParrotColors.parse("зеленый"));
        assertEquals(Optional.of(ParrotVariant.GREEN), ParrotColors.parse("GREEN"));
        assertEquals(Optional.of(ParrotVariant.YELLOW_BLUE), ParrotColors.parse(" жёлтый "));
        assertEquals(Optional.of(ParrotVariant.GRAY), ParrotColors.parse("grey"));
        assertEquals(Optional.of(ParrotVariant.RED_BLUE), ParrotColors.parse("red_blue"));
        assertTrue(ParrotColors.parse("фиолетовый").isEmpty());
        assertTrue(ParrotColors.parse("").isEmpty());
        assertTrue(ParrotColors.parse(null).isEmpty());
    }

    @Test void randomPickNeverRepeatsTheCurrentVariant() {
        Random random = new Random(7);
        for (ParrotVariant current : ParrotVariant.values()) {
            for (int i = 0; i < 50; i++) assertNotEquals(current, ParrotColors.randomDifferent(current, random));
        }
    }

    @Test void everyVariantHasARussianName() {
        for (ParrotVariant variant : ParrotVariant.values()) {
            assertEquals(Optional.of(variant), ParrotColors.parse(ParrotColors.russianName(variant)));
        }
    }
}
