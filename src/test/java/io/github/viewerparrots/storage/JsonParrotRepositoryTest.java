package io.github.viewerparrots.storage;

import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.domain.ParrotVariant;
import io.github.viewerparrots.domain.Tombstone;
import io.github.viewerparrots.domain.Viewer;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.domain.WorldLocation;
import io.github.viewerparrots.storage.dto.JournalEntryDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Раздел 15.1 ТЗ: JSON round-trip, схема, replay журнала, арбитраж generation и tombstone.
 * Плюс аварийные сценарии раздела 8.3, воспроизводимые без запуска сервера.
 */
class JsonParrotRepositoryTest {

	private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");

	private static ViewerParrotRecord record(String id, UUID entityUuid) {
		Viewer viewer = new Viewer(ViewerId.of(id),
				io.github.viewerparrots.domain.DisplayName.of("Дмитрий " + id), NOW);
		return ViewerParrotRecord.created(viewer, UUID.randomUUID(), entityUuid,
				ParrotVariant.BLUE, ParrotTrait.LAZY,
				new WorldLocation("minecraft:overworld", 10.5, 65.0, -20.5), NOW);
	}

	private static JournalEntryDto prepare(long sequence, UUID operationId, ViewerParrotRecord record) {
		return new JournalEntryDto(JournalEntryDto.CURRENT_SCHEMA_VERSION, sequence, operationId,
				JournalEntryDto.Phase.PREPARE, record.viewerId().value(), record.incarnationId(),
				record.generation(), DtoMapper.fromDomain(record), null, null);
	}

	private static JournalEntryDto commit(long sequence, UUID operationId, ViewerParrotRecord record) {
		return new JournalEntryDto(JournalEntryDto.CURRENT_SCHEMA_VERSION, sequence, operationId,
				JournalEntryDto.Phase.COMMIT, record.viewerId().value(), record.incarnationId(),
				record.generation(), DtoMapper.fromDomain(record), null, null);
	}

	private static JournalEntryDto abort(long sequence, UUID operationId, ViewerParrotRecord record) {
		return new JournalEntryDto(JournalEntryDto.CURRENT_SCHEMA_VERSION, sequence, operationId,
				JournalEntryDto.Phase.ABORT, record.viewerId().value(), record.incarnationId(),
				record.generation(), null, null, null);
	}

	private static JournalEntryDto commitTombstone(long sequence, UUID operationId, Tombstone tombstone) {
		return new JournalEntryDto(JournalEntryDto.CURRENT_SCHEMA_VERSION, sequence, operationId,
				JournalEntryDto.Phase.COMMIT, tombstone.viewerId().value(), tombstone.incarnationId(),
				tombstone.lastGeneration(), null, DtoMapper.fromDomain(tombstone), null);
	}

	@Test
	@DisplayName("пустой каталог даёт пустой реестр без ошибок")
	void emptyDirectoryLoadsEmpty(@TempDir Path dir) throws IOException {
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		ParrotRepository.LoadResult result = repository.load();

		assertAll(
				() -> assertTrue(result.records().isEmpty()),
				() -> assertTrue(result.tombstones().isEmpty()),
				() -> assertTrue(result.streamer().isEmpty()),
				() -> assertFalse(result.restoredFromBackup()));
		repository.close();
	}

	@Test
	@DisplayName("checkpoint и перезагрузка сохраняют identity, trait, variant и позицию")
	void checkpointRoundTrip(@TempDir Path dir) throws IOException {
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();

		ViewerParrotRecord original = record("viewer1", UUID.randomUUID());
		UUID streamer = UUID.randomUUID();
		repository.checkpoint(List.of(original), List.of(), streamer);
		repository.close();

		JsonParrotRepository reopened = new JsonParrotRepository(dir);
		ParrotRepository.LoadResult result = reopened.load();
		reopened.close();

		assertAll(
				() -> assertEquals(1, result.records().size()),
				() -> assertEquals(original, result.records().get(0)),
				() -> assertEquals(streamer, result.streamerPlayerUuid()));
	}

