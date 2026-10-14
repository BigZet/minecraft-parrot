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
	@Test
	void invalidUtf8PrimaryRestoresValidBackup(@TempDir Path dir) throws IOException {
		ViewerParrotRecord original = record("backup-viewer", UUID.randomUUID());
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		repository.checkpoint(List.of(original), List.of(), null);
		repository.checkpoint(List.of(original), List.of(), null);
		repository.close();
		Files.write(dir.resolve(JsonParrotRepository.SNAPSHOT_FILE), new byte[] {'{', (byte) 0xff, '}'});
		ParrotRepository.LoadResult recovered = reload(dir);
		assertEquals(List.of(original), recovered.records());
		assertTrue(recovered.restoredFromBackup());
		assertTrue(recovered.warnings().stream().anyMatch(warning -> warning.contains("резервная копия")));
	}

	@Test
	void olderGenerationDeathCannotOverwriteCommittedReplacement(@TempDir Path dir) throws IOException {
		ViewerParrotRecord original = record("viewer", UUID.randomUUID());
		ViewerParrotRecord replacement = original.asRespawned(UUID.randomUUID(), NOW.plusSeconds(1));
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		repository.checkpoint(List.of(original), List.of(), null);
		repository.appendJournal(commit(1, UUID.randomUUID(), replacement));
		repository.appendJournal(commit(2, UUID.randomUUID(), original.asDead(NOW.plusSeconds(2))));
		repository.close();
		assertEquals(List.of(replacement), reload(dir).records());
	}

	@Test
	void validSameGenerationDeathWinsEvenWithEqualRevision(@TempDir Path dir) throws IOException {
		ViewerParrotRecord original = record("viewer", UUID.randomUUID());
		ViewerParrotRecord renamed = original.withDisplayName(io.github.viewerparrots.domain.DisplayName.of("Pending name"), NOW);
		ViewerParrotRecord death = original.asDead(NOW.plusSeconds(1));
		assertEquals(renamed.revision(), death.revision());
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		repository.appendJournal(commit(1, UUID.randomUUID(), renamed));
		repository.appendJournal(commit(2, UUID.randomUUID(), death));
		repository.close();
		assertEquals(List.of(death), reload(dir).records());
	}

	@Test
	void sameGenerationLateAlivePayloadCannotResurrectDeath(@TempDir Path dir) throws IOException {
		ViewerParrotRecord original = record("viewer", UUID.randomUUID());
		ViewerParrotRecord death = original.asDead(NOW);
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		repository.appendJournal(commit(1, UUID.randomUUID(), death));
		repository.appendJournal(commit(2, UUID.randomUUID(), original.withDesiredBehavior(
				io.github.viewerparrots.domain.DesiredBehavior.FOLLOWING, NOW.plusSeconds(1))));
		repository.close();
		assertEquals(List.of(death), reload(dir).records());
	}

	@Test
	void tombstonedIncarnationCannotReplayOverNewRegistration(@TempDir Path dir) throws IOException {
		ViewerParrotRecord old = record("viewer", UUID.randomUUID());
		ViewerParrotRecord fresh = record("viewer", UUID.randomUUID());
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		repository.appendJournal(commit(1, UUID.randomUUID(), old));
		repository.appendJournal(commitTombstone(2, UUID.randomUUID(), Tombstone.of(old, NOW)));
		repository.appendJournal(commit(3, UUID.randomUUID(), fresh));
		repository.appendJournal(commit(4, UUID.randomUUID(), old.withTrait(ParrotTrait.LOUD, NOW.plusSeconds(1))));
		repository.close();
		assertEquals(List.of(fresh), reload(dir).records());
	}

	@Test
	void oldTombstoneCannotRemoveDifferentCurrentIncarnation(@TempDir Path dir) throws IOException {
		ViewerParrotRecord old = record("viewer", UUID.randomUUID());
		ViewerParrotRecord fresh = record("viewer", UUID.randomUUID());
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		repository.checkpoint(List.of(fresh), List.of(Tombstone.of(old, NOW)), null);
		repository.appendJournal(commitTombstone(1, UUID.randomUUID(), Tombstone.of(old, NOW.plusSeconds(1))));
		repository.close();
		assertEquals(List.of(fresh), reload(dir).records());
	}

	@Test
	void conflictingIncarnationWithoutRemovalIsStorageFault(@TempDir Path dir) throws IOException {
		ViewerParrotRecord first = record("viewer", UUID.randomUUID());
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		repository.appendJournal(commit(1, UUID.randomUUID(), first));
		repository.appendJournal(commit(2, UUID.randomUUID(), record("viewer", UUID.randomUUID())));
		repository.close();
		assertThrows(StorageFaultException.class, () -> reload(dir));
	}

	@Test
	void completeChecksumCorruptionWithoutNewlineIsStorageFault(@TempDir Path dir) throws IOException {
		var json = com.google.gson.JsonParser.parseString(JournalCodec.write(
				commit(1, UUID.randomUUID(), record("viewer", UUID.randomUUID())))).getAsJsonObject();
		json.addProperty("checksum", "0".repeat(64));
		Files.writeString(dir.resolve(JsonParrotRepository.JOURNAL_FILE), json.toString(), StandardCharsets.UTF_8);
		assertThrows(StorageFaultException.class, () -> reload(dir));
	}

	@Test
	void completeUnknownEnumWithoutNewlineIsStorageFault(@TempDir Path dir) throws IOException {
		var json = com.google.gson.JsonParser.parseString(JournalCodec.write(
				commit(1, UUID.randomUUID(), record("viewer", UUID.randomUUID())))).getAsJsonObject();
		json.getAsJsonObject("record").addProperty("trait", "UNKNOWN");
		json.addProperty("checksum", io.github.viewerparrots.json.CanonicalJson.checksum(json));
		Files.writeString(dir.resolve(JsonParrotRepository.JOURNAL_FILE), json.toString(), StandardCharsets.UTF_8);
		assertThrows(StorageFaultException.class, () -> reload(dir));
	}

	@Test
	void malformedUtf8InCompleteFinalRecordIsStorageFault(@TempDir Path dir) throws IOException {
		byte[] bytes = JournalCodec.write(commit(1, UUID.randomUUID(), record("viewer", UUID.randomUUID()))).getBytes(StandardCharsets.UTF_8);
		for (int i = 0; i < bytes.length; i++) {
			if (bytes[i] == 'v') { bytes[i] = (byte) 0xff; break; }
		}
		Files.write(dir.resolve(JsonParrotRepository.JOURNAL_FILE), bytes);
		assertThrows(StorageFaultException.class, () -> reload(dir));
	}
	@Test
	void checkpointAfterBackupRecoveryPreservesValidBackup(@TempDir Path dir) throws IOException {
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		ViewerParrotRecord original = record("backup-viewer", UUID.randomUUID());
		repository.checkpoint(List.of(original), List.of(), null);
		repository.checkpoint(List.of(original), List.of(), null);
		repository.close();
		Path primary = dir.resolve(JsonParrotRepository.SNAPSHOT_FILE);
		Files.writeString(primary, "{broken", StandardCharsets.UTF_8);
		JsonParrotRepository recovered = new JsonParrotRepository(dir);
		assertTrue(recovered.load().restoredFromBackup());
		recovered.checkpoint(List.of(original), List.of(), null);
		recovered.close();
		Files.writeString(primary, "{broken again", StandardCharsets.UTF_8);
		assertEquals(List.of(original), reload(dir).records());
	}
	@Test
	void checkpointDoesNotHideCommitAwaitingServerCallback(@TempDir Path dir) throws IOException {
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		ViewerParrotRecord committed = record("viewer", UUID.randomUUID());
		repository.appendJournal(commit(1, UUID.randomUUID(), committed));
		repository.checkpointAtSequence(List.of(), List.of(), null, 0);
		repository.close();
		assertEquals(List.of(committed), reload(dir).records());
	}

	@Test
	void targetAssignmentIsDurableWithoutCheckpoint(@TempDir Path dir) throws IOException {
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		UUID target = UUID.randomUUID();
		repository.appendJournal(new JournalEntryDto(1, 1, UUID.randomUUID(), JournalEntryDto.Phase.COMMIT,
				"__streamer__", null, 0, null, null, target, null));
		repository.close();
		assertEquals(target, reload(dir).streamerPlayerUuid());
	}

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
	void discardedTailDoesNotCorruptSubsequentAppend(@TempDir Path dir) throws IOException {
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		ViewerParrotRecord first = record("first", UUID.randomUUID());
		repository.appendJournal(commit(1, UUID.randomUUID(), first));
		repository.close();
		Files.writeString(dir.resolve(JsonParrotRepository.JOURNAL_FILE), "{\"sequence\":2",
				StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
		JsonParrotRepository recovered = new JsonParrotRepository(dir);
		assertTrue(recovered.load().warnings().stream().anyMatch(w -> w.contains("хвост")));
		ViewerParrotRecord second = record("second", UUID.randomUUID());
		recovered.appendJournal(commit(recovered.nextJournalSequence(), UUID.randomUUID(), second));
		recovered.close();
		assertEquals(2, reload(dir).records().size());
	}

	@Test
	void incompleteUtf8AtJournalTailIsDiscarded(@TempDir Path dir) throws IOException {
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		repository.appendJournal(commit(1, UUID.randomUUID(), record("first", UUID.randomUUID())));
		repository.close();
		byte[] tail = "{\"displayName\":\"Д".getBytes(StandardCharsets.UTF_8);
		Files.write(dir.resolve(JsonParrotRepository.JOURNAL_FILE), java.util.Arrays.copyOf(tail, tail.length - 1),
				java.nio.file.StandardOpenOption.APPEND);
		JsonParrotRepository recovered = new JsonParrotRepository(dir);
		ParrotRepository.LoadResult result = recovered.load();
		assertEquals(1, result.records().size());
		assertTrue(result.warnings().stream().anyMatch(w -> w.contains("хвост")));
		recovered.appendJournal(commit(recovered.nextJournalSequence(), UUID.randomUUID(), record("second", UUID.randomUUID())));
		recovered.close();
		assertEquals(2, reload(dir).records().size());
	}

	@Test
	void malformedUtf8InCommittedBytesIsNeverDiscarded(@TempDir Path dir) throws IOException {
		Path journal = dir.resolve(JsonParrotRepository.JOURNAL_FILE);
		Files.write(journal, new byte[] {(byte) 0xff, '\n', '{'});
		assertThrows(IOException.class, () -> new JsonParrotRepository(dir).load());
	}

	@Test
	void completeFinalRecordWithoutNewlineCanBeAppended(@TempDir Path dir) throws IOException {
		ViewerParrotRecord first = record("first", UUID.randomUUID());
		Files.writeString(dir.resolve(JsonParrotRepository.JOURNAL_FILE),
				JournalCodec.write(commit(1, UUID.randomUUID(), first)), StandardCharsets.UTF_8);
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		repository.appendJournal(commit(repository.nextJournalSequence(), UUID.randomUUID(),
				record("second", UUID.randomUUID())));
		repository.close();
		assertEquals(2, reload(dir).records().size());
	}

	@Test
	void newerSnapshotDoesNotFallBackToOlderBackup(@TempDir Path dir) throws IOException {
		JsonParrotRepository repository = new JsonParrotRepository(dir);
		repository.load();
		repository.checkpoint(List.of(), List.of(), null);
		repository.checkpoint(List.of(), List.of(), null);
		repository.close();
		Path snapshot = dir.resolve(JsonParrotRepository.SNAPSHOT_FILE);
		String newer = Files.readString(snapshot).replace("\"schemaVersion\": 1", "\"schemaVersion\": 2");
		Files.writeString(snapshot, newer, StandardCharsets.UTF_8);
		assertThrows(StorageFaultException.class, () -> new JsonParrotRepository(dir).load());
		assertEquals(newer, Files.readString(snapshot));
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
