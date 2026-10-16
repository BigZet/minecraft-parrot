package io.github.viewerparrots.roulette;

import java.util.Objects;
import java.util.random.RandomGenerator;

/** Category probability comes from its weight, independently of how many prizes it contains. */
public final class WeightedLoot {
    private WeightedLoot() { }

    public record Selection(RouletteSettings.Prize prize, int color, boolean jackpot) { }

    public static Selection pick(RouletteSettings settings, RandomGenerator random) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(random, "random");
        int total = settings.categories().stream().mapToInt(RouletteSettings.Category::weight).sum();
        int roll = random.nextInt(total);
        for (RouletteSettings.Category category : settings.categories()) {
            if (roll < category.weight()) {
                RouletteSettings.Prize prize = category.items().get(random.nextInt(category.items().size()));
                String name = category.name().trim();
                boolean jackpot = name.equalsIgnoreCase("jackpot") || name.equalsIgnoreCase("джекпот");
                return new Selection(prize, category.color(), jackpot);
            }
            roll -= category.weight();
        }
        throw new IllegalStateException("Random generator returned a value outside the category weight range");
    }
}
