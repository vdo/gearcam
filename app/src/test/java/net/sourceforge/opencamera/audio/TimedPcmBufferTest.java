package net.sourceforge.opencamera.audio;

import org.junit.Test;
import static org.junit.Assert.*;

public class TimedPcmBufferTest {
    @Test public void resamplesAtTheRequestedTimestamp() {
        TimedPcmBuffer buffer = new TimedPcmBuffer(1, 8);
        buffer.append(new short[] {0, 1000, 2000, 3000}, 4, 1000000, 100000);
        float[][] output = new float[1][2];
        assertEquals(0, buffer.read(1050000, 10000, output, 0, 2));
        assertEquals(500f / 32768, output[0][0], 0.000001);
        assertEquals(1500f / 32768, output[0][1], 0.000001);
    }

    @Test public void independentClocksAlignTheSameSignal() {
        TimedPcmBuffer first = new TimedPcmBuffer(1, 10), second = new TimedPcmBuffer(1, 10);
        first.append(new short[] {0, 1000, 2000, 3000}, 4, 1000000, 100000);
        second.append(new short[] {500, 1500, 2500, 3500}, 4, 1050000, 100000);
        float[][] output = new float[2][2];
        assertEquals(0, first.read(1100000, 10000, output, 0, 2));
        assertEquals(0, second.read(1100000, 10000, output, 1, 2));
        assertArrayEquals(output[0], output[1], 0.000001f);
    }

    @Test public void channelSelectionDoesNotSwapInterleavedInputs() {
        TimedPcmBuffer buffer = new TimedPcmBuffer(2, 8);
        buffer.append(new short[] {1000, -2000, 1000, -2000, 1000, -2000}, 3, 0, 100000);
        float[][] output = new float[3][1];
        assertEquals(0, buffer.read(50000, 10000, output, 1, 1));
        assertEquals(0, output[0][0], 0);
        assertEquals(1000f / 32768, output[1][0], 0.000001);
        assertEquals(-2000f / 32768, output[2][0], 0.000001);
    }

    @Test public void missingInputProducesAnExplicitGap() {
        TimedPcmBuffer buffer = new TimedPcmBuffer(1, 4);
        float[][] output = {{0.8f, 0.8f}};
        assertEquals(2, buffer.read(0, 48000, output, 0, 2));
        assertArrayEquals(new float[2], output[0], 0);
    }

    @Test public void ringIsBoundedAndDiscardsOldSamples() {
        TimedPcmBuffer buffer = new TimedPcmBuffer(1, 3);
        buffer.append(new short[] {1, 2, 3, 4, 5}, 5, 0, 100000);
        float[][] output = new float[1][1];
        assertEquals(1, buffer.read(0, 10000, output, 0, 1));
        assertEquals(0, buffer.read(250000, 10000, output, 0, 1));
        assertEquals(3.5f / 32768, output[0][0], 0.000001);
    }

    @Test public void doesNotInterpolateAcrossADropout() {
        TimedPcmBuffer buffer = new TimedPcmBuffer(1, 4);
        buffer.append(new short[] {1000}, 1, 0, 100000);
        buffer.append(new short[] {1000}, 1, 10000000, 100000);
        assertEquals(1, buffer.read(5000000, 10000, new float[1][1], 0, 1));
    }
}
