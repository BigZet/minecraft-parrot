package io.github.viewerparrots.ratelimit;

/**
 * Token bucket на игровых тиках (раздел 7 ТЗ).
 *
 * <p>Пополнение непрерывное, а не пачкой в конце окна: {@code refillPerWindow} токенов
 * равномерно распределены по {@code windowTicks}. Иначе всплеск запросов на границе окна
 * получал бы двойной бюджет.
 *
 * <p>Токены хранятся дробными, чтобы бюджет вроде «4 звука за 20 тиков» не округлялся
 * до нуля на каждом отдельном тике.
 */
public final class TokenBucket {
	private final double capacity;
	private final double refillPerTick;

	private double tokens;
	private long lastTick;

	public TokenBucket(int capacity, int refillPerWindow, int windowTicks, long startTick) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity должен быть > 0, получено " + capacity);
		}
		if (refillPerWindow <= 0) {
			throw new IllegalArgumentException("refillPerWindow должен быть > 0, получено " + refillPerWindow);
		}
		if (windowTicks <= 0) {
			throw new IllegalArgumentException("windowTicks должен быть > 0, получено " + windowTicks);
		}
		this.capacity = capacity;
		this.refillPerTick = (double) refillPerWindow / windowTicks;
		this.tokens = capacity;
		this.lastTick = startTick;
	}

	/**
	 * Пытается списать один токен.
	 *
	 * @return {@code true} если бюджет позволил действие
	 */
	public boolean tryConsume(long currentTick) {
		refill(currentTick);
		if (this.tokens >= 1.0) {
			this.tokens -= 1.0;
			return true;
		}
		return false;
	}

	/**
	 * @return через сколько тиков накопится следующий токен; 0 если он уже есть
	 */
	public long retryAfterTicks(long currentTick) {
		refill(currentTick);
		if (this.tokens >= 1.0) {
			return 0L;
		}
		return (long) Math.ceil((1.0 - this.tokens) / this.refillPerTick);
	}

	public double availableTokens(long currentTick) {
		refill(currentTick);
		return this.tokens;
	}

	private void refill(long currentTick) {
		if (currentTick <= this.lastTick) {
			// Время назад не идёт; счётчик тиков сервера монотонный.
			return;
		}
		long elapsed = currentTick - this.lastTick;
		this.tokens = Math.min(this.capacity, this.tokens + elapsed * this.refillPerTick);
		this.lastTick = currentTick;
	}
}
