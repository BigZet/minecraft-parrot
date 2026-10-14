package io.github.viewerparrots.api.event;

import io.github.viewerparrots.domain.ViewerId;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class EventDispatcherTest {
    @Test
    void globalCommandEventsAreNullableButEntityEventsStillRequireViewer() {
        for (ParrotEvent.Type type : List.of(ParrotEvent.Type.COMMAND_ACCEPTED,
                ParrotEvent.Type.COMMAND_COMPLETED, ParrotEvent.Type.COMMAND_REJECTED)) {
            assertNull(ParrotEvent.of(type, null, null, 0, Instant.EPOCH, 1,
                    UUID.randomUUID(), null, "SAVE", null).viewerId());
        }
        for (ParrotEvent.Type type : ParrotEvent.Type.values()) {
            if (type == ParrotEvent.Type.COMMAND_ACCEPTED || type == ParrotEvent.Type.COMMAND_COMPLETED || type == ParrotEvent.Type.COMMAND_REJECTED) continue;
            assertThrows(NullPointerException.class, () -> ParrotEvent.of(type, null, null, 0,
                    Instant.EPOCH, 1, null, null, null, null));
        }
    }

    @Test
    void failingListenerEmitsStructuredIdentityAndDoesNotInterruptDelivery() {
        Logger logger = (Logger) LogManager.getLogger("viewerparrots");
        Level previous = logger.getLevel();
        List<String> messages = new ArrayList<>();
        AbstractAppender capture = new AbstractAppender("dispatcher-capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) { messages.add(event.getMessage().getFormattedMessage()); }
        };
        capture.start();
        logger.addAppender(capture);
        logger.setLevel(Level.ALL);
        try {
            EventDispatcher dispatcher = new EventDispatcher();
            List<String> delivered = new ArrayList<>();
            dispatcher.subscribe(event -> { delivered.add("failing"); throw new IllegalStateException("bad\nlistener"); });
            dispatcher.subscribe(event -> delivered.add("remaining"));
            UUID command = UUID.randomUUID();
            UUID incarnation = UUID.randomUUID();
            dispatcher.publish(ParrotEvent.of(ParrotEvent.Type.COMMAND_ACCEPTED,
                    ViewerId.of("dispatcher-viewer"), incarnation, 7, Instant.EPOCH, 1,
                    command, null, "SIT", null));
            assertEquals(List.of("failing", "remaining"), delivered);
            assertEquals(1, messages.size());
            assertTrue(messages.getFirst().startsWith("{"), "Listener failure must emit structured JSON instead of raw prose");
            var line = JsonParser.parseString(messages.getFirst()).getAsJsonObject();
            assertEquals("listener_failed", line.get("event").getAsString());
            assertEquals(command.toString(), line.get("commandId").getAsString());
            assertEquals("dispatcher-viewer", line.get("viewerId").getAsString());
            assertEquals(incarnation.toString(), line.get("incarnationId").getAsString());
            assertEquals(7, line.get("generation").getAsLong());
            assertTrue(line.has("entityUuid") && line.get("entityUuid").isJsonNull());
            assertEquals("SIT", line.get("action").getAsString());
            assertEquals("FAILED", line.get("status").getAsString());
            assertEquals("INTERNAL_ERROR", line.get("reason").getAsString());
            assertTrue(line.get("durationMs").getAsDouble() >= 0);
            assertFalse(messages.getFirst().contains("\n"), "One listener cannot inject a second log line");
        } finally {
            logger.removeAppender(capture);
            logger.setLevel(previous);
            capture.stop();
        }
    }

    @Test
    void listenerChangesApplyToNextEventWithoutInterruptingCurrentDelivery() {
        EventDispatcher dispatcher = new EventDispatcher();
        List<String> received = new ArrayList<>();
        Consumer<ParrotEvent> second = e -> received.add("second");
        Consumer<ParrotEvent> added = e -> received.add("added");
        dispatcher.subscribe(e -> {
            received.add("first");
            dispatcher.unsubscribe(second);
            dispatcher.subscribe(added);
        });
        dispatcher.subscribe(second);
        ParrotEvent event = ParrotEvent.of(ParrotEvent.Type.COMMAND_ACCEPTED,
                ViewerId.of("viewer"), UUID.randomUUID(), 1, Instant.EPOCH, 1,
                UUID.randomUUID(), null, null, null);
        assertDoesNotThrow(() -> dispatcher.publish(event));
        assertEquals(List.of("first", "second"), received);
        received.clear();
        dispatcher.publish(event);
        assertEquals(List.of("first", "added"), received);
    }
}
