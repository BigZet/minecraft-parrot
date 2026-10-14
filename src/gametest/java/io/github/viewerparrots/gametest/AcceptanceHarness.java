package io.github.viewerparrots.gametest;

import com.google.gson.GsonBuilder;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.domain.*;
import io.github.viewerparrots.entity.ModEntities;
import io.github.viewerparrots.entity.ParrotMarker;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.block.Blocks;
import net.minecraft.block.Block;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameRules;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Test-only workload/telemetry. Never packaged in the user's mod. */
public final class AcceptanceHarness implements ModInitializer {
    private static final ParrotTrait[] ACTIVE_TRAITS = {
            ParrotTrait.NORMAL, ParrotTrait.COWARD, ParrotTrait.LAZY, ParrotTrait.LOUD
    };
    private final List<Entity> workload = new ArrayList<>();
    private final List<ViewerId> workloadIds = new ArrayList<>();
    private final List<Map<String, Object>> summaries = new ArrayList<>();
    private final List<Long> ticks = new ArrayList<>();
    private final List<Long> schedulerTicks = new ArrayList<>();
    private long tickStarted;
    private long measurementStarted;
    private int elapsedTicks;
    private int warmupTicks;
    private int measurementTicks;
    private int scenarioIndex = -1;
    private ServerPlayerEntity target;
    private String scenario;
    private long gcCountAtMeasurement;
    private long gcTimeAtMeasurement;
    private int minimumLoadedEntities;
    private boolean storageStayedReady;
    private ServerWorld arenaWorld;
    private BlockPos arenaCenter;
    private Map<String, Object> arena;
    private int minimumWarmupEntities;
    private int maximumAiDisabledEntities;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> {
            dispatcher.register(CommandManager.literal("viewerparrots-qa").requires(source -> source.hasPermissionLevel(4))
                    .then(CommandManager.literal("benchmark").executes(ctx -> start(ctx.getSource(), 6000, 12000)))
                    .then(CommandManager.literal("quick-benchmark").executes(ctx -> start(ctx.getSource(), 100, 600)))
                    .then(CommandManager.literal("quick-guard-probe").executes(ctx -> start(ctx.getSource(), 20, 80)))
                    .then(CommandManager.literal("crud-latency").executes(ctx -> CrudLatencyProbe.start(ctx.getSource())))
                    .then(CommandManager.literal("status").executes(ctx -> {
                        ctx.getSource().sendFeedback(() -> Text.literal("qa scenario=" + scenario + " elapsedTicks=" + elapsedTicks
                                + " runs=" + summaries.size()), false);
                        return 1;
                    })));
        });
        ServerTickEvents.START_SERVER_TICK.register(server -> tickStarted = System.nanoTime());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (scenarioIndex < 0) return;
            elapsedTicks++;
            int required = scenario.equals("baseline") ? 0 : 50;
            int loaded = loadedWorkload();
            int disabled = (int) workload.stream().filter(entity -> entity instanceof net.minecraft.entity.mob.MobEntity mob && mob.isAiDisabled()).count();
            maximumAiDisabledEntities = Math.max(maximumAiDisabledEntities, disabled);
            if (loaded != required || disabled != 0 || target.isRemoved() || target.getServerWorld() != arenaWorld || !insideArena(target.getPos())) {
                abort("Workload must retain exactly " + required + " original loaded/ticking bodies, enabled AI, and an online target inside the unchanged arena", loaded, disabled);
                return;
            }
            if (elapsedTicks <= warmupTicks) minimumWarmupEntities = Math.min(minimumWarmupEntities, loaded);
            if (elapsedTicks == warmupTicks) {
                measurementStarted = System.nanoTime();
                gcCountAtMeasurement = gcCount();
                gcTimeAtMeasurement = gcTime();
                minimumLoadedEntities = workload.size();
                storageStayedReady = true;
            }
            if (elapsedTicks > warmupTicks) {
                ticks.add(System.nanoTime() - tickStarted);
                ServerContext.current().ifPresent(context -> {
                    schedulerTicks.add(context.behaviorScheduler().lastTickNanos());
                    storageStayedReady &= context.allowsMutations();
                });
                minimumLoadedEntities = Math.min(minimumLoadedEntities, loaded);
            }
            if (elapsedTicks >= warmupTicks + measurementTicks) finishRun();
        });
    }

    private int start(ServerCommandSource source, int warmup, int measure) {
        if (scenarioIndex >= 0) {
            source.sendError(Text.literal("Benchmark is already running"));
            return 0;
        }
        ServerContext context = ServerContext.current().orElseThrow();
        if (!context.allowsMutations() || context.registry().size() != 0) {
            source.sendError(Text.literal("Benchmark requires READY storage and a clean world without viewer records"));
            return 0;
        }
        target = context.streamerService().onlineTarget().orElse(null);
        if (target == null) {
            source.sendError(Text.literal("Set an online streamer first: /vp setstreamer <player>"));
            return 0;
        }
        warmupTicks = warmup;
        measurementTicks = measure;
        summaries.clear();
        prepareArena(); // Geometry is prepared once, before every scenario's first baseline warmup.
        scenarioIndex = 0;
        beginRun();
        source.sendFeedback(() -> Text.literal("Benchmark started: 3 runs each baseline, vanilla50, custom50"), false);
        return 1;
    }

    private void beginRun() {
        ServerContext context = ServerContext.current().orElseThrow();
        clearWorkload(context);
        scenario = switch (scenarioIndex / 3) { case 0 -> "baseline"; case 1 -> "vanilla50"; default -> "custom50"; };
        ServerWorld world = target.getServerWorld();
        if (!scenario.equals("baseline")) {
            for (int i = 0; i < 50; i++) {
                Vec3d position = Vec3d.ofBottomCenter(arenaCenter).add((i % 10) - 5, 1, (i / 10) - 2);
                Entity entity;
                if (scenario.equals("vanilla50")) {
                    var vanilla = EntityType.PARROT.create(world);
                    vanilla.setPersistent();
                    entity = vanilla;
                } else {
                    ViewerId id = ViewerId.of("qa-load-" + i);
                    ViewerParrotRecord record = ViewerParrotRecord.created(new Viewer(id, DisplayName.of("QA " + i), Instant.now()),
                            UUID.randomUUID(), UUID.randomUUID(), ParrotVariant.values()[i % 5],
                            ACTIVE_TRAITS[i % ACTIVE_TRAITS.length], null, Instant.now());
                    context.registry().put(record);
                    ViewerParrotEntity custom = ModEntities.VIEWER_PARROT.create(world);
                    custom.setUuid(record.entityUuid());
                    custom.setMarker(ParrotMarker.of(record));
                    custom.setPersistent();
                    entity = custom;
                    workloadIds.add(id);
                }
                entity.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
                if (!world.spawnEntity(entity)) throw new IllegalStateException("QA workload spawn was rejected: " + entity.getUuid());
                workload.add(entity);
            }
        }
        ticks.clear();
        schedulerTicks.clear();
        elapsedTicks = 0;
        minimumWarmupEntities = workload.size();
        maximumAiDisabledEntities = 0;
        System.out.println("VIEWERPARROTS_QA_BEGIN " + scenario + " run=" + (scenarioIndex % 3 + 1));
    }

    private void clearWorkload(ServerContext context) {
        for (Entity entity : workload) entity.discard();
        workload.clear();
        for (ViewerId id : workloadIds) {
            context.registry().remove(id, Instant.now());
            context.forgetRuntime(id);
        }
        workloadIds.clear();
    }

    private void finishRun() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", scenario);
        if (scenario.equals("custom50")) result.put("traits", Map.of("NORMAL", 13, "COWARD", 13, "LAZY", 12, "LOUD", 12));
        result.put("run", scenarioIndex % 3 + 1);
        result.put("warmupTicks", warmupTicks);
        result.put("sampleTicks", ticks.size());
        result.put("minimumLoadedEntities", minimumLoadedEntities);
        result.put("minimumWarmupEntities", minimumWarmupEntities);
        result.put("maximumAiDisabledEntities", maximumAiDisabledEntities);
        result.put("arena", arena);
        result.put("storageStayedReady", storageStayedReady);
        result.put("tps", measurementTicks * 1_000_000_000.0 / (System.nanoTime() - measurementStarted));
        result.put("tickP50Ms", percentile(ticks, 0.50));
        result.put("tickP95Ms", percentile(ticks, 0.95));
        result.put("tickP99Ms", percentile(ticks, 0.99));
        result.put("schedulerP95Ms", percentile(schedulerTicks, 0.95));
        result.put("usedHeapMiB", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576.0);
        result.put("gcCount", gcCount() - gcCountAtMeasurement);
        result.put("gcTimeMs", gcTime() - gcTimeAtMeasurement);
        result.put("java", System.getProperty("java.runtime.version"));
        result.put("os", System.getProperty("os.name"));
        result.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        result.put("maxHeapMiB", Runtime.getRuntime().maxMemory() / 1048576.0);
        summaries.add(result);
        Path report = Path.of("benchmark-results.json");
        try {
            Files.writeString(report, new GsonBuilder().setPrettyPrinting().create().toJson(summaries));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save benchmark report", e);
        }
        System.out.println("VIEWERPARROTS_QA_RESULT " + result);
        scenarioIndex++;
        if (scenarioIndex == 9) {
            clearWorkload(ServerContext.current().orElseThrow());
            scenarioIndex = -1;
            System.out.println("VIEWERPARROTS_QA_COMPLETE " + report.toAbsolutePath());
        } else {
            beginRun();
        }
    }

    private static double percentile(List<Long> source, double quantile) {
        if (source.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(source);
        Collections.sort(sorted);
        int index = Math.max(0, (int) Math.ceil(quantile * sorted.size()) - 1);
        return sorted.get(index) / 1_000_000.0;
    }

    private void prepareArena() {
        arenaWorld = target.getServerWorld(); arenaCenter = target.getBlockPos();
        arenaWorld.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, target.getServer());
        arenaWorld.getGameRules().get(GameRules.DO_WEATHER_CYCLE).set(false, target.getServer());
        arenaWorld.setWeather(0, 0, false, false);
        int cx = arenaCenter.getX(), cy = arenaCenter.getY(), cz = arenaCenter.getZ();
        for (int x = cx - 17; x <= cx + 17; x++) for (int z = cz - 17; z <= cz + 17; z++) {
            arenaWorld.setBlockState(new BlockPos(x, cy - 1, z), Blocks.GRASS_BLOCK.getDefaultState(), Block.NOTIFY_LISTENERS);
            arenaWorld.setBlockState(new BlockPos(x, cy + 12, z), Blocks.BARRIER.getDefaultState(), Block.NOTIFY_LISTENERS);
            for (int y = cy; y < cy + 12; y++) {
                boolean edge = x == cx - 17 || x == cx + 17 || z == cz - 17 || z == cz + 17;
                arenaWorld.setBlockState(new BlockPos(x, y, z), (edge ? Blocks.BARRIER : Blocks.AIR).getDefaultState(), Block.NOTIFY_LISTENERS);
            }
        }
        arena = Map.of("interiorWidth", 33, "interiorDepth", 33, "interiorHeight", 12,
                "center", List.of(cx, cy, cz), "dimension", arenaWorld.getRegistryKey().getValue().toString(),
                "floor", "minecraft:grass_block", "wallsAndRoof", "minecraft:barrier",
                "preparedBeforeBaseline", true, "unchangedAcrossAllNineWindows", true,
                "aiEnabled", true);
        try { Files.writeString(Path.of("benchmark-arena.json"), new GsonBuilder().setPrettyPrinting().create().toJson(arena)); }
        catch (IOException failure) { throw new IllegalStateException("Cannot persist arena metadata", failure); }
        System.out.println("VIEWERPARROTS_QA_ARENA " + arena);
    }

    private boolean insideArena(Vec3d position) {
        return position.x >= arenaCenter.getX() - 16 && position.x < arenaCenter.getX() + 17
                && position.z >= arenaCenter.getZ() - 16 && position.z < arenaCenter.getZ() + 17
                && position.y >= arenaCenter.getY() && position.y < arenaCenter.getY() + 12;
    }

    private int loadedWorkload() {
        int loaded = 0;
        for (Entity entity : workload) if (!entity.isRemoved() && entity.getWorld() == arenaWorld
                && arenaWorld.getEntity(entity.getUuid()) == entity && arenaWorld.shouldTickEntity(entity.getBlockPos())
                && insideArena(entity.getPos())) loaded++;
        return loaded;
    }

    private void abort(String reason, int loaded, int disabled) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("passed", false); failure.put("scenario", scenario); failure.put("run", scenarioIndex % 3 + 1);
        failure.put("elapsedTicks", elapsedTicks); failure.put("reason", reason); failure.put("loadedEntities", loaded);
        failure.put("aiDisabledEntities", disabled); failure.put("arena", arena); failure.put("accepted", false);
        failure.put("entities", workload.stream().map(entity -> Map.of("uuid", entity.getUuid().toString(),
                "position", List.of(entity.getX(), entity.getY(), entity.getZ()), "removed", entity.isRemoved(),
                "ticking", arenaWorld.shouldTickEntity(entity.getBlockPos()))).toList());
        try { Files.writeString(Path.of("benchmark-failure.json"), new GsonBuilder().setPrettyPrinting().create().toJson(failure)); }
        catch (IOException error) { throw new IllegalStateException("Cannot save benchmark failure", error); }
        scenarioIndex = -1;
        System.out.println("VIEWERPARROTS_QA_ABORT " + failure);
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(gc -> Math.max(0, gc.getCollectionCount())).sum();
    }
    private static long gcTime() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(gc -> Math.max(0, gc.getCollectionTime())).sum();
    }
}
