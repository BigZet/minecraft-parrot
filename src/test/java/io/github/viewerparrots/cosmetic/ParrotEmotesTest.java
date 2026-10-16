package io.github.viewerparrots.cosmetic;

import io.github.viewerparrots.api.ActionParameters;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ParrotEmotesTest {
    @Test void namesAreAcceptedInRussianAndEnglishIgnoringCase() {
        assertEquals(Optional.of(ParrotEmotes.BOW), ParrotEmotes.parse("Поклон"));
        assertEquals(Optional.of(ParrotEmotes.SHAKE), ParrotEmotes.parse(" нет "));
        assertEquals(Optional.of(ParrotEmotes.NOD), ParrotEmotes.parse("да"));
        assertEquals(Optional.of(ParrotEmotes.HOP), ParrotEmotes.parse("JUMP"));
        assertTrue(ParrotEmotes.parse("танец").isEmpty());
        assertTrue(ParrotEmotes.parse(null).isEmpty());
    }

    @Test void randomCoversEveryEmoteAndParametersRejectUnknownIds() {
        var seen = new HashSet<String>();
        Random random = new Random(3);
        for (int i = 0; i < 200; i++) seen.add(ParrotEmotes.random(random));
        assertEquals(new HashSet<>(ParrotEmotes.ALL), seen);
        assertEquals(ParrotEmotes.NOD, new ActionParameters.Emote(ParrotEmotes.NOD).id());
        assertThrows(IllegalArgumentException.class, () -> new ActionParameters.Emote("dance"));
        for (String id : ParrotEmotes.ALL) assertEquals(Optional.of(id), ParrotEmotes.parse(ParrotEmotes.russianName(id)));
    }
}
