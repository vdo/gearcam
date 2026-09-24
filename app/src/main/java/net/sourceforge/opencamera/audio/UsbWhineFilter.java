package net.sourceforge.opencamera.audio;

/** Cancels USB whine: the comb of tones at multiples of 1 kHz, the USB frame rate, that an adapter's own
 *  electronics inject into its capture. One sine per harmonic is adapted (LMS) to the tone actually present
 *  and subtracted, so it follows the device's clock drift, removes nothing that is not a steady tone at those
 *  exact frequencies, and adds no delay. Measured on a BOYA BY-K4: over 20 dB off every harmonic in 0.12 s,
 *  with notches −3 dB wide at ±3 Hz. */
final class UsbWhineFilter {
    private static final int PERIOD = MixerSettings.RATE / 1000; // 1 kHz frame: a whole 48 samples at 48 kHz
    private static final int HARMONICS = 20; // to 20 kHz; harmonic 8 also covers high-speed 8 kHz microframes
    private static final float MU = 8e-4f; // adaptation: wider notch tracks more drift, at more musical damage
    /** Drift is k times as fast at harmonic k, so adapt k times as fast — but stop widening the notch past here:
     *  by harmonic 6 it already spans far more than the drift, and the extra width only costs musical content. */
    private static final int FASTEST = 6;
    /** Each stage passes what it does not notch with a gain of about 1 + mu/2, flat across the band; undo the cascade's. */
    private static final float MAKEUP;
    private static final float[] COS = new float[PERIOD], SIN = new float[PERIOD];
    static {
        double gain = 0;
        for (int k = 1; k <= HARMONICS; k++) gain += MU * Math.min(k, FASTEST) / 2;
        MAKEUP = (float) (1 / (1 + gain));
        for (int i = 0; i < PERIOD; i++) {
            COS[i] = (float) Math.cos(2 * Math.PI * i / PERIOD);
            SIN[i] = (float) Math.sin(2 * Math.PI * i / PERIOD);
        }
    }

    /** Cosine and sine gain per harmonic, interleaved, so the native loop shares this state. */
    private final float[] weights = new float[(HARMONICS + 1) * 2];
    private static final boolean NATIVE = loadNative();
    private static boolean loadNative() {
        try { System.loadLibrary("gearcam_audio"); return true; }
        catch (UnsatisfiedLinkError e) { return false; } // JVM unit tests run the Java loop below
    }
    private int phase;
    private boolean running;
    volatile boolean enabled;

    /** Filters one channel of an interleaved block in place; untouched while disabled. */
    void process(float[] samples, int frames, int channel, int stride) {
        if (!enabled) { running = false; return; }
        if (!running) { // weights left from an earlier take would add back the tone they last cancelled
            running = true;
            java.util.Arrays.fill(weights, 0);
        }
        if (NATIVE) { // in Java this costs more than real time on a phone and starves capture
            phase = nativeProcess(samples, frames, channel, stride, weights, phase, PERIOD, HARMONICS, FASTEST, MU, MAKEUP);
            return;
        }
        for (int f = 0; f < frames; f++) {
            int n = phase;
            phase = (phase + 1) % PERIOD;
            float x = samples[f * stride + channel];
            for (int k = 1; k <= HARMONICS; k++) {
                int index = k * n % PERIOD; // every harmonic of 1 kHz closes in the same 48-sample table
                float cos = COS[index], sin = SIN[index];
                float error = x - (weights[k * 2] * cos + weights[k * 2 + 1] * sin);
                float step = MU * Math.min(k, FASTEST) * error;
                weights[k * 2] += step * cos;
                weights[k * 2 + 1] += step * sin;
                x = error;
            }
            samples[f * stride + channel] = x * MAKEUP;
        }
    }

    /** The loop above, natively; returns the new phase. */
    private static native int nativeProcess(float[] samples, int frames, int channel, int stride,
            float[] weights, int phase, int period, int harmonics, int fastest, float mu, float makeup);
}
