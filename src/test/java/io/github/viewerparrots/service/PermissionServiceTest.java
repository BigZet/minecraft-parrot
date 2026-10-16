package io.github.viewerparrots.service;

import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.api.ParrotAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Раздел 12 ТЗ и AC-17: неоператор не управляет, control-оператор не выполняет admin-команды. */
class PermissionServiceTest {

	private static final PermissionService DEFAULTS = new PermissionService(2, 3);

	private static ActorContext playerAt(int level) {
		return ActorContext.player(UUID.randomUUID(), level);
	}

	@ParameterizedTest
	@EnumSource(ParrotAction.class)
	@DisplayName("уровень 0 не имеет доступа ни к чему")
	void levelZeroHasNoAccess(ParrotAction action) {
		assertFalse(DEFAULTS.isAllowed(playerAt(0), action));
	}

	@ParameterizedTest
	@EnumSource(value = ParrotAction.class, names = {"COME", "FOLLOW", "FREE", "FREEFOLLOW", "VIEW", "DEVIEW", "ARM", "DEARM", "SIT", "DANCE", "SCREAM", "ROULETTE", "INFO", "LIST", "HELP", "STREAMER"})
	@DisplayName("control-уровень выполняет игровые команды")
	void controlLevelRunsGameplay(ParrotAction action) {
		assertTrue(DEFAULTS.isAllowed(playerAt(2), action));
	}

	@ParameterizedTest
	@EnumSource(value = ParrotAction.class, names = {"CREATE", "REMOVE", "RESPAWN", "RENAME", "TRAIT", "SETSTREAMER", "RECONCILE", "SAVE", "RELOAD", "STATUS"})
	@DisplayName("control-уровень не выполняет admin-команды")
	void controlLevelCannotRunAdmin(ParrotAction action) {
		assertAll(
				() -> assertFalse(DEFAULTS.isAllowed(playerAt(2), action)),
				() -> assertTrue(DEFAULTS.isAllowed(playerAt(3), action)));
	}

	@Test
	@DisplayName("уровень 1 ниже control и потому не управляет")
	void belowControlLevelIsDenied() {
		assertFalse(DEFAULTS.isAllowed(playerAt(1), ParrotAction.COME));
	}

	@Test
	@DisplayName("уровень 4 выполняет всё")
	void topLevelRunsEverything() {
		for (ParrotAction action : ParrotAction.values()) {
			assertTrue(DEFAULTS.isAllowed(playerAt(4), action), action.name());
		}
	}

	@Test
	@DisplayName("консоль подчиняется тем же уровням, отдельного обхода нет")
	void consoleUsesTheSameLevels() {
		assertAll(
				() -> assertTrue(DEFAULTS.isAllowed(ActorContext.console(4), ParrotAction.CREATE)),
				() -> assertFalse(DEFAULTS.isAllowed(ActorContext.console(2), ParrotAction.CREATE)));
	}

	@Test
	@DisplayName("внутренний Java API проверяется так же, как игроки")
	void internalApiIsNotTrustedByDefault() {
		ActorContext internal = new ActorContext(
				ActorContext.SourceType.INTERNAL_API, null, 2, "mod:example");

		assertAll(
				() -> assertTrue(DEFAULTS.isAllowed(internal, ParrotAction.DANCE)),
				() -> assertFalse(DEFAULTS.isAllowed(internal, ParrotAction.CREATE)));
	}

	@Test
	@DisplayName("adminLevel ниже controlLevel — ошибка конфигурации")
	void adminBelowControlIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> new PermissionService(3, 2));
	}

	@Test
	@DisplayName("уровни вне диапазона 0..4 отвергаются")
	void levelsOutsideRangeAreRejected() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class, () -> new PermissionService(-1, 3)),
				() -> assertThrows(IllegalArgumentException.class, () -> new PermissionService(2, 5)),
				() -> assertThrows(IllegalArgumentException.class, () -> playerAt(5)),
				() -> assertThrows(IllegalArgumentException.class, () -> playerAt(-1)));
	}

	@Test
	@DisplayName("у источника PLAYER обязателен UUID отправителя")
	void playerSourceRequiresUuid() {
		assertThrows(IllegalArgumentException.class,
				() -> new ActorContext(ActorContext.SourceType.PLAYER, null, 2, "somebody"));
	}

	@Test
	@DisplayName("требуемый уровень берётся из настроек, а не зашит в действие")
	void requiredLevelFollowsConfiguration() {
		PermissionService strict = new PermissionService(4, 4);

		assertAll(
				() -> assertEquals(4, strict.requiredLevel(ParrotAction.COME)),
				() -> assertEquals(4, strict.requiredLevel(ParrotAction.CREATE)),
				() -> assertFalse(strict.isAllowed(playerAt(3), ParrotAction.COME)));
	}
}
