package io.github.viewerparrots.ratelimit;

import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.ratelimit.GlobalRateLimiter.Decision;
import io.github.viewerparrots.time.TestGameClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Глобальные бюджеты раздела 7 ТЗ: 20 действий и 4 звука за 20 тиков, диагностика отдельно. */
class GlobalRateLimiterTest {

	private TestGameClock clock;
	private GlobalRateLimiter limiter;

	@BeforeEach
	void setUp() {
		this.clock = new TestGameClock();
		this.limiter = new GlobalRateLimiter(this.clock, 20, 4);
	}

	@Test
	@DisplayName("бюджет действий исчерпывается на 21-м действии в том же тике")
	void actionBudgetIsExhaustedAfterCapacity() {
		for (int i = 0; i < 20; i++) {
			assertTrue(this.limiter.tryAcquire(ParrotAction.SIT).allowed(), "действие " + i);
		}

		Decision denied = this.limiter.tryAcquire(ParrotAction.SIT);
		assertAll(
				() -> assertFalse(denied.allowed()),
				() -> assertTrue(denied.retryAfterTicks() > 0, "отказ обязан нести retryAfterTicks"));
	}

	@Test
	@DisplayName("бюджет пополняется непрерывно, а не пачкой в конце окна")
	void budgetRefillsContinuously() {
		for (int i = 0; i < 20; i++) {
			this.limiter.tryAcquire(ParrotAction.SIT);
		}
		assertFalse(this.limiter.tryAcquire(ParrotAction.SIT).allowed());

		// 20 действий за 20 тиков — ровно один токен за тик.
		this.clock.advanceTicks(1);
		assertTrue(this.limiter.tryAcquire(ParrotAction.SIT).allowed());
	}

	@Test
	@DisplayName("звук расходует и общий бюджет, и отдельный звуковой")
	void soundConsumesBothBudgets() {
		for (int i = 0; i < 4; i++) {
			assertTrue(this.limiter.tryAcquire(ParrotAction.SCREAM).allowed(), "звук " + i);
		}

		// Звуковой бюджет исчерпан, хотя общий ещё нет.
		assertFalse(this.limiter.tryAcquire(ParrotAction.SCREAM).allowed());
		assertTrue(this.limiter.tryAcquire(ParrotAction.SIT).allowed(), "обычное действие должно оставаться доступным");
	}

	@Test
	@DisplayName("отказ по звуку не списывает общий бюджет действий")
	void deniedSoundDoesNotSpendActionBudget() {
		// Четыре успешных звука списывают 4 из 20 общих действий.
		for (int i = 0; i < 4; i++) {
			assertTrue(this.limiter.tryAcquire(ParrotAction.SCREAM).allowed());
		}
		// Десять отказов подряд: звуковой бюджет пуст, общий трогать нельзя.
		for (int i = 0; i < 10; i++) {
			assertFalse(this.limiter.tryAcquire(ParrotAction.SCREAM).allowed());
		}

		// Остаться должно ровно 20 − 4 = 16 обычных действий.
		int remaining = 0;
		while (this.limiter.tryAcquire(ParrotAction.SIT).allowed()) {
			remaining++;
		}
		assertEquals(16, remaining);
	}

	@Test
	@DisplayName("диагностика ограничена отдельно и по каждому источнику")
	void diagnosticsAreLimitedPerSource() {
		for (int i = 0; i < 5; i++) {
			assertTrue(this.limiter.tryAcquireDiagnostic("player-a").allowed(), "запрос " + i);
		}
		assertFalse(this.limiter.tryAcquireDiagnostic("player-a").allowed());

		// Второй источник имеет собственный бюджет.
		assertTrue(this.limiter.tryAcquireDiagnostic("player-b").allowed());
	}

	@Test
	@DisplayName("исчерпанный бюджет действий не блокирует status")
	void exhaustedActionsDoNotBlockDiagnostics() {
		for (int i = 0; i < 20; i++) {
			this.limiter.tryAcquire(ParrotAction.SIT);
		}
		assertFalse(this.limiter.tryAcquire(ParrotAction.SIT).allowed());
		assertTrue(this.limiter.tryAcquireDiagnostic("console").allowed());
	}

	@Test
	@DisplayName("изменяющие и диагностические действия общий бюджет не расходуют")
	void mutationsAndDiagnosticsBypassActionBudget() {
		for (int i = 0; i < 100; i++) {
			assertTrue(this.limiter.tryAcquire(ParrotAction.CREATE).allowed());
			assertTrue(this.limiter.tryAcquire(ParrotAction.INFO).allowed());
		}
		assertTrue(this.limiter.tryAcquire(ParrotAction.SIT).allowed());
	}

	@Test
	@DisplayName("бюджет не накапливается выше вместимости за долгий простой")
	void budgetDoesNotAccumulateBeyondCapacity() {
		this.clock.advanceTicks(10_000);

		for (int i = 0; i < 20; i++) {
			assertTrue(this.limiter.tryAcquire(ParrotAction.SIT).allowed(), "действие " + i);
		}
		assertFalse(this.limiter.tryAcquire(ParrotAction.SIT).allowed());
	}

	@Test
	@DisplayName("автономный звук берётся из того же звукового бюджета")
	void idleSoundSharesSoundBudget() {
		for (int i = 0; i < 4; i++) {
			assertTrue(this.limiter.tryAcquireIdleSound());
		}
		assertFalse(this.limiter.tryAcquireIdleSound());
	}

}
