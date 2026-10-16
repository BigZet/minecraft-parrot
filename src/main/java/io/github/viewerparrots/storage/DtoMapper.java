package io.github.viewerparrots.storage;

import io.github.viewerparrots.domain.DesiredBehavior;
import io.github.viewerparrots.domain.DisplayName;
import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.domain.ParrotVariant;
import io.github.viewerparrots.domain.PhysicalStatus;
import io.github.viewerparrots.domain.Tombstone;
import io.github.viewerparrots.domain.Viewer;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.domain.WorldLocation;
import io.github.viewerparrots.json.JsonSchemaException;
import io.github.viewerparrots.storage.dto.SnapshotDto;

/**
 * Преобразование между дисковыми DTO и доменом.
 *
 * <p>Именно здесь строки становятся перечислениями и значениями с валидацией. Любая
 * неизвестная константа, некорректный UUID или несогласованная запись превращаются в ошибку
 * с путём: раздел 8.3 ТЗ запрещает молча менять trait или viewerId.
 */
public final class DtoMapper {

	private DtoMapper() {
	}

	public static ViewerParrotRecord toDomain(String key, SnapshotDto.ParrotRecordDto dto) {
		String path = "$.parrots." + key;
		try {
			ViewerId viewerId = ViewerId.of(dto.viewer().viewerId());
			if (!viewerId.value().equals(key)) {
				// Ключ карты и поле внутри записи обязаны совпадать, иначе индекс разъедется.
				throw new JsonSchemaException(path + ".viewer.viewerId",
						"не совпадает с ключом записи: " + dto.viewer().viewerId() + " против " + key);
			}

			Viewer viewer = new Viewer(viewerId,
					DisplayName.of(dto.viewer().displayName()),
					dto.viewer().createdAt(),
					dto.viewer().nickname() == null ? null : DisplayName.of(dto.viewer().nickname()));

			return new ViewerParrotRecord(
					viewer,
					dto.incarnationId(),
					dto.generation(),
					dto.entityUuid(),
					parseEnum(ParrotVariant.class, dto.variant(), path + ".variant"),
					parseEnum(ParrotTrait.class, dto.trait(), path + ".trait"),
					parseEnum(DesiredBehavior.class, dto.desiredBehavior(), path + ".desiredBehavior"),
					parseEnum(PhysicalStatus.class, dto.physicalStatus(), path + ".physicalStatus"),
					toDomain(dto.lastKnownLocation()),
					dto.lastSpawnedAt(),
					dto.updatedAt(),
					dto.revision());
		} catch (JsonSchemaException e) {
			throw e;
		} catch (IllegalArgumentException e) {
			throw new JsonSchemaException(path, e.getMessage(), e);
		}
	}

	private static WorldLocation toDomain(SnapshotDto.LocationDto dto) {
		return dto == null ? null : new WorldLocation(dto.dimension(), dto.x(), dto.y(), dto.z());
	}

	public static Tombstone toDomain(SnapshotDto.TombstoneDto dto, int index) {
		String path = "$.tombstones[" + index + "]";
		try {
			return new Tombstone(ViewerId.of(dto.viewerId()), dto.incarnationId(),
					dto.lastGeneration(), dto.removedAt());
		} catch (IllegalArgumentException e) {
			throw new JsonSchemaException(path, e.getMessage(), e);
		}
	}

	public static SnapshotDto.ParrotRecordDto fromDomain(ViewerParrotRecord record) {
		return new SnapshotDto.ParrotRecordDto(
				new SnapshotDto.ViewerDto(
						record.viewerId().value(),
						record.viewer().displayName().value(),
						record.viewer().createdAt(),
						record.viewer().nicknameValue().map(DisplayName::value).orElse(null)),
				record.incarnationId(),
				record.generation(),
				record.entityUuid(),
				record.variant().name(),
				record.trait().name(),
				record.desiredBehavior().name(),
				record.physicalStatus().name(),
				fromDomain(record.lastKnownLocation()),
				record.lastSpawnedAt(),
				record.updatedAt(),
				record.revision());
	}

	private static SnapshotDto.LocationDto fromDomain(WorldLocation location) {
		return location == null ? null
				: new SnapshotDto.LocationDto(location.dimension(), location.x(), location.y(), location.z());
	}

	public static SnapshotDto.TombstoneDto fromDomain(Tombstone tombstone) {
		return new SnapshotDto.TombstoneDto(tombstone.viewerId().value(), tombstone.incarnationId(),
				tombstone.lastGeneration(), tombstone.removedAt());
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String path) {
		try {
			return Enum.valueOf(type, raw);
		} catch (IllegalArgumentException e) {
			throw new JsonSchemaException(path, "неизвестное значение " + raw, e);
		}
	}
}
