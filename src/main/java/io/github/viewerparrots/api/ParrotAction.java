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
	FREE(Access.CONTROL, Category.GAME),
	FREEFOLLOW(Access.CONTROL, Category.GAME),
	VIEW(Access.CONTROL, Category.GAME),
	DEVIEW(Access.CONTROL, Category.GAME),
	ARM(Access.CONTROL, Category.GAME),
	DEARM(Access.CONTROL, Category.GAME),
	SIT(Access.CONTROL, Category.GAME),
	DANCE(Access.CONTROL, Category.GAME),
	SPIN(Access.CONTROL, Category.GAME),
	SCREAM(Access.CONTROL, Category.SOUND),
	SAY(Access.CONTROL, Category.SOUND),
	LOVE(Access.CONTROL, Category.GAME),
	HAT(Access.CONTROL, Category.GAME),
	DEHAT(Access.CONTROL, Category.GAME),
	COLOR(Access.CONTROL, Category.GAME),
	EXP(Access.CONTROL, Category.GAME),
	ROULETTE(Access.CONTROL, Category.GAME),

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

	public static final int EXP_COOLDOWN_TICKS = 30 * 20;
	public static final int SAY_COOLDOWN_TICKS = 2 * 20;
	public static final int SPIN_DURATION_TICKS = 3 * 20;
	public static final int SPIN_COOLDOWN_TICKS = 5 * 20;
	public static final int HAT_COOLDOWN_TICKS = 2 * 20;
	public static final int COLOR_COOLDOWN_TICKS = 5 * 20;
	public static final int SIMPLE_CONTROL_COOLDOWN_TICKS = 2 * 20;

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
			case COME, FOLLOW, FREE, FREEFOLLOW, VIEW, DEVIEW, ARM, DEARM, SIT, DANCE, SPIN, SCREAM, SAY, LOVE, HAT, DEHAT, COLOR, EXP, ROULETTE, RESPAWN -> true;
			default -> false;
		};
	}
}
