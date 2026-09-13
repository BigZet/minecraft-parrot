package io.github.viewerparrots.behavior;

import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.domain.DesiredBehavior;
import io.github.viewerparrots.domain.ViewerId;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Несохраняемое состояние одного попугая (раздел 4.2 ТЗ).
 *
 * <p>Ничего отсюда не переживает рестарт: после перезапуска восстанавливается только
 * {@code desiredBehavior} из реестра, а незавершённые {@code DANCING}, {@code RETURNING}
 * и LAZY-задания отменяются. Cooldowns тоже сбрасываются — сознательное ограничение MVP.
 *
 * <p>Ссылок на {@code Entity} здесь нет: они живут в {@code LoadedEntityIndex} и удаляются
 * при выгрузке, чтобы после сотни циклов load/unload не копились ссылки.
 */
public final class ParrotRuntime {
	private final ViewerId viewerId;

	private BehaviorState behavior = BehaviorState.IDLE;
	private DesiredBehavior desiredBehavior = DesiredBehavior.IDLE;

	private UUID targetPlayerUuid;
	private long danceEndsAtTick;
	private long movementDeadlineTick;
	private long nextRepathTick;
	private PendingAction pendingAction;
	private int consecutiveBehaviorErrors;
	private boolean behaviorDisabledByErrors;

	public ParrotRuntime(ViewerId viewerId) {
		this.viewerId = Objects.requireNonNull(viewerId, "viewerId");
	}

	/** Отложенное trait-ом LAZY действие: не более одного на зрителя (раздел 10 ТЗ). */
	public record PendingAction(UUID commandId, BehaviorStateMachine.Event event, long executeAtTick,
			long generationAtSchedule) {
	}

	public ViewerId viewerId() {
		return this.viewerId;
	}

	public BehaviorState behavior() {
		return this.behavior;
	}

	public DesiredBehavior desiredBehavior() {
		return this.desiredBehavior;
	}

	/** Применяет результат автомата поведения вместе с отменами. */
	public void apply(BehaviorStateMachine.Accepted accepted, long currentTick) {
		this.behavior = accepted.behavior();
		this.desiredBehavior = accepted.desiredBehavior();

		for (BehaviorStateMachine.Cancellation cancellation : accepted.cancellations()) {
			switch (cancellation) {
				case MOVEMENT -> this.movementDeadlineTick = 0L;
				case DANCE -> this.danceEndsAtTick = 0L;
				case PENDING_LAZY_ACTION -> this.pendingAction = null;
			}
		}
	}

	/** Восстановление намерения после рестарта или bind: временные состояния не возвращаются. */
	public void restoreDesired(DesiredBehavior desired) {
		this.desiredBehavior = desired;
		this.behavior = desired.toBehaviorState();
		this.danceEndsAtTick = 0L;
		this.movementDeadlineTick = 0L;
		this.pendingAction = null;
	}

	public void startDance(long endsAtTick) {
		this.danceEndsAtTick = endsAtTick;
	}

	public boolean isDanceExpired(long currentTick) {
		return this.behavior == BehaviorState.DANCING
				&& this.danceEndsAtTick > 0
				&& currentTick >= this.danceEndsAtTick;
	}

	public void startMovement(long deadlineTick) {
		this.movementDeadlineTick = deadlineTick;
	}

	public long movementDeadlineTick() {
		return this.movementDeadlineTick;
	}

	public boolean isMovementExpired(long currentTick) {
		return this.behavior == BehaviorState.RETURNING
				&& this.movementDeadlineTick > 0
				&& currentTick >= this.movementDeadlineTick;
	}

	/**
	 * Разрешён ли пересчёт маршрута. Раздел 14 ограничивает его одним разом
	 * в {@code repathTicks} на попугая.
	 */
	public boolean canRepath(long currentTick) {
		return currentTick >= this.nextRepathTick;
	}

	public void markRepathed(long currentTick, int repathTicks) {
		this.nextRepathTick = currentTick + repathTicks;
	}

	public Optional<UUID> targetPlayerUuid() {
		return Optional.ofNullable(this.targetPlayerUuid);
	}

	public void setTargetPlayerUuid(UUID targetPlayerUuid) {
		this.targetPlayerUuid = targetPlayerUuid;
	}

	public Optional<PendingAction> pendingAction() {
		return Optional.ofNullable(this.pendingAction);
	}

	/** Новое отложенное действие заменяет предыдущее: их не может быть двух. */
	public void setPendingAction(PendingAction pendingAction) {
		this.pendingAction = pendingAction;
	}

	public void clearPendingAction() {
		this.pendingAction = null;
	}

	// --- Изоляция ошибок поведения (раздел 13 ТЗ) ----------------------------

	/**
	 * После трёх подряд ошибок автономное поведение этого зрителя отключается
	 * до reload или рестарта, чтобы сбой одного попугая не останавливал остальных.
	 */
	public void recordBehaviorError() {
		this.consecutiveBehaviorErrors++;
		if (this.consecutiveBehaviorErrors >= 3) {
			this.behaviorDisabledByErrors = true;
		}
	}

	public void recordBehaviorSuccess() {
		this.consecutiveBehaviorErrors = 0;
	}

	public boolean isBehaviorDisabledByErrors() {
		return this.behaviorDisabledByErrors;
	}

	public int consecutiveBehaviorErrors() {
		return this.consecutiveBehaviorErrors;
	}
}
