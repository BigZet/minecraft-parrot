package io.github.viewerparrots.roulette;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.viewerparrots.config.ConfigLoader;
import io.github.viewerparrots.config.ConfigValidator;
import io.github.viewerparrots.config.ViewerParrotsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RouletteSettingsLoaderTest {
    private final RouletteSettingsLoader loader = new RouletteSettingsLoader(id -> true);

    @Test
    void defaultsRoundTripAndRetainTheAgreedTimingAndWeights() {
        RouletteSettings settings = RouletteSettings.defaults();
        assertEquals(settings, loader.parse(RouletteSettingsLoader.toJson(settings)));
        assertEquals(80, settings.durationTicks());
        assertEquals(40, settings.resultTicks());
        assertEquals(1200, settings.cooldownTicks());
        assertEquals(16, settings.queueCapacity());
        assertEquals(List.of(65, 25, 9, 1), settings.categories().stream().map(RouletteSettings.Category::weight).toList());
    }

    @Test
    void acceptsTheInclusiveTimingAndQueueBounds() {
        var categories = RouletteSettings.defaults().categories();
        for (RouletteSettings candidate : List.of(
                new RouletteSettings(20, 0, 0, 1, categories),
                new RouletteSettings(400, 100, 72000, 64, categories))) {
            assertEquals(candidate, loader.parse(RouletteSettingsLoader.toJson(candidate)));
        }
    }

    @Test
    void rejectsValuesOutsideTheTimingAndQueueBounds() {
        String[] fields = {"durationTicks", "durationTicks", "resultTicks", "resultTicks",
                "cooldownTicks", "cooldownTicks", "queueCapacity", "queueCapacity"};
        int[] values = {19, 401, -1, 101, -1, 72001, 0, 65};
        for (int i = 0; i < fields.length; i++) {
            JsonObject json = json();
            json.addProperty(fields[i], values[i]);
            var failure = assertThrows(IllegalArgumentException.class, () -> loader.parse(json.toString()));
            assertTrue(failure.getMessage().contains(fields[i]));
        }
    }

    @Test
    void rejectsZeroNegativeAndExcessiveCombinedWeights() {
        for (int weight : new int[]{0, -1, RouletteSettings.MAX_TOTAL_WEIGHT}) {
            JsonObject json = json();
            json.getAsJsonArray("categories").get(0).getAsJsonObject().addProperty("weight", weight);
            assertThrows(IllegalArgumentException.class, () -> loader.parse(json.toString()));
        }
    }

    @Test
    void enforcesTheCategoryAndPrizePoolLimits() {
        var defaults = RouletteSettings.defaults();
        var category = defaults.categories().getFirst();
        var prize = new RouletteSettings.Prize("minecraft:bread", 64);
        assertEquals(16, new RouletteSettings(80, 40, 1200, 16,
                java.util.Collections.nCopies(16, category)).categories().size());
        assertThrows(IllegalArgumentException.class, () -> new RouletteSettings(80, 40, 1200, 16,
                java.util.Collections.nCopies(17, category)));
        assertEquals(64, new RouletteSettings.Category("common", 1, 0,
                java.util.Collections.nCopies(64, prize)).items().size());
        assertThrows(IllegalArgumentException.class, () -> new RouletteSettings.Category("common", 1, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RouletteSettings.Category("common", 1, 0,
                java.util.Collections.nCopies(65, prize)));
        var maxWeight = new RouletteSettings.Category("common", RouletteSettings.MAX_TOTAL_WEIGHT, 0, List.of(prize));
        var settings = new RouletteSettings(80, 40, 1200, 16, List.of(maxWeight));
        assertEquals(settings, loader.parse(RouletteSettingsLoader.toJson(settings)));
    }

    @Test
    void rejectsEmptyPoolsInvalidCountsAirAndUnknownItemIds() {
        JsonObject noCategories = json();
        noCategories.getAsJsonArray("categories").remove(0);
        noCategories.getAsJsonArray("categories").remove(0);
        noCategories.getAsJsonArray("categories").remove(0);
        noCategories.getAsJsonArray("categories").remove(0);
        assertThrows(IllegalArgumentException.class, () -> loader.parse(noCategories.toString()));
        for (String itemId : new String[]{"minecraft:air", "Bad Item", "missing"}) {
            JsonObject candidate = json();
            firstPrize(candidate).addProperty("itemId", itemId);
            assertThrows(IllegalArgumentException.class, () -> loader.parse(candidate.toString()));
        }
        for (int count : new int[]{0, 65}) {
            JsonObject candidate = json();
            firstPrize(candidate).addProperty("count", count);
            assertThrows(IllegalArgumentException.class, () -> loader.parse(candidate.toString()));
        }
        var knownItems = new RouletteSettingsLoader(id -> !id.equals("minecraft:diamond"));
        var failure = assertThrows(IllegalArgumentException.class, () -> knownItems.parse(json().toString()));
        assertTrue(failure.getMessage().contains("minecraft:diamond"));
    }

    @Test
    void createsTheSidecarWithoutChangingTheOldMainConfig(@TempDir Path directory) throws Exception {
        Path oldFile = directory.resolve("viewerparrots.json");
        String oldJson = ConfigLoader.toJson(ViewerParrotsConfig.defaults());
        Files.writeString(oldFile, oldJson);
        Path rouletteFile = directory.resolve("viewerparrots-roulette.json");
        assertEquals(RouletteSettings.defaults(), loader.loadOrCreate(rouletteFile));
        assertEquals(RouletteSettings.defaults(), loader.loadOrCreate(rouletteFile));
        assertEquals(oldJson, Files.readString(oldFile));
        assertEquals(ViewerParrotsConfig.defaults(), new ConfigLoader(ConfigValidator.withoutRegistry()).parse(oldJson));
    }

    @Test
    void doesNotWriteAnInvalidDefaultOrOverwriteAnInvalidExistingFile(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("viewerparrots-roulette.json");
        assertThrows(IllegalArgumentException.class, () -> new RouletteSettingsLoader(id -> false).loadOrCreate(file));
        assertFalse(Files.exists(file));
        Files.writeString(file, "{ broken");
        assertThrows(RuntimeException.class, () -> loader.loadOrCreate(file));
        assertEquals("{ broken", Files.readString(file));
    }

    private static JsonObject json() {
        return JsonParser.parseString(RouletteSettingsLoader.toJson(RouletteSettings.defaults())).getAsJsonObject();
    }

    private static JsonObject firstPrize(JsonObject json) {
        return json.getAsJsonArray("categories").get(0).getAsJsonObject()
                .getAsJsonArray("items").get(0).getAsJsonObject();
    }
}
