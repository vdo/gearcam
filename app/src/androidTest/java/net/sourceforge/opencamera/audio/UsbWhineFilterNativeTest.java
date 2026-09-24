package net.sourceforge.opencamera.audio;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The native loop runs on device; this is the check that it matches the Java one it replaced. */
@RunWith(AndroidJUnit4.class)
public class UsbWhineFilterNativeTest {
    private static final int RATE = 48000, SECONDS = 3;

    private static float[] whine() {
        float[] signal = new float[RATE * SECONDS];
        double phase = 0;
        for (int i = 0; i < signal.length; i++) {
            phase += 2 * Math.PI * (1000.08 - 0.075 * i / RATE) / RATE;
            double sum = 0;
            for (int k = 1; k <= 17; k++) sum += Math.pow(10, -k / 12.0) * Math.sin(k * phase);
            signal[i] = (float) (0.0025 * sum);
        }
        return signal;
    }

    private static float[] filtered(float[] input) {
        UsbWhineFilter filter = new UsbWhineFilter();
        filter.enabled = true;
        float[] output = input.clone(), block = new float[480];
        for (int i = 0; i + block.length <= output.length; i += block.length) {
            System.arraycopy(output, i, block, 0, block.length);
            filter.process(block, block.length, 0, 1);
            System.arraycopy(block, 0, output, i, block.length);
        }
        return output;
    }

    private static double level(float[] signal, double frequency) {
        double real = 0, imaginary = 0;
        for (int i = signal.length - RATE; i < signal.length; i++) {
            double p = 2 * Math.PI * frequency * i / RATE;
            real += signal[i] * Math.cos(p);
            imaginary += signal[i] * Math.sin(p);
        }
        return 20 * Math.log10(2 * Math.hypot(real, imaginary) / RATE + 1e-30);
    }

    @Test public void cancelsTheCombOnDevice() {
        float[] in = whine(), out = filtered(in);
        for (int k : new int[] {1, 2, 3, 5, 8, 12, 17}) {
            double before = level(in, 1000.0 * k), after = level(out, 1000.0 * k);
            assertTrue("harmonic " + k + ": " + before + " -> " + after, after < before - 20);
        }
    }

    @Test public void leavesMusicAloneOnDevice() {
        float[] music = new float[RATE * SECONDS];
        for (int i = 0; i < music.length; i++)
            music[i] = (float) (0.2 * Math.sin(2 * Math.PI * 440 * i / RATE) + 0.1 * Math.sin(2 * Math.PI * 1320.5 * i / RATE));
        float[] out = filtered(music);
        for (double f : new double[] {440, 1320.5}) assertEquals(level(music, f), level(out, f), 0.3);
    }

    /** The point of the native loop: it must run far faster than real time on the capture thread. */
    @Test public void isFastEnoughForRealTime() {
        UsbWhineFilter filter = new UsbWhineFilter();
        filter.enabled = true;
        float[] block = new float[4800 * 2];
        for (int w = 0; w < 20; w++) filter.process(block, 4800, 0, 2);
        long start = System.nanoTime();
        int blocks = 50; // 50 x 100 ms = 5 s of stereo audio
        for (int b = 0; b < blocks; b++) { filter.process(block, 4800, 0, 2); filter.process(block, 4800, 1, 2); }
        double seconds = (System.nanoTime() - start) / 1e9;
        double realTime = blocks * 4800 / (double) RATE;
        System.out.println("WHINE COST: " + String.format("%.1f%% of real time for two channels", seconds / realTime * 100));
        assertTrue("whine filter takes " + (seconds / realTime * 100) + "% of real time", seconds < realTime * 0.25);
    }
}