	@Test
	@DisplayName("COMMIT из журнала применяется поверх снимка")
	void commitIsReplayedOverSnapshot(@TempDir Path dir) throws IOException {
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		repository.checkpoint(List.of(), List.of(), null);

		ViewerParrotRecord created = record("viewer1", UUID.randomUUID());
		UUID operation = UUID.randomUUID();
		repository.appendJournal(prepare(repository.nextJournalSequence(), operation, created));
		repository.appendJournal(commit(repository.nextJournalSequence(), operation, created));
		repository.close();

		ParrotRepository.LoadResult result = reload(dir);

		assertAll(
				() -> assertEquals(1, result.records().size()),
				() -> assertEquals(created, result.records().get(0)),
				() -> assertTrue(result.danglingPrepares().isEmpty()));
	}

	@Test
	@DisplayName("PREPARE без COMMIT откатывается и сообщается наверх")
	void danglingPrepareIsRolledBack(@TempDir Path dir) throws IOException {
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		repository.checkpoint(List.of(), List.of(), null);

		ViewerParrotRecord created = record("viewer1", UUID.randomUUID());
		UUID operation = UUID.randomUUID();
		repository.appendJournal(prepare(repository.nextJournalSequence(), operation, created));
		repository.close();

		ParrotRepository.LoadResult result = reload(dir);

		assertAll(
				() -> assertTrue(result.records().isEmpty(), "незакоммиченная запись не должна применяться"),
				() -> assertEquals(1, result.danglingPrepares().size()),
				() -> assertEquals(operation, result.danglingPrepares().get(0).operationId()));
	}

	@Test
	@DisplayName("ABORT отменяет подготовленную операцию и не оставляет висящего PREPARE")
	void abortCancelsPrepare(@TempDir Path dir) throws IOException {
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		repository.checkpoint(List.of(), List.of(), null);

		ViewerParrotRecord created = record("viewer1", UUID.randomUUID());
		UUID operation = UUID.randomUUID();
		repository.appendJournal(prepare(repository.nextJournalSequence(), operation, created));
		repository.appendJournal(abort(repository.nextJournalSequence(), operation, created));
		repository.close();

		ParrotRepository.LoadResult result = reload(dir);

		assertAll(
				() -> assertTrue(result.records().isEmpty()),
				() -> assertTrue(result.danglingPrepares().isEmpty()));
	}

	@Test
	@DisplayName("COMMIT надгробия снимает регистрацию и переживает перезагрузку")
	void tombstoneCommitRemovesRegistration(@TempDir Path dir) throws IOException {
		ViewerParrotRecord created = record("viewer1", UUID.randomUUID());

		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		repository.checkpoint(List.of(created), List.of(), null);

		Tombstone tombstone = Tombstone.of(created, NOW);
		repository.appendJournal(commitTombstone(repository.nextJournalSequence(), UUID.randomUUID(), tombstone));
		repository.close();

		ParrotRepository.LoadResult result = reload(dir);

		assertAll(
				() -> assertTrue(result.records().isEmpty()),
				() -> assertEquals(1, result.tombstones().size()),
				() -> assertEquals(created.incarnationId(), result.tombstones().get(0).incarnationId()));
	}

	@Test
	@DisplayName("записи journal с sequence ≤ checkpoint пропускаются, более новые применяются")
	void entriesBelowCheckpointAreSkipped(@TempDir Path dir) throws IOException {
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();

		ViewerParrotRecord first = record("viewer1", UUID.randomUUID());
		UUID operation = UUID.randomUUID();
		repository.appendJournal(prepare(repository.nextJournalSequence(), operation, first));
		repository.appendJournal(commit(repository.nextJournalSequence(), operation, first));

		// Снимок вбирает журнал: его записи больше применяться не должны.
		repository.checkpoint(List.of(first), List.of(), null);

		ViewerParrotRecord second = record("viewer2", UUID.randomUUID());
		UUID secondOperation = UUID.randomUUID();
		repository.appendJournal(commit(repository.nextJournalSequence(), secondOperation, second));
		repository.close();

		ParrotRepository.LoadResult result = reload(dir);

		assertAll(
				() -> assertEquals(2, result.records().size()),
				() -> assertEquals(2L, result.checkpointSequence()),
				() -> assertEquals(3L, result.lastJournalSequence()));
	}

