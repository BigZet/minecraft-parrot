package io.github.viewerparrots.gametest;

import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.ViewerParrotsMod;
import com.google.gson.GsonBuilder;
import io.github.viewerparrots.api.event.ParrotEvent;
import io.github.viewerparrots.domain.*;
import io.github.viewerparrots.lifecycle.EntityRecoveryService;
import io.github.viewerparrots.entity.ModEntities;
import io.github.viewerparrots.entity.ParrotMarker;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.block.Blocks;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.world.ChunkTicket;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;

import java.lang.reflect.Field;
import java.lang.ref.WeakReference;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Real ticket ownership and public recovery from saved, genuinely unloaded Minecraft chunks. */
public final class RecoveryTicketGameTests implements FabricGameTest {
    private static final ChunkTicketType<UUID> FIXTURE_TICKET =
            ChunkTicketType.create("viewerparrots_recovery_fixture", Comparator.<UUID>naturalOrder());

    @GameTest(batchId = "recovery-disk", templateName = EMPTY_STRUCTURE, tickLimit = 120000)
    public void savedUnloadedEntitiesRecoverThroughPublicJobsAndReleaseTickets(TestContext test) {
        new DiskScenario(test).start();
    }

    @GameTest(batchId = "recovery-ownership", templateName = EMPTY_STRUCTURE, tickLimit = 100)
    public void cancellingOneSameChunkJobRetainsTheOtherTicket(TestContext test) {
        ServerContext context = ServerContext.current().orElseThrow();
        if (context.storageHealth() == ServerContext.StorageHealth.LOADING) {
            test.waitAndRun(1, () -> cancellingOneSameChunkJobRetainsTheOtherTicket(test));
            return;
        }
        test.assertTrue(context.allowsMutations(), "Ticket ownership fixture waits for READY storage");
        EntityRecoveryService service = context.recoveryService();
        ViewerId first = ViewerId.of("ticket-first-" + UUID.randomUUID());
        ViewerId second = ViewerId.of("ticket-second-" + UUID.randomUUID());
        try {
            test.assertTrue(service.activeJobs() == 0, "Ownership fixture begins without unrelated recovery work");
            ChunkPos chunk = new ChunkPos(test.getAbsolutePos(BlockPos.ORIGIN));
            ViewerParrotRecord one = record(first, chunk, test.getWorld());
            ViewerParrotRecord two = record(second, chunk, test.getWorld());
            context.registry().put(one); context.registry().put(two);

            Class<?> jobClass = Class.forName(EntityRecoveryService.class.getName() + "$Job");
            var constructor = jobClass.getDeclaredConstructor(ServerWorld.class, ChunkPos.class, ViewerParrotRecord.class, long.class);
            constructor.setAccessible(true);
            Object firstJob = constructor.newInstance(test.getWorld(), chunk, one, context.clock().currentTick() + 100);
            Object secondJob = constructor.newInstance(test.getWorld(), chunk, two, context.clock().currentTick() + 100);
            Field keyField = jobClass.getDeclaredField("ticketKey"); keyField.setAccessible(true);
            Field addedField = jobClass.getDeclaredField("ticketAdded"); addedField.setAccessible(true);
            UUID firstKey = (UUID) keyField.get(firstJob);
            UUID secondKey = (UUID) keyField.get(secondJob);
            Field jobsField = EntityRecoveryService.class.getDeclaredField("jobs"); jobsField.setAccessible(true);
            @SuppressWarnings("unchecked") Map<ViewerId, Object> jobs = (Map<ViewerId, Object>) jobsField.get(service);
            jobs.put(first, firstJob); jobs.put(second, secondJob);
            Field typeField = EntityRecoveryService.class.getDeclaredField("RECOVERY_TICKET"); typeField.setAccessible(true);
            @SuppressWarnings("unchecked") ChunkTicketType<UUID> type = (ChunkTicketType<UUID>) typeField.get(null);

            // The fixture sets only the already-loaded phase. Cancellation itself uses the
            // production service's real Job and release method, and vanilla stores the tickets.
            test.getWorld().getChunkManager().addTicket(type, chunk, 0, firstKey);
            addedField.setBoolean(firstJob, true);
            test.getWorld().getChunkManager().addTicket(type, chunk, 0, secondKey);
            addedField.setBoolean(secondJob, true);
            test.assertTrue(ticketKeys(test.getWorld(), chunk, type).equals(Set.of(firstKey, secondKey)),
                    "Two same-chunk jobs own two distinct vanilla tickets");
            service.cancel(first);
            test.assertTrue(service.activeJobs() == 1 && service.isRunning(second), "Cancelling one job keeps the other active");
            test.assertTrue(ticketKeys(test.getWorld(), chunk, type).equals(Set.of(secondKey)),
                    "First job release must not remove the second job's chunk ticket");
            service.cancel(second);
            test.assertTrue(ticketKeys(test.getWorld(), chunk, type).isEmpty(), "Final job release removes every owned ticket");
            test.assertTrue(service.activeJobs() == 0, "All recovery Job references are released");
            test.complete();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot establish real ticket ownership fixture", failure);
        } finally {
            service.cancel(first); service.cancel(second);
            context.registry().remove(first, context.clock().now());
            context.registry().remove(second, context.clock().now());
        }
    }

