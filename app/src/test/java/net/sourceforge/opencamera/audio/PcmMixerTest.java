package net.sourceforge.opencamera.audio;

import org.junit.Test;
import static org.junit.Assert.*;

public class PcmMixerTest {
    @Test public void centeredMonoUsesEqualPowerPan() {
        PcmMixer mixer = new PcmMixer(1, 48000);
        float[] out = new float[2];
        mixer.mix(new float[][] {{0.5f}}, out, 1);
        assertEquals(0.5 / Math.sqrt(2), out[0], 0.00001);
        assertEquals(out[0], out[1], 0);
    }

    @Test public void stereoInputsStayIndependent() {
        PcmMixer mixer = new PcmMixer(2, 48000);
        mixer.channels[0].pan = -1; mixer.channels[1].pan = 1;
        float[] out = new float[2];
        mixer.mix(new float[][] {{0.2f}, {-0.4f}}, out, 1);
        assertArrayEquals(new float[] {0.2f, -0.4f}, out, 0.00001f);
    }

    @Test public void gainIsInDecibels() {
        PcmMixer mixer = new PcmMixer(1, 48000);
        mixer.channels[0].pan = -1; mixer.channels[0].gainDb = -6.0206f;
        float[] out = new float[2];
        mixer.mix(new float[][] {{0.8f}}, out, 1);
        assertEquals(0.4, out[0], 0.00001);
    }

    @Test public void mutingDoesNotMuteOtherInputs() {
        PcmMixer mixer = new PcmMixer(2, 48000);
        mixer.channels[0].mute = true; mixer.channels[1].pan = 1;
        float[] out = new float[2];
        mixer.mix(new float[][] {{1}, {0.25f}}, out, 1);
        assertEquals(0, out[0], 0.00001); assertEquals(0.25, out[1], 0.00001);
        assertTrue(mixer.channels[0].inputClipped);
    }

    /** 3-pole Butterworth: flat in the audible band, −3 dB at the cutoff, then −18 dB/octave. */
    @Test public void filtersKeepTheAudibleBandAndCutBeyondIt() {
        assertEquals(0, gainDb(true, false, 1000), 0.05);
        assertEquals(-3.01, gainDb(true, false, 30), 0.1);
        assertEquals(-28.6, gainDb(true, false, 10), 0.5); // a third of the cutoff: (1/3)^6
        assertEquals(0, gainDb(false, true, 1000), 0.05);
        assertEquals(-3.01, gainDb(false, true, 20000), 0.1);
        assertTrue(gainDb(false, true, 23000) < -30);
        assertEquals(0, gainDb(true, true, 1000), 0.05);
    }

    @Test public void highPassRemovesDc() {
        PcmMixer mixer = new PcmMixer(1, 48000);
        mixer.channels[0].pan = -1; mixer.channels[0].highPass = true;
        float[] in = new float[4800], out = new float[9600];
        java.util.Arrays.fill(in, 0.5f);
        for (int block = 0; block < 10; block++) mixer.mix(new float[][] {in}, out, 4800);
        assertEquals(0, out[out.length - 2], 0.0001);
    }

    private static double gainDb(boolean highPass, boolean lowPass, double frequency) {
        PcmMixer mixer = new PcmMixer(1, 48000);
        mixer.channels[0].pan = -1; mixer.channels[0].highPass = highPass; mixer.channels[0].lowPass = lowPass;
        int frames = 4800;
        float[] in = new float[frames], out = new float[frames * 2];
        double power = 0;
        for (int block = 0; block < 20; block++) { // settle for a second, then measure RMS over a second of whole periods
            for (int f = 0; f < frames; f++) in[f] = (float) (0.5 * Math.sin(2 * Math.PI * frequency * (block * frames + f) / 48000));
            mixer.mix(new float[][] {in}, out, frames);
            if (block >= 10) for (int f = 0; f < frames; f++) power += out[f * 2] * out[f * 2];
        }
        return 10 * Math.log10(power / (frames * 10) / (0.5 * 0.5 / 2));
    }

    @Test public void attenuationCannotHideInputClipping() {
        PcmMixer mixer = new PcmMixer(1, 48000);
        mixer.channels[0].gainDb = -30;
        float[] out = new float[2];
        mixer.mix(new float[][] {{-1}}, out, 1);
        assertTrue(mixer.channels[0].inputClipped);
        assertFalse(mixer.channels[0].gainClipped);
        mixer.mix(new float[][] {{0}}, out, 1);
        assertTrue(mixer.channels[0].inputClipped);
        mixer.resetClips(); assertFalse(mixer.channels[0].inputClipped);
    }

    @Test public void limiterIsStereoLinkedAndLeavesQuietSignalsAlone() {
        PcmMixer mixer = new PcmMixer(2, 48000);
        mixer.channels[0].pan = -1; mixer.channels[1].pan = 1;
        mixer.channels[0].gainDb = 12; mixer.channels[1].gainDb = 12;
        float[] out = new float[2];
        mixer.mix(new float[][] {{0.8f}, {0.4f}}, out, 1);
        assertEquals(0.98, out[0], 0.00001); assertEquals(0.49, out[1], 0.00001);
        assertTrue(mixer.busClipped); assertTrue(mixer.reductionDb > 0);
        assertFalse(mixer.channels[0].inputClipped); assertTrue(mixer.channels[0].gainClipped);
    }

    @Test public void disabledLimiterStillClampsPcmWithoutWrapping() {
        PcmMixer mixer = new PcmMixer(1, 48000);
        mixer.limiterEnabled = false; mixer.channels[0].pan = -1; mixer.channels[0].gainDb = 24;
        float[] out = new float[2];
        mixer.mix(new float[][] {{-0.5f}}, out, 1);
        assertEquals(-1, out[0], 0); assertTrue(mixer.busClipped);
        assertEquals(0, mixer.reductionDb, 0);
    }

    @Test public void gainChangesRampInsteadOfJumping() {
        PcmMixer mixer = new PcmMixer(1, 48000);
        mixer.channels[0].pan = -1;
        float[] out = new float[8];
        float[][] input = {{0.5f, 0.5f, 0.5f, 0.5f}};
        mixer.mix(input, out, 4);
        mixer.channels[0].mute = true; mixer.mix(input, out, 4);
        assertEquals(0.375, out[0], 0.00001); assertEquals(0, out[6], 0.00001);
    }
}
