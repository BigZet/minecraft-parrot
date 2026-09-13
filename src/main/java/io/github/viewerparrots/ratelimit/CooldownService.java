package io.github.viewerparrots.ratelimit;

import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.time.GameClock;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Cooldowns по паре viewerId + action (раздел 7 ТЗ).
 *
 * <p>Граница намеренно строгая: действие запрещено при {@code tick < deadline} и разрешено
 * ровно при {@code tick == deadline}. Поэтому cooldown 0 (как у {@code sit}) не запрещает
 * ничего вообще.
 *
 * <p>Cooldown расходуется при принятии валидного действия, включая LAZY-отсрочку. Ошибка
 * валидации его не расходует, а последующая отмена или сбой не возвращают.
 *
 * <p>Состояние не переживает рестарт — это сознательное ограничение MVP (раздел 4.2).
 */
public final class CooldownService {
	private final GameClock clock;
	private final Map<Key, Long> deadlines = new HashMap<>();

	public CooldownService(GameClock clock) {
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	private record Key(ViewerId viewerId, ParrotAction action) {
	}

	public boolean isOnCooldown(ViewerId viewerId, ParrotAction action) {
		return remainingTicks(viewerId, action) > 0;
	}

	/**
	 * @return сколько тиков осталось до разрешения, 0 если действие доступно
	 */
	public long remainingTicks(ViewerId viewerId, ParrotAction action) {
		Long deadline = this.deadlines.get(new Key(viewerId, action));
		if (deadline == null) {
			return 0L;
		}
		long remaining = deadline - this.clock.currentTick();
		return Math.max(0L, remaining);
	}

	/**
	 * Списывает cooldown. Вызывается только после того, как действие принято.
	 *
	 * @param cooldownTicks длительность из конфига; 0 означает отсутствие cooldown
	 */
	public void consume(ViewerId viewerId, ParrotAction action, long cooldownTicks) {
		Objects.requireNonNull(viewerId, "viewerId");
		Objects.requireNonNull(action, "action");
		if (cooldownTicks < 0) {
			throw new IllegalArgumentException("cooldownTicks не может быть отрицательным: " + cooldownTicks);
		}
		if (cooldownTicks == 0) {
			return;
		}
		this.deadlines.put(new Key(viewerId, action), this.clock.currentTick() + cooldownTicks);
	}

	/** Удаление регистрации снимает все её cooldowns. */
	public void clear(ViewerId viewerId) {
		this.deadlines.keySet().removeIf(key -> key.viewerId().equals(viewerId));
	}

	public void clearAll() {
		this.deadlines.clear();
	}

	/** Снимок оставшихся cooldowns для {@code info} (раздел 6 ТЗ). */
	public Map<ParrotAction, Long> remainingFor(ViewerId viewerId) {
		Map<ParrotAction, Long> result = new java.util.EnumMap<>(ParrotAction.class);
		for (Map.Entry<Key, Long> entry : this.deadlines.entrySet()) {
			if (!entry.getKey().viewerId().equals(viewerId)) {
				continue;
			}
			long remaining = Math.max(0L, entry.getValue() - this.clock.currentTick());
			if (remaining > 0) {
				result.put(entry.getKey().action(), remaining);
			}
		}
		return result;
	}
}
