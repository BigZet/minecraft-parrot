package io.github.viewerparrots.time;

import java.time.Duration;
import java.time.Instant;

/**
 * Управляемые часы для тестов: никакого реального времени, никакого {@code Thread.sleep}.
 * Раздел 15.1 ТЗ требует проверять границы cooldown и LAZY-отсрочки детерминированно.
 */
public final class TestGameClock implements GameClock {
	private long tick;
	private Instant instant;

	public TestGameClock() {
		this(0L, Instant.parse("2026-09-13T00:00:00Z"));
	}

	public TestGameClock(long tick, Instant instant) {
		this.tick = tick;
		this.instant = instant;
	}

	@Override
	public long currentTick() {
		return this.tick;
	}

	@Override
	public Instant now() {
		return this.instant;
	}

	/** Двигает обе шкалы согласованно: 20 тиков — одна секунда. */
	public void advanceTicks(long ticks) {
		if (ticks < 0) {
			throw new IllegalArgumentException("время назад не идёт: " + ticks);
		}
		this.tick += ticks;
		this.instant = this.instant.plus(Duration.ofMillis(ticks * 50));
	}

	public void setTick(long tick) {
		this.tick = tick;
	}
}
