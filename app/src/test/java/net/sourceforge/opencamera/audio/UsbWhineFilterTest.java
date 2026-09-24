package net.sourceforge.opencamera.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UsbWhineFilterTest {
    private static final int RATE = 48000, SECONDS = 3;

    /** Level of one frequency in the last second, in dB relative to an amplitude of 1. */
    private static double level(float[] signal, double frequency) {
        double real = 0, imaginary = 0;
        int from = signal.length - RATE;
        for (int i = from; i < signal.length; i++) {
            double phase = 2 * Math.PI * frequency * i / RATE;
            real += signal[i] * Math.cos(phase);
            imaginary += signal[i] * Math.sin(phase);
        }
        return 20 * Math.log10(2 * Math.hypot(real, imaginary) / RATE + 1e-30);
    }

    private static float[] filtered(float[] input) {
        UsbWhineFilter filter = new UsbWhineFilter();
        filter.enabled = true;
        float[] output = input.clone(), block = new float[480]; // fed in 10 ms blocks, mono, as a capture does
        for (int i = 0; i + block.length <= output.length; i += block.length) {
            System.arraycopy(output, i, block, 0, block.length);
            filter.process(block, block.length, 0, 1);
            System.arraycopy(block, 0, output, i, block.length);
        }
        return output;
    }

    @Test public void cancelsTheDriftingOneKilohertzComb() {
        // The measured artifact: harmonics of 1000.08 Hz, drifting 0.075 Hz/s with the device clock, decaying upwards.
        float[] whine = new float[RATE * SECONDS];
        double phase = 0;
        for (int i = 0; i < whine.length; i++) {
            phase += 2 * Math.PI * (1000.08 - 0.075 * i / RATE) / RATE;
            double sum = 0;
            for (int k = 1; k <= 17; k++) sum += Math.pow(10, -k / 12.0) * Math.sin(k * phase);
            whine[i] = (float) (0.0025 * sum);
        }
        float[] clean = filtered(whine);
        for (int k : new int[] {1, 2, 3, 5, 8, 12, 17}) {
            double before = level(whine, 1000.0 * k), after = level(clean, 1000.0 * k);
            assertTrue("harmonic " + k + ": " + before + " -> " + after, after < before - 20);
        }
    }

    @Test public void leavesMusicAlone() {
        float[] music = new float[RATE * SECONDS];
        for (int i = 0; i < music.length; i++)
            music[i] = (float) (0.2 * Math.sin(2 * Math.PI * 440 * i / RATE) + 0.1 * Math.sin(2 * Math.PI * 1320.5 * i / RATE)
                    + 0.05 * Math.sin(2 * Math.PI * 970 * i / RATE));
        float[] out = filtered(music);
        for (double frequency : new double[] {440, 1320.5, 970}) // 30 Hz from the comb: outside every notch
            assertEquals(level(music, frequency), level(out, frequency), 0.3);
    }

    @Test public void disabledIsBitExact() {
        UsbWhineFilter filter = new UsbWhineFilter();
        float[] block = new float[480];
        for (int i = 0; i < block.length; i++) block[i] = (float) Math.sin(2 * Math.PI * 1000 * i / RATE);
        float[] original = block.clone();
        filter.process(block, 480, 0, 1);
        org.junit.Assert.assertArrayEquals(original, block, 0);
    }
}
