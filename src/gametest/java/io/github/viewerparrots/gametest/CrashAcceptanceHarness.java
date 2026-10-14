package io.github.viewerparrots.gametest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.bootstrap.CheckpointScheduler;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.domain.DisplayName;
import io.github.viewerparrots.domain.PhysicalStatus;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import io.github.viewerparrots.storage.dto.JournalEntryDto;
import io.github.viewerparrots.storage.JournalCodec;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Actual dedicated-server crash/restart fixture. Inactive unless explicitly selected by a JVM property.
 * Each invocation uses its own world and writes a machine-readable report before stopping or halting.
 */
public final class CrashAcceptanceHarness implements ModInitializer {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ViewerId VIEWER = ViewerId.of("process-crash");
    private static final ActorContext ADMIN = ActorContext.console(4);
    private static final String SCENARIO = System.getProperty("viewerparrots.qa.crash.scenario", "");
    private static final String MODE = System.getProperty("viewerparrots.qa.crash.mode", "crash");
    private static final UUID PLAYER = UUID.fromString("55114b0c-4fc5-4f1d-b3b2-a580ce2793a4");
    private static volatile boolean armed;
    private static MinecraftServer server;
    private static int verifyTicks;
    private static boolean finished;
    private static boolean fixtureStarted;
    private static final Set<UUID> loadedFromWorld = new LinkedHashSet<>();
    private static final List<String> unloadedFromWorld = new ArrayList<>();

