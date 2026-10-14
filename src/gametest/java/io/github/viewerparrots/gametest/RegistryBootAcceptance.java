package io.github.viewerparrots.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import io.github.viewerparrots.bootstrap.CheckpointScheduler;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import io.github.viewerparrots.domain.PhysicalStatus;
import io.github.viewerparrots.storage.SnapshotCodec;
import io.github.viewerparrots.storage.DtoMapper;
import io.github.viewerparrots.storage.dto.SnapshotDto;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Optional test-only instrumentation of actual registry bootstrap and bounded checkpoint ticks. */
public final class RegistryBootAcceptance implements ModInitializer {
    private static final boolean ENABLED = Boolean.getBoolean("viewerparrots.qa.registry.profile");
    private static final boolean CHECKPOINT = Boolean.parseBoolean(System.getProperty("viewerparrots.qa.registry.checkpoint", "true"));
    private static volatile long started;
    private static volatile long elapsed;
    private static volatile long heapBefore;
    private static volatile int appliedRecords;
    private static volatile String loadThread;
    private static volatile String applyThread;
    private static volatile boolean appliedOnServerThread;
    private static boolean profiled;
    private static boolean completed;
    private static boolean checkpointActive;
    private static boolean captureOnServerThread = true;
    private static int ticks;
    private static int captureCalls;
    private static int maximumCopiesPerCall;
    private static long checkpointStarted;
    private static long checkpointStartTick;
    private static long captureFinishTick;
    private static final Map<Long, Integer> copiesByTick = new LinkedHashMap<>();
    private static final Map<String, Object> report = new LinkedHashMap<>();

