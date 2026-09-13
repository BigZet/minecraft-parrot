package io.github.viewerparrots.behavior;

import io.github.viewerparrots.behavior.BehaviorStateMachine.Accepted;
import io.github.viewerparrots.behavior.BehaviorStateMachine.Cancellation;
import io.github.viewerparrots.behavior.BehaviorStateMachine.Event;
import io.github.viewerparrots.behavior.BehaviorStateMachine.Rejected;
import io.github.viewerparrots.behavior.BehaviorStateMachine.Result;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.domain.DesiredBehavior;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Табличные переходы поведения из раздела 5.2 ТЗ (требование раздела 15.1). */
class BehaviorStateMachineTest {

	private static Accepted accepted(BehaviorState from, DesiredBehavior desired, Event event) {
		Result result = BehaviorStateMachine.apply(from, desired, event);
		return assertInstanceOf(Accepted.class, result, "ожидался принятый переход");
	}

	@ParameterizedTest
	@EnumSource(BehaviorState.class)
	@DisplayName("sit выполняется из любого состояния и отменяет движение, танец и LAZY-задание")
	void sitAlwaysWinsAndCancelsEverything(BehaviorState from) {
		Accepted result = accepted(from, DesiredBehavior.FOLLOWING, Event.COMMAND_SIT);

		assertAll(
				() -> assertEquals(BehaviorState.SITTING, result.behavior()),
				() -> assertEquals(DesiredBehavior.SITTING, result.desiredBehavior()),
				() -> assertTrue(result.cancellations().containsAll(java.util.Set.of(
						Cancellation.MOVEMENT, Cancellation.DANCE, Cancellation.PENDING_LAZY_ACTION))));
	}

	@ParameterizedTest
	@EnumSource(BehaviorState.class)
	@DisplayName("follow меняет и поведение, и сохраняемое намерение")
	void followChangesIntent(BehaviorState from) {
		Accepted result = accepted(from, DesiredBehavior.IDLE, Event.COMMAND_FOLLOW);

		assertAll(
				() -> assertEquals(BehaviorState.FOLLOWING, result.behavior()),
				() -> assertEquals(DesiredBehavior.FOLLOWING, result.desiredBehavior()));
	}

	@ParameterizedTest
	@EnumSource(DesiredBehavior.class)
	@DisplayName("come — временное движение: прежнее намерение сохраняется")
	void comeKeepsPreviousIntent(DesiredBehavior desired) {
		Accepted result = accepted(BehaviorState.IDLE, desired, Event.COMMAND_COME);

		assertAll(
				() -> assertEquals(BehaviorState.RETURNING, result.behavior()),
				() -> assertEquals(desired, result.desiredBehavior()));
	}

	@ParameterizedTest
	@CsvSource({"IDLE,IDLE", "SITTING,SITTING", "FOLLOWING,FOLLOWING"})
	@DisplayName("dance из стабильного состояния сохраняет намерение")
	void danceFromStableStateKeepsIntent(BehaviorState from, DesiredBehavior desired) {
		Accepted result = accepted(from, desired, Event.COMMAND_DANCE);

		assertAll(
				() -> assertEquals(BehaviorState.DANCING, result.behavior()),
				() -> assertEquals(desired, result.desiredBehavior()));
	}

	@ParameterizedTest
	@CsvSource({"RETURNING", "DANCING"})
	@DisplayName("dance из временного состояния отвергается: вложенного стека состояний нет")
	void danceFromTransientStateIsRejected(BehaviorState from) {
		Result result = BehaviorStateMachine.apply(from, DesiredBehavior.IDLE, Event.COMMAND_DANCE);

		Rejected rejected = assertInstanceOf(Rejected.class, result);
		assertEquals(ErrorCode.INVALID_STATE, rejected.code());
	}

	@ParameterizedTest
	@EnumSource(DesiredBehavior.class)
	@DisplayName("после таймера танца восстанавливается сохранённое намерение")
	void danceExpiryRestoresIntent(DesiredBehavior desired) {
		Accepted result = accepted(BehaviorState.DANCING, desired, Event.DANCE_EXPIRED);

		assertAll(
				() -> assertEquals(desired.toBehaviorState(), result.behavior()),
				() -> assertEquals(desired, result.desiredBehavior()),
				() -> assertTrue(result.cancellations().contains(Cancellation.DANCE)));
	}

