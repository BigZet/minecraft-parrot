package io.github.viewerparrots.client;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import io.github.viewerparrots.network.SpeechAudioPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.AudioStream;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundLoader;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Bounded client reception and positional playback through the game's normal sound manager. */
public final class SpeechAudioReceiver {
    private static final int MAX_INCOMPLETE_CLIPS = 8;
    private static final int MAX_ACTIVE_CLIPS = 8;
    private static final long EXPIRY_NANOS = TimeUnit.SECONDS.toNanos(20);
    private static final Map<UUID, Assembly> INCOMPLETE = new LinkedHashMap<>();
    private static final Map<UUID, Long> FINISHED = new LinkedHashMap<>();
    private static final Map<UUID, ParrotSpeechSound> ACTIVE = new LinkedHashMap<>();
    private static ClientWorld receivingWorld;
    private static int incompleteBytes;

    private SpeechAudioReceiver() { }

    /** Explicit paid voice preview also works from the title screen, without a world or bird. */
    public static void preview(byte[] pcm) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (pcm.length == 0 || pcm.length > SpeechAudioPayload.MAX_PCM_BYTES) return;
            client.getSoundManager().play(new PreviewSound(pcm));
        });
    }

    public static void stopAll() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.getSoundManager().stopSounds(ViewerParrotsMod.id("parrot_speech"), null);
        clear(client);
    }

    private static final class PreviewSound extends MovingSoundInstance {
        private final PcmSpeechAudioStream stream;
        private PreviewSound(byte[] pcm) {
            super(SoundEvent.of(ViewerParrotsMod.id("parrot_speech")), SoundCategory.MASTER, SoundInstance.createRandom());
            this.stream = new PcmSpeechAudioStream(new byte[][] {pcm});
            this.relative = true;
            this.attenuationType = SoundInstance.AttenuationType.NONE;
        }
        @Override public void tick() { }
        @Override public CompletableFuture<AudioStream> getAudioStream(SoundLoader loader, Identifier id, boolean repeatInstantly) {
            return CompletableFuture.completedFuture(stream);
        }
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(SpeechAudioPayload.ID,
                (payload, context) -> receive(context.client(), payload));
        ClientTickEvents.END_CLIENT_TICK.register(SpeechAudioReceiver::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear(client));
    }

    private static void receive(MinecraftClient client, SpeechAudioPayload payload) {
        updateWorld(client);
        long now = System.nanoTime();
        expire(now);
        if (receivingWorld == null || !payload.isValidChunk() || FINISHED.containsKey(payload.speechId())) return;
        Assembly assembly = INCOMPLETE.get(payload.speechId());
        if (assembly == null) {
            if (INCOMPLETE.size() >= MAX_INCOMPLETE_CLIPS) return;
            assembly = new Assembly(payload.parrotUuid(), payload.chunkCount(), now);
            INCOMPLETE.put(payload.speechId(), assembly);
        } else if (!assembly.parrotUuid.equals(payload.parrotUuid()) || assembly.chunks.length != payload.chunkCount()) {
            discard(payload.speechId(), now);
            return;
        }
        if (assembly.chunks[payload.chunkIndex()] != null) return;
        if (incompleteBytes + payload.pcm().length > SpeechAudioPayload.MAX_PCM_BYTES) {
            discard(payload.speechId(), now);
            return;
        }
        assembly.chunks[payload.chunkIndex()] = payload.pcm();
        assembly.bytes += payload.pcm().length;
        assembly.received++;
        incompleteBytes += payload.pcm().length;
        if (assembly.received == assembly.chunks.length) {
            INCOMPLETE.remove(payload.speechId());
            incompleteBytes -= assembly.bytes;
            rememberFinished(payload.speechId(), now);
            play(client, assembly);
        }
    }

    private static void play(MinecraftClient client, Assembly assembly) {
        if (speakerPosition(receivingWorld, assembly.parrotUuid) == null) return;
        ParrotSpeechSound previous = ACTIVE.remove(assembly.parrotUuid);
        if (previous != null) previous.stop(client);
        if (ACTIVE.size() >= MAX_ACTIVE_CLIPS) {
            Iterator<ParrotSpeechSound> oldest = ACTIVE.values().iterator();
            oldest.next().stop(client);
            oldest.remove();
        }
        ParrotSpeechSound sound = new ParrotSpeechSound(client, receivingWorld, assembly.parrotUuid, assembly.chunks);
        ACTIVE.put(assembly.parrotUuid, sound);
        client.getSoundManager().play(sound);
    }

    /** The UUID remains the same while vanilla replaces the entity with player shoulder NBT. */
    private static Vec3d speakerPosition(ClientWorld world, UUID uuid) {
        for (Entity entity : world.getEntities()) {
            if (entity instanceof ViewerParrotEntity bird && bird.getUuid().equals(uuid) && bird.isAlive() && !bird.isRemoved())
                return bird.getPos().add(0, bird.getHeight() * 0.5, 0);
        }
        for (var player : world.getPlayers()) {
            if (!player.isAlive()) continue;
            if (matchesShoulder(player.getShoulderEntityLeft(), uuid)) return ViewerShoulderFeature.shoulderPosition(player, true);
            if (matchesShoulder(player.getShoulderEntityRight(), uuid)) return ViewerShoulderFeature.shoulderPosition(player, false);
        }
        return null;
    }

    private static boolean matchesShoulder(NbtCompound nbt, UUID uuid) {
        return ViewerShoulderFeature.isViewerParrot(nbt) && nbt.containsUuid("UUID") && nbt.getUuid("UUID").equals(uuid);
    }

    private static void tick(MinecraftClient client) {
        updateWorld(client);
        expire(System.nanoTime());
        ACTIVE.values().removeIf(sound -> !client.getSoundManager().isPlaying(sound));
    }

    private static void updateWorld(MinecraftClient client) {
        if (receivingWorld != client.world) {
            clear(client);
            receivingWorld = client.world;
        }
    }

    private static void clear(MinecraftClient client) {
        for (ParrotSpeechSound sound : ACTIVE.values()) sound.stop(client);
        ACTIVE.clear();
        INCOMPLETE.clear();
        FINISHED.clear();
        incompleteBytes = 0;
        receivingWorld = null;
    }

    private static void expire(long now) {
        Iterator<Map.Entry<UUID, Assembly>> iterator = INCOMPLETE.entrySet().iterator();
        while (iterator.hasNext()) {
            Assembly assembly = iterator.next().getValue();
            if (now - assembly.started >= EXPIRY_NANOS) {
                incompleteBytes -= assembly.bytes;
                iterator.remove();
            }
        }
        FINISHED.values().removeIf(finished -> now - finished >= EXPIRY_NANOS);
    }

    private static void discard(UUID speechId, long now) {
        Assembly assembly = INCOMPLETE.remove(speechId);
        if (assembly != null) incompleteBytes -= assembly.bytes;
        rememberFinished(speechId, now);
    }

    private static void rememberFinished(UUID speechId, long now) {
        FINISHED.put(speechId, now);
        if (FINISHED.size() > 32) FINISHED.remove(FINISHED.keySet().iterator().next());
    }

    private static final class Assembly {
        private final UUID parrotUuid;
        private final byte[][] chunks;
        private final long started;
        private int bytes;
        private int received;

        private Assembly(UUID parrotUuid, int count, long started) {
            this.parrotUuid = parrotUuid;
            this.chunks = new byte[count][];
            this.started = started;
        }
    }

    private static final class ParrotSpeechSound extends MovingSoundInstance {
        private final MinecraftClient client;
        private final ClientWorld world;
        private final UUID parrotUuid;
        private final PcmSpeechAudioStream stream;

        private ParrotSpeechSound(MinecraftClient client, ClientWorld world, UUID parrotUuid, byte[][] pcm) {
            super(SoundEvent.of(ViewerParrotsMod.id("parrot_speech")), SoundCategory.NEUTRAL, SoundInstance.createRandom());
            this.client = client;
            this.world = world;
            this.parrotUuid = parrotUuid;
            this.stream = new PcmSpeechAudioStream(pcm);
            updatePosition();
        }

        @Override
        public boolean canPlay() {
            // ViewerParrotEntity deliberately reports isSilent() to suppress vanilla calls.
            return client.world == world && speakerPosition(world, parrotUuid) != null;
        }

        @Override
        public void tick() {
            if (!canPlay()) setDone();
            else updatePosition();
        }

        private void updatePosition() {
            Vec3d position = speakerPosition(world, parrotUuid);
            if (position == null) { setDone(); return; }
            x = position.x;
            y = position.y;
            z = position.z;
        }

        @Override
        public CompletableFuture<AudioStream> getAudioStream(SoundLoader loader, Identifier id, boolean repeatInstantly) {
            return CompletableFuture.completedFuture(stream);
        }

        private void stop(MinecraftClient client) {
            setDone();
            client.getSoundManager().stop(this);
        }
    }
}
