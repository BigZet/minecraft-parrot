package io.github.viewerparrots.command;

import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.diagnostics.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CommandFeedbackTest {
    @Test void storageFaultFeedbackIsSingleLineAndBoundsEachDetail() {
        UUID id = UUID.randomUUID();
        String untrusted = "disk\r\n\t" + "x".repeat(5000);
        String feedback = CommandFeedback.render(CommandResult.failed(id, ErrorCode.STORAGE_UNAVAILABLE, untrusted)).getString();
        assertTrue(feedback.contains(id.toString()));
        assertTrue(feedback.contains("STORAGE_UNAVAILABLE"));
        assertFalse(feedback.contains("\n"));
        assertFalse(feedback.contains("\r"));
        assertFalse(feedback.contains("\t"));
        assertTrue(feedback.length() < 650, "Fault details must not flood the client or console");
    }
}
