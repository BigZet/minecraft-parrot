package io.github.viewerparrots.service;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.api.event.ParrotEvent;
import io.github.viewerparrots.behavior.BehaviorStateMachine;
import io.github.viewerparrots.behavior.LifecycleStateMachine;
import io.github.viewerparrots.behavior.ParrotRuntime;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.domain.LifecycleState;
import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import io.github.viewerparrots.ratelimit.GlobalRateLimiter;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Приём игровых команд: come, follow, sit, dance, scream (разделы 6 и 7 ТЗ).
 *
 * <p>Порядок проверок зафиксирован разделом 7 и соблюдается буквально:
 * права → аргументы → существование → готовность хранилища → состояние и target →
 * лимит → cooldown → выполнение. Порядок важен: например, отказ по правам не должен
 * расходовать глобальный бюджет, а ошибка валидации — cooldown.
 *
 * <p>Cooldown списывается только при принятии валидного действия, включая LAZY-отсрочку.
 * Последующая отмена его не возвращает.
 */
public final class ParrotCommandService {
	private final ServerContext context;
	private final CommandResultCache resultCache;

	public ParrotCommandService(ServerContext context) {
		this.context = Objects.requireNonNull(context, "context");
		this.resultCache = new CommandResultCache(context.clock());
	}

	public CommandResultCache resultCache() {
		return this.resultCache;
	}

	/**
	 * Выполняет игровое действие.
	 *
	 * @param commandId идентификатор запроса; повторный вызов с тем же идентификатором
	 *                  вернёт сохранённый результат, не выполняя действие заново
	 */
	public CommandResult submit(UUID commandId, ActorContext actor, ParrotAction action, ViewerId viewerId) {
		Objects.requireNonNull(commandId, "commandId");
		Objects.requireNonNull(actor, "actor");
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(viewerId, "viewerId");

		Optional<CommandResult> cached = this.resultCache.find(commandId);
		if (cached.isPresent()) {
			return cached.get();
		}

		CommandResult result = evaluate(commandId, actor, action, viewerId);
		this.resultCache.remember(result);

		if (result.succeeded()) {
			publish(ParrotEvent.Type.COMMAND_COMPLETED, viewerId, commandId, action.name(), null);
		} else {
			publish(ParrotEvent.Type.COMMAND_REJECTED, viewerId, commandId, action.name(),
					result.code() == null ? null : result.code().name());
		}
		return result;
	}

	private CommandResult evaluate(UUID commandId, ActorContext actor, ParrotAction action, ViewerId viewerId) {
		// 1. Права. Сервис проверяет их повторно за фильтром Brigadier.
		if (!this.context.permissions().isAllowed(actor, action)) {
			return CommandResult.rejected(commandId, ErrorCode.PERMISSION_DENIED);
		}

		// 2. Существование записи.
		Optional<ViewerParrotRecord> maybeRecord = this.context.registry().find(viewerId);
		if (maybeRecord.isEmpty()) {
			return CommandResult.rejected(commandId, ErrorCode.VIEWER_NOT_FOUND);
		}
		ViewerParrotRecord record = maybeRecord.get();

		// 3. Готовность хранилища: в degraded игровые действия тоже запрещены,
		// потому что они меняют desiredBehavior.
		if (!this.context.allowsMutations()) {
			return CommandResult.rejected(commandId, ErrorCode.STORAGE_UNAVAILABLE);
		}

		// 4. Состояние жизненного цикла.
		Optional<ViewerParrotEntity> maybeEntity = this.context.loadedIndex().find(viewerId);
		LifecycleState lifecycle = maybeEntity.isPresent() ? LifecycleState.LOADED : LifecycleState.UNLOADED;
		if (!LifecycleStateMachine.allows(lifecycle, action)) {
			return CommandResult.rejected(commandId,
					lifecycle == LifecycleState.LOADED ? ErrorCode.INVALID_STATE : ErrorCode.ENTITY_UNRESOLVED);
		}
		if (maybeEntity.isEmpty()) {
			// come из UNLOADED допустим, но сначала нужен ограниченный поиск.
			this.context.recoveryService().start(viewerId);
			return CommandResult.rejected(commandId, ErrorCode.ENTITY_UNRESOLVED);
		}
		ViewerParrotEntity parrot = maybeEntity.get();

		// 5. Целевой игрок для пространственных действий.
		if (requiresTarget(action)) {
			if (this.context.streamerService().configuredUuid().isEmpty()) {
				return CommandResult.rejected(commandId, ErrorCode.TARGET_NOT_CONFIGURED);
			}
			if (this.context.streamerService().onlineTarget().isEmpty()) {
				return CommandResult.rejected(commandId, ErrorCode.TARGET_UNAVAILABLE);
			}
		}

		// 6. Переход поведения: отказ здесь означает недопустимое состояние,
		// и cooldown он не расходует.
		ParrotRuntime runtime = this.context.runtime(viewerId);
		BehaviorStateMachine.Event event = toEvent(action);
		BehaviorStateMachine.Result transition =
				BehaviorStateMachine.apply(runtime.behavior(), runtime.desiredBehavior(), event);
		if (transition instanceof BehaviorStateMachine.Rejected rejected) {
			return CommandResult.rejected(commandId, rejected.code());
		}

		// 7. Глобальный лимит нагрузки.
		GlobalRateLimiter.Decision decision = this.context.rateLimiter().tryAcquire(action);
		if (!decision.allowed()) {
			return CommandResult.retryLater(commandId, ErrorCode.GLOBAL_RATE_LIMITED,
					decision.retryAfterTicks());
		}

		// 8. Персональный cooldown.
		long remaining = this.context.cooldowns().remainingTicks(viewerId, action);
		if (remaining > 0) {
			return CommandResult.retryLater(commandId, ErrorCode.COMMAND_ON_COOLDOWN, remaining);
		}

		// 9. Выполнение. Cooldown списывается здесь — действие принято.
		this.context.cooldowns().consume(viewerId, action, cooldownFor(action));

		boolean delayed = shouldDelay(record.trait(), action);
		if (delayed) {
			return scheduleLazy(commandId, viewerId, runtime, record, event, action);
		}

		return execute(commandId, viewerId, runtime, parrot,
				(BehaviorStateMachine.Accepted) transition, action);
	}

