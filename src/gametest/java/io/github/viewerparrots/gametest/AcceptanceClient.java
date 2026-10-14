package io.github.viewerparrots.gametest;

import com.google.gson.JsonParser;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.util.ScreenshotRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/** Client-side evidence for actual network tracking; only loaded in the QA source set. */
public final class AcceptanceClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("viewerparrots-qa-client");
    private final Map<UUID, Boolean> tracking = new HashMap<>();
    private boolean connecting;
    private final String address = System.getProperty("viewerparrots.qa.address", "127.0.0.1:25585");
    private final String screenshotRequest = System.getProperty("viewerparrots.qa.screenshotRequest", "");
    private String lastScreenshotRequest = "";
    private int screenshotTicks;
    private int clientTicks;
    private final boolean danceScreenshots = Boolean.getBoolean("viewerparrots.qa.danceScreenshots");
    private final Map<UUID, Integer> lastDanceScreenshot = new HashMap<>();
    private final Deque<String> pendingScreenshots = new ArrayDeque<>();
    private final String commandRequest = System.getProperty("viewerparrots.qa.commandRequest", "");
    private final HashSet<String> sentCommandIds = new HashSet<>();
    private final boolean autoRespawn = Boolean.getBoolean("viewerparrots.qa.autoRespawn");
    private boolean respawnRequested;

    @Override
    public void onInitializeClient() {
        LOGGER.info("QA_CLIENT initialized connect={}", Boolean.getBoolean("viewerparrots.qa.connect"));
        if (!Boolean.getBoolean("viewerparrots.qa.connect")) return;
        WorldRenderEvents.END.register(context -> {
            if (pendingScreenshots.isEmpty()) return;
            MinecraftClient client = MinecraftClient.getInstance();
            String token = pendingScreenshots.removeFirst();
            // Runs after the actual world/entity renderer, without OS input or focus operations.
            ScreenshotRecorder.saveScreenshot(client.runDirectory, token + ".png", client.getFramebuffer(),
                    message -> LOGGER.info("QA_SCREENSHOT observer={} token={} result={}", client.getSession().getUsername(), token, message.getString()));
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null) {
                tracking.clear();
                lastDanceScreenshot.clear();
                pendingScreenshots.clear();
                respawnRequested = false;
                if (!connecting && client.currentScreen instanceof TitleScreen) {
                    connecting = true;
                    LOGGER.info("QA_CLIENT connecting observer={} address={}", client.getSession().getUsername(), address);
                    ConnectScreen.connect(client.currentScreen, client, ServerAddress.parse(address),
                            new ServerInfo("Viewer Parrots QA", address, ServerInfo.ServerType.OTHER), false, null);
                }
                return;
            }
            clientTicks++;
            if (autoRespawn && client.player != null) {
                if (!client.player.isDead()) {
                    respawnRequested = false;
                    if (client.currentScreen instanceof DeathScreen) client.setScreen(null);
                } else {
                    if (!respawnRequested) {
                        respawnRequested = true;
                        // Vanilla already requests respawn when its immediate-respawn rule hides the death screen.
                        if (client.player.showsDeathScreen()) {
                            client.player.requestRespawn();
                            LOGGER.info("QA_RESPAWN_REQUEST observer={} uuid={} entityId={}",
                                    client.getSession().getUsername(), client.player.getUuid(), client.player.getId());
                        }
                    }
                }
            }
            if (!commandRequest.isEmpty() && clientTicks % 10 == 0 && client.getNetworkHandler() != null
                    && client.player != null && !client.player.isDead()) {
                try {
                    Path request = Path.of(commandRequest);
                    if (Files.isRegularFile(request)) {
                        if (Files.size(request) > 4096) throw new IllegalArgumentException("Command request is too large");
                        var json = JsonParser.parseString(Files.readString(request)).getAsJsonObject();
                        String id = json.get("id").getAsString();
                        String command = json.get("command").getAsString();
                        if (!id.matches("[A-Za-z0-9._-]{1,80}")) throw new IllegalArgumentException("Command id must be a simple unique token");
                        if (command.isBlank() || command.length() > 256 || command.startsWith("/")
                                || command.chars().anyMatch(Character::isISOControl))
                            throw new IllegalArgumentException("Command must be one command without slash, at most 256 characters");
                        if (!sentCommandIds.contains(id)) {
                            if (sentCommandIds.size() >= 1024) throw new IllegalStateException("QA command limit reached");
                            sentCommandIds.add(id);
                            client.getNetworkHandler().sendChatCommand(command);
                            LOGGER.info("QA_COMMAND_SENT observer={} id={} command={}", client.getSession().getUsername(), id, command);
                        }
                    }
                } catch (Exception failure) {
                    LOGGER.warn("QA_COMMAND request failed", failure);
                }
            }
            var present = new HashSet<UUID>();
            for (var entity : client.world.getEntities()) {
                if (!(entity instanceof ViewerParrotEntity parrot)) continue;
                UUID id = parrot.getUuid();
                present.add(id);
                boolean dancing = parrot.isForcedDancing();
                Boolean previous = tracking.put(id, dancing);
                if (previous == null || previous != dancing) {
                    LOGGER.info("QA_TRACK observer={} uuid={} name={} variant={} first={} dancing={}",
                            client.getSession().getUsername(), id, parrot.getName().getString(),
                            parrot.getVariant(), previous == null, dancing);
                }
                if (danceScreenshots) {
                    if (dancing && (previous == null || !previous || clientTicks - lastDanceScreenshot.getOrDefault(id, -20) >= 20)) {
                        requestScreenshot("qa-dance-" + id + "-t" + clientTicks);
                        lastDanceScreenshot.put(id, clientTicks);
                    } else if (!dancing && Boolean.TRUE.equals(previous)) {
                        requestScreenshot("qa-ended-" + id + "-t" + clientTicks);
                        lastDanceScreenshot.remove(id);
                    }
                }
            }
            tracking.keySet().retainAll(present);
            lastDanceScreenshot.keySet().retainAll(present);
            if (!screenshotRequest.isEmpty() && ++screenshotTicks % 20 == 0) {
                try {
                    Path request = Path.of(screenshotRequest);
                    if (!Files.isRegularFile(request)) return;
                    String token = Files.readString(request).trim();
                    if (token.isEmpty() || token.equals(lastScreenshotRequest)) return;
                    if (!token.matches("[A-Za-z0-9._-]{1,80}")) throw new IllegalArgumentException("Screenshot token must be a simple file name");
                    lastScreenshotRequest = token;
                    requestScreenshot(token);
                } catch (Exception failure) {
                    LOGGER.warn("QA_SCREENSHOT request failed", failure);
                }
            }
        });
    }

    private void requestScreenshot(String token) {
        if (pendingScreenshots.size() < 4) pendingScreenshots.addLast(token);
    }
}
