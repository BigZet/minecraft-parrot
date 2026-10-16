package io.github.viewerparrots.cosmetic;

import io.github.viewerparrots.domain.ParrotVariant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/** Имена окрасов для {@code !color [цвет]}: русские и английские, без учёта регистра и «ё». */
public final class ParrotColors {
    private static final Map<String, ParrotVariant> NAMES = Map.ofEntries(
            Map.entry("красный", ParrotVariant.RED_BLUE), Map.entry("red", ParrotVariant.RED_BLUE),
            Map.entry("синий", ParrotVariant.BLUE), Map.entry("blue", ParrotVariant.BLUE),
            Map.entry("зеленый", ParrotVariant.GREEN), Map.entry("green", ParrotVariant.GREEN),
            Map.entry("желтый", ParrotVariant.YELLOW_BLUE), Map.entry("yellow", ParrotVariant.YELLOW_BLUE),
            Map.entry("серый", ParrotVariant.GRAY), Map.entry("gray", ParrotVariant.GRAY), Map.entry("grey", ParrotVariant.GRAY));

    private ParrotColors() { }

    /** {@code "Зелёный"}, {@code "GREEN"} и {@code "green"} дают один и тот же окрас; неизвестное имя — пусто. */
    public static Optional<ParrotVariant> parse(String name) {
        if (name == null) return Optional.empty();
        String key = name.strip().toLowerCase(Locale.ROOT).replace('ё', 'е');
        ParrotVariant named = NAMES.get(key);
        if (named != null) return Optional.of(named);
        for (ParrotVariant variant : ParrotVariant.values()) {
            if (variant.name().toLowerCase(Locale.ROOT).equals(key)) return Optional.of(variant);
        }
        return Optional.empty();
    }

    /** Как {@code !hat}: повтор без аргумента всегда даёт другой окрас. */
    public static ParrotVariant randomDifferent(ParrotVariant current, Random random) {
        Objects.requireNonNull(random, "random");
        ParrotVariant[] variants = ParrotVariant.values();
        ParrotVariant[] others = new ParrotVariant[variants.length - 1];
        int count = 0;
        for (ParrotVariant variant : variants) if (variant != current) others[count++] = variant;
        return others[random.nextInt(count)];
    }

    public static String russianName(ParrotVariant variant) {
        return switch (variant) {
            case RED_BLUE -> "красный";
            case BLUE -> "синий";
            case GREEN -> "зелёный";
            case YELLOW_BLUE -> "жёлтый";
            case GRAY -> "серый";
        };
    }
}
