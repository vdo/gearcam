package net.sourceforge.opencamera.audio;

import android.media.MediaCodec;
import android.media.AudioFormat;
import android.os.Build;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Single-threaded AAC encoder. Timestamps count output frames, never callback delivery times. */
final class AacEncoder implements AutoCloseable {
    private MediaCodec codec;
    private final LiveMuxer muxer;
    private final int slot;
    private final boolean ownsMuxer;
    private boolean eos, trackFinished;
    private boolean floatInput;
    // AAC-LC encoders prime their output with 2048 samples (measured on Pixel 7 with c2.android.aac.encoder,
    // AacDelayTest), and MediaMuxer writes no edit list to hide them, so the decoded track would run 42.7 ms
    // behind the video. Dropping that much audio at the start puts it back on the video clock.
    private static final int PRIMING = 2048;
    private int priming = PRIMING;
    private int seed = (int) System.nanoTime() | 1; // dither generator state, never 0
    private short[] pcm16 = new short[0];
    private long frames;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    /** An AAC-only .m4a file. */
    AacEncoder(File file) throws IOException { this(new LiveMuxer(file.getAbsolutePath(), 1), 0, true); }

    /** The audio track of a live recording's MP4. */
    AacEncoder(LiveMuxer muxer) throws IOException { this(muxer, LiveMuxer.AUDIO, false); }

    private AacEncoder(LiveMuxer muxer, int slot, boolean ownsMuxer) throws IOException {
        this.muxer = muxer; this.slot = slot; this.ownsMuxer = ownsMuxer;
        try {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, MixerSettings.RATE, 2);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            int highest = codec.getCodecInfo().getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    .getAudioCapabilities().getBitrateRange().getUpper();
            format.setInteger(MediaFormat.KEY_BIT_RATE, Math.min(512000, highest));
            // Request float input, then verify the codec's negotiated format. Some AAC codecs
            // accept only PCM16 even though the surrounding capture/mixer supports float.
            if (Build.VERSION.SDK_INT >= 24) format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT);
            try { codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); }
            catch (IllegalArgumentException | MediaCodec.CodecException unsupportedFloat) {
                codec.release();
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                if (Build.VERSION.SDK_INT >= 24) format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            MediaFormat accepted = codec.getInputFormat();
            floatInput = Build.VERSION.SDK_INT >= 24 && accepted.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    accepted.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
            codec.start();
        } catch (IOException | RuntimeException e) {
            close(); throw e;
        }
    }

    void write(float[] samples, int count) throws IOException {
        int offset = Math.min(priming, count); // skip the encoder's priming delay at the start (see PRIMING)
        priming -= offset;
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (offset < count) {
            int index = codec.dequeueInputBuffer(10000);
            if (index >= 0) {
                ByteBuffer buffer = codec.getInputBuffer(index);
                if (buffer == null) throw new IOException("AAC encoder did not provide an input buffer");
                buffer.clear(); buffer.order(ByteOrder.nativeOrder());
                int bytesPerFrame = floatInput ? 8 : 4;
                int n = Math.min(count - offset, buffer.remaining() / bytesPerFrame);
                if (n == 0) throw new IOException("AAC encoder input buffer is too small");
                // Bulk puts and no per-sample calls: this runs on the mix thread, possibly interpreted.
                if (floatInput) buffer.asFloatBuffer().put(samples, offset * 2, n * 2);
                else {
                    if (pcm16.length < n * 2) pcm16 = new short[n * 2];
                    for (int i = 0; i < n * 2; i++) {
                        seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5; // xorshift32
                        float triangular = ((seed >>> 16) - (seed & 0xffff)) / 65536f; // TPDF dither, (-1, 1) LSB
                        float value = samples[offset * 2 + i] * 32768 + triangular;
                        int rounded = value >= 32767 ? 32767 : value <= -32768 ? -32768 : (int) (value + 32768.5f) - 32768;
                        pcm16[i] = (short) rounded;
                    }
                    buffer.asShortBuffer().put(pcm16, 0, n * 2);
                }
                codec.queueInputBuffer(index, 0, n * bytesPerFrame, frames * 1_000_000L / MixerSettings.RATE, 0);
                frames += n; offset += n;
            }
            drain(false);
            if (System.nanoTime() > deadline) throw new IOException("AAC encoder stalled");
        }
    }

    void finish() throws IOException {
        long deadline = System.nanoTime() + 3_000_000_000L;
        boolean queued = false;
        while (!eos) {
            if (!queued) {
                int index = codec.dequeueInputBuffer(10000);
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, frames * 1_000_000L / MixerSettings.RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    queued = true;
                }
            }
            drain(true);
            if (System.nanoTime() > deadline) throw new IOException("AAC encoder did not finish");
        }
        finishTrack();
        if (frames == 0) throw new IOException("No audio was captured");
        if (ownsMuxer) muxer.await(10_000); // the .m4a is complete
    }

    private void finishTrack() {
        if (!trackFinished) { trackFinished = true; muxer.finish(slot); }
    }

    private void drain(boolean wait) throws IOException {
        while (true) {
            int index = codec.dequeueOutputBuffer(info, wait ? 10000 : 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxer.format(slot, codec.getOutputFormat());
            } else if (index >= 0) {
                try {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        ByteBuffer buffer = codec.getOutputBuffer(index);
                        if (buffer == null) throw new IOException("Missing AAC output buffer");
                        buffer.position(info.offset); buffer.limit(info.offset + info.size);
                        muxer.write(slot, buffer, info);
                    }
                    eos |= (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                } finally { codec.releaseOutputBuffer(index, false); }
                if (eos) return;
            }
        }
    }

    @Override public void close() {
        if (codec != null) {
            try { codec.stop(); } catch (RuntimeException ignored) { }
            try { codec.release(); } catch (RuntimeException ignored) { }
            codec = null;
        }
        finishTrack(); // a shared recording can complete without more audio
        if (ownsMuxer) muxer.abandon(); // no-op once the .m4a is complete
    }
}