    private static ViewerParrotRecord record(ViewerId id, ChunkPos chunk, ServerWorld world) {
        return ViewerParrotRecord.created(new Viewer(id, DisplayName.of("Ticket"), Instant.EPOCH), UUID.randomUUID(), UUID.randomUUID(),
                ParrotVariant.BLUE, ParrotTrait.NORMAL, new WorldLocation(world.getRegistryKey().getValue().toString(),
                        chunk.getStartX() + 8, 64, chunk.getStartZ() + 8), Instant.EPOCH);
    }

    private static final class DiskScenario {
        final TestContext test;
        final ServerContext context = ServerContext.current().orElseThrow();
        final EntityRecoveryService service = context.recoveryService();
        final UUID fixtureKey = UUID.randomUUID();
        final List<ViewerParrotRecord> records = new ArrayList<>();
        final List<ViewerParrotEntity> originals = new ArrayList<>();
        final Map<ViewerId, ViewerParrotEntity> recovered = new LinkedHashMap<>();
        final Map<ViewerId, WeakReference<ViewerParrotEntity>> previousObjects = new LinkedHashMap<>();
        final List<WeakReference<ViewerParrotEntity>> retiredObjects = new ArrayList<>();
        final List<Map<String, Object>> heapSamples = new ArrayList<>(10);
        // Allocate the entire scalar evidence table before warmup, so retaining
        // per-cycle QA metadata does not itself create an increasing live heap.
        final long[][] unloadedSamples = new long[101][18];
        final Consumer<ParrotEvent> boundObserver = this::onBound;
        final ChunkPos chunk;
        boolean fixtureTicket;
        boolean awaitingRecovery;
        int completedCycles;
        int reclaimedObjects;
        long waitStarted;
        Resources baseline;
        Resources end;
        Set<ViewerId> baselineRuntimeIds;
        Set<ViewerId> baselineLoadedIds;
        Set<?> baselineLoadedUuids;
        long calibrationMinimum = Long.MAX_VALUE;
        long calibrationMaximum;
        long collectionBeforeSample;
        final boolean leakProbeEnabled = "true".equalsIgnoreCase(System.getenv("VIEWERPARROTS_QA_LEAK_PROBE"));
        ViewerParrotEntity injectedLeak;

        DiskScenario(TestContext test) {
            this.test = test;
            ChunkPos testChunk = new ChunkPos(test.getAbsolutePos(BlockPos.ORIGIN));
            this.chunk = new ChunkPos(testChunk.x + 128, testChunk.z + 128);
        }

        void start() {
            guard(() -> {
                waitStarted = context.clock().currentTick();
                waitUntil(this::isQuiescent, "Disk fixture begins with drained storage and command queues", this::initializeFixture);
            });
        }

