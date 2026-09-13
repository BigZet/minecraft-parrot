package io.github.viewerparrots.behavior;

import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.domain.DesiredBehavior;
import io.github.viewerparrots.domain.ViewerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Раздел 4.2 ТЗ: временные таймеры, LAZY-задание и изоляция ошибок поведения. */
class ParrotRuntimeTest {

	private ParrotRuntime runtime;

	@BeforeEach
	void setUp() {
		this.runtime = new ParrotRuntime(ViewerId.of("viewer1"));
	}

	private void applyEvent(BehaviorStateMachine.Event event, long tick) {
		BehaviorStateMachine.Result result = BehaviorStateMachine.apply(
				this.runtime.behavior(), this.runtime.desiredBehavior(), event);
		this.runtime.apply((BehaviorStateMachine.Accepted) result, tick);
	}

	@Test
	@DisplayName("новый runtime начинает с IDLE")
	void startsIdle() {
		assertAll(
				() -> assertEquals(BehaviorState.IDLE, this.runtime.behavior()),
				() -> assertEquals(DesiredBehavior.IDLE, this.runtime.desiredBehavior()));
	}

	@Test
	@DisplayName("таймер танца срабатывает ровно на дедлайне")
	void danceExpiresOnDeadline() {
		applyEvent(BehaviorStateMachine.Event.COMMAND_DANCE, 0);
		this.runtime.startDance(100);

		assertAll(
				() -> assertFalse(this.runtime.isDanceExpired(99)),
				() -> assertTrue(this.runtime.isDanceExpired(100)),
				() -> assertTrue(this.runtime.isDanceExpired(101)));
	}

	@Test
	@DisplayName("вне состояния DANCING таймер танца не срабатывает")
	void danceTimerIgnoredOutsideDancing() {
		this.runtime.startDance(100);
		assertFalse(this.runtime.isDanceExpired(1000));
	}

	@Test
	@DisplayName("sit отменяет танец, движение и LAZY-задание разом")
	void sitCancelsEverything() {
		applyEvent(BehaviorStateMachine.Event.COMMAND_DANCE, 0);
		this.runtime.startDance(100);
		this.runtime.startMovement(200);
		this.runtime.setPendingAction(new ParrotRuntime.PendingAction(
				UUID.randomUUID(), BehaviorStateMachine.Event.COMMAND_COME, 50, 1L));

		applyEvent(BehaviorStateMachine.Event.COMMAND_SIT, 10);

		assertAll(
				() -> assertEquals(BehaviorState.SITTING, this.runtime.behavior()),
				() -> assertEquals(DesiredBehavior.SITTING, this.runtime.desiredBehavior()),
				() -> assertFalse(this.runtime.isDanceExpired(1000)),
				() -> assertEquals(0L, this.runtime.movementDeadlineTick()),
				() -> assertTrue(this.runtime.pendingAction().isEmpty()));
	}

	@Test
	@DisplayName("новое LAZY-задание заменяет предыдущее: их не может быть двух")
	void newPendingActionReplacesPrevious() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		this.runtime.setPendingAction(new ParrotRuntime.PendingAction(
				first, BehaviorStateMachine.Event.COMMAND_COME, 50, 1L));
		this.runtime.setPendingAction(new ParrotRuntime.PendingAction(
				second, BehaviorStateMachine.Event.COMMAND_FOLLOW, 70, 1L));

		assertEquals(second, this.runtime.pendingAction().orElseThrow().commandId());
	}

	@Test
	@DisplayName("пересчёт маршрута не чаще раза в repathTicks")
	void repathIsThrottled() {
		assertTrue(this.runtime.canRepath(0));
		this.runtime.markRepathed(0, 20);

		assertAll(
				() -> assertFalse(this.runtime.canRepath(19)),
				() -> assertTrue(this.runtime.canRepath(20)));
	}

	@Test
	@DisplayName("восстановление намерения сбрасывает временные состояния")
	void restoreDesiredClearsTransientState() {
		applyEvent(BehaviorStateMachine.Event.COMMAND_DANCE, 0);
		this.runtime.startDance(100);
		this.runtime.startMovement(200);

		this.runtime.restoreDesired(DesiredBehavior.FOLLOWING);

		assertAll(
				() -> assertEquals(BehaviorState.FOLLOWING, this.runtime.behavior()),
				() -> assertEquals(DesiredBehavior.FOLLOWING, this.runtime.desiredBehavior()),
				() -> assertEquals(0L, this.runtime.movementDeadlineTick()),
				() -> assertTrue(this.runtime.pendingAction().isEmpty()));
	}

	@Test
	@DisplayName("после трёх подряд ошибок автономное поведение отключается")
	void behaviorDisablesAfterThreeConsecutiveErrors() {
		this.runtime.recordBehaviorError();
		this.runtime.recordBehaviorError();
		assertFalse(this.runtime.isBehaviorDisabledByErrors());

		this.runtime.recordBehaviorError();
		assertTrue(this.runtime.isBehaviorDisabledByErrors());
	}

	@Test
	@DisplayName("успешный тик обнуляет счётчик подряд идущих ошибок")
	void successResetsErrorStreak() {
		this.runtime.recordBehaviorError();
		this.runtime.recordBehaviorError();
		this.runtime.recordBehaviorSuccess();
		this.runtime.recordBehaviorError();
		this.runtime.recordBehaviorError();

		assertAll(
				() -> assertEquals(2, this.runtime.consecutiveBehaviorErrors()),
				() -> assertFalse(this.runtime.isBehaviorDisabledByErrors()));
	}
}
