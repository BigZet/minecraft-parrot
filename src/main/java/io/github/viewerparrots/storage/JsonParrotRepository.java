package io.github.viewerparrots.storage;

import io.github.viewerparrots.domain.Tombstone;
import io.github.viewerparrots.domain.PhysicalStatus;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.storage.dto.JournalEntryDto;
import io.github.viewerparrots.storage.dto.SnapshotDto;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Реализация хранилища поверх {@code registry.json}, {@code registry.json.bak} и
 * {@code journal.jsonl} в корне конкретного world save (раздел 8.1 ТЗ).
 *
 * <p>Не потокобезопасна: предполагается единственный writer executor.
 */
public final class JsonParrotRepository implements ParrotRepository {
	public static final String SNAPSHOT_FILE = "registry.json";
	public static final String BACKUP_FILE = "registry.json.bak";
	public static final String JOURNAL_FILE = "journal.jsonl";

	private final Path directory;
	private final Path snapshotFile;
	private final Path backupFile;
	private final Path journalFile;

	private JournalWriter journal;
	private long checkpointSequence;

	public JsonParrotRepository(Path directory) {
		this.directory = Objects.requireNonNull(directory, "directory");
		this.snapshotFile = directory.resolve(SNAPSHOT_FILE);
		this.backupFile = directory.resolve(BACKUP_FILE);
		this.journalFile = directory.resolve(JOURNAL_FILE);
	}

	@Override
	public LoadResult load() throws IOException {
		Files.createDirectories(this.directory);

		List<String> warnings = new ArrayList<>();
		boolean restoredFromBackup = false;

		SnapshotDto snapshot;
		if (!Files.exists(this.snapshotFile)) {
			if (Files.exists(this.backupFile)) {
				// Файл мог не пережить замену; backup — не повод начинать с пустого реестра.
				snapshot = readSnapshotOrFail(this.backupFile, "резервная копия");
				warnings.add("основной снимок отсутствует, использована резервная копия");
				restoredFromBackup = true;
			} else {
				snapshot = SnapshotDto.empty();
			}
		} else {
			try {
				snapshot = readSnapshot(this.snapshotFile);
			} catch (IOException | RuntimeException primaryFailure) {
				if (primaryFailure instanceof StorageMigrator.UnsupportedSchemaException unsupported) {
					throw unsupported;
				}
				if (!Files.exists(this.backupFile)) {
					throw new StorageFaultException("снимок повреждён и резервной копии нет: "
							+ primaryFailure.getMessage(), primaryFailure);
				}
				snapshot = readSnapshotOrFail(this.backupFile, "резервная копия");
				warnings.add("основной снимок повреждён (" + primaryFailure.getMessage()
						+ "), использована резервная копия");
				restoredFromBackup = true;
			}
		}

		JournalReader.Result journalResult = JournalReader.read(this.journalFile);
		if (journalResult.hasTruncatedTail()) {
			warnings.add(journalResult.truncatedTail());
		}

		Replay replay = replay(snapshot, journalResult.entries());
		repairJournalTail(journalResult);

		this.checkpointSequence = snapshot.checkpointSequence();
		long lastSequence = Math.max(journalResult.lastSequence(), snapshot.checkpointSequence());
		this.journal = JournalWriter.open(this.journalFile, lastSequence);

		return new LoadResult(replay.records(), replay.tombstones(), replay.streamer(),
				lastSequence, snapshot.checkpointSequence(), replay.danglingPrepares(),
				warnings, restoredFromBackup);
	}

	private void repairJournalTail(JournalReader.Result result) throws IOException {
		if (!Files.exists(this.journalFile) || Files.size(this.journalFile) == 0) {
			return;
		}
		byte[] bytes = Files.readAllBytes(this.journalFile);
		try (FileChannel channel = FileChannel.open(this.journalFile, StandardOpenOption.WRITE)) {
			if (result.hasTruncatedTail()) {
				int boundary = bytes.length - 1;
				while (boundary >= 0 && bytes[boundary] != '\n') {
					boundary--;
				}
				channel.truncate(boundary + 1L);
			} else if (bytes[bytes.length - 1] != '\n') {
				channel.position(bytes.length);
				channel.write(java.nio.ByteBuffer.wrap(new byte[] {'\n'}));
			}
			channel.force(true);
		}
	}

	private record Replay(List<ViewerParrotRecord> records, List<Tombstone> tombstones,
			UUID streamer, List<JournalEntryDto> danglingPrepares) {
	}