        void initializeFixture() {
            baseline = resources();
            baselineRuntimeIds = Set.copyOf(context.runtimes().keySet());
            baselineLoadedIds = context.loadedIndex().loadedViewerIds();
            baselineLoadedUuids = Set.copyOf(mapField(context.loadedIndex(), "viewerIdByEntityUuid").keySet());
            test.assertTrue(service.activeJobs() == 0, "Disk fixture begins without unrelated recovery work");
            context.events().subscribe(boundObserver);
            // Creating the fixture is explicit; the recovery service must later load only
            // this existing FULL chunk. No entity or chunk-loader state is fabricated.
            test.getWorld().getChunkManager().addTicket(FIXTURE_TICKET, chunk, 0, fixtureKey);
            fixtureTicket = true;
            test.getWorld().getChunk(chunk.x, chunk.z, ChunkStatus.FULL, true);
            waitStarted = context.clock().currentTick();
            waitUntil(() -> test.getWorld().isChunkLoaded(chunk.toLong()), "Fixture entity manager becomes ready", this::saveEntities);
        }

        void saveEntities() {
            for (int index = 0; index < 2; index++) {
                ViewerId id = ViewerId.of("disk-recovery-" + UUID.randomUUID());
                ViewerParrotRecord record = record(id, chunk, test.getWorld())
                        .withLocation(new WorldLocation(test.getWorld().getRegistryKey().getValue().toString(),
                                chunk.getStartX() + 6 + index * 3, 71, chunk.getStartZ() + 8), Instant.EPOCH)
                        .withDesiredBehavior(DesiredBehavior.SITTING, Instant.EPOCH);
                records.add(record);
                context.registry().put(record);
                ViewerParrotEntity parrot = ModEntities.VIEWER_PARROT.create(test.getWorld());
                parrot.setUuid(record.entityUuid());
                parrot.setMarker(ParrotMarker.of(record));
                parrot.setPersistent();
                parrot.setNoGravity(true);
                parrot.refreshPositionAndAngles(record.location().orElseThrow().x(), 71,
                        record.location().orElseThrow().z(), 0, 0);
                test.getWorld().setBlockState(parrot.getBlockPos().down(), Blocks.STONE.getDefaultState());
                test.assertTrue(test.getWorld().spawnEntity(parrot), "Real remote entity is inserted into the world");
                originals.add(parrot);
                parrot.setAiDisabled(true);
                test.assertTrue(context.loadedIndex().find(id).orElseThrow() == parrot, "Fixture ENTITY_LOAD binds exact UUID");
            }
            saveAndUnload();
        }

        void saveAndUnload() {
            recovered.clear();
            originals.forEach(parrot -> parrot.setAiDisabled(true));
            test.getWorld().save(null, true, false);
            test.getWorld().getChunkManager().removeTicket(FIXTURE_TICKET, chunk, 0, fixtureKey);
            fixtureTicket = false;
            waitStarted = context.clock().currentTick();
            waitUntil(() -> !test.getWorld().isChunkLoaded(chunk.toLong()) && records.stream()
                    .allMatch(record -> test.getWorld().getEntity(record.entityUuid()) == null),
                    "Saved remote chunk and its entities really unload", this::afterUnloaded);
        }

        void afterUnloaded() {
            for (int index = 0; index < records.size(); index++) {
                ViewerParrotEntity old = originals.get(index);
                test.assertTrue(old.isRemoved(), "Original Java object was unloaded by Minecraft");
                WeakReference<ViewerParrotEntity> weak = new WeakReference<>(old);
                previousObjects.put(records.get(index).viewerId(), weak);
                retiredObjects.add(weak);
                if (leakProbeEnabled && injectedLeak == null) injectedLeak = old;
            }
            // A verifier that strongly retained its old entities could manufacture the
            // very leak being measured. The next callback runs after this frame exits.
            originals.clear();
            recovered.clear();
            waitStarted = context.clock().currentTick();
            waitUntil(this::isQuiescent, "Unloaded checkpoint drains every mod queue", () -> {
                verifyUnloadedResources();
                if (completedCycles >= 10 && completedCycles % 10 == 0) {
                    test.waitAndRun(2, () -> guard(() -> collectHeapSample(0, this::continueAfterUnloaded)));
                } else continueAfterUnloaded();
            });
        }

