package io.github.viewerparrots.network;

import io.github.viewerparrots.ViewerParrotsMod;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** A bounded chunk of signed, little-endian, mono 24 kHz / 16-bit speech PCM. */
public record SpeechAudioPayload(UUID speechId, UUID parrotUuid, int chunkIndex,
                                 int chunkCount, byte[] pcm) implements CustomPayload {
    public static final int CHUNK_BYTES = 48 * 1024;
    public static final int MAX_PCM_BYTES = 2 * 1024 * 1024;
    public static final int MAX_CHUNKS = (MAX_PCM_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES;
    public static final Id<SpeechAudioPayload> ID = new Id<>(ViewerParrotsMod.id("speech_audio"));
    public static final PacketCodec<RegistryByteBuf, SpeechAudioPayload> CODEC =
            PacketCodec.of(SpeechAudioPayload::write, SpeechAudioPayload::read);

    public SpeechAudioPayload {
        Objects.requireNonNull(speechId);
        Objects.requireNonNull(parrotUuid);
        Objects.requireNonNull(pcm);
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    @Override
    public Id<SpeechAudioPayload> getId() {
        return ID;
    }

    private static SpeechAudioPayload read(RegistryByteBuf buffer) {
        return new SpeechAudioPayload(buffer.readUuid(), buffer.readUuid(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readByteArray(CHUNK_BYTES));
    }

    private void write(RegistryByteBuf buffer) {
        if (!isValidChunk()) throw new IllegalArgumentException("Invalid speech audio chunk");
        buffer.writeUuid(speechId);
        buffer.writeUuid(parrotUuid);
        buffer.writeVarInt(chunkIndex);
        buffer.writeVarInt(chunkCount);
        buffer.writeByteArray(pcm);
    }

    public boolean isValidChunk() {
        if (chunkCount < 1 || chunkCount > MAX_CHUNKS || chunkIndex < 0 || chunkIndex >= chunkCount
                || pcm.length < 2 || pcm.length > CHUNK_BYTES || (pcm.length & 1) != 0) {
            return false;
        }
        // Every non-final chunk is full. This also bounds the eventual clip before assembly.
        if (chunkIndex < chunkCount - 1) return pcm.length == CHUNK_BYTES;
        return (long) (chunkCount - 1) * CHUNK_BYTES + pcm.length <= MAX_PCM_BYTES;
    }

    /** Called on the server thread after generation has finished off-thread. */
    public static void send(ServerPlayerEntity player, UUID speechId, UUID parrotUuid, byte[] pcm) {
        Objects.requireNonNull(pcm);
        if (pcm.length < 2 || pcm.length > MAX_PCM_BYTES || (pcm.length & 1) != 0) {
            throw new IllegalArgumentException("Speech PCM must contain 1 to 1048576 complete samples");
        }
        if (!ServerPlayNetworking.canSend(player, ID)) return;
        int count = (pcm.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
        for (int index = 0; index < count; index++) {
            int start = index * CHUNK_BYTES;
            byte[] chunk = Arrays.copyOfRange(pcm, start, Math.min(start + CHUNK_BYTES, pcm.length));
            ServerPlayNetworking.send(player, new SpeechAudioPayload(speechId, parrotUuid, index, count, chunk));
        }
    }
}