	@Test
	@DisplayName("таймер танца в недопустимом состоянии не двигает автомат")
	void danceExpiryOutsideDancingIsRejected() {
		Result result = BehaviorStateMachine.apply(BehaviorState.IDLE, DesiredBehavior.IDLE, Event.DANCE_EXPIRED);
		assertEquals(ErrorCode.INVALID_STATE, assertInstanceOf(Rejected.class, result).code());
	}

	@ParameterizedTest
	@EnumSource(DesiredBehavior.class)
	@DisplayName("прибытие и таймаут движения одинаково возвращают к намерению")
	void arrivalAndTimeoutBothRestoreIntent(DesiredBehavior desired) {
		Accepted arrival = accepted(BehaviorState.RETURNING, desired, Event.ARRIVAL_REACHED);
		Accepted timeout = accepted(BehaviorState.RETURNING, desired, Event.MOVEMENT_TIMEOUT);

		assertAll(
				() -> assertEquals(desired.toBehaviorState(), arrival.behavior()),
				() -> assertEquals(desired.toBehaviorState(), timeout.behavior()),
				() -> assertEquals(desired, arrival.desiredBehavior()),
				() -> assertEquals(desired, timeout.desiredBehavior()));
	}

	@Test
	@DisplayName("scream не меняет состояние")
	void screamDoesNotChangeState() {
		Accepted result = accepted(BehaviorState.DANCING, DesiredBehavior.SITTING, Event.COMMAND_SCREAM);

		assertAll(
				() -> assertEquals(BehaviorState.DANCING, result.behavior()),
				() -> assertEquals(DesiredBehavior.SITTING, result.desiredBehavior()),
				() -> assertTrue(result.cancellations().isEmpty()));
	}

	@Test
	@DisplayName("выход стримера останавливает следование, но намерение FOLLOWING сохраняется")
	void targetLossStopsFollowingButKeepsIntent() {
		Accepted result = accepted(BehaviorState.FOLLOWING, DesiredBehavior.FOLLOWING, Event.TARGET_LOST);

		assertAll(
				() -> assertEquals(BehaviorState.IDLE, result.behavior()),
				() -> assertEquals(DesiredBehavior.FOLLOWING, result.desiredBehavior()),
				() -> assertTrue(result.cancellations().contains(Cancellation.MOVEMENT)));
	}

	@Test
	@DisplayName("танец переживает выход стримера и завершается на месте")
	void targetLossLetsDanceFinish() {
		Accepted result = accepted(BehaviorState.DANCING, DesiredBehavior.IDLE, Event.TARGET_LOST);
		assertEquals(BehaviorState.DANCING, result.behavior());
	}

	@Test
	@DisplayName("выход стримера отменяет незавершённый come")
	void targetLossCancelsPendingCome() {
		Accepted result = accepted(BehaviorState.RETURNING, DesiredBehavior.SITTING, Event.TARGET_LOST);

		assertAll(
				() -> assertEquals(BehaviorState.SITTING, result.behavior()),
				() -> assertTrue(result.cancellations().contains(Cancellation.MOVEMENT)));
	}

	@ParameterizedTest
	@EnumSource(BehaviorState.class)
	@DisplayName("выгрузка, смерть и удаление отменяют все временные действия")
	void deactivationCancelsTransientWork(BehaviorState from) {
		Accepted result = accepted(from, DesiredBehavior.FOLLOWING, Event.DEACTIVATED);

		assertAll(
				() -> assertFalse(result.behavior().isTransient()),
				() -> assertEquals(BehaviorState.FOLLOWING, result.behavior()),
				() -> assertTrue(result.cancellations().containsAll(java.util.Set.of(
						Cancellation.MOVEMENT, Cancellation.DANCE, Cancellation.PENDING_LAZY_ACTION))));
	}

	@Test
	@DisplayName("создание и respawn дают IDLE с намерением IDLE")
	void creationResetsToIdle() {
		Accepted result = accepted(BehaviorState.FOLLOWING, DesiredBehavior.FOLLOWING, Event.CREATED_OR_RESPAWNED);

		assertAll(
				() -> assertEquals(BehaviorState.IDLE, result.behavior()),
				() -> assertEquals(DesiredBehavior.IDLE, result.desiredBehavior()));
	}

	@Test
	@DisplayName("временные состояния никогда не становятся намерением")
	void transientStatesAreNeverIntents() {
		assertAll(
				() -> assertTrue(BehaviorState.RETURNING.isTransient()),
				() -> assertTrue(BehaviorState.DANCING.isTransient()),
				() -> org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
						() -> DesiredBehavior.fromStable(BehaviorState.DANCING)),
				() -> org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
						() -> DesiredBehavior.fromStable(BehaviorState.RETURNING)));
	}
}