        void continueAfterUnloaded() {
            if (completedCycles < 100) recover();
            else {
                test.assertTrue(completedCycles == 100, "One hundred real saved-chunk unload/load cycles completed");
                verifyHeapTrend();
                cleanup();
                waitStarted = context.clock().currentTick();
                waitUntil(this::isQuiescent, "Final cleanup drains queues", () -> {
                    end = resources();
                    test.assertTrue(end.equals(baseline), "All runtime references, indexes, jobs, tickets and queues return to pre-fixture baseline: " + end + " != " + baseline);
                    verifyIdentitySets();
                    writeEvidence(true, "");
                    test.complete();
                });
            }
        }

        void recover() {
            awaitingRecovery = true;
            for (int index = 0; index < records.size(); index++) {
                ViewerParrotRecord record = records.get(index);
                test.assertTrue(!context.loadedIndex().isLoaded(record.viewerId()), "Unload event cleared loaded index");
                test.assertTrue(service.start(record.viewerId()) == EntityRecoveryService.StartResult.STARTED,
                        "Public recovery starts a genuine asynchronous saved-chunk scan");
            }
            test.assertTrue(service.activeJobs() == 2, "Both public same-chunk recovery jobs reserve bounded slots");
            waitStarted = context.clock().currentTick();
            waitUntil(() -> service.activeJobs() == 0, "Recovery jobs finish within their configured deadline", this::verifyRecovered);
        }

