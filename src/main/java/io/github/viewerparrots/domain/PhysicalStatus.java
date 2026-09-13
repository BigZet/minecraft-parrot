package io.github.viewerparrots.domain;

/**
 * Сохраняемый статус физического экземпляра (раздел 4.1 ТЗ).
 * {@code PRESENT} не гарантирует, что сущность сейчас загружена.
 */
public enum PhysicalStatus {
	PRESENT,
	DEAD,
	MISSING
}
