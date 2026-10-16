package io.github.viewerparrots.auth;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Local credentials only. Invoke on a worker: Windows DPAPI may start PowerShell. */
public final class LocalSecretStore {
    private static final int MAX_SECRET_BYTES = 16 * 1024;
    private static final int MAX_PROTECTED_BYTES = MAX_SECRET_BYTES * 4 + 4096;
    private static final long PROCESS_TIMEOUT_SECONDS = 10;
    private static final Set<PosixFilePermission> OWNER_ONLY =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final String POWERSHELL_SETUP = "$ErrorActionPreference='Stop'; "
            + "[Console]::InputEncoding=[System.Text.UTF8Encoding]::new($false); "
            + "[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false); ";
    private static final String ENCRYPT = POWERSHELL_SETUP
            + "try { $s=ConvertTo-SecureString ([Console]::In.ReadToEnd()) -AsPlainText -Force; "
            + "try { [Console]::Out.Write((ConvertFrom-SecureString $s)) } finally { $s.Dispose() }; exit 0 "
            + "} catch { exit 1 }";
    private static final String DECRYPT = POWERSHELL_SETUP
            + "try { $text=[Console]::In.ReadToEnd().Trim().TrimStart([char]0xFEFF); "
            + "$s=ConvertTo-SecureString $text; "
            + "try { [Console]::Out.Write(([System.Net.NetworkCredential]::new('', $s)).Password) "
            + "} finally { $s.Dispose() }; exit 0 } catch { exit 1 }";

    private final Path configDirectory;
    private final boolean windows;

    public LocalSecretStore(Path configDirectory) {
        this(configDirectory, System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"));
    }

    LocalSecretStore(Path configDirectory, boolean windows) {
        this.configDirectory = Objects.requireNonNull(configDirectory).toAbsolutePath().normalize();
        this.windows = windows;
    }

    /** Missing credentials are represented by an empty string. */
    public synchronized String read(String name) throws IOException {
        Path file = secretPath(name);
        try {
            if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return "";
            int limit = windows ? MAX_PROTECTED_BYTES : MAX_SECRET_BYTES;
            byte[] stored;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                stored = input.readNBytes(limit + 1);
            }
            if (stored.length > limit) throw new IOException();
            byte[] plaintext = windows ? dpapi(DECRYPT, stored, MAX_SECRET_BYTES) : stored;
            return decode(plaintext);
        } catch (IOException | SecurityException failure) {
            throw new IOException("Local credentials could not be read.");
        }
    }

    /** Replaces one credential atomically; an empty value removes it. */
    public synchronized void write(String name, String secret) throws IOException {
        Path file = secretPath(name);
        if (secret == null) throw new IllegalArgumentException("Secret is required.");
        if (secret.isEmpty()) {
            delete(name);
            return;
        }
        Path temporary = null;
        try {
            if (secret.length() > MAX_SECRET_BYTES) throw new IOException();
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(secret));
            if (encoded.remaining() > MAX_SECRET_BYTES) throw new IOException();
            byte[] plaintext = new byte[encoded.remaining()];
            encoded.get(plaintext);
            byte[] stored = windows ? dpapi(ENCRYPT, plaintext, MAX_PROTECTED_BYTES) : plaintext;
            if (windows && stored.length == 0) throw new IOException();
            Files.createDirectories(configDirectory);
            temporary = createTemporary(name);
            Files.write(temporary, stored, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | SecurityException failure) {
            throw new IOException("Local credentials could not be saved.");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | SecurityException ignored) {
                    // No credential contents or underlying exception reach logs.
                }
            }
        }
    }

    public synchronized void delete(String name) throws IOException {
        Path file = secretPath(name);
        try {
            Files.deleteIfExists(file);
        } catch (IOException | SecurityException failure) {
            throw new IOException("Local credentials could not be removed.");
        }
    }

    private Path secretPath(String name) {
        if (!"twitch-session".equals(name) && !"openrouter".equals(name)) {
            throw new IllegalArgumentException("Unsupported local credential name.");
        }
        return configDirectory.resolve("viewerparrots-" + name + (windows ? ".dpapi" : ".credentials"));
    }

    private Path createTemporary(String name) throws IOException {
        String prefix = ".viewerparrots-" + name + "-";
        if (Files.getFileStore(configDirectory).supportsFileAttributeView("posix")) {
            return Files.createTempFile(configDirectory, prefix, ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        }
        Path temporary = Files.createTempFile(configDirectory, prefix, ".tmp");
        if (!windows) {
            try {
                AclFileAttributeView acl = Files.getFileAttributeView(temporary, AclFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (acl == null) throw new IOException();
                acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            } catch (IOException | SecurityException failure) {
                Files.deleteIfExists(temporary);
                throw new IOException();
            }
        }
        return temporary;
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** The static command is public code; all secret material travels through stdin. */
    private static byte[] dpapi(String command, byte[] input, int outputLimit) throws IOException {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) throw new IOException();
        Path powerShell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        ProcessBuilder builder = new ProcessBuilder(powerShell.toString(), "-NoProfile", "-NonInteractive",
                "-WindowStyle", "Hidden", "-Command", command)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        // A launcher running pwsh 7 may export incompatible module paths to Windows PowerShell.
        builder.environment().remove("PSModulePath");
        Process process = builder.start();
        ExecutorService io = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "viewerparrots-local-credentials");
            thread.setDaemon(true);
            return thread;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROCESS_TIMEOUT_SECONDS);
        try {
            Future<?> writer = io.submit(() -> {
                try (var stream = process.getOutputStream()) {
                    stream.write(input);
                }
                return null;
            });
            Future<byte[]> reader = io.submit(() -> {
                try (var stream = process.getInputStream()) {
                    return stream.readNBytes(outputLimit + 1);
                }
            });
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException();
            }
            writer.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            byte[] result = reader.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (result.length > outputLimit) throw new IOException();
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException();
        } catch (ExecutionException | TimeoutException failure) {
            throw new IOException();
        } finally {
            process.destroyForcibly();
            io.shutdownNow();
        }
    }
}
