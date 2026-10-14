package io.github.viewerparrots.diagnostics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.viewerparrots.domain.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class AuditLogTest {
    private static final UUID COMMAND = new UUID(0, 1), INCARNATION = new UUID(0, 2), ENTITY = new UUID(0, 3);
    private static final AuditLog.Context CONTEXT = new AuditLog.Context(COMMAND, "viewer1", INCARNATION, 7L, ENTITY, "COME");
    private static final class Clock implements AuditLog.Clock {
        long nanos;
        public long nanoTime() { return nanos; }
        void advanceMillis(long amount) { nanos += amount * 1_000_000L; }
    }
    private static final class Sink implements AuditLog.Sink {
        final List<String> lines = new ArrayList<>();
        final List<AuditLog.Level> levels = new ArrayList<>();
        boolean debugEnabled = true, fail;
        public boolean debugEnabled() { return debugEnabled; }
        public void emit(AuditLog.Level level, String json) {
            if (fail) throw new IllegalStateException("Broken audit sink");
            levels.add(level); lines.add(json);
        }
        JsonObject line(int index) { return JsonParser.parseString(lines.get(index)).getAsJsonObject(); }
    }

    @Test void allTenFieldsContainFullIdentityAndActualElapsedDuration() {
        Clock clock = new Clock(); Sink sink = new Sink(); AuditLog.Engine engine = new AuditLog.Engine(clock, sink);
        long started = engine.started(); clock.nanos = 12_500_000;
        engine.emit(AuditLog.Level.INFO, "command_completed", CONTEXT, "SUCCEEDED", null, started, Map.of("target", "alive"), null);
        assertEquals(1, sink.lines.size(), "INFO must produce one structured line");
        JsonObject line = sink.line(0);
        assertTrue(line.keySet().containsAll(Set.of("event", "commandId", "viewerId", "incarnationId", "generation", "entityUuid", "action", "status", "reason", "durationMs")));
        assertEquals(COMMAND.toString(), line.get("commandId").getAsString());
        assertEquals("viewer1", line.get("viewerId").getAsString());
        assertEquals(INCARNATION.toString(), line.get("incarnationId").getAsString());
        assertEquals(7, line.get("generation").getAsLong());
        assertEquals(ENTITY.toString(), line.get("entityUuid").getAsString());
        assertEquals("COME", line.get("action").getAsString());
        assertEquals("SUCCEEDED", line.get("status").getAsString());
        assertTrue(line.get("reason").isJsonNull());
        assertEquals(12.5, line.get("durationMs").getAsDouble());
        assertEquals("alive", line.getAsJsonObject("details").get("target").getAsString());
        assertEquals(AuditLog.Level.INFO, sink.levels.getFirst());
    }

    @Test void absentContextAndUnavailableFieldsRemainExplicitJsonNulls() {
        Clock clock = new Clock(); Sink sink = new Sink(); AuditLog.Engine engine = new AuditLog.Engine(clock, sink);
        engine.emit(AuditLog.Level.INFO, "startup", null, null, null, engine.started(), null, null);
        assertEquals(1, sink.lines.size(), "Startup must produce one structured line even without identity");
        JsonObject line = sink.line(0);
        for (String key : List.of("commandId", "viewerId", "incarnationId", "generation", "entityUuid", "action", "status", "reason")) assertTrue(line.get(key).isJsonNull(), key);
        assertEquals(new AuditLog.Context(COMMAND, null, null, null, null, null), AuditLog.Context.of(COMMAND, null, null));
        assertEquals(new AuditLog.Context(null, null, null, null, null, null), AuditLog.Context.viewer(null, null, null));
    }

    @Test void contextCopiesImmutableIdentityFromRecordWithoutInventingCommandId() {
        ViewerParrotRecord record = ViewerParrotRecord.created(new Viewer(ViewerId.of("viewer1"), DisplayName.of("Bird"), Instant.EPOCH),
                INCARNATION, ENTITY, ParrotVariant.YELLOW_BLUE, ParrotTrait.NORMAL, null, Instant.EPOCH);
        AuditLog.Context context = AuditLog.Context.of(null, record, "CREATE");
        assertNull(context.commandId()); assertEquals("viewer1", context.viewerId());
        assertEquals(INCARNATION, context.incarnationId()); assertEquals(1L, context.generation()); assertEquals(ENTITY, context.entityUuid());
    }

    @Test void userTextAndThrowableAreEscapedBoundedAndNeverWriteAdditionalLines() {
        Clock clock = new Clock(); Sink sink = new Sink(); AuditLog.Engine engine = new AuditLog.Engine(clock, sink);
        String injected = "name\n\r\t\"{malicious}" + "a".repeat(20_000);
        engine.emit(AuditLog.Level.ERROR, "service_failure", CONTEXT, "REJECTED", "INTERNAL_ERROR", engine.started(),
                Map.of("name", injected), new IllegalStateException(injected));
        assertEquals(1, sink.lines.size(), "Unexpected failure must produce its structured diagnostic");
        String json = sink.lines.getFirst(); JsonObject line = sink.line(0);
        assertFalse(json.contains("\n")); assertFalse(json.contains("\r"));
        assertTrue(line.getAsJsonObject("details").get("name").getAsString().length() <= AuditLog.MAX_TEXT_LENGTH);
        JsonObject error = line.getAsJsonObject("error");
        assertEquals(IllegalStateException.class.getName(), error.get("type").getAsString());
        assertTrue(error.get("message").getAsString().length() <= AuditLog.MAX_TEXT_LENGTH);
        assertTrue(error.get("stackTrace").getAsString().length() <= AuditLog.MAX_STACK_LENGTH);
        assertTrue(error.get("stackTrace").getAsString().contains("AuditLogTest"));
    }

    @Test void disabledDebugAvoidsClockAndDetailsTraversalEntirely() {
        Sink sink = new Sink(); sink.debugEnabled = false;
        AuditLog.Engine engine = new AuditLog.Engine(() -> { throw new AssertionError("Clock must not run for disabled DEBUG"); }, sink);
        Map<String, Object> hostile = new AbstractMap<>() {
            public Set<Entry<String, Object>> entrySet() { fail("Disabled debug must not inspect details"); return Set.of(); }
        };
        assertDoesNotThrow(() -> engine.emit(AuditLog.Level.DEBUG, "transition", CONTEXT, "IDLE", null, 0, hostile, null));
        assertTrue(sink.lines.isEmpty()); assertEquals(0, engine.cachedWarnings());
    }

    @Test void warnsCoalesceByViewerAndReasonAtExactSixtySecondBoundary() {
        Clock clock = new Clock(); Sink sink = new Sink(); AuditLog.Engine engine = new AuditLog.Engine(clock, sink);
        engine.emit(AuditLog.Level.WARN, "missing", CONTEXT, "MISSING", "PARROT_ENTITY_MISSING", 0, null, null);
        clock.advanceMillis(59_999);
        engine.emit(AuditLog.Level.WARN, "other_event", CONTEXT, "MISSING", "PARROT_ENTITY_MISSING", 0, null, null);
        assertEquals(1, sink.lines.size());
        clock.advanceMillis(1);
        engine.emit(AuditLog.Level.WARN, "missing", CONTEXT, "MISSING", "PARROT_ENTITY_MISSING", 0, null, null);
        assertEquals(2, sink.lines.size()); assertEquals(0, sink.line(0).get("repeatCount").getAsLong());
        assertEquals(2, sink.line(1).get("repeatCount").getAsLong());
        engine.emit(AuditLog.Level.WARN, "missing", AuditLog.Context.viewer(null, ViewerId.of("viewer2"), null), "MISSING", "PARROT_ENTITY_MISSING", 0, null, null);
        engine.emit(AuditLog.Level.WARN, "timeout", CONTEXT, "MISSING", "RECOVERY_TIMEOUT", 0, null, null);
        assertEquals(4, sink.lines.size());
    }

    @Test void capacityOverflowDoesNotEvictLiveThrottleKeysOrReenableWarnings() {
        Clock clock = new Clock(); Sink sink = new Sink(); AuditLog.Engine engine = new AuditLog.Engine(clock, sink, 1, 120_000_000_000L);
        engine.emit(AuditLog.Level.WARN, "missing", CONTEXT, "MISSING", "same", 0, null, null);
        clock.advanceMillis(1);
        engine.emit(AuditLog.Level.WARN, "missing", AuditLog.Context.viewer(null, ViewerId.of("other"), null), "MISSING", "other", 0, null, null);
        engine.emit(AuditLog.Level.WARN, "missing", CONTEXT, "MISSING", "same", 0, null, null);
        assertEquals(1, sink.lines.size()); assertEquals(1, engine.cachedWarnings());
        clock.advanceMillis(120_000);
        engine.emit(AuditLog.Level.WARN, "missing", AuditLog.Context.viewer(null, ViewerId.of("other"), null), "MISSING", "other", 0, null, null);
        assertEquals(2, sink.lines.size()); assertEquals(1, engine.cachedWarnings());
        engine.clear(); assertEquals(0, engine.cachedWarnings());
    }

    @Test void concurrentWarnsCannotEmitMoreThanOneLineInWindow() throws Exception {
        Clock clock = new Clock(); Sink sink = new Sink(); AuditLog.Engine engine = new AuditLog.Engine(clock, sink);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 20; i++) jobs.add(pool.submit(() -> engine.emit(AuditLog.Level.WARN, "fault", CONTEXT, "IDLE", "BEHAVIOR_FAILURE", 0, null, null)));
            for (var job : jobs) job.get();
        }
        assertEquals(1, sink.lines.size()); clock.advanceMillis(60_000);
        engine.emit(AuditLog.Level.WARN, "fault", CONTEXT, "IDLE", "BEHAVIOR_FAILURE", 0, null, null);
        assertEquals(20, sink.line(1).get("repeatCount").getAsLong());
    }

    @Test void loggerFailureAndUnsupportedDetailObjectsCannotChangeGameplay() {
        Clock clock = new Clock(); Sink sink = new Sink(); AuditLog.Engine engine = new AuditLog.Engine(clock, sink);
        sink.fail = true;
        assertDoesNotThrow(() -> engine.emit(AuditLog.Level.ERROR, "fault", CONTEXT, "FAILED", "INTERNAL_ERROR", 0, null, null));
        sink.fail = false;
        Object unsupported = new Object() { public String toString() { throw new AssertionError("Never stringify arbitrary gameplay objects"); } };
        assertDoesNotThrow(() -> engine.emit(AuditLog.Level.INFO, "healthy", CONTEXT, "SUCCEEDED", null, 0, Map.of("object", unsupported), null));
        assertEquals(1, sink.lines.size()); assertTrue(sink.line(0).getAsJsonObject("details").get("object").getAsString().contains("unsupported"));
    }

    @Test void feedbackTextEscapesControlsBoundsCodepointsAndPreservesAstralPairs() {
        String safe = AuditLog.safeText("C:\\world\\data\n\r\t\u0000" + "🦜".repeat(600));
        assertTrue(safe.startsWith("C:\\world\\data\\n\\r\\t\\u0000"));
        assertFalse(safe.contains("\n")); assertFalse(safe.contains("\r")); assertFalse(safe.contains("\t"));
        assertTrue(safe.codePointCount(0, safe.length()) <= 512);
        for (int i = 0; i < safe.length(); i++) {
            char value = safe.charAt(i);
            if (Character.isHighSurrogate(value)) assertTrue(i + 1 < safe.length() && Character.isLowSurrogate(safe.charAt(++i)));
            else assertFalse(Character.isLowSurrogate(value));
        }
        assertEquals("", AuditLog.safeText(null));
    }
}
