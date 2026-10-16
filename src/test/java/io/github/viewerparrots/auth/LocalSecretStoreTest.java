package io.github.viewerparrots.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class LocalSecretStoreTest {
    @Test
    void missingSecretDoesNotCreateFiles(@TempDir Path directory) throws IOException {
        Path config = directory.resolve("config");
        LocalSecretStore store = new LocalSecretStore(config);

        assertEquals("", store.read("twitch-session"));
        store.delete("openrouter");
        assertFalse(Files.exists(config));
    }

    @Test
    void rejectsNamesThatCouldReadOrOverwriteOtherFiles(@TempDir Path directory) {
        LocalSecretStore store = new LocalSecretStore(directory);

        for (String name : new String[]{null, "", "Twitch-session", "../token", "twitch-session.txt",
                "openrouter/other", "C:\\token", "secret_name_that_must_not_be_echoed"}) {
            assertThrows(IllegalArgumentException.class, () -> store.read(name));
            assertThrows(IllegalArgumentException.class, () -> store.write(name, "dummy"));
            assertThrows(IllegalArgumentException.class, () -> store.delete(name));
        }
    }

    @Test
    void fallbackPreservesUnicodeWhitespaceAndReplacesWholeSecret(@TempDir Path directory) throws IOException {
        LocalSecretStore store = new LocalSecretStore(directory, false);
        String original = "  тестовая птица 🦜\n\"access\":\"dummy\"\n ";

        store.write("twitch-session", original);
        assertEquals(original, store.read("twitch-session"));
        store.write("twitch-session", "next_dummy");

        assertEquals("next_dummy", store.read("twitch-session"));
        assertEquals("next_dummy", Files.readString(directory.resolve("viewerparrots-twitch-session.credentials")));
        try (var entries = Files.list(directory)) {
            assertEquals(1, entries.count());
        }
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(directory.resolve("viewerparrots-twitch-session.credentials")));
        }
    }

    @Test
    void deleteAndEmptyWriteRemoveOnlyTheSelectedSecret(@TempDir Path directory) throws IOException {
        LocalSecretStore store = new LocalSecretStore(directory, false);
        store.write("twitch-session", "session_dummy");
        store.write("openrouter", "key_dummy");

        store.delete("twitch-session");
        assertEquals("", store.read("twitch-session"));
        assertEquals("key_dummy", store.read("openrouter"));
        store.write("openrouter", "");
        assertEquals("", store.read("openrouter"));
        try (var entries = Files.list(directory)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void sizeLimitCountsUtf8BytesAndPreservesLastGoodSecret(@TempDir Path directory) throws IOException {
        LocalSecretStore store = new LocalSecretStore(directory, false);
        store.write("twitch-session", "last_good_dummy");

        IOException error = assertThrows(IOException.class,
                () -> store.write("twitch-session", "🦜".repeat(4097)));

        assertFalse(error.toString().contains("🦜"));
        assertNull(error.getCause());
        assertEquals("last_good_dummy", store.read("twitch-session"));
        store.write("openrouter", "a".repeat(16 * 1024));
        assertEquals(16 * 1024, store.read("openrouter").length());
    }

    @Test
    void corruptOrOversizedFilesFailWithoutExposingContents(@TempDir Path directory) throws IOException {
        LocalSecretStore store = new LocalSecretStore(directory, false);
        Path credentials = directory.resolve("viewerparrots-openrouter.credentials");
        Files.writeString(credentials, "sensitive_dummy".repeat(2000));

        IOException oversized = assertThrows(IOException.class, () -> store.read("openrouter"));
        assertFalse(oversized.toString().contains("sensitive_dummy"));
        assertNull(oversized.getCause());
        Files.write(credentials, new byte[]{(byte) 0xc3, (byte) 0x28});
        IOException invalidUtf8 = assertThrows(IOException.class, () -> store.read("openrouter"));
        assertNull(invalidUtf8.getCause());
    }

    @Test
    void filesystemFailuresHideUnderlyingDetails(@TempDir Path directory) throws IOException {
        Path config = directory.resolve("sensitive_dummy_path");
        Files.writeString(config, "dummy");
        LocalSecretStore store = new LocalSecretStore(config, false);

        IOException error = assertThrows(IOException.class, () -> store.write("openrouter", "sensitive_dummy"));

        assertFalse(error.toString().contains("sensitive_dummy"));
        assertNull(error.getCause());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsDpapiRoundTripsUnicodeWithoutPlaintextOnDisk(@TempDir Path directory) throws IOException {
        LocalSecretStore store = new LocalSecretStore(directory);
        String secret = " dummy_секрет 🦜\n\"refresh_token\":\"dummy\" ";

        store.write("twitch-session", secret);

        assertEquals(secret, store.read("twitch-session"));
        String encrypted = Files.readString(directory.resolve("viewerparrots-twitch-session.dpapi"));
        assertTrue(encrypted.startsWith("01000000"));
        assertFalse(encrypted.contains("refresh_token"));
        assertEquals(secret, powerShell(
                "$s=ConvertTo-SecureString ([Console]::In.ReadToEnd()); "
                        + "[Console]::Out.Write(([System.Net.NetworkCredential]::new('', $s)).Password)", encrypted));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void readsExistingPowerShellSecureStringTextWithBom(@TempDir Path directory) throws IOException {
        String secret = "legacy_dummy_ключ 🦜";
        String encrypted = powerShell(
                "$s=ConvertTo-SecureString ([Console]::In.ReadToEnd()) -AsPlainText -Force; "
                        + "[Console]::Out.Write((ConvertFrom-SecureString $s))", secret);
        Files.writeString(directory.resolve("viewerparrots-openrouter.dpapi"), "\uFEFF" + encrypted + "\r\n");

        assertEquals(secret, new LocalSecretStore(directory).read("openrouter"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsDecryptFailureDoesNotExposePowerShellOutput(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("viewerparrots-twitch-session.dpapi"), "invalid_sensitive_dummy");

        IOException error = assertThrows(IOException.class,
                () -> new LocalSecretStore(directory).read("twitch-session"));

        assertFalse(error.toString().contains("sensitive_dummy"));
        assertNull(error.getCause());
    }

    private static String powerShell(String script, String input) throws IOException {
        Path executable = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        String encoding = "$ErrorActionPreference='Stop'; "
                + "[Console]::InputEncoding=[System.Text.UTF8Encoding]::new($false); "
                + "[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false); ";
        ProcessBuilder builder = new ProcessBuilder(executable.toString(), "-NoProfile", "-NonInteractive",
                "-WindowStyle", "Hidden", "-Command", encoding + script)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().remove("PSModulePath");
        Process process = builder.start();
        try {
            try (var stream = process.getOutputStream()) {
                stream.write(input.getBytes(StandardCharsets.UTF_8));
            }
            byte[] result = process.getInputStream().readNBytes(128 * 1024);
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            return new String(result, StandardCharsets.UTF_8);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Test interrupted");
        } finally {
            process.destroyForcibly();
        }
    }
}
