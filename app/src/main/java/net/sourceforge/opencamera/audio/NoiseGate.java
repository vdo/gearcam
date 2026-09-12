package net.sourceforge.opencamera.audio;

/** Light, self-adjusting noise gate for a voice mic. It learns the background level and turns it down
 *  20 dB between phrases, so hum and room noise drop out in the pauses without the dead silence of a
 *  hard gate. It cannot remove noise under the voice. No lookahead, so no added delay. */
final class NoiseGate {
    private static final float RANGE = 0.1f; // closed: −20 dB
    private static final float OPEN = 10, CLOSE = 4; // power over the background: +10 dB opens, under +6 dB closes
    private final float detector, opening, closing;
    private final int holdSamples;
    private final float[] history = new float[500]; // 10 ms block powers: the last 5 s
    private int historySize, historyNext, hold;
    private float power, gain = 1;
    volatile boolean enabled;

    NoiseGate(int rate) {
        detector = smoothing(0.005, rate); // voice level: 5 ms
        opening = smoothing(0.002, rate); // keeps consonants
        closing = smoothing(0.15, rate); // a natural fade
        holdSamples = rate / 10; // rides over short gaps between words
    }

    private static float smoothing(double seconds, int rate) { return (float) (1 - Math.exp(-1 / (seconds * rate))); }

    /** Gates samples[0, count) in place; untouched while disabled. */
    void process(float[] samples, int count) {
        float blockPower = 0;
        for (int i = 0; i < count; i++) blockPower += samples[i] * samples[i];
        history[historyNext] = blockPower / Math.max(1, count);
        historyNext = (historyNext + 1) % history.length;
        if (historySize < history.length) historySize++;
        // The background is the quietest block of the last 5 s: breaths and pauses reach it, held notes do not.
        float background = Float.MAX_VALUE;
        for (int i = 0; i < historySize; i++) if (history[i] < background) background = history[i];
        if (background < 1e-10f) background = 1e-10f; // digital silence: −100 dBFS
        if (!enabled && gain > 0.9999f) { gain = 1; hold = 0; return; }
        float open = background * OPEN, close = background * CLOSE;
        for (int i = 0; i < count; i++) {
            float x = samples[i];
            power += detector * (x * x - power);
            if (power > open) hold = holdSamples;
            else if (power < close && hold > 0) hold--;
            float target = !enabled || hold > 0 ? 1 : RANGE;
            gain += (target - gain) * (target > gain ? opening : closing);
            samples[i] = x * gain;
        }
    }
}
