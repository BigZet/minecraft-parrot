package io.github.viewerparrots.twitch;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.api.CommandRequest;
import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.auth.TwitchAuthService;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.control.ControlPanelService;
import io.github.viewerparrots.settings.ModSettings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * World-owned IRC connection; credentials and network work stay off the game thread.
 * A dedicated server only ever reads chat anonymously, so no Twitch token reaches it.
 */
public final class TwitchChatBridge {
    private static volatile TwitchChatBridge active;
    private static volatile boolean paused;
    private static volatile boolean chatStopped;
    private final ServerContext context;
    private final AtomicLong connectGeneration = new AtomicLong();
    private final ArrayDeque<ControlPanelService.RecentCommand> history = new ArrayDeque<>();
    private volatile Connection connection;
    private volatile String lastResult = "—";
    private volatile String connectionState = "STOPPED";
    private boolean autoConnectChecked;

    private TwitchChatBridge(ServerContext context) { this.context = context; }

    public static void onWorldOpened(ServerContext context) {
        active = new TwitchChatBridge(context);
    }

    public static void onWorldClosed(MinecraftServer server) {
        var bridge = active;
        if (bridge != null && bridge.context.server() == server) {
            active = null;
            bridge.disconnect();
        }
    }

    public static void tick(ServerContext context) {
        var bridge = active;
        if (bridge == null || bridge.context != context) return;
        // Bootstrap first: queue capacity belongs to the profile loaded for this world.
        if (!bridge.autoConnectChecked && context.isRegistryReady()) {
            bridge.autoConnectChecked = true;
            if (ControlPanelService.settings().twitch().autoConnect()) connectFromSettings();
        }
        var connection = bridge.connection;
        if (connection == null) return;
        if (!bridge.accepting(connection)) { connection.inbox.discardPending(); return; }
        io.github.viewerparrots.cage.CageJail.drain(context);
        int perTick = ControlPanelService.settings().active().moderation().commandsPerTick();
        for (int i = 0; i < perTick; i++) {
            var next = connection.inbox.pollEntry();
            if (next.isEmpty()) break;
            var entry = next.get();
            var request = entry.request();
            var actor = ActorContext.twitch(request.viewerId().value(), entry.roles());
            try {
                context.api().submit(request, actor).whenComplete((result, error) ->
                        context.server().execute(() -> {
                            if (active == bridge && ServerContext.current().orElse(null) == context)
                                bridge.record(request, result, error);
                        }));
            } catch (RuntimeException failed) { bridge.record(request, null, failed); }
        }
    }

    public static String status() {
        var bridge = active;
        if (bridge == null) return "STOPPED";
        var connection = bridge.connection;
        if (connection == null) return bridge.connectionState;
        if (connection.closed) return "STOPPED";
        if (chatStopped) return "CHAT_STOPPED";
        if (paused) return "PAUSED";
        return connection.status.name();
    }

    public static int pending() {
        var bridge = active;
        var connection = bridge == null ? null : bridge.connection;
        return connection == null ? 0 : connection.inbox.pending();
    }

    public static List<ControlPanelService.RecentCommand> recent() {
        var bridge = active;
        if (bridge == null) return List.of();
        synchronized (bridge.history) { return List.copyOf(bridge.history); }
    }

    public static void setPaused(boolean value) { paused = value; refreshAcceptance(); }
    public static void setChatStopped(boolean value) { chatStopped = value; refreshAcceptance(); }

    private static void refreshAcceptance() {
        var bridge = active;
        var connection = bridge == null ? null : bridge.connection;
        if (connection != null) connection.inbox.setAccepting(bridge.accepting(connection));
    }

