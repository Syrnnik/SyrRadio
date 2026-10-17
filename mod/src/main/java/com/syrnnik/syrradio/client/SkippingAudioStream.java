package com.syrnnik.syrradio.client;

import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Поток, который начинает играть не с начала. Нужен тем, кто зашёл посреди
 * трека: ogg перематывать нельзя, поэтому просто декодируем и выбрасываем
 * первые offsetMs. Это идёт в фоновом потоке и занимает доли секунды.
 */
final class SkippingAudioStream implements AudioStream {
    private final AudioStream inner;
    private long skipBytes;

    SkippingAudioStream(AudioStream inner, long offsetMs) {
        this.inner = inner;
        AudioFormat f = inner.getFormat();
        int frame = Math.max(1, f.getFrameSize());
        long bytes = (long) (offsetMs / 1000.0 * f.getSampleRate()) * frame;
        this.skipBytes = Math.max(0, bytes - bytes % frame);
    }

    @Override
    public AudioFormat getFormat() {
        return inner.getFormat();
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        while (skipBytes > 0) {
            ByteBuffer b = inner.read(Math.max(size, 1 << 16));
            int n = b.remaining();
            if (n == 0) {
                skipBytes = 0;
                return b;
            }
            if (n <= skipBytes) {
                skipBytes -= n;
                continue;
            }
            b.position(b.position() + (int) skipBytes);
            skipBytes = 0;
            return b;
        }
        return inner.read(size);
    }

    @Override
    public void close() throws IOException {
        inner.close();
    }
}
