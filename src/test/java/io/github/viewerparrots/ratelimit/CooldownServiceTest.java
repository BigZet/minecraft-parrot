package io.github.viewerparrots.ratelimit;

import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.time.TestGameClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Границы cooldown из раздела 7 ТЗ, требование раздела 15.1: tick = deadline − 1 и tick = deadline. */
class CooldownServiceTest {

	private static final ViewerId VIEWER = ViewerId.of("viewer1");
	private static final ViewerId OTHER = ViewerId.of("viewer2");
	private static final long SCREAM_COOLDOWN = 300L;

	private TestGameClock clock;
	private CooldownService cooldowns;

	@BeforeEach
	void setUp() {
		this.clock = new TestGameClock();
		this.cooldowns = new CooldownService(this.clock);
	}

	@Test
	@DisplayName("на тике deadline − 1 действие ещё запрещено, на deadline уже разрешено")
	void boundaryIsExclusiveOnDeadline() {
		this.cooldowns.consume(VIEWER, ParrotAction.SCREAM, SCREAM_COOLDOWN);

		this.clock.advanceTicks(SCREAM_COOLDOWN - 1);
		assertAll(
				() -> assertTrue(this.cooldowns.isOnCooldown(VIEWER, ParrotAction.SCREAM)),
				() -> assertEquals(1L, this.cooldowns.remainingTicks(VIEWER, ParrotAction.SCREAM)));

		this.clock.advanceTicks(1);
		assertAll(
				() -> assertFalse(this.cooldowns.isOnCooldown(VIEWER, ParrotAction.SCREAM)),
				() -> assertEquals(0L, this.cooldowns.remainingTicks(VIEWER, ParrotAction.SCREAM)));
	}

	@Test
	@DisplayName("cooldown разделён по паре viewerId + action")
	void cooldownIsPerViewerAndAction() {
		this.cooldowns.consume(VIEWER, ParrotAction.SCREAM, SCREAM_COOLDOWN);

		assertAll(
				() -> assertTrue(this.cooldowns.isOnCooldown(VIEWER, ParrotAction.SCREAM)),
				() -> assertFalse(this.cooldowns.isOnCooldown(VIEWER, ParrotAction.DANCE)),
				() -> assertFalse(this.cooldowns.isOnCooldown(OTHER, ParrotAction.SCREAM)));
	}

	@Test
	@DisplayName("exp разрешён раз в 30 секунд отдельно на зрителя")
	void experienceBottleWaitsThirtySecondsWithoutBlockingOtherViewers() {
		this.cooldowns.consume(VIEWER, ParrotAction.EXP, ParrotAction.EXP_COOLDOWN_TICKS);
		assertFalse(this.cooldowns.isOnCooldown(OTHER, ParrotAction.EXP));
		assertFalse(this.cooldowns.isOnCooldown(VIEWER, ParrotAction.LOVE));
		this.clock.advanceTicks(599);
		assertTrue(this.cooldowns.isOnCooldown(VIEWER, ParrotAction.EXP));
		assertEquals(1L, this.cooldowns.remainingTicks(VIEWER, ParrotAction.EXP));
		this.clock.advanceTicks(1);
		assertFalse(this.cooldowns.isOnCooldown(VIEWER, ParrotAction.EXP));
	}

	@Test
	@DisplayName("нулевой cooldown ничего не запрещает: повторный sit — успешный no-op")
	void zeroCooldownNeverBlocks() {
		this.cooldowns.consume(VIEWER, ParrotAction.SIT, 0L);
		assertFalse(this.cooldowns.isOnCooldown(VIEWER, ParrotAction.SIT));
	}

	@Test
	@DisplayName("отвергнутое действие cooldown не расходует")
	void rejectedActionDoesNotConsume() {
		// Валидация не прошла — consume не вызывается вовсе.
		assertAll(
				() -> assertFalse(this.cooldowns.isOnCooldown(VIEWER, ParrotAction.COME)),
				() -> assertEquals(0L, this.cooldowns.remainingTicks(VIEWER, ParrotAction.COME)));
	}

	@Test
	@DisplayName("повторное списание продлевает срок от текущего тика")
	void repeatedConsumeExtendsFromNow() {
		this.cooldowns.consume(VIEWER, ParrotAction.COME, 100L);
		this.clock.advanceTicks(50);
		this.cooldowns.consume(VIEWER, ParrotAction.COME, 100L);

		assertEquals(100L, this.cooldowns.remainingTicks(VIEWER, ParrotAction.COME));
	}

	@Test
	@DisplayName("удаление регистрации снимает её cooldowns и не трогает чужие")
	void clearRemovesOnlyOwnCooldowns() {
		this.cooldowns.consume(VIEWER, ParrotAction.SCREAM, SCREAM_COOLDOWN);
		this.cooldowns.consume(OTHER, ParrotAction.SCREAM, SCREAM_COOLDOWN);

		this.cooldowns.clear(VIEWER);

		assertAll(
				() -> assertFalse(this.cooldowns.isOnCooldown(VIEWER, ParrotAction.SCREAM)),
				() -> assertTrue(this.cooldowns.isOnCooldown(OTHER, ParrotAction.SCREAM)));
	}

	@Test
	@DisplayName("снимок для info показывает только незавершённые cooldowns")
	void snapshotSkipsExpired() {
		this.cooldowns.consume(VIEWER, ParrotAction.SCREAM, 300L);
		this.cooldowns.consume(VIEWER, ParrotAction.DANCE, 200L);

		this.clock.advanceTicks(200);

		assertEquals(java.util.Map.of(ParrotAction.SCREAM, 100L), this.cooldowns.remainingFor(VIEWER));
	}

	@Test
	@DisplayName("отрицательная длительность отвергается, а не молча трактуется как ноль")
	void negativeCooldownIsRejected() {
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> this.cooldowns.consume(VIEWER, ParrotAction.COME, -1L));
	}
}
