package io.github.viewerparrots.api;

/**
 * Действия внутреннего API, по одному на каждую команду раздела 6 ТЗ.
 *
 * <p>Здесь же закреплены три независимых свойства, которые иначе разъезжаются между
 * разделами 7 и 12: требуемый уровень прав, участие в общем лимите игровых действий
 * и участие в отдельном лимите звуков.
 */
public enum ParrotAction {
	// --- control level (раздел 12) ---
	HELP(Access.CONTROL, Category.DIAGNOSTIC),
	STREAMER(Access.CONTROL, Category.DIAGNOSTIC),
	INFO(Access.CONTROL, Category.DIAGNOSTIC),
	LIST(Access.CONTROL, Category.DIAGNOSTIC),
	COME(Access.CONTROL, Category.GAME),
	FOLLOW(Access.CONTROL, Category.GAME),
	SIT(Access.CONTROL, Category.GAME),
	DANCE(Access.CONTROL, Category.GAME),
	SCREAM(Access.CONTROL, Category.SOUND),

	// --- admin level (раздел 12) ---
	CREATE(Access.ADMIN, Category.MUTATION),
	REMOVE(Access.ADMIN, Category.MUTATION),
	RESPAWN(Access.ADMIN, Category.MUTATION),
	RENAME(Access.ADMIN, Category.MUTATION),
	TRAIT(Access.ADMIN, Category.MUTATION),
	SETSTREAMER(Access.ADMIN, Category.MUTATION),
	RECONCILE(Access.ADMIN, Category.MUTATION),
	SAVE(Access.ADMIN, Category.MUTATION),
	RELOAD(Access.ADMIN, Category.MUTATION),
	STATUS(Access.ADMIN, Category.DIAGNOSTIC);

	public enum Access {
		CONTROL,
		ADMIN
	}

	public enum Category {
		/** Игровое действие: расходует общий бюджет actionsPer20Ticks. */
		GAME,
		/** Звук: расходует и общий бюджет действий, и отдельный бюджет звуков. */
		SOUND,
		/** Изменяющая операция: ограничена очередью и правилом одной операции на viewer. */
		MUTATION,
		/** Диагностика: отдельный лимит, чтобы status оставался доступен под нагрузкой. */
		DIAGNOSTIC
	}

	private final Access access;
	private final Category category;

	ParrotAction(Access access, Category category) {
		this.access = access;
		this.category = category;
	}

	public Access access() {
		return this.access;
	}

	public Category category() {
		return this.category;
	}

	public boolean requiresAdmin() {
		return this.access == Access.ADMIN;
	}

	/** {@code sit} тоже учитывается в общем лимите (раздел 7 ТЗ). */
	public boolean countsTowardActionBudget() {
		return this.category == Category.GAME || this.category == Category.SOUND;
	}

	public boolean countsTowardSoundBudget() {
		return this.category == Category.SOUND;
	}

	/** Действия, у которых есть собственный cooldown по viewerId + action (раздел 7 ТЗ). */
	public boolean hasCooldown() {
		return switch (this) {
			case COME, FOLLOW, SIT, DANCE, SCREAM, RESPAWN -> true;
			default -> false;
		};
	}
}
