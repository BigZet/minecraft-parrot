package io.github.viewerparrots.diagnostics;

/**
 * Ожидаемые ошибки возвращаются этим кодом вместе с локализуемым messageKey и details —
 * без исключений в бизнес-ветке (раздел 13 ТЗ).
 *
 * <p>{@code messageKey} строится как {@code viewerparrots.error.<код в нижнем регистре>},
 * чтобы локализации ru_ru и en_us не расходились с этим перечислением.
 */
public enum ErrorCode {
	PERMISSION_DENIED,
	INVALID_ARGUMENT,

	VIEWER_NOT_FOUND,
	PARROT_ALREADY_EXISTS,
	ALREADY_REMOVED,

	TARGET_NOT_CONFIGURED,
	TARGET_UNAVAILABLE,
	SPEECH_NOT_CONFIGURED,
	SPEECH_FAILED,

	PARROT_ENTITY_MISSING,
	ENTITY_UNRESOLVED,
	ENTITY_ALREADY_LOADED,

	INVALID_STATE,
	RESPAWN_DISABLED,

	/** Возвращается с retryAfterTicks. */
	COMMAND_ON_COOLDOWN,
	/** Возвращается с retryAfterTicks. */
	GLOBAL_RATE_LIMITED,

	MAX_ACTIVE_PARROTS_REACHED,
	SERVER_BUSY,
	OPERATION_PENDING,

	NO_SAFE_POSITION,
	PATH_NOT_FOUND,
	DIMENSION_UNAVAILABLE,
	RECOVERY_TIMEOUT,

	STORAGE_UNAVAILABLE,
	CONFIG_INVALID,
	CONFIG_RESTART_REQUIRED,

	/** Пользователю сообщается только commandId; stack trace уходит в лог. */
	INTERNAL_ERROR;

	private final String messageKey = "viewerparrots.error." + name().toLowerCase(java.util.Locale.ROOT);

	public String messageKey() {
		return this.messageKey;
	}

	/** Коды, к которым обязателен {@code retryAfterTicks} (раздел 13 ТЗ). */
	public boolean carriesRetryAfter() {
		return this == COMMAND_ON_COOLDOWN || this == GLOBAL_RATE_LIMITED;
	}
}
