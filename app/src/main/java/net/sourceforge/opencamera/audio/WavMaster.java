package net.sourceforge.opencamera.audio;

import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;

/** Lossless PCM24 copy of the stereo mix. No sample-rate increase or lossy encoding. */
final class WavMaster implements AutoCloseable {
    private final FileOutputStream output;
    private final byte[] block = new byte[480 * 6];
    private long bytes;
    private boolean closed;

    WavMaster(File file) throws IOException {
        this(new FileOutputStream(file));
    }

    WavMaster(FileOutputStream output) throws IOException {
        this.output = output;
        try {
            output.getChannel().truncate(0).position(0);
            ascii("RIFF"); le(36, 4); ascii("WAVEfmt "); le(16, 4);
            le(1, 2); le(2, 2); le(MixerSettings.RATE, 4); le(MixerSettings.RATE * 6, 4);
            le(6, 2); le(24, 2); ascii("data"); le(0, 4);
        } catch (IOException | RuntimeException failure) {
            try { output.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    void write(float[] pcm, int frames) throws IOException {
        if (bytes + frames * 6L > 0xfffffff0L - 44) throw new IOException("WAV master reached its 4 GB limit");
        for (int start = 0; start < frames; start += 480) {
            int count = Math.min(480, frames - start);
            for (int i = 0; i < count * 2; i++) {
                int sample = Math.max(-8388608, Math.min(8388607, Math.round(pcm[start * 2 + i] * 8388608)));
                block[i * 3] = (byte) sample; block[i * 3 + 1] = (byte) (sample >> 8); block[i * 3 + 2] = (byte) (sample >> 16);
            }
            output.write(block, 0, count * 6); bytes += count * 6L;
        }
    }

    private void ascii(String text) throws IOException { output.write(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII)); }

    private void le(long value, int count) throws IOException {
        for (int i = 0; i < count; i++) output.write((int) (value >> (i * 8)) & 255);
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        try { output.getChannel().position(4); le(bytes + 36, 4); output.getChannel().position(40); le(bytes, 4); }
        finally { output.close(); }
    }
}
