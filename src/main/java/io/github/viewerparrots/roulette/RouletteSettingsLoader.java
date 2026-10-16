package io.github.viewerparrots.roulette;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.viewerparrots.json.JsonSchemaException;
import io.github.viewerparrots.json.JsonView;
import io.github.viewerparrots.json.StrictJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

/** Load config/viewerparrots-roulette.json on the existing storage worker, never in a chat/game tick. */
public final class RouletteSettingsLoader {
    public static final String FILE_NAME = "viewerparrots-roulette.json";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Predicate<String> itemExists;

    public RouletteSettingsLoader(Predicate<String> itemExists) {
        this.itemExists = Objects.requireNonNull(itemExists, "itemExists");
    }

    public RouletteSettings loadOrCreate(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        if (Files.exists(file)) return parse(Files.readString(file, StandardCharsets.UTF_8));
        RouletteSettings settings = RouletteSettings.defaults();
        validateItems(settings);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, toJson(settings), StandardCharsets.UTF_8);
        return settings;
    }

    public RouletteSettings parse(String text) {
        JsonView root = JsonView.ofRoot(StrictJson.parseObject(text));
        root.requireKnownFields("durationTicks", "resultTicks", "cooldownTicks", "queueCapacity", "categories");
        List<RouletteSettings.Category> categories = new ArrayList<>();
        for (JsonView category : root.requireObjectArray("categories")) {
            category.requireKnownFields("name", "weight", "color", "items");
            List<RouletteSettings.Prize> items = new ArrayList<>();
            for (JsonView item : category.requireObjectArray("items")) {
                item.requireKnownFields("itemId", "count");
                items.add(new RouletteSettings.Prize(item.requireString("itemId"), item.requireInt("count")));
            }
            categories.add(new RouletteSettings.Category(category.requireString("name"),
                    category.requireInt("weight"), color(category), items));
        }
        RouletteSettings settings = new RouletteSettings(root.requireInt("durationTicks"),
                root.requireInt("resultTicks"), root.requireInt("cooldownTicks"),
                root.requireInt("queueCapacity"), categories);
        validateItems(settings);
        return settings;
    }

    private void validateItems(RouletteSettings settings) {
        for (int c = 0; c < settings.categories().size(); c++) {
            List<RouletteSettings.Prize> items = settings.categories().get(c).items();
            for (int i = 0; i < items.size(); i++) {
                String id = items.get(i).itemId();
                if (!itemExists.test(id)) {
                    throw new IllegalArgumentException("$.categories[" + c + "].items[" + i
                            + "].itemId: unknown item " + id);
                }
            }
        }
    }

    private static int color(JsonView view) {
        JsonElement value = view.raw().get("color");
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String color = value.getAsString();
            if (!color.matches("#[0-9a-fA-F]{6}")) {
                throw new JsonSchemaException(view.child("color"), "ожидался цвет #RRGGBB");
            }
            return Integer.parseInt(color.substring(1), 16);
        }
        return view.requireInt("color");
    }

    public static String toJson(RouletteSettings settings) {
        JsonObject root = new JsonObject();
        root.addProperty("durationTicks", settings.durationTicks());
        root.addProperty("resultTicks", settings.resultTicks());
        root.addProperty("cooldownTicks", settings.cooldownTicks());
        root.addProperty("queueCapacity", settings.queueCapacity());
        JsonArray categories = new JsonArray();
        for (RouletteSettings.Category category : settings.categories()) {
            JsonObject group = new JsonObject();
            group.addProperty("name", category.name());
            group.addProperty("weight", category.weight());
            group.addProperty("color", String.format(Locale.ROOT, "#%06X", category.color()));
            JsonArray items = new JsonArray();
            for (RouletteSettings.Prize prize : category.items()) {
                JsonObject item = new JsonObject();
                item.addProperty("itemId", prize.itemId());
                item.addProperty("count", prize.count());
                items.add(item);
            }
            group.add("items", items);
            categories.add(group);
        }
        root.add("categories", categories);
        return JSON.toJson(root) + "\n";
    }
}
