package net.sourceforge.opencamera.audio;

/** Allocation-free stereo bus. Gain and pan changes ramp across each processing block. */
public final class PcmMixer {
    public static final class Channel {
        public volatile float gainDb;
        public volatile float pan;
        public volatile boolean mute;
        public volatile float peak;
        public volatile boolean inputClipped;
        public volatile boolean gainClipped;
        /** -1/1 for linked stereo left/right; 0 for a mono pan control. */
        public volatile int stereoSide;
        /** 30 Hz high-pass (DC, infrasonic) and 20 kHz low-pass (ultrasonic). */
        public volatile boolean highPass, lowPass;
        private float left = Float.NaN, right;
    }

    public final Channel[] channels;
    public volatile boolean limiterEnabled = true;
    public volatile boolean busClipped;
    public volatile float leftPeak, rightPeak, reductionDb;
    private float limiterGain = 1;
    private final float release;

    public PcmMixer(int channelCount, int sampleRate) {
        channels = new Channel[channelCount];
        for (int i = 0; i < channelCount; i++) channels[i] = new Channel();
        release = (float) Math.exp(-1.0 / (0.1 * sampleRate));
        fromLeft = new float[channelCount]; fromRight = new float[channelCount]; toLeft = new float[channelCount];
        toRight = new float[channelCount]; gains = new float[channelCount]; inputPeaks = new float[channelCount];
        peaks = new float[channelCount]; filters = new int[channelCount]; filterState = new double[channelCount * 8];
        coefficients = new double[16];
        System.arraycopy(butterworth(30, sampleRate, true), 0, coefficients, 0, 8);
        System.arraycopy(butterworth(20000, sampleRate, false), 0, coefficients, 8, 8);
    }

    // Per-channel ramp endpoints, raw input and filtered peaks for the native loop (see TimedPcmBuffer.NATIVE).
    private final float[] fromLeft, fromRight, toLeft, toRight, gains, inputPeaks, peaks;
    // Filters on per channel (1 high-pass, 2 low-pass) and their state: high-pass then low-pass, 4 each.
    private final int[] filters;
    private final double[] filterState, coefficients;

    /** 3-pole Butterworth (−18 dB/octave) as a first-order section then a biquad with Q = 1, by the bilinear
     *  transform prewarped at the cutoff: {b0, b1, a1} then {b0, b1, b2, a1, a2}. */
    static double[] butterworth(double cutoff, int rate, boolean highPass) {
        double k = Math.tan(Math.PI * cutoff / rate), w = 2 * Math.PI * cutoff / rate;
        double cos = Math.cos(w), alpha = Math.sin(w) / 2, a0 = 1 + alpha;
        double g = highPass ? 1 / (1 + k) : k / (1 + k), b = highPass ? (1 + cos) / 2 : (1 - cos) / 2;
        return new double[] {g, highPass ? -g : g, (k - 1) / (k + 1),
                b / a0, (highPass ? -2 * b : 2 * b) / a0, b / a0, -2 * cos / a0, (1 - alpha) / a0};
    }

    /** One filter on one sample; keep in step with section() in audio_dsp_jni.cpp. */
    private static double section(double x, double[] k, int ko, double[] s, int so) {
        double y = k[ko] * x + k[ko + 1] * s[so] - k[ko + 2] * s[so + 1];
        s[so] = x; s[so + 1] = y;
        double z = k[ko + 3] * y + s[so + 2];
        s[so + 2] = k[ko + 4] * y - k[ko + 6] * z + s[so + 3];
        s[so + 3] = k[ko + 5] * y - k[ko + 7] * z;
        return z;
    }

    public void mix(float[][] input, float[] output, int frames) { mix(input, output, frames, TimedPcmBuffer.NATIVE); }