        void verifyRecovered() {
            for (int index = 0; index < records.size(); index++) {
                ViewerParrotRecord expected = records.get(index);
                ViewerParrotEntity found = context.loadedIndex().find(expected.viewerId()).orElseThrow(
                        () -> new IllegalStateException("Saved entity did not recover: " + service.lastFailure(expected.viewerId())));
                test.assertTrue(found == recovered.get(expected.viewerId()), "Real bound event identifies the loaded saved object");
                test.assertTrue(found != previousObjects.get(expected.viewerId()).get(), "Recovery reads a new Java object from entity storage");
                test.assertTrue(found.getUuid().equals(expected.entityUuid()) && found.marker().orElseThrow().matches(expected),
                        "Recovery preserves exact incarnation, generation and entity UUID");
                test.assertTrue(!found.isAiDisabled(), "Recovered current identity is authorized after actual entity loading");
                test.assertTrue(service.lastFailure(expected.viewerId()).isEmpty(), "Successful recovery has no timeout or missing diagnosis");
            }
            try {
                test.assertTrue(ticketKeys(test.getWorld(), chunk, recoveryTicketType()).isEmpty(),
                        "Both public jobs release every real vanilla recovery ticket after saved-entity loading");
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            awaitingRecovery = false;
            completedCycles++;
            originals.clear();
            for (ViewerParrotRecord record : records) originals.add(recovered.get(record.viewerId()));
            // The hundredth recovered pair must unload too. The final sample is taken
            // in the same unloaded/registered phase as every earlier heap checkpoint.
            saveAndUnload();
        }

        boolean isQuiescent() {
            return context.allowsMutations() && context.writer().pendingTasks() == 0
                    && context.writer().queueDepth() == 0 && context.parrotService().operationCount() == 0
                    && collectionSize(context.parrotService(), "spawnContinuations") == 0
                    && context.commandService().diagnostics().get("pendingCommands") == 0
                    && mapField(context.commandService(), "activeIntentCommands").isEmpty()
                    && collectionSize(context, "namesToRefresh") == 0 && service.activeJobs() == 0;
        }

        void verifyIdentitySets() {
            test.assertTrue(context.runtimes().keySet().equals(baselineRuntimeIds), "Runtime identities return exactly to baseline");
            test.assertTrue(context.loadedIndex().loadedViewerIds().equals(baselineLoadedIds), "Viewer entity index identities return exactly to baseline");
            test.assertTrue(mapField(context.loadedIndex(), "viewerIdByEntityUuid").keySet().equals(baselineLoadedUuids),
                    "UUID entity index identities return exactly to baseline");
        }

        void verifyUnloadedResources() {
            verifyIdentitySets();
            Resources measured = resources();
            Resources expected = new Resources(baseline.runtimeReferences, baseline.loadedViewerReferences,
                    baseline.loadedUuidEntries, baseline.recoveryJobs, baseline.recoveryTickets, baseline.fixtureTickets,
                    baseline.writerPending, baseline.writerQueued, baseline.operations, baseline.spawnContinuations,
                    baseline.pendingCommands, baseline.activeIntentCommands, baseline.namesToRefresh, baseline.pendingReservations,
                    baseline.listeners + 1, baseline.registeredRecords + records.size());
            test.assertTrue(measured.equals(expected), "Every genuine unload restores references/tickets/queues: " + measured + " != " + expected);
            long[] row = unloadedSamples[completedCycles];
            long[] values = {completedCycles, context.clock().currentTick(), measured.runtimeReferences, measured.loadedViewerReferences,
                    measured.loadedUuidEntries, measured.recoveryJobs, measured.recoveryTickets, measured.fixtureTickets,
                    measured.writerPending, measured.writerQueued, measured.operations, measured.spawnContinuations,
                    measured.pendingCommands, measured.activeIntentCommands, measured.namesToRefresh, measured.pendingReservations,
                    measured.listeners, measured.registeredRecords};
            System.arraycopy(values, 0, row, 0, row.length);
        }

        void collectHeapSample(int repetition, Runnable next) {
            verifyUnloadedResources();
            collectionBeforeSample = gcCollections();
            System.gc();
            waitStarted = context.clock().currentTick();
            waitUntil(() -> gcCollections() > collectionBeforeSample, "Explicit full GC must actually complete before measuring heap", () ->
                    test.waitAndRun(2, () -> guard(() -> {
                        verifyIdentitySets();
                        int retained = (int) retiredObjects.stream().filter(weak -> weak.get() != null).count();
                        test.assertTrue(retained == 0, "Post-GC unload must release every old entity Java object; retained=" + retained);
                        reclaimedObjects += retiredObjects.size();
                        retiredObjects.clear();
                        long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                        if (completedCycles == 10) {
                            calibrationMinimum = Math.min(calibrationMinimum, heap);
                            calibrationMaximum = Math.max(calibrationMaximum, heap);
                            if (repetition < 2) { collectHeapSample(repetition + 1, next); return; }
                        }
                        heapSamples.add(Map.of("completedRecoveryCycles", completedCycles, "postGcHeapBytes", heap,
                                "gcCollections", gcCollections(), "reclaimedEntityObjects", reclaimedObjects,
                                "resources", resources()));
                        ViewerParrotsMod.LOGGER.info("QA_RECOVERY_HEAP cycles={} postGcHeapBytes={} reclaimedEntities={} queuesAndTicketsRestored=true",
                                completedCycles, heap, reclaimedObjects);
                        next.run();
                    })));
        }

        Map<String, Object> heapTrend() {
            if (heapSamples.size() < 3) return Map.of("measured", false);
            // Caches still settle between cycles 10 and 20 (a one-off ~250 KiB step, flat afterwards),
            // so the trend starts at the cycle-20 sample; the cycle-10 sample stays in the report.
            long first = (long) heapSamples.get(1).get("postGcHeapBytes");
            long last = (long) heapSamples.getLast().get("postGcHeapBytes");
            boolean monotonic = true;
            for (int index = 2; index < heapSamples.size(); index++) {
                if ((long) heapSamples.get(index).get("postGcHeapBytes") < (long) heapSamples.get(index - 1).get("postGcHeapBytes")) monotonic = false;
            }
            long calibrationSpan = calibrationMaximum - calibrationMinimum;
            // The entire Minecraft JVM is measured. The 16 KiB floor covers this
            // audit's bounded metadata; retained growth is limited to 256 KiB or
            // three times measured calibration noise over 80 measured cycles.
            // Unstable calibration above 256 KiB fails rather than loosening it.
            // These are small absolute gates, not percentages of a multi-GiB heap.
            long noise = Math.max(16L * 1024, calibrationSpan);
            long growthLimit = Math.max(256L * 1024, 3 * calibrationSpan);
            return Map.of("measured", true, "rawMonotonicNondecreasing", monotonic && last > first,
                    "retainedGrowthBytes", last - first, "calibrationSpanBytes", calibrationSpan,
                    "noiseAllowanceBytes", noise, "totalGrowthLimitBytes", growthLimit,
                    "calibrationStable", calibrationSpan <= 256L * 1024,
                    "passed", calibrationSpan <= 256L * 1024 && last - first <= growthLimit && !(monotonic && last - first > noise));
        }

        void verifyHeapTrend() {
            test.assertTrue(heapSamples.size() == 10, "Ten comparable post-GC samples cover cycles 10 through 100");
            test.assertTrue(reclaimedObjects == 202, "All 202 distinct entity objects from initial spawn and 100 disk loads were reclaimed");
            var trend = heapTrend();
            test.assertTrue(Boolean.TRUE.equals(trend.get("passed")), "Post-GC heap must not show retained/monotonic growth beyond measured noise: " + trend);
        }

        Resources resources() {
            try {
                int recoveryTickets = 0;
                int fixtureTickets = 0;
                for (ServerWorld world : context.server().getWorlds()) {
                    recoveryTickets += countTickets(world, recoveryTicketType());
                    fixtureTickets += countTickets(world, FIXTURE_TICKET);
                }
                return new Resources(context.runtimes().size(), context.loadedIndex().size(),
                        mapField(context.loadedIndex(), "viewerIdByEntityUuid").size(), service.activeJobs(), recoveryTickets,
                        fixtureTickets, context.writer().pendingTasks(), context.writer().queueDepth(), context.parrotService().operationCount(),
                        collectionSize(context.parrotService(), "spawnContinuations"), context.commandService().diagnostics().get("pendingCommands"),
                        mapField(context.commandService(), "activeIntentCommands").size(), collectionSize(context, "namesToRefresh"),
                        context.registry().pendingReservations(), context.events().listenerCount(), context.registry().size());
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }

        void writeEvidence(boolean passed, String failure) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schemaVersion", 1);
            report.put("scenario", "100 real saved-chunk recovery/load/unload cycles");
            report.put("passed", passed);
            report.put("failure", failure);
            report.put("completedRecoveryCycles", completedCycles);
            report.put("initialSetupUnloadCount", 1);
            report.put("postRecoveryUnloadCount", completedCycles);
            report.put("recordsPerCycle", 2);
            report.put("baseline", baseline);
            report.put("end", end);
            report.put("unloadedCheckpoints", unloadedSamples);
            report.put("unloadedCheckpointColumns", List.of("completedRecoveryCycles", "tick", "runtimeReferences", "loadedViewerReferences",
                    "loadedUuidEntries", "recoveryJobs", "recoveryTickets", "fixtureTickets", "writerPending", "writerQueued",
                    "operations", "spawnContinuations", "pendingCommands", "activeIntentCommands", "namesToRefresh", "pendingReservations",
                    "listeners", "registeredRecords"));
            report.put("postGcSamples", heapSamples);
            report.put("postGcTrend", heapTrend());
            report.put("reclaimedEntityObjects", reclaimedObjects);
            report.put("leakProbeEnabled", leakProbeEnabled);
            report.put("heapMethod", "Whole-JVM live heap after completed explicit GC in the same unloaded/registered phase; first 20 recovery cycles warm caches (trend from the cycle-20 sample); three calibration GCs at cycle 10; raw values and small absolute noise/growth gates reported. This is a lifecycle leak check, not allocation profiling or the 4 GiB load benchmark.");
            report.put("javaVersion", System.getProperty("java.runtime.version"));
            report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
            report.put("generatedAt", Instant.now().toString());
            Path path = Path.of("..", "reports", "verification", "recovery-100-cycles.json").toAbsolutePath().normalize();
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report));
            } catch (java.io.IOException error) { throw new IllegalStateException("Cannot write lifecycle evidence", error); }
        }

        void onBound(ParrotEvent event) {
            if (!awaitingRecovery || event.type() != ParrotEvent.Type.PARROT_BOUND
                    || records.stream().noneMatch(record -> record.viewerId().equals(event.viewerId()))) return;
            recovered.put(event.viewerId(), context.loadedIndex().find(event.viewerId()).orElseThrow());
            // Once vanilla has loaded the saved entity, retain it only for assertions and
            // the next save. Public recovery tickets still have to be released independently.
            if (!fixtureTicket) {
                test.getWorld().getChunkManager().addTicket(FIXTURE_TICKET, chunk, 0, fixtureKey);
                fixtureTicket = true;
            }
        }

        void waitUntil(java.util.function.BooleanSupplier condition, String message, Runnable next) {
            if (condition.getAsBoolean()) guard(next);
            else {
                test.assertTrue(context.clock().currentTick() - waitStarted < 1000, message);
                test.waitAndRun(1, () -> guard(() -> waitUntil(condition, message, next)));
            }
        }

        void cleanup() {
            context.events().unsubscribe(boundObserver);
            awaitingRecovery = false;
            if (fixtureTicket) test.getWorld().getChunkManager().removeTicket(FIXTURE_TICKET, chunk, 0, fixtureKey);
            fixtureTicket = false;
            for (ViewerParrotRecord record : records) {
                service.cancel(record.viewerId());
                context.loadedIndex().find(record.viewerId()).ifPresent(ViewerParrotEntity::discard);
                context.loadedIndex().unbind(record.viewerId());
                context.forgetRuntime(record.viewerId());
                context.markUnresolved(record.viewerId());
                context.registry().remove(record.viewerId(), context.clock().now());
            }
            originals.clear();
            recovered.clear();
            previousObjects.clear();
            injectedLeak = null;
        }

        void guard(Runnable action) {
            try { action.run(); }
            catch (RuntimeException | Error failure) {
                try { writeEvidence(false, failure.toString()); } catch (RuntimeException evidenceFailure) { failure.addSuppressed(evidenceFailure); }
                cleanup(); throw failure;
            }
        }
    }

    private record Resources(int runtimeReferences, int loadedViewerReferences, int loadedUuidEntries,
                             int recoveryJobs, int recoveryTickets, int fixtureTickets,
                             int writerPending, int writerQueued, int operations, int spawnContinuations,
                             int pendingCommands, int activeIntentCommands, int namesToRefresh, int pendingReservations,
                             int listeners, int registeredRecords) { }

    private static long gcCollections() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private static Map<?, ?> mapField(Object target, String name) {
        return (Map<?, ?>) field(target, name);
    }

    private static int collectionSize(Object target, String name) {
        return ((java.util.Collection<?>) field(target, name)).size();
    }

    private static int countTickets(ServerWorld world, ChunkTicketType<?> type) throws ReflectiveOperationException {
        Field managerField = ServerChunkManager.class.getDeclaredField("ticketManager"); managerField.setAccessible(true);
        ChunkTicketManager manager = (ChunkTicketManager) managerField.get(world.getChunkManager());
        Field ticketsField = ChunkTicketManager.class.getDeclaredField("ticketsByPosition"); ticketsField.setAccessible(true);
        Long2ObjectMap<?> byPosition = (Long2ObjectMap<?>) ticketsField.get(manager);
        int count = 0;
        for (Object tickets : byPosition.values()) {
            for (Object candidate : (Iterable<?>) tickets) if (((ChunkTicket<?>) candidate).getType() == type) count++;
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static ChunkTicketType<UUID> recoveryTicketType() throws ReflectiveOperationException {
        Field field = EntityRecoveryService.class.getDeclaredField("RECOVERY_TICKET");
        field.setAccessible(true);
        return (ChunkTicketType<UUID>) field.get(null);
    }

    private static Set<UUID> ticketKeys(ServerWorld world, ChunkPos chunk, ChunkTicketType<UUID> type)
            throws ReflectiveOperationException {
        Field managerField = ServerChunkManager.class.getDeclaredField("ticketManager"); managerField.setAccessible(true);
        ChunkTicketManager manager = (ChunkTicketManager) managerField.get(world.getChunkManager());
        Field ticketsField = ChunkTicketManager.class.getDeclaredField("ticketsByPosition"); ticketsField.setAccessible(true);
        Long2ObjectMap<?> byPosition = (Long2ObjectMap<?>) ticketsField.get(manager);
        Object tickets = byPosition.get(chunk.toLong());
        Set<UUID> keys = new HashSet<>();
        if (tickets == null) return keys;
        Field argumentField = ChunkTicket.class.getDeclaredField("argument"); argumentField.setAccessible(true);
        for (Object candidate : (Iterable<?>) tickets) {
            ChunkTicket<?> ticket = (ChunkTicket<?>) candidate;
            if (ticket.getType() == type) keys.add((UUID) argumentField.get(ticket));
        }
        return keys;
    }
}
