package io.github.viewerparrots.gametest;

import com.google.gson.GsonBuilder;
import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.domain.DisplayName;
import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.domain.ViewerId;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** End-to-end durable CRUD timing on a real, paced dedicated server. QA only. */
public final class CrudLatencyProbe {
    private static boolean running;
    private final ServerContext context = ServerContext.current().orElseThrow();
    private final Map<String, List<Double>> samples = new LinkedHashMap<>();
    private final String prefix = "qa-latency-" + UUID.randomUUID() + "-";
    private final int reservedBefore = context.registry().reservedSlots();
    private static final ActorContext ADMIN = ActorContext.console(4);

    public static int start(ServerCommandSource source) {
        if (running || ServerContext.current().orElseThrow().streamerService().onlineTarget().isEmpty()) {
            source.sendError(Text.literal("Latency probe needs an online streamer and no concurrent latency probe"));
            return 0;
        }
        running = true;
        CrudLatencyProbe probe = new CrudLatencyProbe();
        probe.iteration(0).whenComplete((ignored, error) -> {
            running = false;
            try { probe.report(error); }
            catch (Exception failure) { throw new IllegalStateException("Cannot write CRUD latency report", failure); }
        });
        source.sendFeedback(() -> Text.literal("Started 50 sequential samples of create, rename, trait and remove"), false);
        return 1;
    }

    private CompletableFuture<Void> iteration(int index) {
        if (index == 50) return CompletableFuture.completedFuture(null);
        ViewerId id = ViewerId.of(prefix + index);
        return measure("create", () -> context.parrotService().create(UUID.randomUUID(), ADMIN, id, DisplayName.of("Timing")))
                .thenCompose(ignored -> measure("rename", () -> context.parrotService().rename(UUID.randomUUID(), ADMIN, id, DisplayName.of("Renamed"))))
                .thenCompose(ignored -> measure("trait", () -> context.parrotService().setTrait(UUID.randomUUID(), ADMIN, id,
                        context.registry().find(id).orElseThrow().trait() == ParrotTrait.NORMAL ? ParrotTrait.LOUD : ParrotTrait.NORMAL)))
                .thenCompose(ignored -> measure("remove", () -> context.parrotService().remove(UUID.randomUUID(), ADMIN, id)))
                .thenCompose(ignored -> iteration(index + 1));
    }

    private CompletableFuture<CommandResult> measure(String action, Supplier<CompletableFuture<CommandResult>> operation) {
        long started = System.nanoTime();
        return operation.get().thenApply(result -> {
            if (!result.succeeded()) throw new IllegalStateException(action + ": " + result.status() + "/" + result.code());
            samples.computeIfAbsent(action, ignored -> new ArrayList<>()).add((System.nanoTime() - started) / 1_000_000.0);
            return result;
        });
    }

    private void report(Throwable error) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        Map<String, Object> measurements = new LinkedHashMap<>();
        boolean passed = error == null && context.registry().reservedSlots() == reservedBefore;
        for (var entry : samples.entrySet()) {
            List<Double> sorted = entry.getValue().stream().sorted().toList();
            double p95 = sorted.get((int) Math.ceil(sorted.size() * .95) - 1);
            measurements.put(entry.getKey(), Map.of("samples", sorted.size(), "p50Ms", sorted.get(sorted.size() / 2), "p95Ms", p95, "maxMs", sorted.getLast()));
            passed &= sorted.size() == 50 && p95 <= 1000;
        }
        report.put("passed", passed);
        report.put("scope", "public-service-completion-after-durable-journal-and-server-thread-callback");
        report.put("operations", measurements);
        report.put("reservedBefore", reservedBefore);
        report.put("reservedAfter", context.registry().reservedSlots());
        report.put("pendingOperations", context.parrotService().operationCount());
        report.put("java", System.getProperty("java.runtime.version"));
        if (error != null) report.put("error", error.toString());
        Files.writeString(Path.of("crud-latency-results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("VIEWERPARROTS_QA_CRUD " + report);
    }
}
