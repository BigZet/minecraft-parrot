package io.github.viewerparrots.speech;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Read on the storage worker only; credentials never become game/config DTOs. */
public final class OpenRouterToken {
    private OpenRouterToken() { }

    public static String load(Path configDirectory) throws IOException {
        String environment = System.getenv("OPENROUTER_API_KEY");
        if (environment != null && !environment.isBlank()) return environment.strip();
        Path local = configDirectory.resolve("viewerparrots-openrouter-token.txt");
        if (Files.isRegularFile(local)) return Files.readString(local).replace("﻿", "").strip();

        // Reuse the key already configured for the local voice tester on this PC.
        String appData = System.getenv("LOCALAPPDATA");
        String windows = System.getenv("SystemRoot");
        if (appData == null || windows == null) return "";
        Path encrypted = Path.of(appData, "ViewerParrots", "openrouter-key.dpapi");
        if (!Files.isRegularFile(encrypted)) return "";
        Path powerShell = Path.of(windows, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        // The game may inherit a PSModulePath without Windows PowerShell's built-in modules (PowerShell 7 parent,
        // launcher environment); importing Security by its $PSHOME path keeps ConvertTo-SecureString available.
        String command = "$ErrorActionPreference='Stop'; "
                + "Import-Module ($PSHOME+'\\Modules\\Microsoft.PowerShell.Security\\Microsoft.PowerShell.Security.psd1'); "
                + "$secret = ConvertTo-SecureString ([Console]::In.ReadToEnd().Trim()); "
                + "([System.Net.NetworkCredential]::new('', $secret)).Password";
        if (Files.size(encrypted) > 65536) return "";
        Process process = new ProcessBuilder(powerShell.toString(), "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", command)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(Files.readString(encrypted, StandardCharsets.UTF_8).strip().replace("\uFEFF", "").getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) return "";
            return new String(process.getInputStream().readNBytes(512), StandardCharsets.UTF_8).strip();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            process.destroyForcibly();
        }
    }
}