	@Test
	@DisplayName("неполная последняя строка журнала отбрасывается с предупреждением")
	void truncatedTailIsDiscardedWithWarning(@TempDir Path dir) throws IOException {
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		repository.checkpoint(List.of(), List.of(), null);

		ViewerParrotRecord created = record("viewer1", UUID.randomUUID());
		UUID operation = UUID.randomUUID();
		repository.appendJournal(prepare(repository.nextJournalSequence(), operation, created));
		repository.appendJournal(commit(repository.nextJournalSequence(), operation, created));
		repository.close();

		// Имитация падения в момент записи третьей строки.
		Path journal = dir.resolve(JsonParrotRepository.JOURNAL_FILE);
		Files.writeString(journal, Files.readString(journal, StandardCharsets.UTF_8)
				+ "{\"schemaVersion\":1,\"sequence\":3,\"operationId\":\"", StandardCharsets.UTF_8);

		ParrotRepository.LoadResult result = reload(dir);

		assertAll(
				() -> assertEquals(1, result.records().size(), "подтверждённая операция обязана уцелеть"),
				() -> assertTrue(result.warnings().stream().anyMatch(w -> w.contains("хвост"))));
	}

	@Test
	@DisplayName("повреждение середины журнала — storage fault, а не молчаливый пропуск")
	void corruptedMiddleIsAFault(@TempDir Path dir) throws IOException {
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		repository.checkpoint(List.of(), List.of(), null);

		ViewerParrotRecord created = record("viewer1", UUID.randomUUID());
		UUID operation = UUID.randomUUID();
		repository.appendJournal(prepare(repository.nextJournalSequence(), operation, created));
		repository.appendJournal(commit(repository.nextJournalSequence(), operation, created));
		repository.close();

		Path journal = dir.resolve(JsonParrotRepository.JOURNAL_FILE);
		List<String> lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
		lines.set(0, "{\"schemaVersion\":1,\"sequence\":1,\"broken\":true}");
		Files.write(journal, lines, StandardCharsets.UTF_8);

		assertThrows(RuntimeException.class, () -> new JsonParrotRepository(dir).load());
	}

	@Test
	@DisplayName("подделанная контрольная сумма отвергается")
	void tamperedChecksumIsRejected(@TempDir Path dir) throws IOException {
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		repository.checkpoint(List.of(), List.of(), null);

		ViewerParrotRecord created = record("viewer1", UUID.randomUUID());
		repository.appendJournal(commit(repository.nextJournalSequence(), UUID.randomUUID(), created));
		repository.close();

		Path journal = dir.resolve(JsonParrotRepository.JOURNAL_FILE);
		String content = Files.readString(journal, StandardCharsets.UTF_8);
		// Меняем имя, не пересчитывая checksum.
		Files.writeString(journal, content.replace("Дмитрий viewer1", "Подделка viewer"),
				StandardCharsets.UTF_8);

		StorageFaultException fault = assertThrows(StorageFaultException.class,
				() -> new JsonParrotRepository(dir).load());
		assertTrue(fault.getMessage().contains("контрольная сумма"));
	}

	@Test
	@DisplayName("разрыв sequence — storage fault, даже когда контрольные суммы верны")
	void sequenceGapIsAFault(@TempDir Path dir) throws IOException {
		Files.createDirectories(dir);

		// Записи собираются вручную: JournalWriter не даёт пропустить sequence, а править
		// готовую строку нельзя — сломалась бы контрольная сумма, и тест проходил бы
		// по совершенно другой причине.
		ViewerParrotRecord first = record("viewer1", UUID.randomUUID());
		ViewerParrotRecord third = record("viewer2", UUID.randomUUID());
		String journalContent = JournalCodec.write(commit(1, UUID.randomUUID(), first)) + "\n"
				+ JournalCodec.write(commit(3, UUID.randomUUID(), third)) + "\n";
		Files.writeString(dir.resolve(JsonParrotRepository.JOURNAL_FILE), journalContent,
				StandardCharsets.UTF_8);

		StorageFaultException fault = assertThrows(StorageFaultException.class,
				() -> new JsonParrotRepository(dir).load());
		assertTrue(fault.getMessage().contains("разрыв sequence"), fault.getMessage());
	}

