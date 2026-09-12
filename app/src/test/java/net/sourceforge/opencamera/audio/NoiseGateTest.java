package net.sourceforge.opencamera.audio;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class NoiseGateTest {
    private static final int RATE = 48000, BLOCK = 480;
    private final Random random = new Random(1);
    private final float[] block = new float[BLOCK];

    @Test public void dipsTheBackgroundAndOpensForTheVoice() {
        NoiseGate gate = new NoiseGate(RATE);
        gate.enabled = true;
        assertEquals(-20, gainDb(gate, 0.001, 200, 100), 1); // hiss at −60 dBFS, learned within a second
        for (int n = 0; n < 20; n++) { // a voice at −20 dBFS passes untouched from its second 10 ms block
            for (int i = 0; i < BLOCK; i++) block[i] = (float) (0.1 * Math.sin(2 * Math.PI * 1000 * (n * BLOCK + i) / RATE) + random.nextGaussian() * 0.001);
            float[] voice = block.clone();
            gate.process(block, BLOCK);
            if (n > 0) for (int i = 0; i < BLOCK; i++) assertEquals(voice[i], block[i], 0.001);
        }
        assertEquals(-20, gainDb(gate, 0.001, 150, 100), 1); // closes again after the hold and fade
    }

    @Test public void learnsALouderBackground() {
        NoiseGate gate = new NoiseGate(RATE);
        gate.enabled = true;
        gainDb(gate, 0.001, 100, 0);
        assertEquals(-20, gainDb(gate, 0.01, 800, 700), 1); // 20 dB louder: dipped once the 5 s memory has passed
    }

    @Test public void disabledLeavesAudioUntouched() {
        NoiseGate gate = new NoiseGate(RATE);
        for (int i = 0; i < BLOCK; i++) block[i] = (float) random.nextGaussian() * 0.001f;
        float[] input = block.clone();
        gate.process(block, BLOCK);
        assertArrayEquals(input, block, 0);
    }

    /** Runs blocks of Gaussian hiss (amplitude) through the gate; the level change over blocks [from, blocks), in dB. */
    private double gainDb(NoiseGate gate, double amplitude, int blocks, int from) {
        double in = 0, out = 0;
        for (int n = 0; n < blocks; n++) {
            double power = 0;
            for (int i = 0; i < BLOCK; i++) { block[i] = (float) (random.nextGaussian() * amplitude); power += block[i] * block[i]; }
            gate.process(block, BLOCK);
            if (n >= from) { in += power; for (float v : block) out += v * v; }
        }
        return 10 * Math.log10(out / in);
    }
}
