package io.github.viewerparrots.roulette;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** A small independent sidecar config; existing viewerparrots.json files keep their original schema. */
public record RouletteSettings(int durationTicks, int resultTicks, int cooldownTicks,
                               int queueCapacity, List<Category> categories) {
    public static final int MAX_TOTAL_WEIGHT = 1_000_000;
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    public RouletteSettings {
        range("durationTicks", durationTicks, 20, 400);
        range("resultTicks", resultTicks, 0, 100);
        range("cooldownTicks", cooldownTicks, 0, 72_000);
        range("queueCapacity", queueCapacity, 1, 64);
        categories = List.copyOf(Objects.requireNonNull(categories, "categories"));
        range("categories count", categories.size(), 1, 16);
        long total = categories.stream().mapToLong(Category::weight).sum();
        if (total > MAX_TOTAL_WEIGHT) {
            throw new IllegalArgumentException("categories total weight must be at most " + MAX_TOTAL_WEIGHT);
        }
    }

    public record Category(String name, int weight, int color, List<Prize> items) {
        public Category {
            Objects.requireNonNull(name, "category name");
            if (name.isBlank() || name.length() > 64) {
                throw new IllegalArgumentException("category name must contain 1 to 64 characters");
            }
            range("category weight", weight, 1, MAX_TOTAL_WEIGHT);
            range("category color RGB", color, 0, 0xFFFFFF);
            items = List.copyOf(Objects.requireNonNull(items, "category items"));
            range("category items count", items.size(), 1, 64);
        }
    }

    public record Prize(String itemId, int count) {
        public Prize {
            Objects.requireNonNull(itemId, "itemId");
            if (itemId.length() > 128 || !ITEM_ID.matcher(itemId).matches() || itemId.equals("minecraft:air")) {
                throw new IllegalArgumentException("itemId must be a non-air item identifier such as minecraft:diamond: " + itemId);
            }
            range("prize count", count, 1, 64);
        }
    }

    public static RouletteSettings defaults() {
        return new RouletteSettings(80, 40, 1200, 16, List.of(
                new Category("common", 65, 0xB7C8D0, List.of(
                        new Prize("minecraft:wheat_seeds", 8),
                        new Prize("minecraft:bread", 3),
                        new Prize("minecraft:feather", 4))),
                new Category("useful", 25, 0x55FF55, List.of(
                        new Prize("minecraft:coal", 4),
                        new Prize("minecraft:iron_ingot", 2),
                        new Prize("minecraft:arrow", 8))),
                new Category("rare", 9, 0xAA55FF, List.of(
                        new Prize("minecraft:gold_ingot", 2),
                        new Prize("minecraft:emerald", 1),
                        new Prize("minecraft:ender_pearl", 1))),
                new Category("jackpot", 1, 0xFFCC33, List.of(
                        new Prize("minecraft:diamond", 1),
                        new Prize("minecraft:netherite_ingot", 1),
                        new Prize("minecraft:totem_of_undying", 1)))));
    }

    private static void range(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max + ": " + value);
        }
    }
}
