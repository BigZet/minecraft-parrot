package io.github.viewerparrots.config;

/**
 * Политика смерти (раздел 9.6 ТЗ). Ни один режим не выполняет автоматический
 * бесконечный respawn.
 */
public enum DeathMode {
	/**
	 * По умолчанию: обычный урон блокируется до уменьшения здоровья. Защита от
	 * {@code /kill}, void и других модов не гарантируется — подтверждённая смерть
	 * всё равно сохраняется как DEAD.
	 */
	IMMORTAL(true),
	/** Ванильный урон; восстановление только командой. */
	MANUAL_RESPAWN(true),
	/** Ванильный урон; смерть окончательна, respawn запрещён. */
	VANILLA_FINAL(false);

	private final boolean respawnAllowed;

	DeathMode(boolean respawnAllowed) {
		this.respawnAllowed = respawnAllowed;
	}

	/** @return можно ли выполнять {@code respawn} для записи в состоянии DEAD */
	public boolean allowsRespawnAfterDeath() {
		return this.respawnAllowed;
	}

	/** @return блокируется ли обычный игровой урон */
	public boolean blocksOrdinaryDamage() {
		return this == IMMORTAL;
	}
}
