package io.github.viewerparrots.cosmetic;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * Короткие эмоции {@code !emote}: анимации всем телом без mixin в модель попугая.
 * {@code hop} — серверный импульс вверх, крылья взмахивают сами по ванильной позе;
 * остальные — повороты модели в рендерере по прогрессу эмоции.
 */
public final class ParrotEmotes {
    public static final String BOW = "bow";
    public static final String SHAKE = "shake";
    public static final String NOD = "nod";
    public static final String HOP = "hop";
    public static final List<String> ALL = List.of(BOW, SHAKE, NOD, HOP);
    public static final int DURATION_TICKS = 40;

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("поклон", BOW), Map.entry("bow", BOW),
            Map.entry("нет", SHAKE), Map.entry("shake", SHAKE), Map.entry("no", SHAKE),
            Map.entry("кивок", NOD), Map.entry("да", NOD), Map.entry("nod", NOD), Map.entry("yes", NOD),
            Map.entry("прыжок", HOP), Map.entry("hop", HOP), Map.entry("jump", HOP));

    private ParrotEmotes() { }

    public static boolean isKnown(String id) { return id != null && ALL.contains(id); }

    /** {@code "Поклон"}, {@code "bow"} → {@code bow}; неизвестное имя — пусто. */
    public static Optional<String> parse(String name) {
        if (name == null) return Optional.empty();
        String key = name.strip().toLowerCase(Locale.ROOT).replace('ё', 'е');
        return Optional.ofNullable(NAMES.get(key));
    }

    public static String random(Random random) {
        return ALL.get(Objects.requireNonNull(random, "random").nextInt(ALL.size()));
    }

    public static String russianName(String id) {
        return switch (id) {
            case BOW -> "поклон";
            case SHAKE -> "нет";
            case NOD -> "кивок";
            case HOP -> "прыжок";
            default -> id;
        };
    }
}