	/**
	 * Исполнение принятого действия. Отделено от валидации, потому что LAZY-задание
	 * приходит сюда позже и повторяет проверку состояния, но cooldown второй раз
	 * не списывает (раздел 10 ТЗ).
	 */
	public CommandResult execute(UUID commandId, ViewerId viewerId, ParrotRuntime runtime,
			ViewerParrotEntity parrot, BehaviorStateMachine.Accepted accepted, ParrotAction action) {
		String oldState = runtime.behavior().name();
		runtime.apply(accepted, this.context.clock().currentTick());

		switch (action) {
			case DANCE -> {
				long duration = this.context.config().dance().durationTicks();
				runtime.startDance(this.context.clock().currentTick() + duration);
				parrot.setForcedDancing(true);
			}
			case COME -> {
				runtime.startMovement(this.context.clock().currentTick()
						+ this.context.config().come().timeoutTicks());
				parrot.setForcedDancing(false);
			}
			case SIT -> {
				parrot.setForcedDancing(false);
				parrot.getNavigation().stop();
			}
			case FOLLOW -> parrot.setForcedDancing(false);
			case SCREAM -> this.context.soundService().playScream(parrot);
			default -> {
			}
		}

		persistDesiredBehavior(viewerId, runtime);
		publish(ParrotEvent.Type.BEHAVIOR_CHANGED, viewerId, commandId, oldState,
				runtime.behavior().name());

		ViewerParrotsMod.LOGGER.debug("action={} viewerId={} behavior={} desired={}",
				action, viewerId, runtime.behavior(), runtime.desiredBehavior());
		return CommandResult.succeeded(commandId, "viewerparrots.action.ok",
				CommandResult.details("viewerId", viewerId.value(), "action", action.name()));
	}

	private CommandResult scheduleLazy(UUID commandId, ViewerId viewerId, ParrotRuntime runtime,
			ViewerParrotRecord record, BehaviorStateMachine.Event event, ParrotAction action) {
		int min = this.context.config().behavior().lazyMinTicks();
		int max = this.context.config().behavior().lazyMaxTicks();
		long delay = min + this.context.behaviorRandom().nextInt(max - min + 1);
		long executeAt = this.context.clock().currentTick() + delay;

		// Новое отложенное действие заменяет предыдущее: их не может быть двух.
		runtime.setPendingAction(new ParrotRuntime.PendingAction(
				commandId, event, executeAt, record.generation()));

		return CommandResult.succeeded(commandId, "viewerparrots.action.delayed",
				CommandResult.details("viewerId", viewerId.value(),
						"action", action.name(),
						"delayTicks", Long.toString(delay)));
	}

	/**
	 * LAZY задерживает только движение. Раздел 10 прямо требует, чтобы sit, remove и
	 * respawn не задерживались никогда.
	 */
	private static boolean shouldDelay(ParrotTrait trait, ParrotAction action) {
		return trait == ParrotTrait.LAZY && (action == ParrotAction.COME || action == ParrotAction.FOLLOW);
	}

	private static boolean requiresTarget(ParrotAction action) {
		return action == ParrotAction.COME || action == ParrotAction.FOLLOW;
	}

	private static BehaviorStateMachine.Event toEvent(ParrotAction action) {
		return switch (action) {
			case COME -> BehaviorStateMachine.Event.COMMAND_COME;
			case FOLLOW -> BehaviorStateMachine.Event.COMMAND_FOLLOW;
			case SIT -> BehaviorStateMachine.Event.COMMAND_SIT;
			case DANCE -> BehaviorStateMachine.Event.COMMAND_DANCE;
			case SCREAM -> BehaviorStateMachine.Event.COMMAND_SCREAM;
			default -> throw new IllegalArgumentException("не игровое действие: " + action);
		};
	}

	private long cooldownFor(ParrotAction action) {
		var cooldowns = this.context.config().cooldowns();
		return switch (action) {
			case COME -> cooldowns.come();
			case FOLLOW -> cooldowns.follow();
			case SIT -> cooldowns.sit();
			case DANCE -> cooldowns.dance();
			case SCREAM -> cooldowns.scream();
			case RESPAWN -> cooldowns.respawn();
			default -> 0L;
		};
	}

	/** Намерение — durable поле, поэтому его изменение фиксируется журналом. */
	private void persistDesiredBehavior(ViewerId viewerId, ParrotRuntime runtime) {
		this.context.registry().find(viewerId).ifPresent(record -> {
			if (record.desiredBehavior() == runtime.desiredBehavior()) {
				return;
			}
			this.context.parrotService().recordDesiredBehavior(viewerId, runtime.desiredBehavior());
		});
	}

	private void publish(ParrotEvent.Type type, ViewerId viewerId, UUID commandId,
			String oldState, String newState) {
		this.context.registry().find(viewerId).ifPresent(record ->
				this.context.events().publish(ParrotEvent.of(type, viewerId, record.incarnationId(),
						record.generation(), this.context.clock().now(),
						this.context.clock().currentTick(), commandId, oldState, newState, null)));
	}

	/** Для диагностики: сколько результатов держится в кэше дедупликации. */
	public Map<String, Integer> diagnostics() {
		return Map.of("cachedResults", this.resultCache.size());
	}
}
