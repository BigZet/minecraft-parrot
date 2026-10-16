package io.github.viewerparrots.behavior;

import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.domain.DesiredBehavior;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Табличные переходы поведения при {@code LOADED} (раздел 5.2 ТЗ).
 *
 * <p>Ключевое разделение: {@link BehaviorState} — то, что попугай делает сейчас,
 * {@link DesiredBehavior} — сохраняемое намерение, к которому он возвращается после
 * временного действия. Поэтому {@code come} и {@code dance} намерение не меняют, а
 * {@code sit} и {@code follow} меняют.
 *
 * <p>Класс без состояния: он вычисляет переход, а хранение остаётся за runtime-моделью.
 */
public final class BehaviorStateMachine {

	public enum Event {
		CREATED_OR_RESPAWNED,
		COMMAND_SIT,
		COMMAND_FOLLOW,
		COMMAND_FREE,
		COMMAND_FREEFOLLOW,
		COMMAND_VIEW,
		COMMAND_DEVIEW,
		COMMAND_ARM,
		COMMAND_DEARM,
		COMMAND_COME,
		COMMAND_DANCE,
		COMMAND_SPIN,
		COMMAND_HAT,
		COMMAND_DEHAT,
		COMMAND_COLOR,
		COMMAND_NAME,
		COMMAND_EMOTE,
		COMMAND_SCREAM,
		COMMAND_LOVE,
		COMMAND_EXP,
		COMMAND_ROULETTE,
		/** Достигнут радиус прибытия. */
		ARRIVAL_REACHED,
		/** Движение не завершилось за отведённое время. */
		MOVEMENT_TIMEOUT,
		/** Истёк таймер танца. */
		DANCE_EXPIRED,
		/** Выгрузка, смерть или удаление — временные действия отменяются. */
		DEACTIVATED,
		/** Целевой стример вышел или погиб. */
		TARGET_LOST,
		/** A paused FOLLOWING intent resumes when the configured live target returns. */
		TARGET_AVAILABLE
	}

	/** Что нужно отменить при переходе. */
	public enum Cancellation {
		MOVEMENT,
		DANCE,
		PENDING_LAZY_ACTION
	}

	/**
	 * Результат перехода. Либо принят и содержит новое состояние, либо отвергнут
	 * с кодом ошибки — без исключений в бизнес-ветке (раздел 13 ТЗ).
	 */
	public sealed interface Result permits Accepted, Rejected {
	}

	public record Accepted(BehaviorState behavior, DesiredBehavior desiredBehavior,
			Set<Cancellation> cancellations) implements Result {
		public Accepted {
			Objects.requireNonNull(behavior, "behavior");
			Objects.requireNonNull(desiredBehavior, "desiredBehavior");
			cancellations = Set.copyOf(cancellations);
		}
	}

	public record Rejected(ErrorCode code) implements Result {
		public Rejected {
			Objects.requireNonNull(code, "code");
		}
	}

	private BehaviorStateMachine() {
	}

