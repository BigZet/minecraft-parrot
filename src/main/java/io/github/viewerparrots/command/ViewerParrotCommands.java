package io.github.viewerparrots.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.api.ActionParameters;
import io.github.viewerparrots.api.CommandRequest;
import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.api.CommandStatus;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.diagnostics.AuditLog;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.domain.DisplayName;
import io.github.viewerparrots.domain.LifecycleState;
import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.domain.PhysicalStatus;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.twitch.TwitchChatBridge;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Команды {@code /viewerparrot} и алиас {@code /vp} (раздел 6 ТЗ).
 *
 * <p>Дерево фильтруется по правам, но сервис проверяет их повторно: фильтрация Brigadier
 * не распространяется на внутренний Java API (раздел 12 ТЗ).
 *
 * <p>Полный набор раздела 6: идентичность, жизненный цикл и игровые действия.
 * Весь порядок проверок игровых действий живёт в {@code ParrotCommandService}.
 */
public final class ViewerParrotCommands {
	private static final int SUGGESTION_LIMIT = 100;
	private static final int PAGE_SIZE = 20;

	private ViewerParrotCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			LiteralArgumentBuilder<ServerCommandSource> root = build();
			var registered = dispatcher.register(root);
			// Алиас разделяет то же дерево, включая фильтры прав.
			dispatcher.register(CommandManager.literal("vp").redirect(registered));
		});
	}

	private static LiteralArgumentBuilder<ServerCommandSource> build() {
		return CommandManager.literal("viewerparrot")
				.requires(source -> allowed(source, ParrotAction.HELP))
				.executes(ctx -> help(ctx.getSource()))

				.then(CommandManager.literal("help")
						.requires(source -> allowed(source, ParrotAction.HELP))
						.executes(ctx -> help(ctx.getSource())))

				.then(TwitchChatBridge.commands().requires(source -> allowed(source, ParrotAction.STATUS)))

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

				.then(gameAction("come", ParrotAction.COME))
				.then(gameAction("follow", ParrotAction.FOLLOW))
				.then(gameAction("free", ParrotAction.FREE))
				.then(gameAction("freefollow", ParrotAction.FREEFOLLOW))
				.then(gameAction("view", ParrotAction.VIEW))
				.then(gameAction("deview", ParrotAction.DEVIEW))
				.then(gameAction("arm", ParrotAction.ARM))
				.then(gameAction("dearm", ParrotAction.DEARM))
				.then(gameAction("sit", ParrotAction.SIT))
				.then(gameAction("dance", ParrotAction.DANCE))
				.then(gameAction("spin", ParrotAction.SPIN))
				.then(gameAction("scream", ParrotAction.SCREAM))
				.then(CommandManager.literal("say")
						.requires(source -> allowed(source, ParrotAction.SAY))
						.then(viewerIdArgument()
								.then(CommandManager.argument("text", StringArgumentType.greedyString())
										.executes(ctx -> say(ctx, StringArgumentType.getString(ctx, "viewerId"),
												StringArgumentType.getString(ctx, "text"))))))
				.then(gameAction("love", ParrotAction.LOVE))
				.then(gameAction("hat", ParrotAction.HAT))
				.then(gameAction("dehat", ParrotAction.DEHAT))
				.then(gameAction("color", ParrotAction.COLOR))
				.then(gameAction("name", ParrotAction.NAME))
				.then(gameAction("exp", ParrotAction.EXP))
				.then(gameAction("roulette", ParrotAction.ROULETTE))

				.then(CommandManager.literal("save")
						.requires(source -> allowed(source, ParrotAction.SAVE))
						.executes(ctx -> save(ctx.getSource())))

				.then(CommandManager.literal("reload")
						.requires(source -> allowed(source, ParrotAction.RELOAD))
						.executes(ctx -> reload(ctx.getSource())))

				.then(CommandManager.literal("status")
						.requires(source -> allowed(source, ParrotAction.STATUS))
						.executes(ctx -> status(ctx.getSource())));
	}

	/**
	 * Игровые действия раздела 6 устроены одинаково, поэтому строятся общим методом:
	 * весь порядок проверок — права, состояние, лимиты, cooldown — живёт в сервисе,
	 * а не размазан по узлам дерева команд.
	 */
	private static LiteralArgumentBuilder<ServerCommandSource> gameAction(String literal,
			ParrotAction action) {
		return CommandManager.literal(literal)
				.requires(source -> allowed(source, action))
				.then(viewerIdArgument()
						.executes(ctx -> runGameAction(ctx, action,
								StringArgumentType.getString(ctx, "viewerId"))));
	}

	private static int runGameAction(CommandContext<ServerCommandSource> ctx, ParrotAction action,
			String rawViewerId) {
		return withViewerId(ctx, rawViewerId, (context, source, viewerId) ->
				submit(context, source, viewerId, action, ActionParameters.None.INSTANCE, null));
	}

	private static int say(CommandContext<ServerCommandSource> ctx, String rawViewerId, String text) {
		return withViewerId(ctx, rawViewerId, (context, source, viewerId) -> {
			try {
				return submit(context, source, viewerId, ParrotAction.SAY, ActionParameters.Speech.parse(text), null);
			} catch (IllegalArgumentException invalidText) {
				source.sendError(errorText(ErrorCode.INVALID_ARGUMENT, invalidText.getMessage()));
				return 0;
			}
		});
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
					.filter(id -> CommandSource.shouldSuggest(builder.getRemainingLowerCase(), id.toLowerCase(Locale.ROOT)))
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
		return withContext(source, context -> submit(context, source, null, ParrotAction.HELP,
				ActionParameters.None.INSTANCE, result -> showHelp(source, result)));
	}

	private static void showHelp(ServerCommandSource source, CommandResult result) {
		String control = """
				/viewerparrot streamer | info <viewerId> | list [page]
				/viewerparrot come <viewerId> | follow <viewerId> | sit <viewerId>
				/viewerparrot free <viewerId> | freefollow <viewerId> (free flight / roam near streamer)
				/viewerparrot view <viewerId> | deview <viewerId> (watch streamer / stop watching)
				/viewerparrot arm <viewerId> | dearm <viewerId> (vanilla shoulder / release)
				/viewerparrot dance <viewerId> | scream <viewerId> | love <viewerId> | exp <viewerId>
				/viewerparrot spin <viewerId> (spin in place for 3 seconds; 5-second cooldown)
				/viewerparrot hat <viewerId> | dehat <viewerId> (random different hat / remove; 2-second cooldown)
				/viewerparrot say <viewerId> <text> (first 30 words; 2-second cooldown)
				/viewerparrot roulette <viewerId> (4-second spin + 2-second result; prize drops at parrot; 60-second cooldown)
				Alias: /vp. Names may contain spaces; quote viewer IDs containing ':'.""";
		String admin = allowed(source, ParrotAction.CREATE) ? "\n" + """
				/viewerparrot setstreamer <player>
				/viewerparrot twitch connect | disconnect | status
				/viewerparrot create <viewerId> <name> | remove <viewerId>
				/viewerparrot rename <viewerId> <name> | trait <viewerId> <trait>
				/viewerparrot respawn <viewerId> | reconcile <viewerId>
				/viewerparrot save | reload | status
				HERO/THIEF have no effect in MVP; commands use the assigned streamer.""" : "";
		richFeedback(source, result, control + admin);
	}

	private static int showStreamer(ServerCommandSource source) {
		return withContext(source, context -> submit(context, source, null, ParrotAction.STREAMER,
				ActionParameters.None.INSTANCE, result -> {
			Optional<UUID> configured = context.streamerService().configuredUuid();
			String name = context.streamerService().onlineName().orElse("не в сети");
			boolean online = configured.flatMap(uuid -> Optional.ofNullable(context.server().getPlayerManager().getPlayer(uuid))).isPresent();
			boolean alive = context.streamerService().onlineTarget().isPresent();
			richFeedback(source, result, configured.map(uuid -> "Стример " + uuid + ", имя " + name
					+ ", online: " + online + ", доступен как живая цель: " + alive).orElse("Стример не назначен"));
		}));
	}

	private static int setStreamer(ServerCommandSource source, ServerPlayerEntity player) {
		return withContext(source, context -> submit(context, source, null, ParrotAction.SETSTREAMER,
				new ActionParameters.Target(player.getUuid()), null));
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

		return submit(context, source, viewerId, ParrotAction.CREATE, new ActionParameters.Name(displayName), null);
	}

	private static int remove(CommandContext<ServerCommandSource> ctx, String rawViewerId) {
		return withViewerId(ctx, rawViewerId, (context, source, viewerId) ->
				submit(context, source, viewerId, ParrotAction.REMOVE, ActionParameters.None.INSTANCE, null));
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
			return submit(context, source, viewerId, ParrotAction.RENAME, new ActionParameters.Name(displayName), null);
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

			return submit(context, source, viewerId, ParrotAction.TRAIT, new ActionParameters.Trait(trait), result -> {
						CommandFeedback.send(source, result);
						if (!trait.isActiveInMvp()) {
							source.sendFeedback(() -> Text.literal(
									trait + ": без эффекта в MVP"), false);
						}
					});
		});
	}

	private static int respawn(CommandContext<ServerCommandSource> ctx, String rawViewerId) {
		return withViewerId(ctx, rawViewerId, (context, source, viewerId) ->
				submit(context, source, viewerId, ParrotAction.RESPAWN, ActionParameters.None.INSTANCE, null));
	}

	private static int reconcile(CommandContext<ServerCommandSource> ctx, String rawViewerId) {
		return withViewerId(ctx, rawViewerId, (context, source, viewerId) ->
				submit(context, source, viewerId, ParrotAction.RECONCILE, ActionParameters.None.INSTANCE, null));
	}

	private static int info(CommandContext<ServerCommandSource> ctx, String rawViewerId) {
		return withViewerId(ctx, rawViewerId, (context, source, viewerId) -> submit(context, source,
				viewerId, ParrotAction.INFO, ActionParameters.None.INSTANCE, result -> {
			Optional<ViewerParrotRecord> maybeRecord = context.registry().find(viewerId);
			if (maybeRecord.isEmpty()) {
				// Удалённая регистрация остаётся видимой как REMOVED: раздел 9.5 требует,
				// чтобы info сообщал об этом, а не просто «не найдено».
				richFeedback(source, result, viewerId + ": " + LifecycleState.REMOVED);
				return;
			}

			ViewerParrotRecord record = maybeRecord.get();
			var cooldowns = context.cooldowns().remainingFor(viewerId);
			var runtime = context.runtimes().get(viewerId);

			richFeedback(source, result, String.join("\n",
					"viewerId: " + record.viewerId(),
					"имя: " + record.viewer().displayName(),
					"trait: " + record.trait() + (record.trait().isActiveInMvp() ? "" : " (без эффекта в MVP)"),
					"variant: " + record.variant(),
					"incarnation: " + record.incarnationId(),
					"generation: " + record.generation(),
					"physicalStatus: " + record.physicalStatus(),
					"lifecycle: " + context.lifecycle(viewerId),
					"behavior: " + (runtime == null ? "paused" : runtime.behavior()),
					"behaviorFault: " + (runtime != null && runtime.isBehaviorDisabledByErrors()),
					"operationPending: " + context.parrotService().operationPending(viewerId),
					"pendingAction: " + (runtime == null ? "none" : runtime.pendingAction().map(Object::toString).orElse("none")),
					"desiredBehavior: " + record.desiredBehavior(),
					"ожидаемый entityUuid: " + record.expectedEntityUuid().map(UUID::toString).orElse("нет"),
					"позиция: " + record.location().map(Object::toString).orElse("неизвестна"),
					"revision: " + record.revision(),
					"cooldowns: " + (cooldowns.isEmpty() ? "нет" : cooldowns.toString())));
		}));
	}

	private static int list(ServerCommandSource source, int page) {
		return withContext(source, context -> submit(context, source, null, ParrotAction.LIST,
				new ActionParameters.Page(page), result -> showList(context, source, page, result)));
	}

	private static void showList(ServerContext context, ServerCommandSource source, int page, CommandResult result) {
		List<ViewerParrotRecord> all = context.registry().allSorted();
		int totalPages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		// Event listeners may change the registry between API admission and feedback.
		if (page > totalPages) { richFeedback(source, result, "Страниц всего " + totalPages); return; }
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
		richFeedback(source, result, builder.toString());
	}

	private static int save(ServerCommandSource source) {
		return withContext(source, context -> submit(context, source, null, ParrotAction.SAVE,
				ActionParameters.None.INSTANCE, null));
	}

	private static int status(ServerCommandSource source) {
		return withContext(source, context -> submit(context, source, null, ParrotAction.STATUS,
				ActionParameters.None.INSTANCE, result -> showStatus(context, source, result)));
	}

	private static void showStatus(ServerContext context, ServerCommandSource source, CommandResult result) {
		var records = context.registry().allSorted();
		richFeedback(source, result, String.join("\n",
				"хранилище: " + context.storageHealth()
						+ context.storageFaultReason().map(reason -> " (" + reason + ")").orElse(""),
				"записей: " + context.registry().size(),
				"загружено: " + context.loadedIndex().size(),
				"DEAD: " + records.stream().filter(record -> record.physicalStatus() == PhysicalStatus.DEAD).count(),
				"MISSING: " + records.stream().filter(record -> record.physicalStatus() == PhysicalStatus.MISSING).count(),
				"зарезервировано слотов: " + context.registry().reservedSlots()
						+ " из " + context.config().maxActiveParrots(),
				"незавершённых резервов: " + context.registry().pendingReservations(),
				"надгробий: " + context.registry().tombstones().size(),
				"recovery-заданий: " + context.recoveryService().activeJobs()
						+ " из " + Math.min(io.github.viewerparrots.lifecycle.EntityRecoveryService
						.MAX_CONCURRENT_JOBS, context.config().recovery().maxConcurrentJobs()),
				"изменения не в снимке: " + context.registry().isDirty(),
				"dirty age: " + context.dirtyAgeTicks() + " ticks",
				"checkpoint: " + context.repository().checkpointSequence(),
				"writer queue: " + context.writer().queueDepth() + " / " + context.config().limits().queueCapacity(),
				"runtime references: " + context.runtimes().size(),
				"commands: " + context.commandService().diagnostics(),
				"scheduler last tick: " + context.behaviorScheduler().lastTickNanos() + " ns",
				"стример: " + context.streamerService().configuredUuid()
						.map(UUID::toString).orElse("не назначен")));
	}

	private static int reload(ServerCommandSource source) {
		return withContext(source, context -> submit(context, source, null, ParrotAction.RELOAD,
				ActionParameters.None.INSTANCE, null));
	}

	/** Typed facade owns admission, diagnostics budget, command events and error isolation. */
	private static int submit(ServerContext context, ServerCommandSource source, ViewerId viewerId,
			ParrotAction action, ActionParameters parameters, Consumer<CommandResult> successFeedback) {
		UUID commandId = UUID.randomUUID();
		long started = AuditLog.started();
		try {
			CompletableFuture<CommandResult> future = context.api().submit(
					new CommandRequest(commandId, viewerId, action, parameters), toActor(source, context)).toCompletableFuture();
			if (future.isDone()) {
				CommandResult result = future.getNow(null);
				feedback(source, result, successFeedback);
				return result.succeeded() || result.status() == CommandStatus.ACCEPTED ? 1 : 0;
			}
			CommandResult accepted = action.countsTowardActionBudget()
					? context.commandService().resultCache().find(commandId)
						.filter(result -> result.status() == CommandStatus.ACCEPTED).orElse(null) : null;
			if (accepted == null) accepted = new CommandResult(commandId, CommandStatus.ACCEPTED, null,
					"viewerparrots.api.accepted", Map.of(), 0);
			CommandFeedback.send(source, accepted);
			future.whenComplete((result, error) -> {
				if (error != null) internalError(context, source, commandId, viewerId, action, started, error);
				else {
					try { feedback(source, result, successFeedback); }
					catch (RuntimeException failure) { internalError(context, source, commandId, viewerId, action, started, failure); }
				}
			});
			return 1;
		} catch (RuntimeException error) {
			internalError(context, source, commandId, viewerId, action, started, error);
			return 0;
		}
	}

	private static void feedback(ServerCommandSource source, CommandResult result, Consumer<CommandResult> successFeedback) {
		if (result.succeeded() && successFeedback != null) successFeedback.accept(result);
		else CommandFeedback.send(source, result);
	}

	private static void internalError(ServerContext context, ServerCommandSource source, UUID commandId,
			ViewerId viewerId, ParrotAction action, long started, Throwable error) {
		AuditLog.Context identity = viewerId == null ? AuditLog.Context.viewer(commandId, null, action.name())
				: context.registry().find(viewerId).map(record -> AuditLog.Context.of(commandId, record, action.name()))
				.orElseGet(() -> AuditLog.Context.viewer(commandId, viewerId, action.name()));
		AuditLog.error("command_adapter_failed", identity, "FAILED", ErrorCode.INTERNAL_ERROR.name(), started, Map.of(), error);
		CommandFeedback.send(source, CommandResult.failed(commandId, ErrorCode.INTERNAL_ERROR, commandId.toString()));
	}

	private static void richFeedback(ServerCommandSource source, CommandResult result, String text) {
		source.sendFeedback(() -> Text.literal("[" + result.commandId() + "] " + text), false);
	}

	private interface ContextAction { int run(ServerContext context); }
	private static int withContext(ServerCommandSource source, ContextAction action) {
		Optional<ServerContext> context = ServerContext.current();
		if (context.isEmpty()) { source.sendError(Text.literal("Мод не инициализирован")); return 0; }
		return action.run(context.get());
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
		return Text.translatableWithFallback(code.messageKey(), code.name() + ": " + AuditLog.safeText(detail));
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
		return context.get().acceptingCommands() && context.get().permissions().isAllowed(actor, action);
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
