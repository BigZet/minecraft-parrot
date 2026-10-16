package io.github.viewerparrots.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.config.ConfigLoader;
import io.github.viewerparrots.config.ViewerParrotsConfig;
import io.github.viewerparrots.control.ControlPanelService;
import io.github.viewerparrots.settings.ModSettings;
import io.github.viewerparrots.settings.SettingsStore;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Test-only writer for the settings file the mod actually reads since 1.1.0.
 * Reload goes through {@link ControlPanelService}, which parses
 * {@code viewerparrots-settings.json}; the legacy {@code viewerparrots.json}
 * is only a one-time migration source and changing it has no effect.
 */
final class SettingsFixture {
    private SettingsFixture() {}

    static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("viewerparrots-settings.json"); }

    /** Current file text, or the in-memory settings when the file was never written. */
    static String snapshot() {
        try {
            return Files.exists(file()) ? Files.readString(file(), StandardCharsets.UTF_8)
                    : SettingsStore.toJson(ControlPanelService.settings());
        } catch (IOException error) { throw new IllegalStateException(error); }
    }

    static void write(String text) {
        try { Files.writeString(file(), text, StandardCharsets.UTF_8); }
        catch (IOException error) { throw new IllegalStateException(error); }
    }

    /**
     * Replace the active profile's general section with raw JSON and reset its chat command rules, so the
     * cooldowns a scenario sets in {@code general.cooldowns} apply. The text is validated only by the real
     * SettingsStore on reload, which lets scenarios submit deliberately invalid configurations.
     */
    static void writeGeneral(JsonObject general) {
        ModSettings current = ControlPanelService.settings();
        JsonObject root = JsonParser.parseString(snapshot()).getAsJsonObject();
        JsonObject profile = root.getAsJsonObject("profiles").getAsJsonObject(current.activeProfile());
        profile.add("general", general);
        profile.add("commands", new JsonObject());
        write(root.toString());
    }

    static void writeGeneral(ViewerParrotsConfig general) {
        writeGeneral(JsonParser.parseString(ConfigLoader.toJson(general)).getAsJsonObject());
    }

    /**
     * Production reload: the file is parsed on the settings thread, then the live context applies the
     * active profile on the server thread. Fabric's GameTest server reports {@code isDedicated()} as true,
     * so {@link ControlPanelService} skips that apply step there; mirror the streamer's integrated server.
     */
    static CommandResult reload(ServerContext context, ActorContext actor) {
        CommandResult result = FixtureCallbacks.finish(context.server(), context.reload(UUID.randomUUID(), actor));
        if (result.succeeded()) context.applySettings(ControlPanelService.settings().active());
        return result;
    }
}
