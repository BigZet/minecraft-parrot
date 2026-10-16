package io.github.viewerparrots.speech;

import io.github.viewerparrots.domain.ParrotTrait;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Интонации {@code !say [стиль] текст}. ElevenLabs v4 (включая Turbo) читает audio-теги
 * вида {@code [whispers]} прямо из текста, поэтому стиль — это строка-тег перед фразой,
 * без второго запроса и без LLM.
 *
 * <p>Скобки из текста зрителя вырезаются до склейки: иначе зритель вставлял бы любые теги сам.
 * Стиль берётся только из первого токена в квадратных скобках.
 */
public final class SpeechStyle {
    /** Стиль из команды: ключ стиля в нижнем регистре либо {@code null}, и текст без него. */
    public record Parsed(String style, String text) { }

    public static final int MAX_STYLE_LENGTH = 32;
    public static final int MAX_TAGS = 64;
    private static final Pattern STYLE_KEY = Pattern.compile("[\\p{L}\\p{N}_ -]{1," + MAX_STYLE_LENGTH + "}", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern TAG = Pattern.compile("\\[[A-Za-z][A-Za-z ,.-]{0,62}\\]");
    private static final Pattern LEADING_STYLE = Pattern.compile("^\\s*\\[([^\\[\\]]{0," + (MAX_STYLE_LENGTH + 8) + "})\\](.*)$", Pattern.DOTALL);

    public static final Map<String, String> DEFAULT_STYLE_TAGS;
    public static final Map<String, String> DEFAULT_TRAIT_TAGS;

    static {
        var styles = new LinkedHashMap<String, String>();
        styles.put("шёпот", "[whispers]");
        styles.put("зло", "[angry]");
        styles.put("грусть", "[sad]");
        styles.put("радость", "[happy]");
        styles.put("страх", "[fearful]");
        styles.put("усталость", "[tired]");
        styles.put("крик", "[shouting]");
        styles.put("сарказм", "[sarcastic]");
        DEFAULT_STYLE_TAGS = Collections.unmodifiableMap(styles);
        var traits = new LinkedHashMap<String, String>();
        traits.put(ParrotTrait.COWARD.name(), "[nervous]");
        traits.put(ParrotTrait.LAZY.name(), "[tired]");
        traits.put(ParrotTrait.LOUD.name(), "[excited, loud]");
        DEFAULT_TRAIT_TAGS = Collections.unmodifiableMap(traits);
    }

    private SpeechStyle() { }

    /** {@code "[Шёпот] привет"} → стиль {@code "шёпот"}, текст {@code " привет"}; без скобок — стиль {@code null}. */
    public static Parsed parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        var match = LEADING_STYLE.matcher(raw);
        if (!match.matches()) return new Parsed(null, raw);
        String style = normalizeKey(match.group(1));
        if (style.length() > MAX_STYLE_LENGTH) style = style.substring(0, MAX_STYLE_LENGTH);
        return new Parsed(style, match.group(2));
    }

    /** Ключи сравниваются без учёта регистра и разницы между «ё» и «е». */
    public static String normalizeKey(String key) {
        return Objects.requireNonNull(key, "key").strip().toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    public static boolean isValidKey(String key) {
        return key != null && STYLE_KEY.matcher(key.strip()).matches();
    }

    /** Пустая строка — «без тега»; иначе один тег ElevenLabs латиницей, например {@code [excited, loud]}. */
    public static boolean isValidTag(String tag) {
        return tag != null && (tag.isEmpty() || TAG.matcher(tag).matches());
    }

    /** Тег по ключу стиля или {@code null}, если такого стиля нет. */
    public static String tagFor(Map<String, String> styleTags, String style) {
        if (style == null) return null;
        String wanted = normalizeKey(style);
        for (var entry : styleTags.entrySet()) {
            if (normalizeKey(entry.getKey()).equals(wanted)) return entry.getValue();
        }
        return null;
    }

    /** Тег характера либо пустая строка: {@code NORMAL} и неактивные характеры говорят обычно. */
    public static String traitTag(Map<String, String> traitTags, ParrotTrait trait) {
        return traitTags.getOrDefault(Objects.requireNonNull(trait, "trait").name(), "");
    }

    /** Текст зрителя никогда не несёт своих тегов. */
    public static String sanitize(String text) {
        return Objects.requireNonNull(text, "text").replace("[", "").replace("]", "");
    }

    public static String compose(String tag, String text) {
        Objects.requireNonNull(text, "text");
        return tag == null || tag.isEmpty() ? text : tag + " " + text;
    }

    /** Проверенная копия карты тегов для настроек; ключи характеров должны быть именами {@link ParrotTrait}. */
    public static Map<String, String> validatedTags(Map<String, String> tags, String what, boolean traitKeys) {
        Objects.requireNonNull(tags, what);
        if (tags.size() > MAX_TAGS) throw new IllegalArgumentException(what + ": too many entries");
        var copy = new LinkedHashMap<String, String>();
        for (var entry : tags.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().strip();
            if (traitKeys) {
                boolean known = false;
                for (ParrotTrait trait : ParrotTrait.values()) known |= trait.name().equals(key);
                if (!known) throw new IllegalArgumentException(what + ": unknown trait " + key);
            } else if (!isValidKey(key)) {
                throw new IllegalArgumentException(what + ": invalid style name");
            }
            if (!isValidTag(entry.getValue())) throw new IllegalArgumentException(what + ": tag must look like [whispers]");
            if (copy.put(key, entry.getValue()) != null) throw new IllegalArgumentException(what + ": duplicate " + key);
        }
        return Collections.unmodifiableMap(copy);
    }
}
