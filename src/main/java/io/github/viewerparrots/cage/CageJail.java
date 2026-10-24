package io.github.viewerparrots.cage;

import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.diagnostics.AuditLog;
import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.entity.CageCompat;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import io.github.viewerparrots.settings.TwitchRole;
import io.github.viewerparrots.twitch.TwitchChatMessage;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Тюрьма Parrot Cages со стороны попугаев-зрителей: табличка «· в клетке 3:12», записи AuditLog, побег вора,
 * команды {@code /vp cage} и {@code /vp uncage}, Twitch {@code !свободу <ник>} (амнистия голосами зрителей) и
 * {@code !вклетку <ник>} (модераторам, если разрешено в «Клетке»). Срок, тюремщика и настройки хранит Parrot Cages;
 * связь только через общий реестр Fabric ({@link CageCompat}), без общего кода. Всё, кроме приёма чата, — на потоке сервера.
 */
public final class CageJail {
	/** Общий объект для Parrot Cages: назначенный стример мира. */
	public static final String STREAMER_SHARE = "viewerparrots:streamer";
	public static final String STREAMER_TAG = "mobtalk.role.streamer", ADMIN_TAG = "mobtalk.role.admin";
	private static final int SYNC_TICKS = 20, CHAT_CAPACITY = 256, CHAT_PER_TICK = 16, CAGE_RADIUS = 32;

	/** Голоса за амнистию одного срока: кто и когда (мс). */
	private static final class Votes { long jailedAt; final Map<String, Long> voters = new LinkedHashMap<>(); boolean used; }

	private static final Set<ViewerId> JAILED = ConcurrentHashMap.newKeySet();
	private static final Map<ViewerId, Long> THIEF_TRIED = new HashMap<>();
	private static final Map<ViewerId, Votes> VOTES = new HashMap<>();
	private static final ConcurrentLinkedQueue<TwitchChatMessage> CHAT = new ConcurrentLinkedQueue<>();
	private static final AtomicInteger CHAT_SIZE = new AtomicInteger();

	private CageJail() { }

	@SuppressWarnings("unchecked")
	public static void install() {
		var share = FabricLoader.getInstance().getObjectShare();
		share.put(STREAMER_SHARE, (Supplier<UUID>) () -> ServerContext.current().flatMap(c -> c.streamerService().configuredUuid()).orElse(null));
		share.whenAvailable(CageCompat.LISTENERS, (key, value) -> {
			if (value instanceof List<?> list) ((List<Consumer<Map<String, Object>>>) list).add(CageJail::onEvent);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
	}

	static void clear() { JAILED.clear(); THIEF_TRIED.clear(); VOTES.clear(); CHAT.clear(); CHAT_SIZE.set(0); }

	public static boolean isJailed(ViewerId viewerId) { return JAILED.contains(viewerId); }

	// --- События Parrot Cages ----------------------------------------------------

	private static void onEvent(Map<String, Object> event) {
		Optional<ServerContext> context = ServerContext.current();
		if (context.isEmpty() || !(event.get("parrot") instanceof UUID parrot)) return;
		Optional<ViewerParrotRecord> record = context.get().registry().findByEntityUuid(parrot);
		if (record.isEmpty()) return;
		ViewerId id = record.get().viewer().viewerId();
		String kind = String.valueOf(event.get("event"));
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("jailer", String.valueOf(event.getOrDefault("jailerName", "")));
		details.put("minutes", event.get("minutes"));
		if ("released".equals(kind)) details.put("reason", event.get("reason"));
		switch (kind) {
			case "jailed", "extended" -> JAILED.add(id);
			case "released" -> { JAILED.remove(id); THIEF_TRIED.remove(id); VOTES.remove(id); }
			default -> { }
		}
		context.get().loadedIndex().find(id).ifPresent(bird -> sync(bird));
		AuditLog.info("released".equals(kind) ? "parrot_released" : "jailed".equals(kind) ? "parrot_jailed" : "parrot_jail_extended",
				AuditLog.Context.viewer(null, id, "CAGE"), "COMMITTED", null, AuditLog.started(), details, null);
	}

	// --- Табличка и вор, раз в секунду на загруженную птицу --------------------

	public static void tick(ServerContext context, ViewerId id, ViewerParrotEntity parrot) {
		if (parrot.age % SYNC_TICKS != 0) return;
		Map<String, Object> info = sync(parrot);
		if (info == null) { JAILED.remove(id); return; }
		JAILED.add(id);
		long releaseAt = number(info.get("releaseAt")), jailedAt = number(info.get("jailedAt")), now = number(info.get("now"));
		ParrotTrait trait = context.registry().find(id).map(ViewerParrotRecord::trait).orElse(ParrotTrait.NORMAL);
		if (trait != ParrotTrait.THIEF || releaseAt <= 0 || now < jailedAt + (releaseAt - jailedAt) / 2) return;
		if (Long.valueOf(jailedAt).equals(THIEF_TRIED.get(id))) return;
		THIEF_TRIED.put(id, jailedAt);
		CageCompat.call(Map.of("op", "effect", "name", "escape_attempt", "world", parrot.getWorld(), "at", parrot.getPos()));
		int chance = settingInt("thiefChancePercent", 15);
		boolean escaped = ThreadLocalRandom.current().nextInt(100) < chance
				&& Boolean.TRUE.equals(CageCompat.call(Map.of("op", "release", "parrot", parrot.getUuid(), "reason", "escape")));
		AuditLog.info("parrot_escape_attempt", AuditLog.Context.viewer(null, id, "CAGE"), escaped ? "ESCAPED" : "CAUGHT", null,
				AuditLog.started(), Map.of("chancePercent", chance), null);
	}

	/** Табличка птицы по сроку из Parrot Cages; карта info или null, если птица не узник. */
	@SuppressWarnings("unchecked")
	@Nullable private static Map<String, Object> sync(ViewerParrotEntity parrot) {
		Object info = CageCompat.isJailed(parrot) ? CageCompat.call(Map.of("op", "info", "parrot", parrot.getUuid())) : null;
		if (!(info instanceof Map<?, ?> map)) { parrot.setJailUntil(0); return null; }
		long releaseAt = number(map.get("releaseAt"));
		parrot.setJailUntil(releaseAt < 0 ? -1 : releaseAt);
		return (Map<String, Object>) map;
	}

	private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0; }

	private static int settingInt(String key, int fallback) {
		Object settings = CageCompat.call(Map.of("op", "settings"));
		return settings instanceof Map<?, ?> map && map.get(key) instanceof Number n ? n.intValue() : fallback;
	}

	private static boolean settingFlag(String key) {
		Object settings = CageCompat.call(Map.of("op", "settings"));
		return settings instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get(key));
	}

