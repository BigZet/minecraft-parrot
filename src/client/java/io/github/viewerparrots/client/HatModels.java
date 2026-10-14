package io.github.viewerparrots.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.model.ModelPart;
import net.minecraft.util.math.Direction;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The studio catalog's cuboids, baked once into native model parts grouped by color. */
final class HatModels {
    private static final Map<String, List<ColoredPart>> HATS = load();

    private HatModels() { }

    static List<ColoredPart> get(String id) {
        return HATS.get(id);
    }

    static boolean contains(String id) {
        return HATS.containsKey(id);
    }

    private static Map<String, List<ColoredPart>> load() {
        try (InputStream input = HatModels.class.getResourceAsStream("/assets/viewerparrots/hats/catalog.json")) {
            if (input == null) throw new IllegalStateException("Missing bundled parrot hat catalog");
            JsonArray hats = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("hats");
            Map<String, List<ColoredPart>> models = new LinkedHashMap<>();
            for (JsonElement hatElement : hats) {
                JsonObject hat = hatElement.getAsJsonObject();
                Map<Integer, List<ModelPart.Cuboid>> colors = new LinkedHashMap<>();
                for (JsonElement cubeElement : hat.getAsJsonArray("cubes")) {
                    JsonObject cube = cubeElement.getAsJsonObject();
                    JsonArray from = cube.getAsJsonArray("from");
                    JsonArray to = cube.getAsJsonArray("to");
                    float x = from.get(0).getAsFloat();
                    float y = from.get(1).getAsFloat();
                    float z = from.get(2).getAsFloat();
                    float width = to.get(0).getAsFloat() - x;
                    float height = to.get(1).getAsFloat() - y;
                    float depth = to.get(2).getAsFloat() - z;
                    int color = 0xFF000000 | Integer.parseInt(cube.get("color").getAsString().substring(1), 16);
                    // The catalog uses Y up; Minecraft model parts use Y down.
                    ModelPart.Cuboid cuboid = new ModelPart.Cuboid(0, 0, x, -to.get(1).getAsFloat(), z,
                            width, height, depth, 0, 0, 0, false, 1, 1, EnumSet.allOf(Direction.class));
                    colors.computeIfAbsent(color, ignored -> new ArrayList<>()).add(cuboid);
                }
                List<ColoredPart> parts = new ArrayList<>();
                colors.forEach((color, cubes) -> parts.add(new ColoredPart(new ModelPart(List.copyOf(cubes), Map.of()), color)));
                models.put(hat.get("id").getAsString(), List.copyOf(parts));
            }
            return Map.copyOf(models);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read bundled parrot hat catalog", exception);
        }
    }

    record ColoredPart(ModelPart part, int color) { }
}
