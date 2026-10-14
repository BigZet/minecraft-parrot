package io.github.viewerparrots.gametest.crash;

import io.github.viewerparrots.gametest.CrashAcceptanceHarness;
import io.github.viewerparrots.storage.JsonParrotRepository;
import io.github.viewerparrots.storage.dto.JournalEntryDto;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.nio.file.Path;

/** Abrupt process gates belong only to the test mod; the release has no crash switches. */
@Mixin(value = JsonParrotRepository.class, remap = false)
public abstract class RepositoryCrashGateMixin {
    @Inject(method = "appendJournal", at = @At("HEAD"))
    private void beforeAppend(JournalEntryDto entry, CallbackInfo ci) {
        CrashAcceptanceHarness.beforeAppend(entry);
    }

    @Inject(method = "appendJournal", at = @At("RETURN"))
    private void afterAppend(JournalEntryDto entry, CallbackInfo ci) {
        CrashAcceptanceHarness.afterAppend(entry);
    }

    @Inject(method = "writeAndForce", at = @At("HEAD"))
    private static void beforeSnapshotWrite(Path file, String content, CallbackInfo ci) throws IOException {
        CrashAcceptanceHarness.beforeSnapshotWrite(file);
    }

    @Inject(method = "writeAndForce", at = @At("RETURN"))
    private static void afterSnapshotForce(Path file, String content, CallbackInfo ci) {
        CrashAcceptanceHarness.afterSnapshotForce(file, content);
    }
}
