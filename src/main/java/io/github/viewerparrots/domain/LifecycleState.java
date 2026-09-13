package io.github.viewerparrots.domain;

/**
 * Жизненный цикл регистрации (раздел 5.1 ТЗ). Сознательно отделён от поведения:
 * отображаемое имя «ACTIVE» соответствует паре {@code LOADED + IDLE}.
 */
public enum LifecycleState {
	/** Реестр прочитан, состояние физической сущности ещё не установлено. */
	UNRESOLVED,
	/** Совпавшая сущность доступна; разрешены все игровые действия. */
	LOADED,
	/** Известна выгрузка либо ожидаемый чанк пока недоступен. */
	UNLOADED,
	/** Ограниченная проверка завершена, сущность не найдена. */
	MISSING,
	/** Смерть подтверждена. */
	DEAD,
	/** Терминальное состояние incarnation; остаётся только tombstone. */
	REMOVED;

	/** Терминальное состояние: дальнейшие переходы невозможны. */
	public boolean isTerminal() {
		return this == REMOVED;
	}

	/**
	 * Состояния, которые сохраняются между рестартами как есть, вместо перехода
	 * в {@link #UNRESOLVED} (раздел 5.1 ТЗ).
	 */
	public boolean isDurableAcrossRestart() {
		return this == DEAD || this == MISSING || this == REMOVED;
	}
}
