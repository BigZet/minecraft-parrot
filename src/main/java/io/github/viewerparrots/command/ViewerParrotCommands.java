package io.github.viewerparrots.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.bootstrap.CheckpointScheduler;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.domain.DisplayName;
import io.github.viewerparrots.domain.LifecycleState;
import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Команды {@code /viewerparrot} и алиас {@code /vp} (раздел 6 ТЗ).
 *
 * <p>Дерево фильтруется по правам, но сервис проверяет их повторно: фильтрация Brigadier
 * не распространяется на внутренний Java API (раздел 12 ТЗ).
 *
 * <p>Набор этапа M2: идентичность и жизненный цикл. Команды движения
 * ({@code come}, {@code follow}, {@code sit}, {@code dance}, {@code scream}) появятся на M3
 * вместе с очередью действий и таймерами.
 */
public final class ViewerParrotCommands {
	private static final int SUGGESTION_LIMIT = 100;
	private static final int PAGE_SIZE = 20;

	private ViewerParrotCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			LiteralArgumentBuilder<ServerCommandSource> root = build();
			dispatcher.register(root);
			// Алиас разделяет то же дерево, включая фильтры прав.
			dispatcher.register(CommandManager.literal("vp").redirect(dispatcher.register(build())));
		});
	}

	private static LiteralArgumentBuilder<ServerCommandSource> build() {
		return CommandManager.literal("viewerparrot")
				.requires(source -> allowed(source, ParrotAction.HELP))
				.executes(ctx -> help(ctx.getSource()))

				.then(CommandManager.literal("help")
						.requires(source -> allowed(source, ParrotAction.HELP))
						.executes(ctx -> help(ctx.getSource())))

				.then(CommandManager.literal("streamer")
						.requires(source -> allowed(source, ParrotAction.STREAMER))
						.executes(ctx -> showStreamer(ctx.getSource())))

				.then(CommandManager.literal("setstreamer")
						.requires(source -> allowed(source, ParrotAction.SETSTREAMER))
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.executes(ctx -> setStreamer(ctx.getSource(),
										EntityArgumentType.getPlayer(ctx, "player")))))

				.then(CommandManager.literal("create")
						.requires(source -> allowed(source, ParrotAction.CREATE))
						.then(CommandManager.argument("viewerId", StringArgumentType.string())
								.then(CommandManager.argument("displayName", StringArgumentType.greedyString())
										.executes(ctx -> create(ctx,
												StringArgumentType.getString(ctx, "viewerId"),
												StringArgumentType.getString(ctx, "displayName"))))))

				.then(CommandManager.literal("remove")
						.requires(source -> allowed(source, ParrotAction.REMOVE))
						.then(viewerIdArgument()
								.executes(ctx -> remove(ctx, StringArgumentType.getString(ctx, "viewerId")))))

				.then(CommandManager.literal("rename")
						.requires(source -> allowed(source, ParrotAction.RENAME))
						.then(viewerIdArgument()
								.then(CommandManager.argument("displayName", StringArgumentType.greedyString())
										.executes(ctx -> rename(ctx,
												StringArgumentType.getString(ctx, "viewerId"),
												StringArgumentType.getString(ctx, "displayName"))))))

				.then(CommandManager.literal("trait")
						.requires(source -> allowed(source, ParrotAction.TRAIT))
						.then(viewerIdArgument()
								.then(CommandManager.argument("trait", StringArgumentType.word())
										.suggests(traitSuggestions())
										.executes(ctx -> setTrait(ctx,
												StringArgumentType.getString(ctx, "viewerId"),
												StringArgumentType.getString(ctx, "trait"))))))

				.then(CommandManager.literal("respawn")
						.requires(source -> allowed(source, ParrotAction.RESPAWN))
						.then(viewerIdArgument()
								.executes(ctx -> respawn(ctx, StringArgumentType.getString(ctx, "viewerId")))))

				.then(CommandManager.literal("reconcile")
						.requires(source -> allowed(source, ParrotAction.RECONCILE))
						.then(viewerIdArgument()
								.executes(ctx -> reconcile(ctx, StringArgumentType.getString(ctx, "viewerId")))))

				.then(CommandManager.literal("info")
						.requires(source -> allowed(source, ParrotAction.INFO))
						.then(viewerIdArgument()
								.executes(ctx -> info(ctx, StringArgumentType.getString(ctx, "viewerId")))))

				.then(CommandManager.literal("list")
						.requires(source -> allowed(source, ParrotAction.LIST))
						.executes(ctx -> list(ctx.getSource(), 1))
						.then(CommandManager.argument("page", IntegerArgumentType.integer(1))
								.executes(ctx -> list(ctx.getSource(),
										IntegerArgumentType.getInteger(ctx, "page")))))

				.then(CommandManager.literal("save")
						.requires(source -> allowed(source, ParrotAction.SAVE))
						.executes(ctx -> save(ctx.getSource())))

				.then(CommandManager.literal("status")
						.requires(source -> allowed(source, ParrotAction.STATUS))
						.executes(ctx -> status(ctx.getSource())));
	}

	/**
	 * {@code string()}, а не {@code word()}: раздел 4.1 разрешает в viewerId символ
	 * {@code :}, а Brigadier не принимает его в неквотированном слове. С {@code string()}
	 * обычные id по-прежнему вводятся без кавычек, а id с {@code :} — в кавычках.
	 */
	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, String>
			viewerIdArgument() {
		return CommandManager.argument("viewerId", StringArgumentType.string())
				.suggests(viewerIdSuggestions());
	}

	/** Подсказки доступны только авторизованным отправителям и ограничены 100 совпадениями. */
	private static SuggestionProvider<ServerCommandSource> viewerIdSuggestions() {
		return (ctx, builder) -> {
			Optional<ServerContext> context = ServerContext.current();
			if (context.isEmpty() || !allowed(ctx.getSource(), ParrotAction.INFO)) {
				return builder.buildFuture();
			}
			List<String> ids = context.get().registry().allSorted().stream()
					.map(record -> record.viewerId().value())
					.limit(SUGGESTION_LIMIT)
					.toList();
			return CommandSource.suggestMatching(ids, builder);
		};
	}

	private static SuggestionProvider<ServerCommandSource> traitSuggestions() {
		return (ctx, builder) -> CommandSource.suggestMatching(
				java.util.Arrays.stream(ParrotTrait.values()).map(Enum::name).toList(), builder);
	}

	// --- реализации ----------------------------------------------------------

	private static int help(ServerCommandSource source) {
		source.sendFeedback(() -> Text.literal("""
				/viewerparrot setstreamer <player> | streamer
				/viewerparrot create <viewerId> <имя> | remove <viewerId>
				/viewerparrot rename <viewerId> <имя> | trait <viewerId> <trait>
				/viewerparrot respawn <viewerId> | reconcile <viewerId>
				/viewerparrot info <viewerId> | list [страница]
				/viewerparrot save | status"""), false);
		return 1;
	}

	private static int showStreamer(ServerCommandSource source) {
		Optional<ServerContext> maybeContext = ServerContext.current();
		if (maybeContext.isEmpty()) {
			source.sendError(Text.literal("Мод не инициализирован"));
			return 0;
		}
		ServerContext context = maybeContext.get();

		Optional<UUID> configured = context.streamerService().configuredUuid();
		if (configured.isEmpty()) {
			source.sendError(Text.literal("Стример не назначен"));
			return 0;
		}

		String name = context.streamerService().onlineName().orElse("не в сети");
		boolean alive = context.streamerService().onlineTarget().isPresent();
		source.sendFeedback(() -> Text.literal("Стример " + configured.get()
				+ ", имя " + name + ", доступен как цель: " + alive), false);
		return 1;
	}

	private static int setStreamer(ServerCommandSource source, ServerPlayerEntity player) {
		Optional<ServerContext> maybeContext = ServerContext.current();
		if (maybeContext.isEmpty()) {
			source.sendError(Text.literal("Мод не инициализирован"));
			return 0;
		}
		ServerContext context = maybeContext.get();
		if (!context.allowsMutations()) {
			source.sendError(errorText(ErrorCode.STORAGE_UNAVAILABLE));
			return 0;
		}

		context.streamerService().assign(player.getUuid());
		source.sendFeedback(() -> Text.literal("Стример назначен: "
				+ player.getGameProfile().getName() + " (" + player.getUuid() + ")"), true);
		return 1;
	}

	private static int create(CommandContext<ServerCommandSource> ctx, String rawViewerId, String rawName) {
		ServerCommandSource source = ctx.getSource();
		Optional<ServerContext> maybeContext = ServerContext.current();
		if (maybeContext.isEmpty()) {
			source.sendError(Text.literal("Мод не инициализирован"));
			return 0;
		}
		ServerContext context = maybeContext.get();

		ViewerId viewerId;
		DisplayName displayName;
		try {
			viewerId = ViewerId.of(rawViewerId);
			displayName = DisplayName.of(rawName);
		} catch (IllegalArgumentException e) {
			source.sendError(errorText(ErrorCode.INVALID_ARGUMENT, e.getMessage()));
			return 0;
		}

		UUID commandId = UUID.randomUUID();
		context.parrotService().create(commandId, viewerId, displayName)
				.thenAccept(result -> CommandFeedback.send(source, result));
		return 1;
	}

	private static int remove(CommandContext<ServerCommandSource> ctx, String rawViewerId) {
		return withViewerId(ctx, rawViewerId, (context, source, viewerId) -> {
			UUID commandId = UUID.randomUUID();
			context.parrotService().remove(commandId, viewerId)
					.thenAccept(result -> CommandFeedback.send(source, result));
			return 1;
		});
	}

	private static int rename(CommandContext<ServerCommandSource> ctx, String rawViewerId, String rawName) {
		return withViewerId(ctx, rawViewerId, (context, source, viewerId) -> {
			DisplayName displayName;
			try {
				displayName = DisplayName.of(rawName);
			} catch (IllegalArgumentException e) {
				source.sendError(errorText(ErrorCode.INVALID_ARGUMENT, e.getMessage()));
				return 0;
			}
			UUID commandId = UUID.randomUUID();
			context.parrotService().rename(commandId, viewerId, displayName)
					.thenAccept(result -> CommandFeedback.send(source, result));
			return 1;
		});
	}

	private static int setTrait(CommandContext<ServerCommandSource> ctx, String rawViewerId, String rawTrait) {
		return withViewerId(ctx, rawViewerId, (context, source, viewerId) -> {
			ParrotTrait trait;
			try {
				trait = ParrotTrait.valueOf(rawTrait.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				source.sendError(errorText(ErrorCode.INVALID_ARGUMENT, "неизвестный trait " + rawTrait));
				return 0;
			}

			UUID commandId = UUID.randomUUID();
			context.parrotService().setTrait(commandId, viewerId, trait)
					.thenAccept(result -> {
						CommandFeedback.send(source, result);
						if (result.succeeded() && !trait.isActiveInMvp()) {
							source.sendFeedback(() -> Text.literal(
									trait + ": без эффекта в MVP"), false);
						}
					});
			return 1;
		});
	}

	private static int respawn(CommandContext<ServerCommandSource> ctx, String rawViewerId) {
		return withViewerId(ctx, rawViewerId, (context, source, viewerId) -> {
			UUID commandId = UUID.randomUUID();
			context.parrotService().respawn(commandId, viewerId)
					.thenAccept(result -> CommandFeedback.send(source, result));
			return 1;
		});
	}

	private static int reconcile(CommandContext<ServerCommandSource> ctx, String rawViewerId) {
		return withViewerId(ctx, rawViewerId, (context, source, viewerId) -> {
			CommandResult result = context.parrotService().reconcile(UUID.randomUUID(), viewerId);
			CommandFeedback.send(source, result);
			return result.succeeded() ? 1 : 0;
		});
	}

	private static int info(CommandContext<ServerCommandSource> ctx, String rawViewerId) {
		return withViewerId(ctx, rawViewerId, (context, source, viewerId) -> {
			Optional<ViewerParrotRecord> maybeRecord = context.registry().find(viewerId);
			if (maybeRecord.isEmpty()) {
				boolean removed = !context.registry().tombstonesFor(viewerId).isEmpty();
				// Удалённая регистрация остаётся видимой как REMOVED: раздел 9.5 требует,
				// чтобы info сообщал об этом, а не просто «не найдено».
				source.sendError(removed
						? Text.literal(viewerId + ": " + LifecycleState.REMOVED)
						: errorText(ErrorCode.VIEWER_NOT_FOUND));
				return 0;
			}

			ViewerParrotRecord record = maybeRecord.get();
			boolean loaded = context.loadedIndex().isLoaded(viewerId);
			var cooldowns = context.cooldowns().remainingFor(viewerId);

			source.sendFeedback(() -> Text.literal(String.join("\n",
					"viewerId: " + record.viewerId(),
					"имя: " + record.viewer().displayName(),
					"trait: " + record.trait() + (record.trait().isActiveInMvp() ? "" : " (без эффекта в MVP)"),
					"variant: " + record.variant(),
					"incarnation: " + record.incarnationId(),
					"generation: " + record.generation(),
					"physicalStatus: " + record.physicalStatus(),
					"lifecycle: " + (loaded ? LifecycleState.LOADED : LifecycleState.UNLOADED),
					"desiredBehavior: " + record.desiredBehavior(),
					"ожидаемый entityUuid: " + record.expectedEntityUuid().map(UUID::toString).orElse("нет"),
					"позиция: " + record.location().map(Object::toString).orElse("неизвестна"),
					"revision: " + record.revision(),
					"cooldowns: " + (cooldowns.isEmpty() ? "нет" : cooldowns.toString()))), false);
			return 1;
		});
	}

	private static int list(ServerCommandSource source, int page) {
		Optional<ServerContext> maybeContext = ServerContext.current();
		if (maybeContext.isEmpty()) {
			source.sendError(Text.literal("Мод не инициализирован"));
			return 0;
		}
		ServerContext context = maybeContext.get();

		List<ViewerParrotRecord> all = context.registry().allSorted();
		int totalPages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		if (page > totalPages) {
			source.sendError(Text.literal("Страниц всего " + totalPages));
			return 0;
		}

		int from = (page - 1) * PAGE_SIZE;
		int to = Math.min(all.size(), from + PAGE_SIZE);
		StringBuilder builder = new StringBuilder("Записей " + all.size()
				+ ", страница " + page + " из " + totalPages);
		for (ViewerParrotRecord record : all.subList(from, to)) {
			// Без полного dump NBT: раздел 6 требует краткой сводки.
			builder.append('\n').append(record.viewerId())
					.append(" — ").append(record.viewer().displayName())
					.append(", ").append(record.trait())
					.append(", ").append(record.variant())
					.append(", ").append(record.physicalStatus())
					.append(context.loadedIndex().isLoaded(record.viewerId()) ? ", загружен" : "");
		}
		source.sendFeedback(() -> Text.literal(builder.toString()), false);
		return all.size();
	}

	private static int save(ServerCommandSource source) {
		Optional<ServerContext> maybeContext = ServerContext.current();
		if (maybeContext.isEmpty()) {
			source.sendError(Text.literal("Мод не инициализирован"));
			return 0;
		}
		ServerContext context = maybeContext.get();
		if (!context.allowsMutations()) {
			source.sendError(errorText(ErrorCode.STORAGE_UNAVAILABLE));
			return 0;
		}

		// Итог сообщается после завершения записи, а не по факту приёма команды.
		CheckpointScheduler.request(context).whenComplete((result, error) -> {
			if (error != null) {
				source.sendError(Text.literal("Checkpoint не выполнен: " + error.getMessage()));
				return;
			}
			source.sendFeedback(() -> Text.literal("Checkpoint " + result.checkpointSequence()
					+ " записан" + (result.atomicReplace() ? " атомарно" : " без атомарной замены")), true);
		});
		return 1;
	}

	private static int status(ServerCommandSource source) {
		Optional<ServerContext> maybeContext = ServerContext.current();
		if (maybeContext.isEmpty()) {
			source.sendError(Text.literal("Мод не инициализирован"));
			return 0;
		}
		ServerContext context = maybeContext.get();

		source.sendFeedback(() -> Text.literal(String.join("\n",
				"хранилище: " + context.storageHealth()
						+ context.storageFaultReason().map(reason -> " (" + reason + ")").orElse(""),
				"записей: " + context.registry().size(),
				"загружено: " + context.loadedIndex().size(),
				"зарезервировано слотов: " + context.registry().reservedSlots()
						+ " из " + context.config().maxActiveParrots(),
				"незавершённых резервов: " + context.registry().pendingReservations(),
				"надгробий: " + context.registry().tombstones().size(),
				"recovery-заданий: " + context.recoveryService().activeJobs()
						+ " из " + Math.min(io.github.viewerparrots.lifecycle.EntityRecoveryService
						.MAX_CONCURRENT_JOBS, context.config().recovery().maxConcurrentJobs()),
				"изменения не в снимке: " + context.registry().isDirty(),
				"checkpoint: " + context.repository().checkpointSequence(),
				"стример: " + context.streamerService().configuredUuid()
						.map(UUID::toString).orElse("не назначен"))), false);
		return 1;
	}

	// --- вспомогательное -----------------------------------------------------

	/**
	 * Локализуемый текст ошибки с читаемым fallback: на dedicated server у консоли нет
	 * загруженных переводов, и без fallback она увидела бы сырой ключ.
	 */
	private static Text errorText(ErrorCode code) {
		return Text.translatableWithFallback(code.messageKey(), code.name());
	}

	private static Text errorText(ErrorCode code, String detail) {
		return Text.translatableWithFallback(code.messageKey(), code.name() + ": " + detail);
	}

	private interface ViewerIdAction {
		int run(ServerContext context, ServerCommandSource source, ViewerId viewerId);
	}

	private static int withViewerId(CommandContext<ServerCommandSource> ctx, String rawViewerId,
			ViewerIdAction action) {
		ServerCommandSource source = ctx.getSource();
		Optional<ServerContext> maybeContext = ServerContext.current();
		if (maybeContext.isEmpty()) {
			source.sendError(Text.literal("Мод не инициализирован"));
			return 0;
		}
		ViewerId viewerId;
		try {
			viewerId = ViewerId.of(rawViewerId);
		} catch (IllegalArgumentException e) {
			source.sendError(errorText(ErrorCode.INVALID_ARGUMENT, e.getMessage()));
			return 0;
		}
		return action.run(maybeContext.get(), source, viewerId);
	}

	/**
	 * Фильтр узлов дерева. Уровень берётся из конфигурации, пока контекст существует;
	 * до инициализации используется значение по умолчанию из ТЗ.
	 */
	private static boolean allowed(ServerCommandSource source, ParrotAction action) {
		Optional<ServerContext> context = ServerContext.current();
		if (context.isEmpty()) {
			return source.hasPermissionLevel(action.requiresAdmin() ? 3 : 2);
		}
		ActorContext actor = toActor(source, context.get());
		return context.get().permissions().isAllowed(actor, action);
	}

	private static ActorContext toActor(ServerCommandSource source, ServerContext context) {
		int level = maxLevel(source);
		ServerPlayerEntity player = source.getPlayer();
		if (player != null) {
			return ActorContext.player(player.getUuid(), level);
		}
		return ActorContext.console(level);
	}

	/** Brigadier не отдаёт уровень напрямую, поэтому он определяется перебором. */
	private static int maxLevel(ServerCommandSource source) {
		for (int level = 4; level >= 1; level--) {
			if (source.hasPermissionLevel(level)) {
				return level;
			}
		}
		return 0;
	}
}
