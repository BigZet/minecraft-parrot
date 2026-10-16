package io.github.viewerparrots.settings;

import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.config.ConfigLoader;
import io.github.viewerparrots.config.ViewerParrotsConfig;
import io.github.viewerparrots.roulette.RouletteSettings;
import io.github.viewerparrots.roulette.RouletteSettingsLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SettingsStoreTest {
    @TempDir Path directory;

    @Test void defaultsRoundTripAndContainAllCommandsAndVoices() {
        ModSettings defaults = ModSettings.defaults();
        assertEquals(defaults, SettingsStore.parse(SettingsStore.toJson(defaults)));
        assertEquals(19, defaults.active().commands().size());
        assertEquals(24, defaults.active().speech().voices().size());
        assertEquals(180.0, defaults.active().gameplay().spinDegreesPerTick());
        assertEquals(75, defaults.active().gameplay().headTurnThreshold());
    }

    @Test void migratesThreeLegacyFilesWithoutChangingThemOrReadingCredentialContents() throws Exception {
        String general = ConfigLoader.toJson(ViewerParrotsConfig.defaults()).replace("\"come\": 100", "\"come\": 240");
        String twitch = "{\"autoConnect\":true,\"channel\":\"oldchannel\",\"login\":\"oldlogin\"}";
        var rouletteDefaults = RouletteSettings.defaults();
        String roulette = RouletteSettingsLoader.toJson(new RouletteSettings(80, 40, 1800, 16, rouletteDefaults.categories()));
        Files.writeString(directory.resolve("viewerparrots.json"), general);
        Files.writeString(directory.resolve("viewerparrots-twitch.json"), twitch);
        Files.writeString(directory.resolve("viewerparrots-roulette.json"), roulette);
        Files.writeString(directory.resolve("viewerparrots-twitch-token.txt"), "NON_SENSITIVE_TEST_SENTINEL");
        ModSettings loaded = new SettingsStore(directory).loadOrCreate();
        assertEquals("Текущие настройки", loaded.active().name());
        assertEquals(240, loaded.active().general().cooldowns().come());
        assertEquals(240, loaded.active().commands().get(ParrotAction.COME).cooldownTicks());
        assertEquals(1800, loaded.active().commands().get(ParrotAction.ROULETTE).cooldownTicks());
        assertEquals("oldchannel", loaded.twitch().channel());
        assertTrue(loaded.twitch().autoConnect());
        assertFalse(SettingsStore.toJson(loaded).contains("NON_SENSITIVE_TEST_SENTINEL"));
        assertEquals(general, Files.readString(directory.resolve("viewerparrots.json")));
        assertEquals(twitch, Files.readString(directory.resolve("viewerparrots-twitch.json")));
        assertEquals(roulette, Files.readString(directory.resolve("viewerparrots-roulette.json")));
    }

    @Test void invalidUnifiedFileIsNotSilentlyReplacedOrRecoveredFromBackup() throws Exception {
        SettingsStore store = new SettingsStore(directory);
        store.loadOrCreate();
        Path file = directory.resolve("viewerparrots-settings.json");
        Files.writeString(file, "{ invalid");
        assertThrows(RuntimeException.class, store::loadOrCreate);
        assertThrows(RuntimeException.class, () -> store.save(ModSettings.defaults()));
        assertEquals("{ invalid", Files.readString(file));
    }

    @Test void savesPreviousValidVersionAsBackupAndDoesNotRetainTemporaryFiles() throws Exception {
        SettingsStore store = new SettingsStore(directory);
        ModSettings old = store.loadOrCreate();
        var other = renamed(old.active(), "Другой профиль");
        ModSettings updated = new ModSettings(1, "other", Map.of("default", old.active(), "other", other), old.twitch());
        store.save(updated);
        assertEquals(updated, store.loadOrCreate());
        assertEquals(old, SettingsStore.parse(Files.readString(directory.resolve("viewerparrots-settings.json.bak"))));
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test void rejectsUnknownActiveProfileDuplicateNamesAndAccountSecrets() {
        ModSettings defaults = ModSettings.defaults();
        assertThrows(IllegalArgumentException.class, () -> defaults.withActiveProfile("missing"));
        assertThrows(IllegalArgumentException.class, () -> new ModSettings(1, "a",
                Map.of("a", defaults.active(), "b", defaults.active()), defaults.twitch()));
        String withSecret = SettingsStore.toJson(defaults).replace("\"guest\": true", "\"guest\": true, \"oauthToken\": \"NOT_A_REAL_SECRET\"");
        assertThrows(RuntimeException.class, () -> SettingsStore.parse(withSecret));
    }

    @Test void exportContainsOnlyProfileAndImportCannotEscapeTheExportsDirectory() throws Exception {
        SettingsStore store = new SettingsStore(directory);
        ModSettings settings = ModSettings.defaults();
        Path exported = store.exportProfile(settings.active(), "fun.json");
        String text = Files.readString(exported);
        assertFalse(text.contains("\"twitch\""));
        assertFalse(text.contains("\"clientId\""));
        assertFalse(text.contains("\"login\""));
        assertEquals(settings.active(), store.importProfile(exported));
        assertThrows(IllegalArgumentException.class, () -> store.exportProfile(settings.active(), "../outside.json"));
    }

    @Test void rejectsNonFiniteGameplayAndDuplicateVoiceIds() {
        var profile = ModSettings.Profile.defaults();
        var gameplay = profile.gameplay();
        assertThrows(IllegalArgumentException.class, () -> new ModSettings.Gameplay(60, Double.NaN,
                gameplay.freefollowRadius(), gameplay.freefollowReturnDistance(), 75, 75));
        var speech = profile.speech();
        assertThrows(IllegalArgumentException.class, () -> new ModSettings.Speech(true, speech.model(), 30,
                60, 16, 0, 0, java.util.List.of(speech.voices().getFirst(), speech.voices().getFirst())));
    }

    private static ModSettings.Profile renamed(ModSettings.Profile original, String name) {
        return new ModSettings.Profile(name, original.general(), original.commands(), original.speech(),
                original.roulette(), original.gameplay(), original.moderation());
    }
}
