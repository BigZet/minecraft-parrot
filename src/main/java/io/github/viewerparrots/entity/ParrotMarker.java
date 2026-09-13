package io.github.viewerparrots.entity;

import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;

import java.util.Objects;
import java.util.UUID;

/**
 * Маркер, сохраняемый в NBT собственной сущности (раздел 4.3 ТЗ).
 *
 * <p>Маркер не создаёт регистрацию: он только позволяет сверить загрузившуюся сущность
 * с реестром. UUID самой сущности хранит Minecraft, поэтому здесь его нет.
 */
public record ParrotMarker(int schemaVersion, ViewerId viewerId, UUID incarnationId, long generation) {
	public static final int CURRENT_SCHEMA_VERSION = 1;

	public ParrotMarker {
		Objects.requireNonNull(viewerId, "viewerId");
		Objects.requireNonNull(incarnationId, "incarnationId");
		if (generation < 1) {
			throw new IllegalArgumentException("generation должен быть ≥ 1, получено " + generation);
		}
	}

	public static ParrotMarker of(ViewerParrotRecord record) {
		return new ParrotMarker(CURRENT_SCHEMA_VERSION, record.viewerId(),
				record.incarnationId(), record.generation());
	}

	/**
	 * Совпадает ли маркер с записью реестра. Проверяются все три составляющие: viewerId,
	 * incarnation и generation. Совпадения одного viewerId недостаточно — отозванная
	 * generation обязана быть отвергнута (раздел 4.4 ТЗ).
	 */
	public boolean matches(ViewerParrotRecord record) {
		return this.viewerId.equals(record.viewerId())
				&& this.incarnationId.equals(record.incarnationId())
				&& this.generation == record.generation();
	}
}