    @Override
    public void onInitialize() {
        if (SCENARIO.isEmpty()) return;
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (MODE.equals("verify") && entity instanceof ViewerParrotEntity parrot
                    && parrot.marker().map(marker -> marker.viewerId().equals(VIEWER)).orElse(false))
                loadedFromWorld.add(parrot.getUuid());
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (MODE.equals("verify") && entity instanceof ViewerParrotEntity parrot
                    && parrot.marker().map(marker -> marker.viewerId().equals(VIEWER)).orElse(false)) {
                unloadedFromWorld.add(parrot.getUuid() + ":reason=" + parrot.getRemovalReason() + ":position=" + parrot.getPos());
                new Exception("QA exact persisted entity unload audit: " + parrot.getUuid() + " " + parrot.getRemovalReason()).printStackTrace();
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(started -> {
            server = started;
            try {
                if (MODE.equals("smoke")) return;
                if (MODE.equals("verify")) {
                    for (int x = 7; x <= 9; x++) for (int z = 7; z <= 9; z++) {
                        started.getOverworld().setChunkForced(x, z, true);
                        started.getOverworld().getChunk(x, z);
                    }
                    return;
                }
            } catch (Throwable failure) {
                fail(failure);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(current -> {
            if (server != current || finished) return;
            ServerContext active = ServerContext.current().orElse(null);
            if (active == null || active.storageHealth() == ServerContext.StorageHealth.LOADING) return;
            if (MODE.equals("smoke")) {
                if (++verifyTicks == 5) verifySmoke();
            } else if (MODE.equals("verify")) {
                verifyTicks++;
                if (!fixtureStarted && ServerContext.current().isPresent()) {
                    fixtureStarted = true;
                    try { connectFixturePlayer(); } catch (Throwable failure) { fail(failure); }
                }
                if (verifyTicks == 10) {
                    ServerContext context = ServerContext.current().orElseThrow();
                    if (context.allowsMutations() && context.registry().contains(VIEWER))
                        context.recoveryService().start(VIEWER);
                }
                if (verifyTicks >= 150) verifyRestart();
            } else {
                if (!fixtureStarted && ServerContext.current().filter(ServerContext::allowsMutations).isPresent()) {
                    fixtureStarted = true;
                    try { setUpFixture(); } catch (Throwable failure) { fail(failure); }
                }
                if (++verifyTicks >= 1200) fail(new AssertionError("Process scenario did not reach its gate within 60 seconds"));
            }
        });
    }

    private static void setUpFixture() {
        ServerContext context = ServerContext.current().orElseThrow();
        require(context.allowsMutations(), "Fixture storage is READY");
        ServerWorld world = server.getOverworld();
        world.setChunkForced(8, 8, true);
        for (int x = 112; x <= 144; x++) for (int z = 112; z <= 144; z++)
            world.setBlockState(new BlockPos(x, 63, z), Blocks.STONE.getDefaultState());
        connectFixturePlayer();
        context.streamerService().assign(UUID.randomUUID(), ADMIN, PLAYER).thenCompose(assigned -> {
            require(assigned.succeeded(), "Durable fixture streamer assignment");
            server.save(false, true, true); // Deliberately precedes the tested PREPARE/spawn/COMMIT.
            armed = MODE.equals("crash") && !SCENARIO.equals("AFTER_REMOVE_COMMIT_BEFORE_DISCARD")
                    && !SCENARIO.equals("DURING_CHECKPOINT") && !SCENARIO.equals("DISK_WRITE_FAILURE");
            return context.parrotService().create(UUID.randomUUID(), ADMIN, VIEWER, DisplayName.of("Crash fixture"));
        }).thenCompose(created -> {
            require(created.succeeded(), "Fixture creation is committed");
            writeWitness(context.registry().find(VIEWER).orElseThrow(), null, "fixture committed");
            server.save(false, true, true);
            if (SCENARIO.equals("AFTER_REMOVE_COMMIT_BEFORE_DISCARD")) {
                armed = true;
                return context.parrotService().remove(UUID.randomUUID(), ADMIN, VIEWER).thenApply(removed -> null);
            }
            return CheckpointScheduler.request(context).thenCompose(first -> {
                if (SCENARIO.equals("DISK_WRITE_FAILURE")) {
                    armed = true;
                    return CheckpointScheduler.request(context).handle((result, error) -> {
                        require(error != null, "Actual filesystem write rejection reaches the checkpoint callback");
                        require(context.storageHealth() == ServerContext.StorageHealth.DEGRADED, "Write error degrades storage");
                        require(context.loadedIndex().find(VIEWER).orElseThrow().isAiDisabled(), "Write error freezes authorized AI");
                        Map<String, Object> report = snapshot(context);
                        report.put("passed", true);
                        report.put("writeFailure", String.valueOf(error));
                        report.put("injection", "read-only temporary snapshot causes the filesystem WRITE open to fail");
                        writeReport(report);
                        finishNormally();
                        return null;
                    });
                }
                return context.parrotService().rename(UUID.randomUUID(), ADMIN, VIEWER,
                        DisplayName.of("Committed after backup")).thenCompose(renamed -> {
                    require(renamed.succeeded(), "Second committed mutation");
                    writeWitness(context.registry().find(VIEWER).orElseThrow(), null, "second mutation committed");
                    armed = SCENARIO.equals("DURING_CHECKPOINT") && MODE.equals("crash");
                    return CheckpointScheduler.request(context).thenApply(second -> null);
                });
            });
        }).whenComplete((ignored, error) -> {
            if (error != null) fail(error);
            else if (!finished) {
                if (MODE.equals("crash") && !SCENARIO.equals("DISK_WRITE_FAILURE"))
                    fail(new AssertionError("Expected abrupt gate was not reached: " + SCENARIO));
                else {
                    server.save(false, true, true);
                    Map<String, Object> report = snapshot(context);
                    report.put("passed", true);
                    report.put("fixturePrepared", true);
                    writeReport(report);
                    finishNormally();
                }
            }
        });
    }

    private static void connectFixturePlayer() {
        ConnectedClientData data = ConnectedClientData.createDefault(new GameProfile(PLAYER, "CrashFixture"), false);
        ServerPlayerEntity player = new ServerPlayerEntity(server, server.getOverworld(), data.gameProfile(), data.syncedOptions()) {
            @Override public boolean isSpectator() { return false; }
            @Override public boolean isCreative() { return true; }
        };
        ClientConnection connection = new ClientConnection(NetworkSide.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerManager().onPlayerConnect(connection, player, data);
        player.refreshPositionAndAngles(128.5, 64, 128.5, 0, 0);
    }

    public static void beforeAppend(JournalEntryDto entry) {
        if (!armed || !entry.viewerId().equals(VIEWER.value())) return;
        if (SCENARIO.equals("BEFORE_PREPARE") && entry.phase() == JournalEntryDto.Phase.PREPARE)
            halt(entry, "before PREPARE write");
        if (SCENARIO.equals("AFTER_SPAWN_BEFORE_COMMIT") && entry.phase() == JournalEntryDto.Phase.COMMIT
                && entry.record() != null) {
            // Persist the actual prepared Minecraft entity, so restart must reject its marker.
            server.submit(() -> {
                require(countPhysical() == 1, "Prepared entity exists before COMMIT");
                server.save(false, true, true);
                return true;
            }).join();
            halt(entry, "spawned AI-disabled entity saved, COMMIT not appended");
        }
    }

    public static void afterAppend(JournalEntryDto entry) {
        if (!armed || !entry.viewerId().equals(VIEWER.value())) return;
        if (SCENARIO.equals("AFTER_PREPARE") && entry.phase() == JournalEntryDto.Phase.PREPARE)
            halt(entry, "PREPARE forced to disk, no spawn");
        if (SCENARIO.equals("AFTER_COMMIT_BEFORE_WORLD_SAVE") && entry.phase() == JournalEntryDto.Phase.COMMIT
                && entry.record() != null) halt(entry, "COMMIT forced to disk, no subsequent world save");
        if (SCENARIO.equals("AFTER_REMOVE_COMMIT_BEFORE_DISCARD") && entry.tombstone() != null)
            halt(entry, "tombstone forced to disk, saved live entity not yet discarded");
    }

    public static void beforeSnapshotWrite(Path file) throws IOException {
        if (armed && SCENARIO.equals("DISK_WRITE_FAILURE") && file.getFileName().toString().startsWith("registry")) {
            require(file.toFile().setReadOnly(), "OS read-only attribute was set on the newly-created snapshot temp file");
        }
    }

    public static void afterSnapshotForce(Path file, String content) {
        if (armed && SCENARIO.equals("DURING_CHECKPOINT") && file.getFileName().toString().startsWith("registry")) {
            JsonObject witness = readWitness();
            witness.addProperty("gate", "temporary snapshot forced, before backup/copy/atomic rename");
            witness.addProperty("temporarySnapshot", file.getFileName().toString());
            writeJson(Path.of("crash-witness.json"), witness);
            Runtime.getRuntime().halt(77);
        }
    }

    private static void halt(JournalEntryDto entry, String gate) {
        writeWitness(null, entry, gate);
        System.out.println("VIEWERPARROTS_PROCESS_CRASH " + SCENARIO + " " + gate);
        System.out.flush();
        Runtime.getRuntime().halt(77);
    }

    private static void verifyRestart() {
        try {
            ServerContext context = ServerContext.current().orElseThrow();
            JsonObject witness = readWitness();
            boolean degradedExpected = SCENARIO.equals("CORRUPT_JOURNAL_MIDDLE") || SCENARIO.equals("READ_ONLY_STORAGE");
            require(context.storageHealth() == (degradedExpected ? ServerContext.StorageHealth.DEGRADED : ServerContext.StorageHealth.READY),
                    "Restart has expected explicit storage health");
            int physical = countPhysical();
            int authorized = countAuthorized(context);
            System.out.println("VIEWERPARROTS_RESTART_STATE " + snapshot(context) + " physical=" + physical
                    + " authorized=" + authorized + " loadedIndex=" + context.loadedIndex().isLoaded(VIEWER)
                    + " loadedFromWorld=" + loadedFromWorld + " entities=" + physicalEntities().stream().map(parrot ->
                    parrot.getUuid() + ":aiDisabled=" + parrot.isAiDisabled() + ":awaiting=" + parrot.isAwaitingAuthorization()).toList());
            require(authorized <= 1, "Never two authorized physical instances");
            if (degradedExpected) {
                require(authorized == 0, "Corrupt/read-only storage authorizes no entity");
                for (ViewerParrotEntity parrot : physicalEntities()) require(parrot.isAiDisabled(), "Degraded storage freezes loaded saved entity");
                CommandResult rejected = context.parrotService().create(UUID.randomUUID(), ADMIN,
                        ViewerId.of("must-be-blocked"), DisplayName.of("Blocked")).join();
                require(rejected.code() == ErrorCode.STORAGE_UNAVAILABLE, "Mutation returns explicit STORAGE_UNAVAILABLE");
                require(context.storageFaultReason().filter(reason -> !reason.isBlank()).isPresent(), "Degraded reason is diagnostic");
            } else if (List.of("BEFORE_PREPARE", "AFTER_PREPARE", "AFTER_SPAWN_BEFORE_COMMIT", "AFTER_REMOVE_COMMIT_BEFORE_DISCARD").contains(SCENARIO)) {
                require(!context.registry().contains(VIEWER), "Uncommitted/removed identity cannot become registered");
                require(physical == 0 && authorized == 0, "Prepared/tombstoned saved entity is discarded on load");
                if (SCENARIO.equals("AFTER_REMOVE_COMMIT_BEFORE_DISCARD"))
                    require(context.registry().tombstonesFor(VIEWER).stream().anyMatch(tombstone ->
                            tombstone.incarnationId().toString().equals(witness.get("incarnationId").getAsString())), "Exact durable tombstone replays");
                if (SCENARIO.equals("AFTER_SPAWN_BEFORE_COMMIT") || SCENARIO.equals("AFTER_REMOVE_COMMIT_BEFORE_DISCARD"))
                    require(loadedFromWorld.contains(UUID.fromString(witness.get("entityUuid").getAsString())),
                            "The persisted orphan/tombstoned UUID actually loaded from the Minecraft world before being discarded");
            } else {
                ViewerParrotRecord record = context.registry().find(VIEWER).orElseThrow();
                require(record.incarnationId().toString().equals(witness.get("incarnationId").getAsString()), "Committed incarnation survives process restart");
                require(record.entityUuid().toString().equals(witness.get("entityUuid").getAsString()), "Committed entity UUID survives process restart");
                require(record.generation() == witness.get("generation").getAsLong(), "Committed generation survives process restart");
                if (SCENARIO.equals("AFTER_COMMIT_BEFORE_WORLD_SAVE")) {
                    require(physical == 0 && authorized == 0, "Unsaved physical entity is absent");
                    require(record.physicalStatus() == PhysicalStatus.MISSING, "Durable identity becomes explicit MISSING after bounded recovery");
                } else {
                    require(physical == 1 && authorized == 1, "Saved committed entity loads and binds exactly once");
                    require(record.viewer().displayName().value().equals(witness.get("displayName").getAsString()), "Latest committed mutation replays");
                }
            }
            Map<String, Object> report = snapshot(context);
            report.put("passed", true);
            report.put("witness", witness);
            report.put("restartObservedTicks", verifyTicks);
            report.put("physicalEntities", physical);
            report.put("authorizedEntities", authorized);
            report.put("entityUuidsLoadedFromWorld", loadedFromWorld.stream().map(UUID::toString).toList());
            report.put("entityUnloads", List.copyOf(unloadedFromWorld));
            writeReport(report);
            finishNormally();
        } catch (Throwable failure) { fail(failure); }
    }

    private static void verifySmoke() {
        try {
            ServerContext context = ServerContext.current().orElseThrow();
            require(context.allowsMutations(), "Actual dedicated server completes startup with READY storage");
            Map<String, Object> report = snapshot(context);
            report.put("passed", true);
            report.put("startupSmoke", true);
            writeReport(report);
            finishNormally();
        } catch (Throwable failure) { fail(failure); }
    }

    private static List<ViewerParrotEntity> physicalEntities() {
        List<ViewerParrotEntity> result = new ArrayList<>();
        for (ServerWorld world : server.getWorlds()) for (Entity entity : world.iterateEntities())
            if (entity instanceof ViewerParrotEntity parrot && !parrot.isRemoved()
                    && parrot.marker().map(marker -> marker.viewerId().equals(VIEWER)).orElse(false)) result.add(parrot);
        return result;
    }

    private static int countPhysical() { return physicalEntities().size(); }
    private static int countAuthorized(ServerContext context) {
        return (int) physicalEntities().stream().filter(parrot -> !parrot.isAiDisabled() && !parrot.isAwaitingAuthorization()
                && context.registry().find(VIEWER).filter(record -> parrot.getUuid().equals(record.entityUuid())
                && parrot.marker().map(marker -> marker.incarnationId().equals(record.incarnationId())
                && marker.generation() == record.generation()).orElse(false)).isPresent()).count();
    }

    private static Map<String, Object> snapshot(ServerContext context) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scenario", SCENARIO);
        report.put("mode", MODE);
        report.put("at", Instant.now().toString());
        report.put("java", System.getProperty("java.runtime.version"));
        report.put("world", server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().toString());
        report.put("storageHealth", context.storageHealth().name());
        report.put("storageFault", context.storageFaultReason().orElse(null));
        report.put("registryRecords", context.registry().size());
        report.put("tombstones", context.registry().tombstones().size());
        report.put("lifecycle", context.lifecycle(VIEWER).name());
        report.put("forcedChunks", server.getOverworld().getForcedChunks().longStream().mapToObj(Long::toString).toList());
        context.registry().find(VIEWER).ifPresent(record -> {
            report.put("incarnationId", record.incarnationId().toString());
            report.put("entityUuid", String.valueOf(record.entityUuid()));
            report.put("generation", record.generation());
            report.put("physicalStatus", record.physicalStatus().name());
            report.put("displayName", record.viewer().displayName().value());
            report.put("lastKnownLocation", String.valueOf(record.lastKnownLocation()));
        });
        return report;
    }

    private static void writeWitness(ViewerParrotRecord record, JournalEntryDto entry, String gate) {
        JsonObject witness = entry != null && entry.tombstone() != null && Files.exists(Path.of("crash-witness.json"))
                ? readWitness() : new JsonObject();
        witness.addProperty("scenario", SCENARIO);
        witness.addProperty("gate", gate);
        witness.addProperty("at", Instant.now().toString());
        if (entry != null) {
            witness.add("journalEntry", JsonParser.parseString(JournalCodec.write(entry)));
            if (entry.record() != null) {
                witness.addProperty("incarnationId", entry.incarnationId().toString());
                witness.addProperty("entityUuid", entry.record().entityUuid().toString());
                witness.addProperty("generation", entry.generation());
                witness.addProperty("displayName", entry.record().viewer().displayName());
            }
        }
        if (record != null) {
            witness.addProperty("incarnationId", record.incarnationId().toString());
            witness.addProperty("entityUuid", record.entityUuid().toString());
            witness.addProperty("generation", record.generation());
            witness.addProperty("displayName", record.viewer().displayName().value());
        }
        writeJson(Path.of("crash-witness.json"), witness);
    }

    private static JsonObject readWitness() {
        try { return JSON.fromJson(Files.readString(Path.of("crash-witness.json")), JsonObject.class); }
        catch (IOException error) { throw new IllegalStateException("Missing crash witness", error); }
    }
    private static void writeReport(Map<String, Object> report) { writeJson(Path.of("crash-report.json"), report); }
    private static void writeJson(Path path, Object value) {
        try {
            Files.writeString(path, JSON.toJson(value));
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
        } catch (IOException failure) { throw new IllegalStateException("Cannot persist process evidence", failure); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void finishNormally() {
        finished = true;
        armed = false;
        server.stop(false);
    }
    private static void fail(Throwable failure) {
        if (finished) return;
        finished = true;
        failure.printStackTrace();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scenario", SCENARIO);
        report.put("mode", MODE);
        report.put("passed", false);
        report.put("failure", String.valueOf(failure));
        if (server != null) ServerContext.current().ifPresent(context -> {
            report.put("state", snapshot(context));
            report.put("physicalEntities", countPhysical());
            report.put("authorizedEntities", countAuthorized(context));
            report.put("loadedIndex", context.loadedIndex().isLoaded(VIEWER));
            report.put("entityUuidsLoadedFromWorld", loadedFromWorld.stream().map(UUID::toString).toList());
            report.put("entityUnloads", List.copyOf(unloadedFromWorld));
            report.put("entities", physicalEntities().stream().map(parrot -> parrot.getUuid()
                    + ":aiDisabled=" + parrot.isAiDisabled() + ":awaiting=" + parrot.isAwaitingAuthorization()).toList());
        });
        try { writeReport(report); } finally { Runtime.getRuntime().halt(78); }
    }
}
