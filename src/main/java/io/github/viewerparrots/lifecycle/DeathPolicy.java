package io.github.viewerparrots.lifecycle;

import io.github.viewerparrots.config.DeathMode;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.registry.tag.DamageTypeTags;

/**
 * Политика урона и смерти (раздел 9.6 ТЗ).
 *
 * <p>В режиме {@code IMMORTAL} блокируется обычный игровой урон — до уменьшения здоровья,
 * а не после. Защита сознательно неполная: {@code /kill}, выпадение из мира и удаление
 * другими модами пройдут, и подтверждённая смерть всё равно будет записана как DEAD.
 * Обещать полную неуязвимость нельзя.
 */
public final class DeathPolicy {

	private DeathPolicy() {
	}

	/**
	 * @return {@code true} если урон разрешён
	 */
	public static boolean allowsDamage(DeathMode mode, DamageSource source) {
		if (!mode.blocksOrdinaryDamage()) {
			return true;
		}
		// Источники, которые нельзя и не нужно блокировать: они не являются
		// «обычным уроном» и их перехват сломал бы административное удаление.
		return bypassesImmortality(source);
	}

	private static boolean bypassesImmortality(DamageSource source) {
		return source.isOf(DamageTypes.GENERIC_KILL)
				|| source.isOf(DamageTypes.OUT_OF_WORLD)
				|| source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY);
	}
}
