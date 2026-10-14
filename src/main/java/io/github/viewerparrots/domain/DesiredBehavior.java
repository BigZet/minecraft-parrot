package io.github.viewerparrots.domain;

/**
 * Сохраняемое намерение (раздел 4.1 ТЗ). После рестарта восстанавливается только оно:
 * незавершённые RETURNING, DANCING и LAZY-задания отменяются.
 */
public enum DesiredBehavior {
	IDLE,
	FREE,
	FREEFOLLOW,
	SITTING,
	FOLLOWING;

	public BehaviorState toBehaviorState() {
		return switch (this) {
			case IDLE -> BehaviorState.IDLE;
			case FREE -> BehaviorState.FREE;
			case FREEFOLLOW -> BehaviorState.FREEFOLLOW;
			case SITTING -> BehaviorState.SITTING;
			case FOLLOWING -> BehaviorState.FOLLOWING;
		};
	}

	/**
	 * @throws IllegalArgumentException для временных состояний: они намерением не становятся
	 */
	public static DesiredBehavior fromStable(BehaviorState state) {
		return switch (state) {
			case IDLE -> IDLE;
			case FREE -> FREE;
			case FREEFOLLOW -> FREEFOLLOW;
			case SITTING -> SITTING;
			case FOLLOWING -> FOLLOWING;
			case RETURNING, DANCING, SHOULDER -> throw new IllegalArgumentException(
					"Временное состояние " + state + " не является намерением");
		};
	}
}
