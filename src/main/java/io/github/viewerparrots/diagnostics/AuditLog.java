package io.github.viewerparrots.diagnostics;

import com.google.gson.*;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

/** Bounded structured diagnostics. Failures here never escape into gameplay. */
public final class AuditLog {
    public static final int MAX_TEXT_LENGTH = 1024, MAX_STACK_LENGTH = 8192;
    private static final long WARN_INTERVAL_NANOS = 60_000_000_000L;
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    public enum Level { DEBUG, INFO, WARN, ERROR }
    public record Context(UUID commandId, String viewerId, UUID incarnationId, Long generation, UUID entityUuid, String action) {
        public Context { viewerId = bounded(viewerId, 128); action = bounded(action, 128); }
        public static Context of(UUID commandId, ViewerParrotRecord record, String action) {
            return record == null ? new Context(commandId, null, null, null, null, action)
                    : new Context(commandId, record.viewerId().value(), record.incarnationId(), record.generation(), record.entityUuid(), action);
        }
        public static Context viewer(UUID commandId, ViewerId viewerId, String action) {
            return new Context(commandId, viewerId == null ? null : viewerId.value(), null, null, null, action);
        }
    }
    @FunctionalInterface public interface Clock { long nanoTime(); default long millis() { return nanoTime() / 1_000_000L; } }
    @FunctionalInterface public interface Sink { void emit(Level level, String json); default boolean debugEnabled() { return true; } }
    public static final class Engine {
        private final Clock clock;
        private final Sink sink;
        private final int capacity;
        private final long expiryNanos;
        private final Map<WarnKey, WarnState> warnings = new HashMap<>();
        private long lastSweep;
        private boolean swept;
        public Engine(Clock clock, Sink sink) { this(clock, sink, 1024, 300_000_000_000L); }
        public Engine(Clock clock, Sink sink, int capacity, long expiryNanos) {
            this.clock = Objects.requireNonNull(clock); this.sink = Objects.requireNonNull(sink);
            if (capacity < 1 || expiryNanos < WARN_INTERVAL_NANOS) throw new IllegalArgumentException("Invalid audit WARN bounds");
            this.capacity = capacity; this.expiryNanos = expiryNanos;
        }
        public long started() { try { return clock.nanoTime(); } catch (Throwable ignored) { return System.nanoTime(); } }
        public boolean debugEnabled() { try { return sink.debugEnabled(); } catch (Throwable ignored) { return false; } }
        public synchronized void emit(Level level, String event, Context context, String status, String reason,
                                      long startedNanos, Map<String, ?> details, Throwable error) {
            try {
                if (level == Level.DEBUG && !sink.debugEnabled()) return;
                if (level == null) level = Level.INFO;
                long now = clock.nanoTime();
                String stableReason = bounded(reason, 512);
                long repeats = level == Level.WARN ? warnCount(new WarnKey(context == null ? null : context.viewerId(), stableReason), now) : 0;
                if (repeats < 0) return;
                JsonObject line = new JsonObject();
                line.add("event", text(event, 128));
                line.add("commandId", uuid(context == null ? null : context.commandId()));
                line.add("viewerId", text(context == null ? null : context.viewerId(), 128));
                line.add("incarnationId", uuid(context == null ? null : context.incarnationId()));
                line.add("generation", context == null || context.generation() == null ? JsonNull.INSTANCE : new JsonPrimitive(context.generation()));
                line.add("entityUuid", uuid(context == null ? null : context.entityUuid()));
                line.add("action", text(context == null ? null : context.action(), 128));
                line.add("status", text(status, 128));
                line.add("reason", text(stableReason, 512));
                line.addProperty("durationMs", Math.max(0.0, (now - startedNanos) / 1_000_000.0));
                if (level == Level.WARN) line.addProperty("repeatCount", repeats);
                if (details != null) line.add("details", value(details, 0, new int[]{128}));
                if (error != null) line.add("error", failure(error));
                sink.emit(level, JSON.toJson(line));
            } catch (Throwable ignored) {
                // Audit is a side effect: serialization, clocks and appenders cannot fail a command.
            }
        }
        private long warnCount(WarnKey key, long now) {
            WarnState state = warnings.get(key);
            if (state != null && now - state.lastSeen >= expiryNanos) { warnings.remove(key); state = null; }
            if (state != null) {
                state.lastSeen = now;
                if (now - state.lastEmitted < WARN_INTERVAL_NANOS) {
                    if (state.repeats < Long.MAX_VALUE) state.repeats++;
                    return -1;
                }
                long count = state.repeats == Long.MAX_VALUE ? Long.MAX_VALUE : state.repeats + 1;
                state.repeats = 0; state.lastEmitted = now;
                return count;
            }
            if (!swept || now - lastSweep >= WARN_INTERVAL_NANOS || warnings.size() >= capacity) {
                warnings.values().removeIf(candidate -> now - candidate.lastSeen >= expiryNanos);
                lastSweep = now; swept = true;
            }
            // Never evict a live key: otherwise an overflow/reinsert could bypass its 60s window.
            if (warnings.size() >= capacity) return -1;
            warnings.put(key, new WarnState(now));
            return 0;
        }
        public synchronized void clear() { warnings.clear(); swept = false; lastSweep = 0; }
        public synchronized int cachedWarnings() { return warnings.size(); }
    }
    private record WarnKey(String viewerId, String reason) { }
    private static final class WarnState {
        long lastEmitted, lastSeen, repeats;
        WarnState(long now) { lastEmitted = now; lastSeen = now; }
    }
    private static final class Slf4jSink implements Sink {
        private final Logger logger;
        Slf4jSink() {
            Logger candidate;
            try { candidate = LoggerFactory.getLogger("viewerparrots"); } catch (Throwable ignored) { candidate = null; }
            logger = candidate;
        }
        public boolean debugEnabled() { return logger != null && logger.isDebugEnabled(); }
        public void emit(Level level, String json) {
            if (logger == null) return;
            switch (level) {
                case DEBUG -> logger.debug(json);
                case INFO -> logger.info(json);
                case WARN -> logger.warn(json);
                case ERROR -> logger.error(json);
            }
        }
    }
    static volatile Engine engine = new Engine(System::nanoTime, new Slf4jSink());
    private AuditLog() { }
    public static long started() { return engine.started(); }
    public static boolean isDebugEnabled() { return engine.debugEnabled(); }
    /** Single-line feedback text; Windows path separators remain readable. */
    public static String safeText(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder();
        int remaining = 512;
        for (int index = 0; index < value.length() && remaining > 0;) {
            int codePoint = value.codePointAt(index); index += Character.charCount(codePoint);
            if (codePoint >= 0xD800 && codePoint <= 0xDFFF) codePoint = 0xFFFD;
            String part = switch (codePoint) {
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                default -> {
                    if (Character.isISOControl(codePoint) || codePoint == 0x2028 || codePoint == 0x2029) {
                        String hex = Integer.toHexString(codePoint);
                        yield "\\u" + "0".repeat(Math.max(0, 4 - hex.length())) + hex;
                    }
                    yield new String(Character.toChars(codePoint));
                }
            };
            int count = part.codePointCount(0, part.length());
            if (count > remaining) break;
            result.append(part); remaining -= count;
        }
        return result.toString();
    }
    public static void emit(Level level, String event, Context context, String status, String reason, long startedNanos, Map<String, ?> details, Throwable error) {
        try { engine.emit(level, event, context, status, reason, startedNanos, details, error); } catch (Throwable ignored) { }
    }
    public static void info(String event, Context context, String status, String reason, long startedNanos, Map<String, ?> details, Throwable error) { emit(Level.INFO, event, context, status, reason, startedNanos, details, error); }
    public static void debug(String event, Context context, String status, String reason, long startedNanos, Map<String, ?> details, Throwable error) { emit(Level.DEBUG, event, context, status, reason, startedNanos, details, error); }
    public static void warn(String event, Context context, String status, String reason, long startedNanos, Map<String, ?> details, Throwable error) { emit(Level.WARN, event, context, status, reason, startedNanos, details, error); }
    public static void error(String event, Context context, String status, String reason, long startedNanos, Map<String, ?> details, Throwable error) { emit(Level.ERROR, event, context, status, reason, startedNanos, details, error); }
    public static void clear() { engine.clear(); }

