package io.github.viewerparrots.storage;

import io.github.viewerparrots.domain.DesiredBehavior;
import io.github.viewerparrots.domain.DisplayName;
import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.domain.ParrotVariant;
import io.github.viewerparrots.domain.PhysicalStatus;
import io.github.viewerparrots.domain.Viewer;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.json.JsonView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NicknameStorageTest {
    private static ViewerParrotRecord record(DisplayName nickname) {
        Viewer viewer = new Viewer(ViewerId.of("twitch:7"), DisplayName.of("numnerd"), Instant.parse("2026-10-16T20:00:00Z"), nickname);
        return new ViewerParrotRecord(viewer, UUID.randomUUID(), 1, UUID.randomUUID(), ParrotVariant.GREEN, ParrotTrait.NORMAL,
                DesiredBehavior.FREE, PhysicalStatus.PRESENT, null, null, Instant.parse("2026-10-16T20:01:00Z"), 3);
    }

    @Test void nicknameSurvivesSnapshotRoundTripAndIsAbsentWhenUnset() {
        var named = record(DisplayName.of("Кеша"));
        var json = SnapshotCodec.recordToJson(DtoMapper.fromDomain(named));
        assertEquals("Кеша", json.getAsJsonObject("viewer").get("nickname").getAsString());
        var back = DtoMapper.toDomain("twitch:7", SnapshotCodec.readRecord(JsonView.ofRoot(json)));
        assertEquals(named, back);
        assertEquals("Кеша", back.viewer().shownName().value());

        var plain = record(null);
        var plainJson = SnapshotCodec.recordToJson(DtoMapper.fromDomain(plain));
        // Файлы версии 1.1.0 выглядят ровно так: без поля nickname.
        assertFalse(plainJson.getAsJsonObject("viewer").has("nickname"));
        var plainBack = DtoMapper.toDomain("twitch:7", SnapshotCodec.readRecord(JsonView.ofRoot(plainJson)));
        assertNull(plainBack.viewer().nickname());
        assertEquals("numnerd", plainBack.viewer().shownName().value());
    }

    @Test void nicknameChangesKeepTheViewerNameAndBumpRevision() {
        var plain = record(null);
        var renamed = plain.withNickname(DisplayName.of("Гоша"), Instant.parse("2026-10-16T20:02:00Z"));
        assertEquals("numnerd", renamed.viewer().displayName().value());
        assertEquals("Гоша", renamed.viewer().shownName().value());
        assertEquals(plain.revision() + 1, renamed.revision());
        assertNull(renamed.withNickname(null, Instant.parse("2026-10-16T20:03:00Z")).viewer().nickname());
        assertEquals("Гоша", renamed.withDisplayName(DisplayName.of("NumNerd"), Instant.now()).viewer().nickname().value());
    }
}