	@Test
	@DisplayName("повреждённый снимок восстанавливается из резервной копии")
	void corruptedSnapshotFallsBackToBackup(@TempDir Path dir) throws IOException {
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();

		ViewerParrotRecord first = record("viewer1", UUID.randomUUID());
		repository.checkpoint(List.of(first), List.of(), null);
		// Второй checkpoint переносит первый снимок в .bak.
		repository.checkpoint(List.of(first), List.of(), null);
		repository.close();

		Path snapshot = dir.resolve(JsonParrotRepository.SNAPSHOT_FILE);
		Files.writeString(snapshot, "{ это не JSON", StandardCharsets.UTF_8);

		ParrotRepository.LoadResult result = reload(dir);

		assertAll(
				() -> assertTrue(result.restoredFromBackup()),
				() -> assertEquals(1, result.records().size()),
				() -> assertTrue(result.warnings().stream().anyMatch(w -> w.contains("резервная копия"))));
	}

	@Test
	@DisplayName("повреждены и снимок, и резервная копия — fault, а не пустой реестр")
	void bothCorruptedProducesFaultNotEmptyRegistry(@TempDir Path dir) throws IOException {
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		repository.checkpoint(List.of(record("viewer1", UUID.randomUUID())), List.of(), null);
		repository.checkpoint(List.of(record("viewer1", UUID.randomUUID())), List.of(), null);
		repository.close();

		Files.writeString(dir.resolve(JsonParrotRepository.SNAPSHOT_FILE), "{ мусор", StandardCharsets.UTF_8);
		Files.writeString(dir.resolve(JsonParrotRepository.BACKUP_FILE), "{ тоже мусор", StandardCharsets.UTF_8);

		assertThrows(StorageFaultException.class, () -> new JsonParrotRepository(dir).load());
	}

	@Test
	@DisplayName("более новая версия схемы запрещает работу с файлом")
	void newerSchemaVersionIsRejected(@TempDir Path dir) throws IOException {
		Files.createDirectories(dir);
		Files.writeString(dir.resolve(JsonParrotRepository.SNAPSHOT_FILE), """
				{"schemaVersion": 2, "checkpointSequence": 0, "parrots": {}, "tombstones": []}
				""", StandardCharsets.UTF_8);

		assertThrows(RuntimeException.class, () -> new JsonParrotRepository(dir).load());
	}

	@Test
	@DisplayName("ключ карты обязан совпадать с viewerId внутри записи")
	void mismatchedKeyIsRejected(@TempDir Path dir) throws IOException {
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		repository.checkpoint(List.of(record("viewer1", UUID.randomUUID())), List.of(), null);
		repository.close();

		Path snapshot = dir.resolve(JsonParrotRepository.SNAPSHOT_FILE);
		String content = Files.readString(snapshot, StandardCharsets.UTF_8);
		Files.writeString(snapshot, content.replaceFirst("\"viewer1\": \\{", "\"viewerX\": {"),
				StandardCharsets.UTF_8);

		assertThrows(RuntimeException.class, () -> new JsonParrotRepository(dir).load());
	}

	@Test
	@DisplayName("журнал не пишется с непоследовательным sequence")
	void journalRejectsNonSequentialAppend(@TempDir Path dir) throws IOException {
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();

		ViewerParrotRecord created = record("viewer1", UUID.randomUUID());
		assertThrows(IllegalArgumentException.class,
				() -> repository.appendJournal(commit(5, UUID.randomUUID(), created)));
		repository.close();
	}

	@Test
	@DisplayName("compaction сохраняет записи, нужные для replay от резервной копии")
	void compactionKeepsEntriesNeededForBackupReplay(@TempDir Path dir) throws IOException {
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();

		ViewerParrotRecord first = record("viewer1", UUID.randomUUID());
		ViewerParrotRecord second = record("viewer2", UUID.randomUUID());
		repository.appendJournal(commit(1, UUID.randomUUID(), first));
		repository.checkpoint(List.of(first), List.of(), null);
		repository.appendJournal(commit(2, UUID.randomUUID(), second));
		repository.checkpoint(List.of(first, second), List.of(), null);

		// .bak соответствует checkpointSequence = 1, значит запись 2 обязана уцелеть.
		repository.compactJournal(1);
		repository.close();

		JournalReader.Result journal = JournalReader.read(dir.resolve(JsonParrotRepository.JOURNAL_FILE));

		assertAll(
				() -> assertEquals(1, journal.entries().size()),
				() -> assertEquals(2L, journal.entries().get(0).sequence()));
	}

	private static ParrotRepository.LoadResult reload(Path dir) throws IOException {
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		try {
			return repository.load();
		} finally {
			repository.close();
		}
	}
}
