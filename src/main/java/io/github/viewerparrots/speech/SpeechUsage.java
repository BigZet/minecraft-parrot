package io.github.viewerparrots.speech;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.viewerparrots.json.StrictJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.time.Instant;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/** Instance-wide rolling usage. Commit on the HTTP worker immediately before sending, never refund. */
public final class SpeechUsage {
    public record Counts(long requests, long words) { }
    private final Path file;
    private final LongSupplier clock;
    private final TreeMap<Long, Counts> buckets = new TreeMap<>();
    public SpeechUsage(Path directory) throws IOException { this(directory, () -> Instant.now().getEpochSecond()); }
    SpeechUsage(Path directory, LongSupplier clock) throws IOException {
        this.file = directory.resolve("viewerparrots-usage.json"); this.clock = clock;
        if (Files.exists(file)) try {
            for (var element : StrictJson.parseObject(Files.readString(file)).getAsJsonArray("buckets")) {
                var row = element.getAsJsonObject();
                long second = row.get("second").getAsLong();
                long requests = row.get("requests").getAsLong(), words = row.get("words").getAsLong();
                if (requests < 0 || words < 0 || buckets.put(second, new Counts(requests, words)) != null)
                    throw new IllegalArgumentException("Invalid usage bucket");
            }
            prune();
        } catch (RuntimeException invalid) { throw new IOException("Повреждён viewerparrots-usage.json; файл сохранён без изменений."); }
    }
    private void prune() { buckets.headMap(clock.getAsLong() - 3600, true).clear(); }
    public synchronized Counts counts() {
        prune(); long requests = 0, words = 0;
        for (var bucket : buckets.values()) { requests += bucket.requests(); words += bucket.words(); }
        return new Counts(requests, words);
    }
    public synchronized boolean record(int words, int requestLimit, int wordLimit) throws IOException {
        if (words < 1) throw new IllegalArgumentException("words");
        var counts = counts();
        if ((requestLimit > 0 && counts.requests() >= requestLimit)
                || (wordLimit > 0 && counts.words() + words > wordLimit)) return false;
        long second = clock.getAsLong(); var previous = buckets.get(second);
        buckets.put(second, previous == null ? new Counts(1, words)
                : new Counts(previous.requests() + 1, previous.words() + words));
        try { persist(); } catch (IOException failure) {
            if (previous == null) buckets.remove(second); else buckets.put(second, previous);
            throw failure;
        }
        return true;
    }
    private void persist() throws IOException {
        JsonArray rows = new JsonArray();
        buckets.forEach((second, counts) -> {
            var row = new JsonObject(); row.addProperty("second", second);
            row.addProperty("requests", counts.requests()); row.addProperty("words", counts.words()); rows.add(row);
        });
        var root = new JsonObject(); root.add("buckets", rows);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "usage-", ".tmp");
        try {
            Files.writeString(temp, new Gson().toJson(root));
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
