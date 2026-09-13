package io.github.viewerparrots.storage;

import io.github.viewerparrots.domain.Tombstone;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.storage.dto.JournalEntryDto;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable-хранилище реестра (раздел 8 ТЗ). Все методы вызываются из выделенного writer
 * executor, а не с игрового потока; результат возвращается на server thread вызывающим.
 */
public interface ParrotRepository {

	/**
	 * Читает снимок и воспроизводит журнал.
	 *
	 * @throws StorageFaultException если данные повреждены и восстановить их нельзя;
	 *         пустой реестр вместо данных не создаётся никогда
	 */
	LoadResult load() throws IOException;

	/** Дописывает запись журнала с durable flush до возврата. */
	void appendJournal(JournalEntryDto entry) throws IOException;

	long nextJournalSequence();

	/** Immutable snapshot → временный файл → force → атомарная замена, прежний в {@code .bak}. */
	CheckpointResult checkpoint(List<ViewerParrotRecord> records, List<Tombstone> tombstones,
			UUID streamerPlayerUuid) throws IOException;

	/**
	 * Итог загрузки.
	 *
	 * @param danglingPrepares операции с durable PREPARE, но без COMMIT и ABORT: их
	 *        подготовленные сущности нужно удалить до начала AI (раздел 8.3 ТЗ)
	 * @param warnings         восстановимые проблемы, например отброшенный хвост журнала
	 */
	record LoadResult(
			List<ViewerParrotRecord> records,
			List<Tombstone> tombstones,
			UUID streamerPlayerUuid,
			long lastJournalSequence,
			long checkpointSequence,
			List<JournalEntryDto> danglingPrepares,
			List<String> warnings,
			boolean restoredFromBackup
	) {
		public LoadResult {
			records = List.copyOf(records);
			tombstones = List.copyOf(tombstones);
			danglingPrepares = List.copyOf(danglingPrepares);
			warnings = List.copyOf(warnings);
		}

		public Optional<UUID> streamer() {
			return Optional.ofNullable(this.streamerPlayerUuid);
		}
	}

	record CheckpointResult(long checkpointSequence, boolean atomicReplace, List<String> warnings) {
		public CheckpointResult {
			warnings = List.copyOf(warnings);
		}
	}
}
