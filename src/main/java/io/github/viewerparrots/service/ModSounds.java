package io.github.viewerparrots.service;

import io.github.viewerparrots.config.ViewerParrotsConfig;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/** The five real parrot recordings selected for the bundled sound pool. */
public final class ModSounds {

    private ModSounds() { }

    public static void register() {
        Identifier speechId = Identifier.of("viewerparrots", "parrot_speech");
        Registry.register(Registries.SOUND_EVENT, speechId, SoundEvent.of(speechId));
        for (String soundId : ViewerParrotsConfig.PARROT_CALL_SOUND_IDS) {
            Identifier id = Identifier.of(soundId);
            Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
        }
    }
}