    private static String bounded(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        int end = limit - 1;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1)) && Character.isLowSurrogate(value.charAt(end))) end--;
        return value.substring(0, end) + "…";
    }
    private static JsonElement text(String value, int limit) { return value == null ? JsonNull.INSTANCE : new JsonPrimitive(bounded(value, limit)); }
    private static JsonElement uuid(UUID value) { return value == null ? JsonNull.INSTANCE : new JsonPrimitive(value.toString()); }
    private static JsonElement value(Object item, int depth, int[] remaining) {
        if (remaining[0]-- <= 0 || depth > 4) return new JsonPrimitive("[truncated]");
        if (item == null) return JsonNull.INSTANCE;
        if (item instanceof String string) return text(string, MAX_TEXT_LENGTH);
        if (item instanceof UUID id) return uuid(id);
        if (item instanceof Enum<?> enumeration) return text(enumeration.name(), MAX_TEXT_LENGTH);
        if (item instanceof Boolean bool) return new JsonPrimitive(bool);
        if (item instanceof Byte || item instanceof Short || item instanceof Integer || item instanceof Long) return new JsonPrimitive((Number) item);
        if (item instanceof Float floating) return Float.isFinite(floating) ? new JsonPrimitive(floating) : JsonNull.INSTANCE;
        if (item instanceof Double floating) return Double.isFinite(floating) ? new JsonPrimitive(floating) : JsonNull.INSTANCE;
        if (item instanceof Map<?, ?> map) {
            JsonObject object = new JsonObject(); int count = 0;
            for (var entry : map.entrySet()) {
                if (count++ >= 32 || remaining[0] <= 0) break;
                if (entry.getKey() instanceof String key) object.add(bounded(key, 128), value(entry.getValue(), depth + 1, remaining));
            }
            return object;
        }
        if (item instanceof Iterable<?> values) {
            JsonArray array = new JsonArray(); int count = 0;
            for (Object entry : values) {
                if (count++ >= 32 || remaining[0] <= 0) break;
                array.add(value(entry, depth + 1, remaining));
            }
            return array;
        }
        return new JsonPrimitive("[unsupported:" + bounded(item.getClass().getName(), 128) + "]");
    }
    private static JsonObject failure(Throwable error) {
        JsonObject result = new JsonObject();
        result.add("type", text(error.getClass().getName(), 256));
        result.add("message", text(error.getMessage(), MAX_TEXT_LENGTH));
        StringBuilder stack = new StringBuilder();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        for (int cause = 0; current != null && cause < 4 && visited.add(current) && stack.length() < MAX_STACK_LENGTH; cause++) {
            if (cause > 0) stack.append("Caused by: ");
            stack.append(bounded(current.getClass().getName(), 256)).append(": ").append(bounded(current.getMessage(), MAX_TEXT_LENGTH)).append('\n');
            StackTraceElement[] frames = current.getStackTrace();
            for (int index = 0; index < Math.min(64, frames.length) && stack.length() < MAX_STACK_LENGTH; index++) {
                StackTraceElement frame = frames[index];
                stack.append(" at ").append(bounded(frame.getClassName(), 256)).append('.').append(bounded(frame.getMethodName(), 256))
                        .append('(').append(bounded(frame.getFileName(), 128)).append(':').append(frame.getLineNumber()).append(")\n");
            }
            current = current.getCause();
        }
        result.add("stackTrace", text(stack.toString(), MAX_STACK_LENGTH));
        return result;
    }
}
