package io.github.viewerparrots.client;

import net.minecraft.client.sound.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;

/** Reads retained network chunks on Minecraft's sound executor, without decoding or file I/O. */
final class PcmSpeechAudioStream implements AudioStream {
    private static final AudioFormat FORMAT = new AudioFormat(24_000, 16, 1, true, false);
    private byte[][] chunks;
    private int chunkIndex;
    private int chunkOffset;
    private ByteBuffer buffer;

    PcmSpeechAudioStream(byte[][] chunks) {
        this.chunks = chunks;
    }

    @Override
    public AudioFormat getFormat() {
        return FORMAT;
    }

    @Override
    public ByteBuffer read(int size) {
        if (chunkIndex >= chunks.length) return null;
        int capacity = size & ~1;
        if (capacity < 2) return null;
        if (buffer == null || buffer.capacity() < capacity) buffer = ByteBuffer.allocateDirect(capacity);
        buffer.clear();
        buffer.limit(capacity);
        while (buffer.hasRemaining() && chunkIndex < chunks.length) {
            byte[] chunk = chunks[chunkIndex];
            int amount = Math.min(buffer.remaining(), chunk.length - chunkOffset);
            buffer.put(chunk, chunkOffset, amount);
            chunkOffset += amount;
            if (chunkOffset == chunk.length) {
                chunkIndex++;
                chunkOffset = 0;
            }
        }
        buffer.flip();
        // Source uploads each returned buffer to OpenAL before calling read again, so reuse is safe.
        return buffer;
    }

    @Override
    public void close() {
        chunks = new byte[0][];
        buffer = null;
    }
}
