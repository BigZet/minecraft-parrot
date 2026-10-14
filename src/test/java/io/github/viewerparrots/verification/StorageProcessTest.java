package io.github.viewerparrots.verification;

import io.github.viewerparrots.domain.*;
import io.github.viewerparrots.storage.*;
import io.github.viewerparrots.storage.dto.JournalEntryDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Abrupt termination of a real writer process, without Java shutdown/close hooks. */
public class StorageProcessTest {
    @Test
    void prepareOnlyCrashDoesNotAuthorizeRecord(@TempDir Path dir) throws Exception {
        crash(dir, "prepare");
        var repository = new JsonParrotRepository(dir);
        var recovered = repository.load();
        assertTrue(recovered.records().isEmpty());
        assertEquals(1, recovered.danglingPrepares().size());
        repository.close();
    }

    @Test
    void committedCrashKeepsIdentity(@TempDir Path dir) throws Exception {
        crash(dir, "commit");
        var repository = new JsonParrotRepository(dir);
        var recovered = repository.load();
        assertEquals(List.of(record()), recovered.records());
        assertTrue(recovered.danglingPrepares().isEmpty());
        repository.close();
    }

    @Test
    void removedCrashKeepsTombstoneAndNoLiveRecord(@TempDir Path dir) throws Exception {
        crash(dir, "remove");
        var repository = new JsonParrotRepository(dir);
        var recovered = repository.load();
        assertTrue(recovered.records().isEmpty());
        assertEquals(1, recovered.tombstones().size());
        assertEquals(record().incarnationId(), recovered.tombstones().getFirst().incarnationId());
        repository.close();
    }

    private static void crash(Path directory, String phase) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(executable, "@" + System.getProperty("viewerparrots.testClasspathFile"),
                StorageProcessTest.class.getName(), directory.toString(), phase).redirectErrorStream(true).start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(finished, "Crash probe timed out: " + output);
        assertEquals(77, process.exitValue(), "Probe must stop via Runtime.halt: " + output);
    }

    public static void main(String[] args) throws Exception {
        JsonParrotRepository repository = new JsonParrotRepository(Path.of(args[0]));
        repository.load();
        ViewerParrotRecord record = record();
        UUID operation = UUID.fromString("99999999-9999-9999-9999-999999999999");
        repository.appendJournal(entry(1, operation, JournalEntryDto.Phase.PREPARE, record));
        if (!args[1].equals("prepare")) repository.appendJournal(entry(2, operation, JournalEntryDto.Phase.COMMIT, record));
        if (args[1].equals("remove")) {
            Tombstone tombstone = Tombstone.of(record, Instant.EPOCH.plusSeconds(5));
            repository.appendJournal(new JournalEntryDto(1, 3, UUID.randomUUID(), JournalEntryDto.Phase.COMMIT,
                    record.viewerId().value(), record.incarnationId(), record.generation(), null,
                    DtoMapper.fromDomain(tombstone), null));
        }
        Runtime.getRuntime().halt(77);
    }

    private static JournalEntryDto entry(long sequence, UUID operation, JournalEntryDto.Phase phase, ViewerParrotRecord record) {
        return new JournalEntryDto(1, sequence, operation, phase, record.viewerId().value(), record.incarnationId(),
                record.generation(), DtoMapper.fromDomain(record), null, null);
    }

    private static ViewerParrotRecord record() {
        return ViewerParrotRecord.created(new Viewer(ViewerId.of("process-crash"), DisplayName.of("Процесс"), Instant.EPOCH),
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"), ParrotVariant.GREEN,
                ParrotTrait.LOUD, new WorldLocation("minecraft:overworld", 0, 64, 0), Instant.EPOCH);
    }
}