	/**
	 * Записи журнала с sequence ≤ checkpointSequence уже отражены в снимке и пропускаются.
	 * Применяется только {@code COMMIT}: одинокий {@code PREPARE} после аварии откатывается,
	 * то есть просто не применяется, но сообщается наверх.
	 */
	private Replay replay(SnapshotDto snapshot, List<JournalEntryDto> entries) {
		Map<String, ViewerParrotRecord> records = new LinkedHashMap<>();
		for (Map.Entry<String, SnapshotDto.ParrotRecordDto> entry
				: new TreeMap<>(snapshot.parrots()).entrySet()) {
			records.put(entry.getKey(), DtoMapper.toDomain(entry.getKey(), entry.getValue()));
		}

		Map<UUID, Tombstone> tombstones = new LinkedHashMap<>();
		for (int i = 0; i < snapshot.tombstones().size(); i++) {
			Tombstone tombstone = DtoMapper.toDomain(snapshot.tombstones().get(i), i);
			tombstones.put(tombstone.incarnationId(), tombstone);
		}

		UUID streamer = snapshot.streamerPlayerUuid();

		Set<UUID> preparedOperations = new LinkedHashSet<>();
		Set<UUID> settledOperations = new LinkedHashSet<>();
		Map<UUID, JournalEntryDto> preparedEntries = new LinkedHashMap<>();

		for (JournalEntryDto entry : entries) {
			if (entry.sequence() <= snapshot.checkpointSequence()) {
				continue;
			}

			switch (entry.phase()) {
				case PREPARE -> {
					preparedOperations.add(entry.operationId());
					preparedEntries.put(entry.operationId(), entry);
				}
				case ABORT -> settledOperations.add(entry.operationId());
				case COMMIT -> {
					settledOperations.add(entry.operationId());
					applyCommit(entry, records, tombstones);
					if (entry.streamerPlayerUuid() != null) streamer = entry.streamerPlayerUuid();
				}
			}
		}

		List<JournalEntryDto> dangling = new ArrayList<>();
		for (UUID operationId : preparedOperations) {
			if (!settledOperations.contains(operationId)) {
				dangling.add(preparedEntries.get(operationId));
			}
		}

		return new Replay(List.copyOf(records.values()), List.copyOf(tombstones.values()),
				streamer, dangling);
	}

	private static void applyCommit(JournalEntryDto entry, Map<String, ViewerParrotRecord> records,
			Map<UUID, Tombstone> tombstones) {
		entry.tombstonePayload().ifPresent(dto -> {
			Tombstone tombstone = DtoMapper.toDomain(dto, 0);
			tombstones.put(tombstone.incarnationId(), tombstone);
			// Надгробие снимает регистрацию: раздел 8.2 фиксирует его до удаления сущности.
			// Historical removals must never revoke a later registration of the same viewer.
			records.computeIfPresent(tombstone.viewerId().value(), (viewer, current) ->
					current.incarnationId().equals(tombstone.incarnationId()) ? null : current);
		});
		entry.recordPayload().ifPresent(dto -> {
			ViewerParrotRecord incoming;
			try {
				incoming = DtoMapper.toDomain(entry.viewerId(), dto);
			} catch (RuntimeException invalid) {
				throw new StorageFaultException("журнал:sequence " + entry.sequence()
						+ ": недопустимая запись: " + invalid.getMessage(), invalid);
			}
			if (tombstones.containsKey(incoming.incarnationId())) return;
			ViewerParrotRecord current = records.get(entry.viewerId());
			if (current != null) {
				if (!current.incarnationId().equals(incoming.incarnationId())) {
					throw new StorageFaultException("журнал:sequence " + entry.sequence()
							+ ": смена incarnation без tombstone для " + entry.viewerId());
				}
				// Entity events may have queued an older death after a durable respawn.
				// Revision alone cannot arbitrate: a real death can share a pending mutation's revision.
				if (incoming.generation() < current.generation()) return;
				if (incoming.generation() == current.generation()
						&& current.physicalStatus() == PhysicalStatus.DEAD
						&& incoming.physicalStatus() != PhysicalStatus.DEAD) return;
			}
			records.put(entry.viewerId(), incoming);
		});
	}

	@Override
	public void appendJournal(JournalEntryDto entry) throws IOException {
		requireLoaded();
		this.journal.append(entry);
	}

	@Override
	public long nextJournalSequence() {
		requireLoaded();
		return this.journal.nextSequence();
	}

	@Override
	public CheckpointResult checkpoint(List<ViewerParrotRecord> records, List<Tombstone> tombstones,
			UUID streamerPlayerUuid) throws IOException {
		requireLoaded();
		return checkpointAtSequence(records, tombstones, streamerPlayerUuid, this.journal.lastSequence());
	}

