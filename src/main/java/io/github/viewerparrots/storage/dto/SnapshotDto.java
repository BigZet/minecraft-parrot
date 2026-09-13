package io.github.viewerparrots.storage.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Дисковый формат реестра, схема v1 (раздел 8.1 ТЗ).
 *
 * <p>DTO намеренно отделены от runtime-моделей и хранят перечисления строками: неизвестная
 * константа обязана стать ошибкой отображения с путём, а не молча сменить trait или variant.
 * Это же разделение делает возможной миграцию схемы без правки домена.
 */
public record SnapshotDto(
		int schemaVersion,
		long checkpointSequence,
		UUID streamerPlayerUuid,
		Map<String, ParrotRecordDto> parrots,
		List<TombstoneDto> tombstones
) {
	public static final int CURRENT_SCHEMA_VERSION = 1;

	public SnapshotDto {
		parrots = Map.copyOf(parrots);
		tombstones = List.copyOf(tombstones);
	}

	public static SnapshotDto empty() {
		return new SnapshotDto(CURRENT_SCHEMA_VERSION, 0L, null, Map.of(), List.of());
	}

	public record ViewerDto(String viewerId, String displayName, Instant createdAt) {
	}

	public record LocationDto(String dimension, double x, double y, double z) {
	}

	public record ParrotRecordDto(
			ViewerDto viewer,
			UUID incarnationId,
			long generation,
			UUID entityUuid,
			String variant,
			String trait,
			String desiredBehavior,
			String physicalStatus,
			LocationDto lastKnownLocation,
			Instant lastSpawnedAt,
			Instant updatedAt,
			long revision
	) {
	}

	public record TombstoneDto(String viewerId, UUID incarnationId, long lastGeneration, Instant removedAt) {
	}
}