    /** Guest mode must be selected explicitly; failed authorization never silently becomes guest access. */
    public static CompletableFuture<String> connectFromSettings() {
        var bridge = active;
        if (bridge == null) return CompletableFuture.completedFuture("Мир не открыт.");
        var result = new CompletableFuture<String>();
        long generation = bridge.connectGeneration.incrementAndGet();
        bridge.context.server().execute(() -> {
            if (!bridge.current(generation)) { result.complete("Подключение отменено."); return; }
            if (!bridge.context.isRegistryReady()) { result.complete("Мир ещё загружается. Повторите подключение чуть позже."); return; }
            bridge.autoConnectChecked = true;
            var settings = ControlPanelService.settings().twitch();
            if (settings.channel().isEmpty()) { result.complete("Укажите Twitch-канал в настройках."); return; }
            boolean guest = guestOnly(bridge.context.server().isDedicated(), settings.guest());
            bridge.closeConnection();
            bridge.connectionState = "CONNECTING";
            var auth = guest ? null : ControlPanelService.auth();
            if (!guest && auth == null) {
                bridge.connectionState = "STOPPED";
                result.complete("Настройки Twitch ещё загружаются.");
                return;
            }
            if (auth != null) auth.configure(settings.clientId(), settings.login());
            CompletableFuture<String> token = guest ? CompletableFuture.completedFuture("") : auth.accessToken();
            token.whenComplete((access, error) -> bridge.context.server().execute(() -> {
                if (!bridge.current(generation)) { result.complete("Подключение отменено."); return; }
                if (!settings.equals(ControlPanelService.settings().twitch())) {
                    bridge.connectionState = "STOPPED";
                    result.complete("Настройки Twitch изменились. Нажмите «Подключить» ещё раз.");
                    return;
                }
                if (error != null || (!guest && (access == null || access.isBlank()))) {
                    bridge.connectionState = "NEEDS_LOGIN";
                    result.complete("Войдите в Twitch через настройки или явно включите гостевое чтение.");
                    return;
                }
                try {
                    String login = guest ? "" : auth.status().login();
                    var credentials = new TwitchChatSettings(settings.autoConnect(), settings.channel(), login,
                            guest ? "" : access);
                    credentials.requireConfigured();
                    Connection installed = bridge.new Connection(credentials, guest, bridge.context.twitchQueueCapacity());
                    bridge.connection = installed;
                    bridge.lastResult = "—";
                    installed.client.start();
                    result.complete("Подключение Twitch начато.");
                } catch (IllegalArgumentException invalid) {
                    bridge.connectionState = "NEEDS_LOGIN";
                    result.complete("Проверьте канал и вход Twitch в настройках.");
                }
            }));
        });
        return result;
    }

    /** A dedicated server ignores the account setting: it never logs in and never holds a Twitch token. */
    static boolean guestOnly(boolean dedicated, boolean guestSetting) { return dedicated || guestSetting; }

    public static void disconnectNow() {
        var bridge = active;
        if (bridge == null) return;
        bridge.connectGeneration.incrementAndGet();
        var connection = bridge.connection;
        if (connection != null) connection.close();
        bridge.context.server().execute(() -> {
            if (active == bridge && bridge.connection == connection) {
                bridge.connection = null;
                bridge.connectionState = "STOPPED";
                bridge.autoConnectChecked = true;
            }
        });
    }

    /** The auth callback is only a signal; always obtain the current grant afresh. */
    public static void credentialsChanged() {
        var bridge = active;
        if (bridge == null) return;
        bridge.context.server().execute(() -> {
            if (active != bridge) return;
            var connection = bridge.connection;
            if (connection == null || connection.guest || connection.closed
                    || ControlPanelService.settings().twitch().guest()) return;
            var auth = ControlPanelService.auth();
            if (auth == null) return;
            if (!auth.status().hasCredentials()) {
                bridge.disconnect();
                bridge.connectionState = "NEEDS_LOGIN";
            } else if (auth.status().state() == TwitchAuthService.State.AUTHENTICATED) connectFromSettings();
        });
    }

    private boolean current(long generation) {
        return active == this && connectGeneration.get() == generation && ServerContext.current().orElse(null) == context;
    }

    private boolean accepting(Connection connection) {
        return active == this && this.connection == connection && !connection.closed && !paused && !chatStopped
                && connection.status == TwitchIrcClient.Status.CONNECTED
                && context.acceptingCommands() && context.isRegistryReady();
    }

    private void record(CommandRequest request, CommandResult result, Throwable error) {
        String code = error != null || result == null ? "ERROR"
                : result.code() == null ? result.status().name() : result.code().name();
        if (result != null && result.retryAfterTicks() > 0) code += " (" + ((result.retryAfterTicks() + 19) / 20) + " с)";
        lastResult = code;
        synchronized (history) {
            history.addFirst(new ControlPanelService.RecentCommand(request.viewerId().value(), request.action().name(), code));
            while (history.size() > 50) history.removeLast();
        }
    }

    private void closeConnection() {
        var previous = connection;
        connection = null;
        if (previous != null) previous.close();
    }

    private void disconnect() {
        connectGeneration.incrementAndGet();
        closeConnection();
        connectionState = "STOPPED";
    }