	// --- Twitch: !свободу и !вклетку ---------------------------------------------

	/** Поток IRC: команды клетки уходят в свою очередь, остальное — в обычный приём. */
	public static boolean offer(TwitchChatMessage message) {
		if (!CageCompat.loaded() || message == null || message.text() == null) return false;
		String first = message.text().strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
		if (!first.equals("!свободу") && !first.equals("!вклетку")) return false;
		if (CHAT_SIZE.incrementAndGet() > CHAT_CAPACITY) { CHAT_SIZE.decrementAndGet(); return true; }
		CHAT.add(message);
		return true;
	}

	public static void drain(ServerContext context) {
		for (int i = 0; i < CHAT_PER_TICK; i++) {
			TwitchChatMessage message = CHAT.poll();
			if (message == null) return;
			CHAT_SIZE.decrementAndGet();
			try { handle(context, message); }
			catch (RuntimeException failure) {
				AuditLog.warn("cage_chat_failed", null, "FAILED", "INTERNAL_ERROR", AuditLog.started(), Map.of(), failure);
			}
		}
	}

	private static void handle(ServerContext context, TwitchChatMessage message) {
		String[] words = message.text().strip().split("\\s+", 3);
		if (words.length < 2) return;
		String nick = words[1].startsWith("@") ? words[1].substring(1) : words[1];
		Optional<ViewerParrotRecord> target = findByNick(context, nick);
		if (target.isEmpty()) return;
		ViewerId id = target.get().viewer().viewerId();
		Optional<ViewerParrotEntity> bird = context.loadedIndex().find(id);
		if (bird.isEmpty()) return;
		if (words[0].equalsIgnoreCase("!свободу")) vote(context, message, id, bird.get());
		else jailFromChat(context, message, id, bird.get());
	}

	private static Optional<ViewerParrotRecord> findByNick(ServerContext context, String nick) {
		for (ViewerParrotRecord record : context.registry().allSorted()) {
			var viewer = record.viewer();
			if (viewer.displayName().value().equalsIgnoreCase(nick) || viewer.nickname() != null && viewer.nickname().value().equalsIgnoreCase(nick))
				return Optional.of(record);
		}
		return Optional.empty();
	}

	/** Пять разных зрителей за минуту — и узник на свободе; голос самого узника не считается, амнистия одна на срок. */
	private static void vote(ServerContext context, TwitchChatMessage message, ViewerId prisoner, ViewerParrotEntity bird) {
		Map<String, Object> info = sync(bird);
		if (info == null) return;
		String voter = "twitch:" + message.userId();
		if (voter.equals(prisoner.value())) return;
		long jailedAt = number(info.get("jailedAt"));
		Votes votes = VOTES.computeIfAbsent(prisoner, ignored -> new Votes());
		if (votes.jailedAt != jailedAt) { votes.jailedAt = jailedAt; votes.voters.clear(); votes.used = false; }
		if (votes.used) return;
		long now = System.currentTimeMillis(), window = settingInt("amnestyWindowSeconds", 60) * 1000L;
		votes.voters.values().removeIf(at -> now - at > window);
		votes.voters.put(voter, now);
		int needed = settingInt("amnestyVotes", 5);
		AuditLog.debug("parrot_amnesty_vote", AuditLog.Context.viewer(null, prisoner, "CAGE"), "COUNTED", null, AuditLog.started(),
				Map.of("votes", votes.voters.size(), "needed", needed), null);
		if (votes.voters.size() < needed) return;
		votes.used = true;
		CageCompat.call(Map.of("op", "release", "parrot", bird.getUuid(), "reason", "amnesty"));
	}

