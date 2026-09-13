package io.github.viewerparrots.domain;

/**
 * Характер попугая. HERO и THIEF в MVP хранятся и отображаются, но не дают поведения
 * и не выдаются случайно (разделы 1.3 и 10 ТЗ).
 */
public enum ParrotTrait {
	NORMAL(true),
	COWARD(true),
	HERO(false),
	LAZY(true),
	LOUD(true),
	THIEF(false);

	private final boolean activeInMvp;

	ParrotTrait(boolean activeInMvp) {
		this.activeInMvp = activeInMvp;
	}

	/** {@code false} — trait сохраняется и показывается, но эффекта в MVP не имеет. */
	public boolean isActiveInMvp() {
		return this.activeInMvp;
	}
}