    public static LiteralArgumentBuilder<ServerCommandSource> commands() {
        return CommandManager.literal("twitch")
                .executes(ctx -> status(ctx.getSource()))
                .then(CommandManager.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(CommandManager.literal("connect").executes(ctx -> connect(ctx.getSource())))
                .then(CommandManager.literal("disconnect").executes(ctx -> disconnect(ctx.getSource())))
                .then(CommandManager.literal("channel")
                        .then(CommandManager.argument("channel", StringArgumentType.word())
                                .executes(ctx -> setChannel(ctx.getSource(), StringArgumentType.getString(ctx, "channel")))));
    }

    private static TwitchChatBridge forSource(ServerCommandSource source) {
        var bridge = active;
        if (bridge == null || bridge.context.server() != source.getServer()) {
            source.sendError(Text.literal("Twitch доступен в открытом мире."));
            return null;
        }
        return bridge;
    }

    private static int connect(ServerCommandSource source) {
        var bridge = forSource(source);
        if (bridge == null) return 0;
        if (bridge.context.streamerService().configuredUuid().isEmpty()) {
            source.sendError(Text.literal("Сначала назначьте стримера: /vp setstreamer <ваш_ник>"));
            return 0;
        }
        connectFromSettings().whenComplete((message, error) -> source.getServer().execute(() -> {
            if (active == bridge) source.sendFeedback(() -> Text.literal(error == null ? message : "Не удалось подключить Twitch."), false);
        }));
        return 1;
    }

    private static int disconnect(ServerCommandSource source) {
        if (forSource(source) == null) return 0;
        disconnectNow();
        source.sendFeedback(() -> Text.literal("Twitch отключён. Попугаи остаются в мире."), false);
        return 1;
    }

    /** Saves the channel to viewerparrots-settings.json; an open connection keeps its channel until reconnect. */
    private static int setChannel(ServerCommandSource source, String channel) {
        if (forSource(source) == null) return 0;
        var current = ControlPanelService.settings();
        var twitch = current.twitch();
        ModSettings next;
        try {
            next = new ModSettings(current.schemaVersion(), current.activeProfile(), current.profiles(),
                    new ModSettings.Twitch(twitch.autoConnect(), channel, twitch.login(), twitch.clientId(), twitch.guest()));
        } catch (IllegalArgumentException invalid) {
            source.sendError(Text.literal("Укажите логин Twitch-канала: латиница, цифры и _, до 25 символов."));
            return 0;
        }
        String saved = next.twitch().channel();
        ControlPanelService.save(next).whenComplete((applied, error) -> source.getServer().execute(() -> {
            if (error != null) source.sendError(Text.literal("Не удалось сохранить канал Twitch."));
            else source.sendFeedback(() -> Text.literal("Канал Twitch: #" + saved
                    + ". Подключение: /vp twitch connect"), true);
        }));
        return 1;
    }

    private static int status(ServerCommandSource source) {
        var bridge = forSource(source);
        if (bridge == null) return 0;
        var connection = bridge.connection;
        String channel = connection == null ? "" : " #" + connection.channel
                + (connection.guest ? " (гостевое чтение)" : " (вход по аккаунту)");
        source.sendFeedback(() -> Text.literal("Twitch: " + status() + channel
                + " | очередь: " + pending() + " | последняя команда: " + bridge.lastResult), false);
        return 1;
    }

    private final class Connection {
        private final String channel;
        private final boolean guest;
        private final TwitchCommandInbox inbox;
        private final TwitchIrcClient client;
        private volatile TwitchIrcClient.Status status = TwitchIrcClient.Status.CONNECTING;
        private volatile boolean closed;

        private Connection(TwitchChatSettings settings, boolean guest, int capacity) {
            channel = settings.channel();
            this.guest = guest;
            inbox = new TwitchCommandInbox(channel, capacity);
            client = new TwitchIrcClient(channel, settings.login(), settings.oauthToken(), message -> {
                // !свободу и !вклетку — не команды птицы зрителя, а дело тюрьмы Parrot Cages.
                if (accepting(this) && !io.github.viewerparrots.cage.CageJail.offer(message)) inbox.offer(message);
            }, next -> {
                if (closed || active != TwitchChatBridge.this || connection != this) return;
                status = next;
                connectionState = next.name();
                inbox.setAccepting(accepting(this));
                ViewerParrotsMod.LOGGER.info("Twitch: {}", next);
                if (next == TwitchIrcClient.Status.AUTH_FAILED && !guest) authenticationFailed();
            });
        }

        private void authenticationFailed() {
            context.server().execute(() -> {
                if (closed || active != TwitchChatBridge.this || connection != this) return;
                var auth = ControlPanelService.auth();
                if (auth == null) return;
                auth.authenticationFailed().whenComplete((updated, error) -> context.server().execute(() -> {
                    if (closed || active != TwitchChatBridge.this || connection != this) return;
                    if (error == null && updated.state() == TwitchAuthService.State.AUTHENTICATED) connectFromSettings();
                }));
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