	private static void jailFromChat(ServerContext context, TwitchChatMessage message, ViewerId id, ViewerParrotEntity bird) {
		if (!settingFlag("modJailCommand")) return;
		if (!message.roles().contains(TwitchRole.MODERATOR) && !message.roles().contains(TwitchRole.BROADCASTER)) return;
		Optional<ServerPlayerEntity> streamer = context.streamerService().onlineTarget();
		if (streamer.isEmpty()) return;
		String result = cage(bird, streamer.get(), -1);
		AuditLog.info("parrot_jail_chat", AuditLog.Context.viewer(null, id, "CAGE"), result.isEmpty() ? "ACCEPTED" : "REJECTED", null,
				AuditLog.started(), Map.of("moderator", message.displayName(), "result", result), null);
	}

	/** Птица летит в ближайшую свободную клетку у стримера; "" — получилось, иначе причина. */
	static String cage(ViewerParrotEntity bird, ServerPlayerEntity streamer, int minutes) {
		if (!CageCompat.loaded()) return "Мод Parrot Cages не установлен";
		Map<String, Object> query = new HashMap<>();
		query.put("op", "cage"); query.put("parrot", bird); query.put("world", streamer.getServerWorld());
		query.put("center", streamer.getPos()); query.put("radius", CAGE_RADIUS); query.put("minutes", minutes);
		query.put("jailer", streamer.getUuid()); query.put("jailerName", streamer.getGameProfile().getName());
		Object answer = CageCompat.call(query);
		return answer instanceof String text ? text : "Parrot Cages не ответил";
	}

	// --- /vp cage и /vp uncage -----------------------------------------------------

	/** Сажают стример и администратор (теги Mob Talk, стример Viewer Parrots, оператор 3+). */
	public static boolean warden(ServerCommandSource source) { return isAdmin(source) || isStreamer(source); }

	public static boolean isAdmin(ServerCommandSource source) {
		ServerPlayerEntity player = source.getPlayer();
		return source.hasPermissionLevel(3) || player != null && player.getCommandTags().contains(ADMIN_TAG);
	}

	private static boolean isStreamer(ServerCommandSource source) {
		ServerPlayerEntity player = source.getPlayer();
		if (player == null) return false;
		if (player.getCommandTags().contains(STREAMER_TAG)) return true;
		return ServerContext.current().flatMap(c -> c.streamerService().configuredUuid()).filter(player.getUuid()::equals).isPresent();
	}

	public static int cageCommand(ServerCommandSource source, String rawViewerId, int minutes) {
		Optional<ViewerParrotEntity> bird = bird(source, rawViewerId);
		if (bird.isEmpty()) return 0;
		ServerPlayerEntity center = ServerContext.current().flatMap(c -> c.streamerService().onlineTarget()).orElse(source.getPlayer());
		if (center == null) { source.sendError(Text.literal("Стример не в игре: не у кого искать клетку")); return 0; }
		String result = cage(bird.get(), center, minutes);
		if (!result.isEmpty()) { source.sendError(Text.literal(result)); return 0; }
		source.sendFeedback(() -> Text.literal("Попугай " + bird.get().getDisplayName().getString() + " отправлен в клетку"), true);
		return 1;
	}

	public static int uncageCommand(ServerCommandSource source, String rawViewerId) {
		Optional<ViewerParrotEntity> bird = bird(source, rawViewerId);
		if (bird.isEmpty()) return 0;
		if (!CageCompat.isJailed(bird.get())) { source.sendError(Text.literal("Этот попугай не в тюрьме")); return 0; }
		boolean done = Boolean.TRUE.equals(CageCompat.call(Map.of("op", "release", "parrot", bird.get().getUuid(), "reason", "admin")));
		if (!done) { source.sendError(Text.literal("Клетка не ответила: попугай далеко от загруженных чанков")); return 0; }
		source.sendFeedback(() -> Text.literal("Попугай " + bird.get().getDisplayName().getString() + " выпущен"), true);
		return 1;
	}

	private static Optional<ViewerParrotEntity> bird(ServerCommandSource source, String rawViewerId) {
		Optional<ServerContext> context = ServerContext.current();
		if (context.isEmpty()) { source.sendError(Text.literal("Мод не инициализирован")); return Optional.empty(); }
		if (!CageCompat.loaded()) { source.sendError(Text.literal("Мод Parrot Cages не установлен")); return Optional.empty(); }
		ViewerId id;
		try { id = ViewerId.of(rawViewerId); }
		catch (IllegalArgumentException invalid) { source.sendError(Text.literal("Неверный viewerId: " + rawViewerId)); return Optional.empty(); }
		Optional<ViewerParrotEntity> bird = context.get().loadedIndex().find(id);
		if (bird.isEmpty()) source.sendError(Text.literal("Попугай " + rawViewerId + " сейчас не в загруженном мире"));
		return bird;
	}
}