    void mix(float[][] input, float[] output, int frames, boolean useNative) {
        java.util.Arrays.fill(output, 0, frames * 2, 0);
        for (int c = 0; c < channels.length; c++) {
            Channel ch = channels[c];
            float gain = ch.mute ? 0 : (float) Math.pow(10, clamp(ch.gainDb, -60, 24) / 20);
            int wanted = (ch.highPass ? 1 : 0) | (ch.lowPass ? 2 : 0), started = wanted & ~filters[c];
            if ((started & 1) != 0) java.util.Arrays.fill(filterState, c * 8, c * 8 + 4, 0); // no stale state
            if ((started & 2) != 0) java.util.Arrays.fill(filterState, c * 8 + 4, c * 8 + 8, 0);
            filters[c] = wanted;
            double angle = (clamp(ch.pan, -1, 1) + 1) * Math.PI / 4;
            float left = gain * (float) Math.cos(angle), right = gain * (float) Math.sin(angle);
            if (ch.stereoSide != 0) {
                // Stereo balance only attenuates the opposite side. Center is unity on both.
                float balance = clamp(ch.pan, -1, 1);
                left = ch.stereoSide < 0 ? gain * Math.min(1, 1 - balance) : 0;
                right = ch.stereoSide > 0 ? gain * Math.min(1, 1 + balance) : 0;
            }
            if (Float.isNaN(ch.left)) { ch.left = left; ch.right = right; }
            if (useNative) {
                fromLeft[c] = ch.left; fromRight[c] = ch.right; toLeft[c] = left; toRight[c] = right; gains[c] = gain;
                continue;
            }
            float dl = (left - ch.left) / frames, dr = (right - ch.right) / frames;
            float peak = 0;
            for (int f = 0; f < frames; f++) { // keep in step with nativeAccumulate() in audio_dsp_jni.cpp
                float value = input[c][f];
                if (Math.abs(value) >= 32760f / 32768) ch.inputClipped = true; // the source, before filters
                if (wanted != 0) {
                    double x = value;
                    if ((wanted & 1) != 0) x = section(x, coefficients, 0, filterState, c * 8);
                    if ((wanted & 2) != 0) x = section(x, coefficients, 8, filterState, c * 8 + 4);
                    value = (float) x;
                }
                float postGain = Math.abs(value * gain);
                peak = Math.max(peak, postGain);
                if (postGain >= 1) ch.gainClipped = true;
                ch.left += dl; ch.right += dr;
                output[f * 2] += value * ch.left;
                output[f * 2 + 1] += value * ch.right;
            }
            ch.left = left; ch.right = right; ch.peak = Math.max(peak, ch.peak * 0.94f);
        }
        if (useNative) {
            nativeAccumulate(input, channels.length, frames, fromLeft, fromRight, toLeft, toRight,
                    filters, coefficients, filterState, output, inputPeaks, peaks);
            for (int c = 0; c < channels.length; c++) {
                Channel ch = channels[c];
                float peak = peaks[c] * gains[c];
                if (inputPeaks[c] >= 32760f / 32768) ch.inputClipped = true;
                if (peak >= 1) ch.gainClipped = true;
                ch.left = toLeft[c]; ch.right = toRight[c]; ch.peak = Math.max(peak, ch.peak * 0.94f);
            }
        }
        float lp = 0, rp = 0, minGain = 1;
        if (useNative) {
            limiterState[0] = limiterGain;
            nativeLimit(output, frames, limiterEnabled, release, limiterState);
            limiterGain = limiterState[0]; lp = limiterState[1]; rp = limiterState[2]; minGain = limiterState[3];
            if (limiterState[4] != 0) busClipped = true;
        } else for (int f = 0; f < frames; f++) { // keep in step with nativeLimit() in audio_dsp_jni.cpp
            int i = f * 2;
            float peak = Math.max(Math.abs(output[i]), Math.abs(output[i + 1]));
            if (peak >= 1) busClipped = true;
            float wanted = limiterEnabled && peak > 0.98f ? 0.98f / peak : 1;
            limiterGain = !limiterEnabled ? 1 : wanted < limiterGain ? wanted :
                    Math.min(wanted, 1 - (1 - limiterGain) * release);
            minGain = Math.min(minGain, limiterGain);
            output[i] = clamp(output[i] * limiterGain, -1, 1);
            output[i + 1] = clamp(output[i + 1] * limiterGain, -1, 1);
            lp = Math.max(lp, Math.abs(output[i])); rp = Math.max(rp, Math.abs(output[i + 1]));
        }
        leftPeak = Math.max(lp, leftPeak * 0.94f); rightPeak = Math.max(rp, rightPeak * 0.94f);
        reductionDb = (float) (-20 * Math.log10(Math.max(0.00001f, minGain)));
    }

    public void resetClips() {
        busClipped = false;
        for (Channel channel : channels) { channel.inputClipped = false; channel.gainClipped = false; }
    }

    /** Filters each channel's input and adds it into the interleaved stereo output with its gain ramp;
     *  returns raw input peaks and filtered peaks. */
    private static native void nativeAccumulate(float[][] input, int channels, int frames, float[] fromLeft, float[] fromRight,
            float[] toLeft, float[] toRight, int[] filters, double[] coefficients, double[] filterState,
            float[] output, float[] inputPeaks, float[] peaks);

    /** The limiter loop, natively. state: {gain in/out, left peak, right peak, min gain, clipped (1)}. */
    private static native void nativeLimit(float[] output, int frames, boolean enabled, float release, float[] state);
    private final float[] limiterState = new float[5];

    private static float clamp(float v, float min, float max) {
        return Float.isNaN(v) ? 0 : Math.max(min, Math.min(max, v));
    }
}
