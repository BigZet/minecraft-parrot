package io.github.viewerparrots.twitch;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.bootstrap.ServerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import java.io.IOException;

/** A world-owned connection: socket work never runs on the Minecraft thread. */
public final class TwitchChatBridge {
    private static TwitchChatBridge active;
    private final ServerContext context;
    private Connection connection;
    private String lastResult = "—";

    private TwitchChatBridge(ServerContext context) { this.context = context; }

    public static void onWorldOpened(ServerContext context) {
        if (context.server().isDedicated()) return;
        active = new TwitchChatBridge(context);
        try {
            var settings = TwitchChatSettings.loadOrCreate(FabricLoader.getInstance().getConfigDir());
            if (settings.autoConnect()) active.connect(settings);
        } catch (IOException | IllegalArgumentException invalidSettings) {
            ViewerParrotsMod.LOGGER.warn("Twitch: check viewerparrots-twitch.json and the local token file.");
        }
    }

    public static void onWorldClosed(MinecraftServer server) {
        if (active != null && active.context.server() == server) {
            active.disconnect();
            active = null;
        }
    }

    public static void tick(ServerContext context) {
        if (active == null || active.context != context || active.connection == null) return;
        var bridge = active;
        var inbox = bridge.connection.inbox;
        if (!context.acceptingCommands() || !context.isRegistryReady()) {
            inbox.discardPending();
            return;
        }
        for (int i = 0; i < 4; i++) {
            var next = inbox.poll();
            if (next.isEmpty()) break;
            var request = next.get();
            // Level 4 belongs to this trusted adapter, after its fixed command/ownership mapping.
            var actor = new ActorContext(ActorContext.SourceType.INTERNAL_API, null, 4, request.viewerId().value());
            try {
                context.api().submit(request, actor).whenComplete((result, error) -> {
                    bridge.lastResult = error != null ? "ERROR"
                            : result.code() == null ? result.status().name() : result.code().name();
                });
            } catch (RuntimeException commandFailed) {
                bridge.lastResult = "ERROR";
            }
        }
    }

    private void connect(TwitchChatSettings settings) {
        settings.requireConfigured();
        disconnect();
        connection = new Connection(settings);
        lastResult = "—";
        connection.client.start();
    }

    private void disconnect() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    public static LiteralArgumentBuilder<ServerCommandSource> commands() {
        return CommandManager.literal("twitch")
                .executes(ctx -> status(ctx.getSource()))
                .then(CommandManager.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(CommandManager.literal("connect").executes(ctx -> connect(ctx.getSource())))
                .then(CommandManager.literal("disconnect").executes(ctx -> disconnect(ctx.getSource())));
    }

    private static TwitchChatBridge forSource(ServerCommandSource source) {
        if (active == null || active.context.server() != source.getServer()) {
            source.sendError(Text.literal("Twitch доступен в открытом одиночном мире."));
            return null;
        }
        return active;
    }

    private static int connect(ServerCommandSource source) {
        var bridge = forSource(source);
        if (bridge == null) return 0;
        if (bridge.context.streamerService().configuredUuid().isEmpty()) {
            source.sendError(Text.literal("Сначала назначьте стримера: /vp setstreamer <ваш_ник>"));
            return 0;
        }
        try {
            var settings = TwitchChatSettings.loadOrCreate(FabricLoader.getInstance().getConfigDir());
            bridge.connect(settings);
            source.sendFeedback(() -> Text.literal("Подключение Twitch начато. Статус: /vp twitch status"), false);
            return 1;
        } catch (IOException | IllegalArgumentException invalidSettings) {
            source.sendError(Text.literal("Проверьте канал и login в config/viewerparrots-twitch.json и токен в config/viewerparrots-twitch-token.txt."));
            return 0;
        }
    }

    private static int disconnect(ServerCommandSource source) {
        var bridge = forSource(source);
        if (bridge == null) return 0;
        bridge.disconnect();
        source.sendFeedback(() -> Text.literal("Twitch отключён. Попугаи остаются в мире."), false);
        return 1;
    }

    private static int status(ServerCommandSource source) {
        var bridge = forSource(source);
        if (bridge == null) return 0;
        var connection = bridge.connection;
        String state = connection == null ? "STOPPED" : connection.status.name();
        String channel = connection == null ? "" : " #" + connection.channel
                + (connection.guest ? " (гостевое чтение)" : " (вход по токену)");
        int pending = connection == null ? 0 : connection.inbox.pending();
        source.sendFeedback(() -> Text.literal("Twitch: " + state + channel
                + " | очередь: " + pending + " | последняя команда: " + bridge.lastResult), false);
        return 1;
    }

    private static final class Connection {
        private final String channel;
        private final boolean guest;
        private final TwitchCommandInbox inbox;
        private final TwitchIrcClient client;
        private volatile TwitchIrcClient.Status status = TwitchIrcClient.Status.CONNECTING;
        private volatile boolean closed;

        private Connection(TwitchChatSettings settings) {
            channel = settings.channel();
            guest = settings.oauthToken().isEmpty();
            inbox = new TwitchCommandInbox(channel, 256);
            client = new TwitchIrcClient(channel, settings.login(), settings.oauthToken(), inbox::offer, next -> {
                if (closed) return;
                status = next;
                inbox.setAccepting(next == TwitchIrcClient.Status.CONNECTED);
                ViewerParrotsMod.LOGGER.info("Twitch: {}", next);
            });
        }

        private void close() {
            closed = true;
            inbox.setAccepting(false);
            status = TwitchIrcClient.Status.STOPPED;
            client.close();
        }
    }
}
