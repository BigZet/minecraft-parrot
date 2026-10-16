package io.github.viewerparrots.behavior;

import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.behavior.LifecycleStateMachine.Event;
import io.github.viewerparrots.domain.LifecycleState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Табличные переходы жизненного цикла из раздела 5.1 ТЗ. */
class LifecycleStateMachineTest {

	@ParameterizedTest
	@CsvSource({
			"UNRESOLVED,ENTITY_MATCHED,LOADED",
			"UNLOADED,ENTITY_MATCHED,LOADED",
			"MISSING,ENTITY_MATCHED,LOADED",
			"LOADED,CHUNK_UNLOADED,UNLOADED",
			"UNRESOLVED,RECOVERY_EXHAUSTED,MISSING",
			"UNLOADED,RECOVERY_EXHAUSTED,MISSING",
			"LOADED,DEATH_CONFIRMED,DEAD",
			"UNLOADED,DEATH_CONFIRMED,DEAD",
			"MISSING,RESPAWNED,UNRESOLVED",
			"DEAD,RESPAWNED,UNRESOLVED",
			"LOADED,REMOVED,REMOVED",
			"DEAD,REMOVED,REMOVED",
	})
	@DisplayName("определённые переходы дают ожидаемое состояние")
	void definedTransitions(LifecycleState from, Event event, LifecycleState expected) {
		assertEquals(Optional.of(expected), LifecycleStateMachine.transition(from, event));
	}

	@ParameterizedTest
	@EnumSource(Event.class)
	@DisplayName("REMOVED терминально: удалённая incarnation не оживает ничем")
	void removedIsTerminal(Event event) {
		assertTrue(LifecycleStateMachine.transition(LifecycleState.REMOVED, event).isEmpty());
	}

	@Test
	@DisplayName("выгрузка сама по себе не доказывает смерть")
	void unloadIsNotDeath() {
		assertAll(
				() -> assertEquals(Optional.of(LifecycleState.UNLOADED),
						LifecycleStateMachine.transition(LifecycleState.LOADED, Event.CHUNK_UNLOADED)),
				// Из UNLOADED смерть возможна только как отдельное подтверждённое событие.
				() -> assertEquals(Optional.of(LifecycleState.DEAD),
						LifecycleStateMachine.transition(LifecycleState.UNLOADED, Event.DEATH_CONFIRMED)));
	}

	@Test
	@DisplayName("мёртвая запись не привязывается к загрузившейся сущности")
	void deadDoesNotBind() {
		assertTrue(LifecycleStateMachine.transition(LifecycleState.DEAD, Event.ENTITY_MATCHED).isEmpty());
	}

	@Test
	@DisplayName("поздняя загрузка совпадающей сущности допускает bind даже из MISSING")
	void lateLoadFromMissingBinds() {
		assertEquals(Optional.of(LifecycleState.LOADED),
				LifecycleStateMachine.transition(LifecycleState.MISSING, Event.ENTITY_MATCHED));
	}

	@Test
	@DisplayName("respawn допустим только из MISSING и DEAD")
	void respawnOnlyFromMissingOrDead() {
		assertAll(
				() -> assertTrue(LifecycleStateMachine.transition(LifecycleState.LOADED, Event.RESPAWNED).isEmpty()),
				() -> assertTrue(LifecycleStateMachine.transition(LifecycleState.UNLOADED, Event.RESPAWNED).isEmpty()),
				() -> assertTrue(LifecycleStateMachine.transition(LifecycleState.UNRESOLVED, Event.RESPAWNED).isEmpty()));
	}

	@Test
	@DisplayName("remove разрешён в любом нетерминальном состоянии")
	void removeAllowedEverywhere() {
		for (LifecycleState state : LifecycleState.values()) {
			if (state.isTerminal()) {
				continue;
			}
			assertEquals(Optional.of(LifecycleState.REMOVED),
					LifecycleStateMachine.transition(state, Event.REMOVED),
					"remove должен работать из " + state);
		}
	}

	@Test
	@DisplayName("MISSING и DEAD переживают рестарт как есть, а PRESENT становится UNRESOLVED")
	void durableStatesSurviveRestart() {
		assertAll(
				() -> assertTrue(LifecycleState.DEAD.isDurableAcrossRestart()),
				() -> assertTrue(LifecycleState.MISSING.isDurableAcrossRestart()),
				() -> assertFalse(LifecycleState.LOADED.isDurableAcrossRestart()),
				() -> assertFalse(LifecycleState.UNLOADED.isDurableAcrossRestart()));
	}

	@Test
	@DisplayName("игровые команды доступны только в LOADED")
	void gameplayActionsRequireLoaded() {
		for (ParrotAction action : new ParrotAction[]{
				ParrotAction.FOLLOW, ParrotAction.SIT, ParrotAction.DANCE, ParrotAction.HAT, ParrotAction.DEHAT, ParrotAction.COLOR, ParrotAction.NAME, ParrotAction.EMOTE,
				ParrotAction.FREE, ParrotAction.FREEFOLLOW, ParrotAction.VIEW, ParrotAction.DEVIEW,
				ParrotAction.ARM, ParrotAction.DEARM,
				ParrotAction.SCREAM, ParrotAction.LOVE, ParrotAction.EXP, ParrotAction.ROULETTE}) {
			assertTrue(LifecycleStateMachine.allows(LifecycleState.LOADED, action), action + " в LOADED");
			for (LifecycleState state : LifecycleState.values()) {
				if (state == LifecycleState.LOADED) {
					continue;
				}
				assertFalse(LifecycleStateMachine.allows(state, action), action + " не должен работать в " + state);
			}
		}
	}

	@Test
	@DisplayName("come дополнительно разрешён в UNLOADED как безопасная попытка recovery")
	void comeIsAllowedWhileUnloaded() {
		assertAll(
				() -> assertTrue(LifecycleStateMachine.allows(LifecycleState.UNLOADED, ParrotAction.COME)),
				() -> assertFalse(LifecycleStateMachine.allows(LifecycleState.MISSING, ParrotAction.COME)),
				() -> assertFalse(LifecycleStateMachine.allows(LifecycleState.DEAD, ParrotAction.COME)));
	}

	@Test
	@DisplayName("в REMOVED остаётся только диагностика")
	void removedExposesOnlyDiagnostics() {
		assertEquals(java.util.Set.of(ParrotAction.INFO),
				LifecycleStateMachine.allowedActions(LifecycleState.REMOVED));
	}

	@Test
	@DisplayName("info доступен во всех состояниях")
	void infoIsAlwaysAvailable() {
		for (LifecycleState state : LifecycleState.values()) {
			assertTrue(LifecycleStateMachine.allows(state, ParrotAction.INFO), "info в " + state);
		}
	}
}
