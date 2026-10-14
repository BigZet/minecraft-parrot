package io.github.viewerparrots.verification;

import com.google.gson.GsonBuilder;
import io.github.viewerparrots.domain.*;
import io.github.viewerparrots.storage.JsonParrotRepository;
import io.github.viewerparrots.registry.ViewerParrotRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RegistryLoadTest {
    @Test
    void tenThousandDormantViewersLoadWithoutEntities(@TempDir Path dir) throws Exception {
        List<ViewerParrotRecord> records = new ArrayList<>(10000);
        for (int i = 0; i < 10000; i++) records.add(ViewerParrotRecord.created(
                new Viewer(ViewerId.of("load-" + i), DisplayName.of("Зритель " + i), Instant.EPOCH),
                new UUID(1, i), new UUID(2, i), ParrotVariant.BLUE, ParrotTrait.NORMAL, null, Instant.EPOCH));
        JsonParrotRepository writer = new JsonParrotRepository(dir);
        writer.load();
        writer.checkpoint(records, List.of(), null);
        writer.close();
        long heapBefore = usedHeap();
        long started = System.nanoTime();
        JsonParrotRepository reader = new JsonParrotRepository(dir);
        var loaded = reader.load();
        ViewerParrotRegistry registry = new ViewerParrotRegistry();
        registry.replaceAll(loaded.records(), loaded.tombstones(), loaded.streamerPlayerUuid());
        registry.initializeAppliedJournalSequence(loaded.lastJournalSequence());
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        assertEquals(10000, loaded.records().size());
        assertEquals(records.get(9999), loaded.records().get(9999));
        reader.close();
        long heapAfter = usedHeap();
        assertEquals(10000, registry.size());
        assertEquals(records.get(9999), registry.find(ViewerId.of("load-9999")).orElseThrow());
        assertTrue(seconds < 3.0, "Repository and registry startup must finish within 3 seconds: " + seconds);
        assertTrue(heapAfter - heapBefore < 64L * 1048576, "Registry startup must retain less than 64 MiB");
        java.lang.ref.Reference.reachabilityFence(registry);
        Path evidence = Path.of("build", "reports", "verification", "registry-load.json");
        Files.createDirectories(evidence.getParent());
        Files.writeString(evidence, new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                "records", 10000, "loadSeconds", seconds, "approximateRetainedHeapMiB", Math.max(0, heapAfter - heapBefore) / 1048576.0,
                "scope", "repository-load-and-registry-hydration", "loadedEntities", 0,
                "java", System.getProperty("java.runtime.version"), "os", System.getProperty("os.name"))));
    }

    private static long usedHeap() {
        System.gc();
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }
}
