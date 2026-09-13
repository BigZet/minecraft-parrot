package io.github.viewerparrots.time;

import java.time.Instant;

/**
 * Единственный источник времени для домена и сервисов. Внедряется как зависимость,
 * чтобы cooldowns, deadlines и LAZY-отсрочки тестировались детерминированно
 * (разделы 10 и 15.1 ТЗ).
 *
 * <p>Две шкалы намеренно разделены и не выводятся друг из друга:
 * <ul>
 *   <li>{@link #currentTick()} — счётчик тиков сервера; по нему живут cooldowns и
 *       deadlines, потому что игровое время останавливается вместе с сервером;</li>
 *   <li>{@link #now()} — календарное UTC-время; по нему живут {@code createdAt} и
 *       {@code updatedAt}, которые должны оставаться осмысленными в JSON-снимке.</li>
 * </ul>
 */
public interface GameClock {
	/** Монотонный счётчик тиков сервера. */
	long currentTick();

	/** Текущее календарное время в UTC. */
	Instant now();
}
