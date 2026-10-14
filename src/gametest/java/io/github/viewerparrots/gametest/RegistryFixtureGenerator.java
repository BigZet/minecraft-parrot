package io.github.viewerparrots.gametest;

import io.github.viewerparrots.domain.DisplayName;
import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.domain.ParrotVariant;
import io.github.viewerparrots.domain.DesiredBehavior;
import io.github.viewerparrots.domain.PhysicalStatus;
import io.github.viewerparrots.domain.WorldLocation;
import io.github.viewerparrots.domain.Viewer;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.storage.JsonParrotRepository;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A separate fixture JVM writes the snapshot; it cannot warm the measured server's classes or heap. */
public final class RegistryFixtureGenerator {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected isolated world/data/viewerparrots directory");
        Path directory = Path.of(args[0]).toAbsolutePath();
        List<ViewerParrotRecord> records = new ArrayList<>(10000);
        for (int i = 0; i < 10000; i++) {
            Instant created = Instant.ofEpochSecond(i * 17L);
            records.add(new ViewerParrotRecord(new Viewer(ViewerId.of("boot-" + i),
                            DisplayName.of("Зритель " + i), created), new UUID(1, i), 1 + i % 7, null,
                    ParrotVariant.values()[i % ParrotVariant.values().length],
                    ParrotTrait.values()[i % ParrotTrait.values().length],
                    DesiredBehavior.values()[i % DesiredBehavior.values().length], PhysicalStatus.DEAD,
                    i % 4 == 0 ? null : new WorldLocation(i % 2 == 0 ? "minecraft:overworld" : "minecraft:the_nether",
                            i % 256 + .5, 64, -(i % 512) - .25),
                    i % 4 == 0 ? null : created.plusSeconds(1), created.plusSeconds(2), i % 17));
        }
        JsonParrotRepository repository = new JsonParrotRepository(directory);
        repository.load();
        repository.checkpoint(records, List.of(), null);
        repository.close();
        System.out.println("REGISTRY_FIXTURE_WRITTEN records=" + records.size() + " directory=" + directory);
    }
}
