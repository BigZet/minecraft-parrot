package io.github.viewerparrots.gametest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.github.viewerparrots.api.*;
import io.github.viewerparrots.bootstrap.CheckpointScheduler;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.domain.*;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import io.github.viewerparrots.lifecycle.EntityRecoveryService;
import io.github.viewerparrots.storage.DtoMapper;
import io.github.viewerparrots.storage.SnapshotCodec;
import io.github.viewerparrots.storage.dto.SnapshotDto;
import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtString;
import net.minecraft.nbt.scanner.NbtScanQuery;
import net.minecraft.nbt.scanner.SelectiveNbtCollector;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTask;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.*;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.GameRules;
import net.minecraft.world.chunk.ChunkStatus;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** AC13/14: public mutations, real saved chunks, and actual dedicated JVM restart. */
public final class DiskLifecycleAcceptance implements ModInitializer {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final ViewerId ID = ViewerId.of("disk-lifecycle");
    private static final ActorContext ADMIN = ActorContext.console(4);
    private static final UUID PLAYER = UUID.fromString("316a9c2b-cbe6-4898-a97c-e14edf186501");
    private static final UUID TICKET_KEY = UUID.fromString("311a9c2b-cbe6-4898-a97c-e14edf186502");
    private static final ChunkTicketType<UUID> FIXTURE = ChunkTicketType.create("viewerparrots_disk_fixture", Comparator.<UUID>naturalOrder());
    private static final ChunkPos A = new ChunkPos(8, 8), B = new ChunkPos(256, 256);
    private static final String SCENARIO = System.getProperty("viewerparrots.qa.disk.scenario", "");
    private static final String MODE = System.getProperty("viewerparrots.qa.disk.mode", "run");
    private MinecraftServer server;
    private ServerContext context;
    private ServerWorld world;
    private boolean begun, finished, observing;
    private long startedTick, deadline;
    private String stage = "startup";
    private BooleanSupplier ready;
    private Runnable next;
    private ViewerParrotRecord original, replacement;
    private ViewerParrotEntity originalBody;
    private final Map<String, Object> proof = new LinkedHashMap<>();
    private final List<Map<String, Object>> diskLoads = new ArrayList<>();

    @Override public void onInitialize() {
        if (SCENARIO.isEmpty()) return;
        ServerTickEvents.END_SERVER_TICK.register(current -> {
            if (finished) return;
            try {
                if (!begun) {
                    ServerContext active = ServerContext.current().orElse(null);
                    if (active == null || active.storageHealth() == ServerContext.StorageHealth.LOADING) return;
                    require(active.allowsMutations(), "Storage READY before fixture mutation");
                    begun = true; server = current; context = active; world = current.getOverworld();
                    startedTick = context.clock().currentTick(); start();
                }
                if (context.clock().currentTick() - startedTick > 2400) throw new AssertionError("Global fixture timeout: " + stage);
                if (ready != null) {
                    if (ready.getAsBoolean()) {
                        Runnable action = next; ready = null; next = null; action.run();
                    } else if (context.clock().currentTick() > deadline) throw new AssertionError("Stage timeout: " + stage + " resources=" + resources());
                }
            } catch (Throwable failure) { fail(failure); }
        });
    }

