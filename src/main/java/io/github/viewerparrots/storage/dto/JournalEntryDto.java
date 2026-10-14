package io.github.viewerparrots.storage.dto;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Запись append-only журнала lifecycle-транзакций (раздел 8.2 ТЗ).
 *
 * <p>Снимка недостаточно: между записью снимка и созданием сущности сервер может упасть,
 * и только журнал показывает, какое намерение было зафиксировано durable.
 */
public record JournalEntryDto(
		int schemaVersion,
		long sequence,
		UUID operationId,
		Phase phase,
		String viewerId,
		UUID incarnationId,
		long generation,
		SnapshotDto.ParrotRecordDto record,
		SnapshotDto.TombstoneDto tombstone,
		UUID streamerPlayerUuid,
		String checksum
) {
	public static final int CURRENT_SCHEMA_VERSION = 1;

	/**
	 * {@code PREPARE} durably фиксирует намерение с заранее выбранным entityUuid,
	 * {@code COMMIT} разрешает применение, {@code ABORT} отменяет подготовленное.
	 */
	public enum Phase {
		PREPARE,
		COMMIT,
		ABORT
	}

	public JournalEntryDto {
		Objects.requireNonNull(operationId, "operationId");
		Objects.requireNonNull(phase, "phase");
		Objects.requireNonNull(viewerId, "viewerId");
		if (sequence < 1) {
			throw new IllegalArgumentException("sequence должен быть ≥ 1, получено " + sequence);
		}
	}

	public JournalEntryDto(int schemaVersion, long sequence, UUID operationId, Phase phase,
			String viewerId, UUID incarnationId, long generation, SnapshotDto.ParrotRecordDto record,
			SnapshotDto.TombstoneDto tombstone, String checksum) {
		this(schemaVersion, sequence, operationId, phase, viewerId, incarnationId, generation,
				record, tombstone, null, checksum);
	}

	public Optional<SnapshotDto.ParrotRecordDto> recordPayload() {
		return Optional.ofNullable(this.record);
	}

	public Optional<SnapshotDto.TombstoneDto> tombstonePayload() {
		return Optional.ofNullable(this.tombstone);
	}

	public JournalEntryDto withChecksum(String newChecksum) {
		return new JournalEntryDto(this.schemaVersion, this.sequence, this.operationId, this.phase,
				this.viewerId, this.incarnationId, this.generation, this.record, this.tombstone, this.streamerPlayerUuid, newChecksum);
	}
}
