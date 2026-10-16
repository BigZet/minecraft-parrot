package io.github.viewerparrots.speech;

import io.github.viewerparrots.api.ActionParameters;
import io.github.viewerparrots.domain.ParrotTrait;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpeechStyleTest {
    @Test void leadingBracketTokenBecomesStyleAndIsRemovedFromText() {
        var parsed = SpeechStyle.parse("  [Шёпот] привет всем");
        assertEquals("шепот", parsed.style());
        assertEquals(" привет всем", parsed.text());
        assertNull(SpeechStyle.parse("привет [шёпот] всем").style());
        assertNull(SpeechStyle.parse("[незакрытый привет").style());
    }

    @Test void styleLookupIgnoresCaseAndYo() {
        assertEquals("[whispers]", SpeechStyle.tagFor(SpeechStyle.DEFAULT_STYLE_TAGS, "ШЕПОТ"));
        assertEquals("[angry]", SpeechStyle.tagFor(SpeechStyle.DEFAULT_STYLE_TAGS, "зло"));
        assertNull(SpeechStyle.tagFor(SpeechStyle.DEFAULT_STYLE_TAGS, "весело"));
        assertNull(SpeechStyle.tagFor(SpeechStyle.DEFAULT_STYLE_TAGS, ""));
        assertNull(SpeechStyle.tagFor(SpeechStyle.DEFAULT_STYLE_TAGS, (String) null));
    }

    @Test void traitTagAppliesOnlyToTraitsWithOne() {
        assertEquals("[nervous]", SpeechStyle.traitTag(SpeechStyle.DEFAULT_TRAIT_TAGS, ParrotTrait.COWARD));
        assertEquals("", SpeechStyle.traitTag(SpeechStyle.DEFAULT_TRAIT_TAGS, ParrotTrait.NORMAL));
        assertEquals("", SpeechStyle.traitTag(SpeechStyle.DEFAULT_TRAIT_TAGS, ParrotTrait.HERO));
    }

    @Test void viewerTextNeverCarriesItsOwnTags() {
        assertEquals("whispers привет", SpeechStyle.sanitize("[whispers] привет"));
        assertEquals("[angry] привет", SpeechStyle.compose("[angry]", "привет"));
        assertEquals("привет", SpeechStyle.compose("", "привет"));
        assertEquals("привет", SpeechStyle.compose(null, "привет"));
    }

    @Test void tagsAreValidatedForSettings() {
        assertTrue(SpeechStyle.isValidTag("[excited, loud]"));
        assertTrue(SpeechStyle.isValidTag(""));
        assertFalse(SpeechStyle.isValidTag("whispers"));
        assertFalse(SpeechStyle.isValidTag("[шёпот]"));
        assertFalse(SpeechStyle.isValidTag("[a] [b]"));
        assertThrows(IllegalArgumentException.class, () -> SpeechStyle.validatedTags(Map.of("ok", "nope"), "t", false));
        assertThrows(IllegalArgumentException.class, () -> SpeechStyle.validatedTags(Map.of("PIRATE", "[angry]"), "t", true));
        assertThrows(IllegalArgumentException.class, () -> SpeechStyle.validatedTags(Map.of("", "[angry]"), "t", false));
        assertEquals(Map.of("COWARD", "[nervous]"), SpeechStyle.validatedTags(Map.of("COWARD", "[nervous]"), "t", true));
    }

    @Test void speechParametersParseStyleAndKeepPlainTextUnchanged() {
        var styled = ActionParameters.Speech.parse("[Зло] Уходи отсюда");
        assertEquals("зло", styled.style());
        assertEquals("Уходи отсюда", styled.text());
        var plain = ActionParameters.Speech.parse("Просто фраза");
        assertNull(plain.style());
        assertEquals("Просто фраза", plain.text());
        assertThrows(IllegalArgumentException.class, () -> ActionParameters.Speech.parse("[зло]   "));
    }
}
