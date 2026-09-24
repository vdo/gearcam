package net.sourceforge.opencamera.audio;

/** A bounded timestamped ring. Interpolation puts independent device clocks onto the video clock. */
public final class TimedPcmBuffer {
    private final int channels, capacity;
    private final float[] samples;
    private final long[] times;
    private int head, size;

    public TimedPcmBuffer(int channels, int capacity) {
        this.channels = channels; this.capacity = capacity;
        samples = new float[channels * capacity]; times = new long[capacity];
    }

    public synchronized void append(float[] data, int frames, long firstNs, double periodNs) {
        for (int f = 0; f < frames; f++) {
            long time = firstNs + Math.round(f * periodNs);
            if (size > 0 && time <= times[(head + size - 1) % capacity]) continue;
            if (size == capacity) { head = (head + 1) % capacity; size--; }
            int index = (head + size++) % capacity;
            times[index] = time;
            System.arraycopy(data, f * channels, samples, index * channels, channels);
        }
    }

    // Compatibility for PCM16 devices/tests; high-resolution capture never passes through shorts.
    public synchronized void append(short[] data, int frames, long firstNs, double periodNs) {
        float[] converted = new float[frames * channels];
        for (int i = 0; i < converted.length; i++) converted[i] = data[i] / 32768f;
        append(converted, frames, firstNs, periodNs);
    }

    private static final int RADIUS = 16, PHASES = 1024;
    private static final int TAPS = RADIUS * 2;
    private static final float[] KERNEL = kernels(); // (PHASES + 1) rows of TAPS
    private static float[] kernels() {
        float[] table = new float[(PHASES + 1) * TAPS];
        for (int phase = 0; phase <= PHASES; phase++) {
            double fraction = phase / (double) PHASES, sum = 0;
            for (int tap = 0; tap < TAPS; tap++) {
                double x = tap - RADIUS + 1 - fraction;
                double sinc = Math.abs(x) < 1e-12 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
                double window = Math.abs(x) >= RADIUS ? 0 :
                        0.42 + 0.5 * Math.cos(Math.PI * x / RADIUS) + 0.08 * Math.cos(2 * Math.PI * x / RADIUS);
                table[phase * TAPS + tap] = (float) (sinc * window); sum += table[phase * TAPS + tap];
            }
            for (int tap = 0; tap < TAPS; tap++) table[phase * TAPS + tap] /= sum;
        }
        return table;
    }

    /* Reading costs ~30 multiply-adds per sample plus per-frame bookkeeping. Phones that run app Java
     * interpreted (JIT off, debuggable builds) cannot keep up with a multichannel interface that way,
     * so it runs natively. JVM unit tests have no Android library and use the Java loop below. */
    static final boolean NATIVE = loadNative();
    private static boolean loadNative() {
        try { System.loadLibrary("gearcam_audio"); return true; }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    /** Timestamp of the newest buffered frame, or 0 when empty: how far capture has got. */
    public synchronized long newestNs() { return size == 0 ? 0 : times[(head + size - 1) % capacity]; }

    /** Frames held, for diagnosing starvation against overflow. */
    public synchronized int size() { return size; }

    /** Same nominal sample rate; band-limited fractional delay aligns independent device clocks. */
    public synchronized int read(long firstNs, int rate, float[][] output, int offset, int frames) {
        return read(firstNs, rate, output, offset, frames, NATIVE);
    }

    synchronized int read(long firstNs, int rate, float[][] output, int offset, int frames, boolean useNative) {
        int missing = 0, cursor = 0;
        if (useNative) {
            long result = nativeRead(samples, times, head, size, capacity, channels, KERNEL, firstNs, rate, output, offset, frames);
            cursor = (int) (result >>> 32); missing = (int) result;
        } else for (int f = 0; f < frames; f++) { // keep in step with nativeRead() in audio_dsp_jni.cpp
            long time = firstNs + Math.round(f * 1_000_000_000.0 / rate);
            while (cursor + 1 < size && times[(head + cursor + 1) % capacity] <= time) cursor++;
            int current = (head + cursor) % capacity, next = (current + 1) % capacity;
            boolean available = cursor + 1 < size && time >= times[current] &&
                    time <= times[next] && times[next] - times[current] < 5_000_000;
            if (!available) missing++;
            float fraction = available ? (float) (time - times[current]) / (times[next] - times[current]) : 0;
            boolean sinc = available && cursor >= RADIUS - 1 && cursor + RADIUS < size;
            if (sinc) {
                long span = times[(head + cursor + RADIUS) % capacity] - times[(head + cursor - RADIUS + 1) % capacity];
                sinc = span < (RADIUS * 2 + 2) * 1_000_000_000L / rate;
            }
            int kernel = Math.min(PHASES, Math.round(fraction * PHASES)) * TAPS;
            for (int c = 0; c < channels; c++) {
                float value = 0;
                if (sinc) {
                    for (int tap = 0; tap < TAPS; tap++)
                        value += samples[((head + cursor - RADIUS + 1 + tap) % capacity) * channels + c] * KERNEL[kernel + tap];
                } else if (available) value = samples[current * channels + c] +
                        fraction * (samples[next * channels + c] - samples[current * channels + c]);
                output[offset + c][f] = value;
            }
        }
        int discard = Math.max(0, cursor - RADIUS);
        head = (head + discard) % capacity; size -= discard;
        return missing;
    }

    /** The Java loop above, natively. Returns the final cursor in the high 32 bits and missing frames in the low. */
    private static native long nativeRead(float[] samples, long[] times, int head, int size, int capacity, int channels,
            float[] kernel, long firstNs, int rate, float[][] output, int offset, int frames);
}