	/** The captured registry may lag a durable writer callback; never stamp those unapplied entries. */
	public CheckpointResult checkpointAtSequence(List<ViewerParrotRecord> records, List<Tombstone> tombstones,
			UUID streamerPlayerUuid, long appliedSequence) throws IOException {
		requireLoaded();
		if (appliedSequence < 0 || appliedSequence > this.journal.lastSequence()) {
			throw new IllegalArgumentException("checkpoint sequence outside durable journal: " + appliedSequence);
		}
		List<String> warnings = new ArrayList<>();

		Map<String, SnapshotDto.ParrotRecordDto> parrots = new TreeMap<>();
		for (ViewerParrotRecord record : records) {
			parrots.put(record.viewerId().value(), DtoMapper.fromDomain(record));
		}
		List<SnapshotDto.TombstoneDto> tombstoneDtos = new ArrayList<>();
		for (Tombstone tombstone : tombstones) {
			tombstoneDtos.add(DtoMapper.fromDomain(tombstone));
		}

		long sequence = appliedSequence;
		SnapshotDto snapshot = new SnapshotDto(SnapshotDto.CURRENT_SCHEMA_VERSION, sequence,
				streamerPlayerUuid, parrots, tombstoneDtos);

		Path temp = Files.createTempFile(this.directory, "registry", ".tmp");
		try {
			writeAndForce(temp, SnapshotCodec.write(snapshot));

			// Прежний снимок сохраняется как backup до замены: иначе авария в момент замены
			// оставила бы систему вовсе без валидного файла.
			if (Files.exists(this.snapshotFile)) {
				boolean validPrimary = false;
				try {
					readSnapshot(this.snapshotFile);
					validPrimary = true;
				} catch (StorageMigrator.UnsupportedSchemaException unsupported) {
					throw unsupported;
				} catch (IOException | RuntimeException invalid) {
					warnings.add("повреждённый основной снимок не заменяет проверенную резервную копию");
				}
				if (validPrimary) Files.copy(this.snapshotFile, this.backupFile,
						StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
			}

			boolean atomic = true;
			try {
				Files.move(temp, this.snapshotFile,
						StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				// Заявлять полную атомарность в этом случае нельзя (раздел 8.2 ТЗ).
				Files.move(temp, this.snapshotFile, StandardCopyOption.REPLACE_EXISTING);
				atomic = false;
				warnings.add("файловая система не поддерживает атомарную замену; "
						+ "использована последовательная запись с резервной копией");
			}

			this.checkpointSequence = sequence;
			return new CheckpointResult(sequence, atomic, warnings);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	/**
	 * Обрезает журнал, сохраняя записи, нужные для replay от резервной копии.
	 *
	 * @param backupCheckpointSequence checkpointSequence, записанный в {@code .bak};
	 *        всё, что новее, обязано остаться — иначе откат на backup потеряет
	 *        подтверждённые операции (раздел 8.2 ТЗ)
	 */
	public void compactJournal(long backupCheckpointSequence) throws IOException {
		requireLoaded();
		JournalReader.Result result = JournalReader.read(this.journalFile);
		List<JournalEntryDto> kept = result.entries().stream()
				.filter(entry -> entry.sequence() > backupCheckpointSequence)
				.toList();

		if (kept.size() == result.entries().size()) {
			return;
		}

		Path temp = Files.createTempFile(this.directory, "journal", ".tmp");
		try {
			StringBuilder builder = new StringBuilder();
			for (JournalEntryDto entry : kept) {
				builder.append(JournalCodec.write(entry)).append('\n');
			}
			writeAndForce(temp, builder.toString());

			this.journal.close();
			try {
				Files.move(temp, this.journalFile,
						StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, this.journalFile, StandardCopyOption.REPLACE_EXISTING);
			}
			this.journal = JournalWriter.open(this.journalFile,
					kept.isEmpty() ? backupCheckpointSequence : kept.get(kept.size() - 1).sequence());
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	public long checkpointSequence() {
		return this.checkpointSequence;
	}

	public void close() throws IOException {
		if (this.journal != null) {
			this.journal.close();
			this.journal = null;
		}
	}

	private static SnapshotDto readSnapshot(Path file) throws IOException {
		return SnapshotCodec.read(Files.readString(file, StandardCharsets.UTF_8));
	}

	private static SnapshotDto readSnapshotOrFail(Path file, String description) throws IOException {
		try {
			return readSnapshot(file);
		} catch (RuntimeException e) {
			throw new StorageFaultException(description + " также повреждена: " + e.getMessage(), e);
		}
	}

	private static void writeAndForce(Path file, String content) throws IOException {
		try (FileChannel channel = FileChannel.open(file,
				StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
			channel.write(java.nio.ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
			channel.force(true);
		}
	}

	private void requireLoaded() {
		if (this.journal == null) {
			throw new IllegalStateException("репозиторий не загружен: сначала вызовите load()");
		}
	}
}
