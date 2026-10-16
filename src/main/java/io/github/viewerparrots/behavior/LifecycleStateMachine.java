package io.github.viewerparrots.behavior;

import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.domain.LifecycleState;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Переходы жизненного цикла регистрации (раздел 5.1 ТЗ).
 *
 * <p>Класс не хранит состояние: он отвечает на два вопроса — разрешён ли переход по событию
 * и разрешено ли действие в данном состоянии. Владение состоянием остаётся за реестром.
 */
public final class LifecycleStateMachine {

	/** События жизненного цикла, а не команды пользователя. */
	public enum Event {
		/** Startup прочитал запись с physicalStatus = PRESENT. */
		REGISTRY_LOADED,
		/** Загрузилась сущность, прошедшая проверку маркера. */
		ENTITY_MATCHED,
		/** Чанк с сущностью выгружен. */
		CHUNK_UNLOADED,
		/** Ограниченная проверка завершена, совпадений нет. */
		RECOVERY_EXHAUSTED,
		/** Смерть подтверждена. */
		DEATH_CONFIRMED,
		/** Выполнен respawn: новая generation и новый UUID. */
		RESPAWNED,
		/** Регистрация удалена администратором. */
		REMOVED
	}

	/**
	 * Действия, допустимые в каждом состоянии. {@code UNLOADED} дополнительно разрешает
	 * безопасную попытку {@code come}: она выполняет ограниченный recovery и не создаёт
	 * новую сущность (раздел 6.1 ТЗ).
	 */
	private static final Map<LifecycleState, Set<ParrotAction>> ALLOWED_ACTIONS = Map.of(
			LifecycleState.UNRESOLVED, EnumSet.of(
					ParrotAction.INFO, ParrotAction.REMOVE, ParrotAction.RECONCILE),
			LifecycleState.LOADED, EnumSet.of(
					ParrotAction.INFO, ParrotAction.REMOVE, ParrotAction.RECONCILE,
					ParrotAction.RENAME, ParrotAction.TRAIT, ParrotAction.RESPAWN,
					ParrotAction.COME, ParrotAction.FOLLOW, ParrotAction.SIT,
					ParrotAction.FREE, ParrotAction.FREEFOLLOW, ParrotAction.VIEW,
					ParrotAction.DEVIEW, ParrotAction.ARM, ParrotAction.DEARM,
					ParrotAction.DANCE, ParrotAction.SPIN, ParrotAction.HAT, ParrotAction.DEHAT, ParrotAction.COLOR, ParrotAction.NAME, ParrotAction.EMOTE,
					ParrotAction.SCREAM, ParrotAction.LOVE, ParrotAction.EXP, ParrotAction.ROULETTE),
			LifecycleState.UNLOADED, EnumSet.of(
					ParrotAction.INFO, ParrotAction.REMOVE, ParrotAction.RECONCILE,
					ParrotAction.RENAME, ParrotAction.TRAIT, ParrotAction.COME),
			LifecycleState.MISSING, EnumSet.of(
					ParrotAction.INFO, ParrotAction.REMOVE, ParrotAction.RESPAWN,
					ParrotAction.RENAME, ParrotAction.TRAIT),
			LifecycleState.DEAD, EnumSet.of(
					ParrotAction.INFO, ParrotAction.REMOVE, ParrotAction.RESPAWN),
			LifecycleState.REMOVED, EnumSet.of(
					ParrotAction.INFO)
	);

	private LifecycleStateMachine() {
	}

	/**
	 * @return состояние после события, либо пусто, если переход не определён
	 */
	public static Optional<LifecycleState> transition(LifecycleState from, Event event) {
		if (from.isTerminal()) {
			// Удалённая incarnation никогда не оживает (раздел 4.4 ТЗ).
			return Optional.empty();
		}

		return switch (event) {
			case REMOVED -> Optional.of(LifecycleState.REMOVED);
			case REGISTRY_LOADED -> from == LifecycleState.UNRESOLVED
					? Optional.of(LifecycleState.UNRESOLVED)
					: Optional.empty();
			case ENTITY_MATCHED -> switch (from) {
				// Поздняя загрузка совпадающего текущего UUID допускает bind даже из MISSING,
				// пока замена не зафиксирована (раздел 5.1 ТЗ).
				case UNRESOLVED, UNLOADED, MISSING, LOADED -> Optional.of(LifecycleState.LOADED);
				case DEAD, REMOVED -> Optional.empty();
			};
			case CHUNK_UNLOADED -> from == LifecycleState.LOADED
					? Optional.of(LifecycleState.UNLOADED)
					: Optional.empty();
			case RECOVERY_EXHAUSTED -> switch (from) {
				case UNRESOLVED, UNLOADED -> Optional.of(LifecycleState.MISSING);
				default -> Optional.empty();
			};
			case DEATH_CONFIRMED -> switch (from) {
				case LOADED, UNLOADED, UNRESOLVED, MISSING -> Optional.of(LifecycleState.DEAD);
				case DEAD, REMOVED -> Optional.empty();
			};
			case RESPAWNED -> switch (from) {
				case MISSING, DEAD -> Optional.of(LifecycleState.UNRESOLVED);
				default -> Optional.empty();
			};
		};
	}

	public static boolean allows(LifecycleState state, ParrotAction action) {
		return ALLOWED_ACTIONS.getOrDefault(state, EnumSet.noneOf(ParrotAction.class)).contains(action);
	}

	public static Set<ParrotAction> allowedActions(LifecycleState state) {
		return Collections.unmodifiableSet(
				ALLOWED_ACTIONS.getOrDefault(state, EnumSet.noneOf(ParrotAction.class)));
	}
}