	public static Result apply(BehaviorState current, DesiredBehavior desired, Event event) {
		Objects.requireNonNull(current, "current");
		Objects.requireNonNull(desired, "desired");
		Objects.requireNonNull(event, "event");

		return switch (event) {
			case CREATED_OR_RESPAWNED -> accepted(BehaviorState.FREE, DesiredBehavior.FREE,
					Cancellation.MOVEMENT, Cancellation.DANCE, Cancellation.PENDING_LAZY_ACTION);

			// sit выполняется немедленно из любого состояния и не задерживается trait LAZY.
			case COMMAND_SIT -> accepted(BehaviorState.SITTING, DesiredBehavior.SITTING,
					Cancellation.MOVEMENT, Cancellation.DANCE, Cancellation.PENDING_LAZY_ACTION);

			case COMMAND_FOLLOW -> accepted(BehaviorState.FOLLOWING, DesiredBehavior.FOLLOWING,
					Cancellation.MOVEMENT, Cancellation.DANCE);

			case COMMAND_FREE -> accepted(BehaviorState.FREE, DesiredBehavior.FREE,
					Cancellation.MOVEMENT, Cancellation.DANCE, Cancellation.PENDING_LAZY_ACTION);

			case COMMAND_FREEFOLLOW, COMMAND_DEARM -> accepted(BehaviorState.FREEFOLLOW, DesiredBehavior.FREEFOLLOW,
					Cancellation.MOVEMENT, Cancellation.DANCE, Cancellation.PENDING_LAZY_ACTION);

			case COMMAND_ARM -> accepted(BehaviorState.SHOULDER, DesiredBehavior.FREEFOLLOW,
					Cancellation.MOVEMENT, Cancellation.DANCE, Cancellation.PENDING_LAZY_ACTION);

			// come — временное движение: прежнее стабильное намерение сохраняется.
			case COMMAND_COME -> accepted(BehaviorState.RETURNING, desired,
					Cancellation.MOVEMENT, Cancellation.DANCE);

			case COMMAND_DANCE -> current.isTransient()
					// Вложенный стек состояний не создаётся (раздел 5.2 ТЗ).
					? new Rejected(ErrorCode.INVALID_STATE)
					: accepted(BehaviorState.DANCING, desired);

			// A short visual spin or emote pauses movement without replacing the saved behavior.
			// Transient states (shoulder, dance, returning) reject them: no nested animation stack.
			case COMMAND_SPIN, COMMAND_EMOTE -> current.isTransient()
					? new Rejected(ErrorCode.INVALID_STATE)
					: accepted(current, desired);

			// Звук, сердечки и пузырёк опыта состояние не меняют.
			case COMMAND_SCREAM, COMMAND_LOVE, COMMAND_EXP, COMMAND_ROULETTE, COMMAND_HAT, COMMAND_DEHAT, COMMAND_COLOR, COMMAND_NAME,
					COMMAND_VIEW, COMMAND_DEVIEW -> accepted(current, desired);

			case ARRIVAL_REACHED, MOVEMENT_TIMEOUT -> current == BehaviorState.RETURNING
					? accepted(desired.toBehaviorState(), desired, Cancellation.MOVEMENT)
					: new Rejected(ErrorCode.INVALID_STATE);

			case DANCE_EXPIRED -> current == BehaviorState.DANCING
					? accepted(desired.toBehaviorState(), desired, Cancellation.DANCE)
					: new Rejected(ErrorCode.INVALID_STATE);

			case DEACTIVATED -> accepted(desired.toBehaviorState(), desired,
					Cancellation.MOVEMENT, Cancellation.DANCE, Cancellation.PENDING_LAZY_ACTION);

			// Следование фактически переходит в IDLE, но намерение FOLLOWING сохраняется,
			// чтобы движение возобновилось после появления живого target (раздел 5.3 ТЗ).
			case TARGET_LOST -> switch (current) {
				case FOLLOWING -> accepted(BehaviorState.IDLE, desired, Cancellation.MOVEMENT);
				case FREEFOLLOW -> accepted(BehaviorState.FREE, desired, Cancellation.MOVEMENT);
				case SHOULDER -> accepted(BehaviorState.FREEFOLLOW, DesiredBehavior.FREEFOLLOW,
						Cancellation.MOVEMENT, Cancellation.DANCE, Cancellation.PENDING_LAZY_ACTION);
				case RETURNING -> accepted(desired.toBehaviorState(), desired, Cancellation.MOVEMENT);
				// Танец может завершиться на месте.
				case DANCING, IDLE, FREE, SITTING -> accepted(current, desired);
			};
			case TARGET_AVAILABLE -> {
				if (current == BehaviorState.IDLE && desired == DesiredBehavior.FOLLOWING) {
					yield accepted(BehaviorState.FOLLOWING, desired, Cancellation.MOVEMENT);
				}
				if (current == BehaviorState.FREE && desired == DesiredBehavior.FREEFOLLOW) {
					yield accepted(BehaviorState.FREEFOLLOW, desired, Cancellation.MOVEMENT);
				}
				yield accepted(current, desired);
			}
		};
	}

	private static Accepted accepted(BehaviorState behavior, DesiredBehavior desired,
			Cancellation... cancellations) {
		Set<Cancellation> set = cancellations.length == 0
				? EnumSet.noneOf(Cancellation.class)
				: EnumSet.of(cancellations[0], cancellations);
		return new Accepted(behavior, desired, set);
	}
}