    @Override
    public void onInitialize() {
        if (!ENABLED) return;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (completed || profiled || ++ticks < 20) return;
            if (ServerContext.current().filter(context -> context.storageHealth() != ServerContext.StorageHealth.LOADING).isEmpty()) return;
            profiled = true;
            try {
                ServerContext context = ServerContext.current().orElseThrow();
                // Load DTO locals have left the call stack; checkpoint allocations have not started.
                long heapAfter = retainedHeap();
                long delta = heapAfter - heapBefore;
                int entities = entityCount(server);
                long inactiveRecords = context.registry().allSorted().stream()
                        .filter(record -> record.physicalStatus() == PhysicalStatus.DEAD && record.expectedEntityUuid().isEmpty()).count();
                Map<String, SnapshotDto.ParrotRecordDto> sourceRecords = new TreeMap<>();
                for (var record : context.registry().allSorted()) sourceRecords.put(record.viewerId().value(), DtoMapper.fromDomain(record));
                SnapshotDto source = new SnapshotDto(SnapshotDto.CURRENT_SCHEMA_VERSION, context.registry().appliedJournalSequence(),
                        context.registry().streamerPlayerUuid().orElse(null), sourceRecords,
                        context.registry().tombstones().stream().map(DtoMapper::fromDomain).toList());
                String sourcePayloadSha256 = payloadSha256(source);
                boolean passed = started > 0 && elapsed > 0 && elapsed < 3_000_000_000L
                        && appliedRecords == 10000 && context.registry().size() == 10000
                        && delta < 64L * 1048576 && context.allowsMutations()
                        && "viewerparrots-storage".equals(loadThread) && appliedOnServerThread
                        && context.loadedIndex().size() == 0 && context.runtimes().isEmpty() && entities == 0
                        && inactiveRecords == 10000 && context.registry().reservedSlots() == 0;
                report.put("passed", passed);
                report.put("at", Instant.now().toString());
                report.put("scope", "actual dedicated-server repository.load HEAD through registry.replaceAll RETURN");
                report.put("loadSeconds", elapsed / 1_000_000_000.0);
                report.put("loadThread", loadThread);
                report.put("applyThread", applyThread);
                report.put("fileIoOnDedicatedWriterThread", "viewerparrots-storage".equals(loadThread));
                report.put("registryAppliedOnServerThread", appliedOnServerThread);
                report.put("heapBeforeBytes", heapBefore);
                report.put("heapAfterBytes", heapAfter);
                report.put("retainedHeapDeltaBytes", delta);
                report.put("retainedHeapDeltaMiB", delta / 1048576.0);
                report.put("heapMeasurement", "System.gc before timer and after load DTO locals are released, before checkpoint; whole-JVM retained heap delta");
                report.put("appliedRecords", appliedRecords);
                report.put("registryRecords", context.registry().size());
                report.put("inactiveRecordsWithoutEntityUuid", inactiveRecords);
                report.put("registryPayloadSha256", sourcePayloadSha256);
                report.put("payloadScope", "all fields of every parrot, all tombstones and streamerPlayerUuid; checkpoint sequence recorded separately");
                report.put("registryAppliedJournalSequence", context.registry().appliedJournalSequence());
                report.put("storageHealth", context.storageHealth().name());
                report.put("loadedEntityReferences", context.loadedIndex().size());
                report.put("runtimeEntries", context.runtimes().size());
                report.put("viewerParrotEntities", entities);
                report.put("occupiedCapacitySlots", context.registry().reservedSlots());
                report.put("java", System.getProperty("java.runtime.version"));
                report.put("maxHeapMiB", Runtime.getRuntime().maxMemory() / 1048576.0);
                report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
                report.put("checkpointRequested", CHECKPOINT);
                write();
                if (!passed) Runtime.getRuntime().halt(79);
                if (!CHECKPOINT) { finish(server); return; }
                checkpointActive = true;
                checkpointStarted = System.nanoTime();
                checkpointStartTick = context.clock().currentTick();
                CheckpointScheduler.request(context).whenComplete((result, failure) -> {
                    if (failure != null) { fail(failure); return; }
                    checkpointActive = false;
                    long finishedTick = context.clock().currentTick();
                    Path primary = server.getSavePath(WorldSavePath.ROOT).resolve("data/viewerparrots/registry.json");
                    context.writer().submit(() -> {
                        byte[] bytes = Files.readAllBytes(primary);
                        var snapshot = SnapshotCodec.read(new String(bytes, StandardCharsets.UTF_8));
                        return Map.of("records", snapshot.parrots().size(), "tombstones", snapshot.tombstones().size(),
                                "sequence", snapshot.checkpointSequence(), "bytes", bytes.length,
                                "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                                "payloadSha256", payloadSha256(snapshot),
                                "readThread", Thread.currentThread().getName());
                    }).whenComplete((snapshot, readFailure) -> {
                        if (readFailure != null) { fail(readFailure); return; }
                        int maximumPerTick = copiesByTick.values().stream().mapToInt(Integer::intValue).max().orElse(0);
                        int copied = copiesByTick.values().stream().mapToInt(Integer::intValue).sum();
                        boolean checkpointPassed = captureOnServerThread && captureCalls == 40 && copied == 10000
                                && copiesByTick.size() == 40 && maximumCopiesPerCall <= 256 && maximumPerTick <= 256
                                && snapshot.get("records").equals(10000) && snapshot.get("tombstones").equals(0)
                                && sourcePayloadSha256.equals(snapshot.get("payloadSha256"))
                                && "viewerparrots-storage".equals(snapshot.get("readThread"))
                                && context.allowsMutations() && context.registry().size() == 10000
                                && context.loadedIndex().size() == 0 && context.runtimes().isEmpty() && entityCount(server) == 0;
                        Map<String, Object> checkpoint = new LinkedHashMap<>();
                        checkpoint.put("passed", checkpointPassed);
                        checkpoint.put("seconds", (System.nanoTime() - checkpointStarted) / 1_000_000_000.0);
                        checkpoint.put("startTick", checkpointStartTick);
                        checkpoint.put("captureFinishedTick", captureFinishTick);
                        checkpoint.put("durableFinishedTick", finishedTick);
                        checkpoint.put("captureCalls", captureCalls);
                        checkpoint.put("copyTicks", copiesByTick.size());
                        checkpoint.put("copiedRecords", copied);
                        checkpoint.put("maximumCopiesPerCall", maximumCopiesPerCall);
                        checkpoint.put("maximumCopiesPerTick", maximumPerTick);
                        checkpoint.put("captureOnServerThread", captureOnServerThread);
                        checkpoint.put("copiesByTick", copiesByTick);
                        checkpoint.put("primaryValidated", snapshot);
                        checkpoint.put("atomicReplace", result.atomicReplace());
                        report.put("checkpoint", checkpoint);
                        report.put("passed", checkpointPassed);
                        write();
                        if (!checkpointPassed) Runtime.getRuntime().halt(79);
                        finish(server);
                    });
                });
            } catch (Throwable failure) { fail(failure); }
        });
    }

    public static void beforeRepositoryLoad() {
        if (!ENABLED || started != 0) return;
        heapBefore = retainedHeap();
        loadThread = Thread.currentThread().getName();
        started = System.nanoTime();
    }

    public static void afterRegistryApply(int records) {
        if (!ENABLED || started == 0 || elapsed != 0) return;
        elapsed = System.nanoTime() - started;
        appliedRecords = records;
        applyThread = Thread.currentThread().getName();
        appliedOnServerThread = ServerContext.current().map(context -> context.server().isOnThread()).orElse(false);
    }

    public static void afterCaptureAdvance(int copied, boolean finished) {
        if (!ENABLED || !checkpointActive) return;
        ServerContext context = ServerContext.current().orElseThrow();
        captureOnServerThread &= context.server().isOnThread();
        long tick = context.clock().currentTick();
        captureCalls++;
        maximumCopiesPerCall = Math.max(maximumCopiesPerCall, copied);
        copiesByTick.merge(tick, copied, Integer::sum);
        if (finished) captureFinishTick = tick;
    }

    private static int entityCount(MinecraftServer server) {
        int count = 0;
        for (ServerWorld world : server.getWorlds()) for (Entity entity : world.iterateEntities())
            if (entity instanceof ViewerParrotEntity) count++;
        return count;
    }

    private static void finish(MinecraftServer server) { completed = true; server.stop(false); }

    private static void fail(Throwable failure) {
        report.put("passed", false);
        report.put("failure", String.valueOf(failure));
        write();
        failure.printStackTrace();
        Runtime.getRuntime().halt(79);
    }

    private static long retainedHeap() {
        System.gc();
        System.gc();
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    private static String payloadSha256(SnapshotDto snapshot) throws Exception {
        var payload = SnapshotCodec.toJson(snapshot);
        payload.remove("schemaVersion");
        payload.remove("checkpointSequence");
        if (!payload.has("streamerPlayerUuid")) payload.add("streamerPlayerUuid", JsonNull.INSTANCE);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical(payload).getBytes(StandardCharsets.UTF_8)));
    }

    private static String canonical(JsonElement value) {
        if (value.isJsonObject()) return value.getAsJsonObject().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new com.google.gson.JsonPrimitive(entry.getKey()) + ":" + canonical(entry.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
        if (value.isJsonArray()) {
            var values = new java.util.ArrayList<String>();
            for (JsonElement element : value.getAsJsonArray()) values.add(canonical(element));
            return String.join(",", values).transform(text -> "[" + text + "]");
        }
        return value.toString();
    }

    private static void write() {
        try { Files.writeString(Path.of("registry-bootstrap-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
        catch (Exception failure) { throw new IllegalStateException("Cannot save registry bootstrap evidence", failure); }
    }
}
