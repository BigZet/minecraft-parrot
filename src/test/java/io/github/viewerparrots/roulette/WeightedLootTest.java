package io.github.viewerparrots.roulette;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class WeightedLootTest {
    @Test
    void respectsEveryCategoryBoundaryAndMarksOnlyTheJackpot() {
        RouletteSettings settings = RouletteSettings.defaults();
        int[] rolls = {0, 64, 65, 89, 90, 98, 99};
        int[] categoryIndices = {0, 0, 1, 1, 2, 2, 3};
        for (int i = 0; i < rolls.length; i++) {
            FixedRandom random = new FixedRandom(rolls[i], 2);
            var selection = WeightedLoot.pick(settings, random);
            var category = settings.categories().get(categoryIndices[i]);
            assertEquals(category.items().get(2), selection.prize());
            assertEquals(category.color(), selection.color());
            assertEquals(categoryIndices[i] == 3, selection.jackpot());
            assertEquals(List.of(100, 3), random.bounds);
        }
    }

    @Test
    void categoryWeightDoesNotDependOnItsNumberOfItems() {
        var seed = new RouletteSettings.Prize("minecraft:wheat_seeds", 8);
        var diamond = new RouletteSettings.Prize("minecraft:diamond", 1);
        var gold = new RouletteSettings.Prize("minecraft:gold_ingot", 2);
        var settings = new RouletteSettings(80, 40, 1200, 16, List.of(
                new RouletteSettings.Category("common", 1, 0x999999, List.of(seed)),
                new RouletteSettings.Category("jackpot", 1, 0xFFCC33, List.of(diamond, gold))));
        assertEquals(seed, WeightedLoot.pick(settings, new FixedRandom(0, 0)).prize());
        assertEquals(gold, WeightedLoot.pick(settings, new FixedRandom(1, 1)).prize());
    }

    @Test
    void everyConfiguredPrizeCanWinWithoutAnEmptyResult() {
        var settings = RouletteSettings.defaults();
        int[] categoryRolls = {0, 65, 90, 99};
        for (int c = 0; c < settings.categories().size(); c++) {
            var items = settings.categories().get(c).items();
            for (int item = 0; item < items.size(); item++) {
                assertEquals(items.get(item), WeightedLoot.pick(settings,
                        new FixedRandom(categoryRolls[c], item)).prize());
            }
        }
    }

    private static final class FixedRandom extends Random {
        private final int[] values;
        private int index;
        private final java.util.ArrayList<Integer> bounds = new java.util.ArrayList<>();
        private FixedRandom(int... values) { this.values = values; }
        @Override public int nextInt(int bound) {
            bounds.add(bound);
            int value = values[index++];
            assertTrue(value >= 0 && value < bound);
            return value;
        }
    }
}