    private void start() throws Exception {
        proof.put("scenario", SCENARIO); proof.put("mode", MODE); proof.put("pid", ProcessHandle.current().pid());
        proof.put("source", "actual public create/move/world-save; no synthetic old NBT or old entity spawn");
        proof.put("chunkA", List.of(A.x, A.z)); proof.put("chunkB", List.of(B.x, B.z));
        world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, server);
        ServerEntityEvents.ENTITY_LOAD.register((entity, loadedWorld) -> {
            if (!observing || !(entity instanceof ViewerParrotEntity bird) || original == null || !bird.getUuid().equals(original.entityUuid())) return;
            try {
                var marker = bird.marker().orElseThrow();
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("uuid", bird.getUuid().toString()); event.put("incarnation", marker.incarnationId().toString());
                event.put("generation", marker.generation()); event.put("removed", bird.isRemoved());
                event.put("aiDisabled", bird.isAiDisabled()); event.put("age", bird.age);
                event.put("position", List.of(bird.getX(), bird.getY(), bird.getZ()));
                event.put("matchesOriginal", marker.matches(original)); diskLoads.add(event);
            } catch (Throwable failure) { fail(failure); }
        });
        if (MODE.equals("verify")) {
            JsonObject witness = JsonParser.parseString(Files.readString(Path.of("disk-lifecycle-witness.json"))).getAsJsonObject();
            SnapshotDto dto = SnapshotCodec.read(witness.get("durableSnapshotA").toString());
            original = DtoMapper.toDomain(ID.value(), dto.parrots().get(ID.value()));
            require(context.registry().find(ID).orElseThrow().equals(original), "Restart preserves complete checkpoint record at A");
            require(!world.isChunkLoaded(B.toLong()) && world.getEntity(original.entityUuid()) == null, "Saved old chunk B remains actually unloaded on restart");
            proof.put("restartSnapshotBeforeRecovery", snapshot());
            prepareA();
            await("player/chunk A ready", () -> world.isChunkLoaded(A.toLong()), 100, this::startRecovery);
        } else {
            prepareA();
            await("player/chunk A ready", () -> world.isChunkLoaded(A.toLong()), 100, () -> {
                complete(context.streamerService().assign(UUID.randomUUID(), ADMIN, PLAYER), assigned -> {
                    require(assigned.succeeded(), "Public durable streamer assignment");
                    complete(context.parrotService().create(UUID.randomUUID(), ADMIN, ID, DisplayName.of("Дисковый попугай")), created -> {
                        require(created.succeeded(), "Public create commits actual entity");
                        complete(context.parrotService().setTrait(UUID.randomUUID(), ADMIN, ID, ParrotTrait.NORMAL), trait -> {
                            require(trait.succeeded(), "Public trait commits");
                            UUID command = UUID.randomUUID();
                            CommandResult result = context.commandService().submit(command, ADMIN, ParrotAction.SIT, ID);
                            require(result.status() == CommandStatus.ACCEPTED || result.succeeded(), "Public SIT accepted");
                            complete(context.commandService().completion(command), sitting -> {
                                require(sitting.succeeded(), "Public SIT reaches durable terminal success");
                                original = context.registry().find(ID).orElseThrow();
                                originalBody = context.loadedIndex().find(ID).orElseThrow();
                                require(original.desiredBehavior() == DesiredBehavior.SITTING, "Seed durable SITTING");
                                complete(CheckpointScheduler.request(context), saved -> moveToB());
                            });
                        });
                    });
                });
            });
        }
    }

    private void prepareA() {
        world.setChunkForced(A.x, A.z, true); world.getChunk(A.x, A.z, ChunkStatus.FULL, true); platform(A);
        ConnectedClientData data = ConnectedClientData.createDefault(new GameProfile(PLAYER, "DiskFixture"), false);
        ServerPlayerEntity player = new ServerPlayerEntity(server, world, data.gameProfile(), data.syncedOptions()) {
            @Override public boolean isSpectator() { return false; }
            @Override public boolean isCreative() { return true; }
        };
        ClientConnection connection = new ClientConnection(NetworkSide.SERVERBOUND); new EmbeddedChannel(connection);
        server.getPlayerManager().onPlayerConnect(connection, player, data);
        player.refreshPositionAndAngles(136.5, 64, 136.5, 0, 0);
    }

    private void platform(ChunkPos pos) {
        for (int x = pos.getStartX(); x <= pos.getEndX(); x++) for (int z = pos.getStartZ(); z <= pos.getEndZ(); z++)
            world.setBlockState(new BlockPos(x, 63, z), Blocks.STONE.getDefaultState());
    }

    private void moveToB() {
        addB(); world.getChunk(B.x, B.z, ChunkStatus.FULL, true); platform(B);
        await("B entity chunk loaded", () -> world.isChunkLoaded(B.toLong()), 200, () -> {
            originalBody.setAiDisabled(true); originalBody.setNoGravity(true);
            originalBody.refreshPositionAndAngles(4104.5, 64, 4104.5, 0, 0);
            await("body moved to physical B", () -> originalBody.getChunkPos().equals(B), 40, () -> {
                // Allow vanilla's entity manager to move sections before its synchronous flush.
                long after = context.clock().currentTick() + 10;
                await("entity section migration settled", () -> context.clock().currentTick() >= after, 40, this::saveB);
            });
        });
    }

    private void saveB() {
        server.save(false, true, true);
        proof.put("seedOriginal", recordJson(original)); proof.put("savedBodyPosition", List.of(originalBody.getX(), originalBody.getY(), originalBody.getZ()));
        proof.put("savedBodyUuid", originalBody.getUuid().toString());
        require(originalBody.getUuid().equals(original.entityUuid()) && originalBody.marker().orElseThrow().matches(original), "Real saved body has exact original identity");
        scanExistingB(() -> {
            if (SCENARIO.equals("HINT_LOSS")) {
                try {
                    JsonObject durable = JsonParser.parseString(Files.readString(registryFile())).getAsJsonObject();
                    ViewerParrotRecord persisted = DtoMapper.toDomain(ID.value(), SnapshotCodec.read(durable.toString()).parrots().get(ID.value()));
                    require(persisted.equals(original), "Primary checkpoint still has original full record");
                    require(new ChunkPos(BlockPos.ofFloored(persisted.lastKnownLocation().x(), persisted.lastKnownLocation().y(), persisted.lastKnownLocation().z())).equals(A), "Durable location hint is A before halt, actual saved body is B");
                    proof.put("durableSnapshotA", durable); proof.put("haltBeforeLocationCheckpoint", true); proof.put("passed", true);
                    writeForced(Path.of("disk-lifecycle-witness.json"), JSON.toJson(proof));
                    finished = true; System.out.println("DISK_LIFECYCLE_SEED_HALT " + JSON.toJson(proof)); Runtime.getRuntime().halt(77);
                } catch (Throwable failure) { fail(failure); }
            } else {
                releaseB(); await("original saved B actually unloaded", () -> !world.isChunkLoaded(B.toLong()) && world.getEntity(original.entityUuid()) == null
                                && !context.loadedIndex().isLoaded(ID) && !context.runtimes().containsKey(ID), 600, this::removeUnloaded);
            }
        });
    }

    private void removeUnloaded() {
        require(originalBody.isRemoved(), "Vanilla unload removes original Java body"); originalBody = null;
        require(context.lifecycle(ID) == LifecycleState.UNLOADED, "Public remove is triggered in exact UNLOADED lifecycle");
        require(context.registry().reservedSlots() == 1, "Saved unloaded original still owns capacity slot");
        proof.put("beforeRemoveLifecycle", context.lifecycle(ID).name()); proof.put("beforeRemoveResources", resources());
        proof.put("committedBeforeRemove", snapshot());
        complete(context.parrotService().remove(UUID.randomUUID(), ADMIN, ID), removed -> {
            require(removed.succeeded(), "Public remove commits while original chunk is unloaded");
            require(!context.registry().contains(ID) && context.lifecycle(ID) == LifecycleState.REMOVED, "Remove commits tombstone and deletes registration");
            require(context.registry().tombstonesFor(ID).stream().anyMatch(t -> t.incarnationId().equals(original.incarnationId()) && t.lastGeneration() == original.generation()), "Tombstone preserves exact incarnation and generation");
            require(context.registry().reservedSlots() == 0, "Remove releases saved unloaded slot");
            proof.put("afterRemoveSnapshot", snapshot()); loadOldSavedB();
        });
    }

    private void startRecovery() {
        require(context.registry().streamerPlayerUuid().orElseThrow().equals(PLAYER), "Durable streamer preserved");
        EntityRecoveryService.StartResult result = context.recoveryService().start(ID);
        proof.put("recoveryStart", result.name());
        require(result == EntityRecoveryService.StartResult.STARTED, "Public recovery starts only at saved hint A");
        await("recovery at A finishes MISSING", () -> context.recoveryService().activeJobs() == 0, 200, () -> {
            ViewerParrotRecord missing = context.registry().find(ID).orElseThrow();
            require(missing.physicalStatus() == PhysicalStatus.MISSING && missing.entityUuid().equals(original.entityUuid()), "Hint-loss recovery marks MISSING retaining original UUID");
            require(context.recoveryService().lastFailure(ID).orElseThrow() == ErrorCode.PARROT_ENTITY_MISSING, "Exact missing diagnosis");
            require(!world.isChunkLoaded(B.toLong()), "Recovery does not search or load distant B");
            proof.put("missingSnapshot", snapshot()); proof.put("recoveryFailure", ErrorCode.PARROT_ENTITY_MISSING.name());
            complete(context.parrotService().respawn(UUID.randomUUID(), ADMIN, ID), respawn -> {
                require(respawn.succeeded(), "Public respawn succeeds after actual recovery reports missing");
                replacement = context.registry().find(ID).orElseThrow();
                require(replacement.generation() == original.generation() + 1 && replacement.incarnationId().equals(original.incarnationId())
                        && !replacement.entityUuid().equals(original.entityUuid()), "Respawn increments generation and assigns new UUID preserving incarnation");
                require(replacement.variant() == original.variant() && replacement.trait() == original.trait()
                        && replacement.viewer().equals(original.viewer()), "Respawn preserves full viewer, trait and variant metadata");
                require(context.loadedIndex().find(ID).orElseThrow().getUuid().equals(replacement.entityUuid()), "Replacement is sole authorized bound body");
                proof.put("replacementBeforeOldLoad", snapshot()); loadOldSavedB();
            });
        });
    }

    private void scanExistingB(Runnable action) {
        SelectiveNbtCollector collector = new SelectiveNbtCollector(new NbtScanQuery(NbtString.TYPE, "Status"));
        complete(world.getChunkManager().getChunkIoWorker().scanChunk(B, collector), ignored -> {
            require(collector.getRoot() instanceof NbtCompound, "B is an existing saved chunk, no generated substitute");
            String status = ((NbtCompound) collector.getRoot()).getString("Status");
            require(status.equals("minecraft:full") || status.equals("full"), "Saved B has FULL chunk status");
            proof.put("existingDiskChunkStatus", status); action.run();
        });
    }

    private void loadOldSavedB() {
        require(!world.isChunkLoaded(B.toLong()), "Old physical B is unloaded immediately before disk load");
        scanExistingB(() -> {
            observing = true; addB(); world.getChunk(B.x, B.z, ChunkStatus.FULL, true);
            await("exact persisted old UUID observed on vanilla disk load", () -> !diskLoads.isEmpty(), 300, () -> {
                require(diskLoads.size() == 1, "Exactly one disk ENTITY_LOAD for original UUID");
                Map<String, Object> event = diskLoads.getFirst();
                require(Boolean.TRUE.equals(event.get("matchesOriginal")) && Boolean.TRUE.equals(event.get("removed"))
                        && Boolean.TRUE.equals(event.get("aiDisabled")) && ((Number) event.get("age")).intValue() == 0,
                        "Production validator rejects original saved identity immediately, before its first AI tick");
                require(world.getEntity(original.entityUuid()) == null && context.loadedIndex().findViewerId(original.entityUuid()).isEmpty(), "Stale UUID has no world/index binding");
                int expected = SCENARIO.equals("HINT_LOSS") ? 1 : 0;
                require(physicalCount() == expected && authorizedCount() == expected, "Exact physical and authorized counts after stale saved load");
                if (expected == 1) require(context.loadedIndex().find(ID).orElseThrow().getUuid().equals(replacement.entityUuid()), "Stale load cannot replace new generation binding");
                proof.put("diskLoadEvents", diskLoads); proof.put("afterDiskLoadSnapshot", snapshot());
                proof.put("physicalAfterStaleLoad", physicalCount()); proof.put("authorizedAfterStaleLoad", authorizedCount());
                releaseB(); await("saved B unload and all owned work drains", () -> !world.isChunkLoaded(B.toLong()) && quiescent(), 600, this::finish);
            });
        });
    }

    private void finish() {
        int expected = SCENARIO.equals("HINT_LOSS") ? 1 : 0;
        Map<String, Integer> counts = resources();
        require(counts.get("runtimes") == expected && counts.get("loadedIndex") == expected && counts.get("reverseIndex") == expected,
                "Only deliberate replacement may remain in runtime and both indexes");
        require(counts.get("fixtureTickets") == 0 && counts.get("recoveryTickets") == 0 && counts.get("recoveryJobs") == 0
                && counts.get("writerTasks") == 0 && counts.get("writerQueue") == 0 && counts.get("operations") == 0 && counts.get("pendingReservations") == 0,
                "All temporary tickets, recovery jobs, writes and operations return to zero");
        proof.put("finalResourcesBeforeCheckpoint", counts); proof.put("finalSnapshot", snapshot());
        complete(CheckpointScheduler.request(context), ignored -> {
            await("final checkpoint callback and queue drain", this::quiescent, 100, () -> {
                server.save(false, true, true); proof.put("finalResources", resources()); proof.put("passed", true);
                proof.put("ticks", context.clock().currentTick() - startedTick); proof.put("chunkBUnloadedAtEnd", !world.isChunkLoaded(B.toLong()));
                try { writeForced(Path.of("disk-lifecycle-report.json"), JSON.toJson(proof)); }
                catch (Throwable failure) { fail(failure); return; }
                finished = true; System.out.println("DISK_LIFECYCLE_PASS " + SCENARIO); server.stop(false);
            });
        });
    }

    private boolean quiescent() {
        return context.writer().pendingTasks() == 0 && context.writer().queueDepth() == 0 && context.parrotService().operationCount() == 0
                && context.registry().pendingReservations() == 0 && context.recoveryService().activeJobs() == 0;
    }
    private void addB() { world.getChunkManager().addTicket(FIXTURE, B, 0, TICKET_KEY); }
    private void releaseB() { world.getChunkManager().removeTicket(FIXTURE, B, 0, TICKET_KEY); }
    private void await(String name, BooleanSupplier condition, int ticks, Runnable action) {
        require(ready == null, "One fixture wait at a time"); stage = name; ready = condition; next = action; deadline = context.clock().currentTick() + ticks;
        System.out.println("DISK_LIFECYCLE_STAGE " + name);
    }
    private <T> void complete(CompletableFuture<T> future, Consumer<T> action) {
        future.whenComplete((value, error) -> server.send(new ServerTask(server.getTicks(), () -> {
            if (finished) return;
            try { if (error != null) throw new IllegalStateException("Async fixture operation: " + stage, error); action.accept(value); }
            catch (Throwable failure) { fail(failure); }
        })));
    }
    private JsonObject recordJson(ViewerParrotRecord record) {
        return SnapshotCodec.toJson(new SnapshotDto(1, 0, PLAYER, Map.of(ID.value(), DtoMapper.fromDomain(record)), List.of()));
    }
    private JsonObject snapshot() {
        Map<String, SnapshotDto.ParrotRecordDto> records = new TreeMap<>();
        context.registry().find(ID).ifPresent(record -> records.put(ID.value(), DtoMapper.fromDomain(record)));
        return SnapshotCodec.toJson(new SnapshotDto(1, context.registry().appliedJournalSequence(), PLAYER, records,
                context.registry().tombstonesFor(ID).stream().map(DtoMapper::fromDomain).toList()));
    }
    private Path registryFile() { return server.getSavePath(WorldSavePath.ROOT).resolve("data/viewerparrots/registry.json"); }
    private int physicalCount() {
        int count = 0; for (ServerWorld current : server.getWorlds()) for (Entity entity : current.iterateEntities())
            if (entity instanceof ViewerParrotEntity bird && !bird.isRemoved() && bird.marker().map(m -> m.viewerId().equals(ID)).orElse(false)) count++;
        return count;
    }
    private int authorizedCount() {
        return context.loadedIndex().find(ID).filter(b -> !b.isRemoved() && !b.isAiDisabled()
                && context.registry().find(ID).map(r -> b.marker().map(m -> m.matches(r)).orElse(false)).orElse(false)).isPresent() ? 1 : 0;
    }
    private Map<String, Integer> resources() {
        try {
            Field reverse = context.loadedIndex().getClass().getDeclaredField("viewerIdByEntityUuid"); reverse.setAccessible(true);
            Field recovery = EntityRecoveryService.class.getDeclaredField("RECOVERY_TICKET"); recovery.setAccessible(true);
            Map<String, Integer> values = new LinkedHashMap<>();
            values.put("runtimes", context.runtimes().size()); values.put("loadedIndex", context.loadedIndex().size());
            values.put("reverseIndex", ((Map<?, ?>) reverse.get(context.loadedIndex())).size());
            values.put("recoveryJobs", context.recoveryService().activeJobs()); values.put("recoveryTickets", tickets((ChunkTicketType<?>) recovery.get(null)));
            values.put("fixtureTickets", tickets(FIXTURE)); values.put("writerTasks", context.writer().pendingTasks());
            values.put("writerQueue", context.writer().queueDepth()); values.put("operations", context.parrotService().operationCount());
            values.put("pendingReservations", context.registry().pendingReservations()); return values;
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private int tickets(ChunkTicketType<?> type) throws ReflectiveOperationException {
        Field manager = ServerChunkManager.class.getDeclaredField("ticketManager"); manager.setAccessible(true);
        Field field = ChunkTicketManager.class.getDeclaredField("ticketsByPosition"); field.setAccessible(true); int count = 0;
        for (ServerWorld current : server.getWorlds()) for (Object bucket : ((Long2ObjectMap<?>) field.get(manager.get(current.getChunkManager()))).values())
            for (Object candidate : (Iterable<?>) bucket) if (((ChunkTicket<?>) candidate).getType() == type) count++;
        return count;
    }
    private void fail(Throwable failure) {
        if (finished) return; finished = true; failure.printStackTrace(); proof.put("passed", false); proof.put("failedStage", stage); proof.put("error", failure.toString());
        try { writeForced(Path.of("disk-lifecycle-report.json"), JSON.toJson(proof)); } catch (Throwable ignored) { ignored.printStackTrace(); }
        Runtime.getRuntime().halt(79);
    }
    private static void writeForced(Path file, String json) throws Exception {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(json); while (bytes.hasRemaining()) channel.write(bytes); channel.force(true);
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
