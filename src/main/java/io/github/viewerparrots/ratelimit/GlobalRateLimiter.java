package io.github.viewerparrots.ratelimit;

import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.time.GameClock;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Глобальные лимиты нагрузки (раздел 7 ТЗ):
 * <ul>
 *   <li>игровые действия — {@code actionsPer20Ticks}, capacity равна ему же;</li>
 *   <li>звуки — {@code soundsPer20Ticks}, отдельный бюджет поверх общего;</li>
 *   <li>диагностика — 5 запросов на источник за 20 тиков, отдельно, чтобы
 *       {@code status} оставался доступен под нагрузкой.</li>
 * </ul>
 *
 * <p>Звук расходует оба бюджета, поэтому проверяются они вместе и атомарно: нельзя списать
 * общий бюджет и затем отказать по звуковому.
 */
public final class GlobalRateLimiter {
	private static final int WINDOW_TICKS = 20;
	private static final int DIAGNOSTIC_PER_WINDOW = 5;

	private final GameClock clock;
	private final TokenBucket actions;
	private final TokenBucket sounds;
	private final int diagnosticPerWindow;
	private final Map<String, TokenBucket> diagnosticsBySource = new HashMap<>();

	public GlobalRateLimiter(GameClock clock, int actionsPer20Ticks, int soundsPer20Ticks) {
		this(clock, actionsPer20Ticks, soundsPer20Ticks, DIAGNOSTIC_PER_WINDOW);
	}

	public GlobalRateLimiter(GameClock clock, int actionsPer20Ticks, int soundsPer20Ticks,
			int diagnosticPerWindow) {
		this.clock = Objects.requireNonNull(clock, "clock");
		long startTick = clock.currentTick();
		this.actions = new TokenBucket(actionsPer20Ticks, actionsPer20Ticks, WINDOW_TICKS, startTick);
		this.sounds = new TokenBucket(soundsPer20Ticks, soundsPer20Ticks, WINDOW_TICKS, startTick);
		this.diagnosticPerWindow = diagnosticPerWindow;
	}

	/** Исход проверки: либо разрешено, либо отказ с {@code retryAfterTicks}. */
	public record Decision(boolean allowed, long retryAfterTicks) {
		public static Decision allow() {
			return new Decision(true, 0L);
		}

		public static Decision deny(long retryAfterTicks) {
			return new Decision(false, retryAfterTicks);
		}
	}

	/**
	 * Проверяет и списывает бюджеты для игрового действия. Диагностические и изменяющие
	 * действия сюда не попадают: у них свои ограничители.
	 */
	public Decision tryAcquire(ParrotAction action) {
		Objects.requireNonNull(action, "action");
		if (!action.countsTowardActionBudget()) {
			return Decision.allow();
		}

		long tick = this.clock.currentTick();

		if (action.countsTowardSoundBudget()) {
			// Оба бюджета проверяются до списания любого из них.
			long actionsRetry = this.actions.retryAfterTicks(tick);
			long soundsRetry = this.sounds.retryAfterTicks(tick);
			if (actionsRetry > 0 || soundsRetry > 0) {
				return Decision.deny(Math.max(actionsRetry, soundsRetry));
			}
			this.actions.tryConsume(tick);
			this.sounds.tryConsume(tick);
			return Decision.allow();
		}

		if (this.actions.tryConsume(tick)) {
			return Decision.allow();
		}
		return Decision.deny(this.actions.retryAfterTicks(tick));
	}

	/**
	 * Отдельный бюджет автономных звуков (idle-имитации). При исчерпании звук пропускается
	 * без очереди, в отличие от команды {@code scream}, которая получает отказ с retryAfter.
	 */
	public boolean tryAcquireIdleSound() {
		return this.sounds.tryConsume(this.clock.currentTick());
	}

	/** @param sourceKey стабильный идентификатор отправителя: UUID игрока, консоль, RCON */
	public Decision tryAcquireDiagnostic(String sourceKey) {
		Objects.requireNonNull(sourceKey, "sourceKey");
		long tick = this.clock.currentTick();
		TokenBucket bucket = this.diagnosticsBySource.computeIfAbsent(sourceKey,
				key -> new TokenBucket(this.diagnosticPerWindow, this.diagnosticPerWindow, WINDOW_TICKS, tick));
		if (bucket.tryConsume(tick)) {
			return Decision.allow();
		}
		return Decision.deny(bucket.retryAfterTicks(tick));
	}
}
