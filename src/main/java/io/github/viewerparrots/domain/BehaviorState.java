package io.github.viewerparrots.domain;

/**
 * Текущее поведение при {@link LifecycleState#LOADED} (раздел 5.2 ТЗ).
 */
public enum BehaviorState {
	IDLE,
	FREE,
	FREEFOLLOW,
	SITTING,
	FOLLOWING,
	RETURNING,
	DANCING,
	SHOULDER;

	/**
	 * Временные состояния: отменяются при unload, смерти, удалении и рестарте и никогда
	 * не становятся {@link DesiredBehavior}.
	 */
	public boolean isTransient() {
		return this == RETURNING || this == DANCING || this == SHOULDER;
	}
}
