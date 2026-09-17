package net.sourceforge.opencamera.audio;

import org.junit.Test;
import java.io.File;
import java.io.RandomAccessFile;
import static org.junit.Assert.*;

public class HighResolutionAudioTest {
    @Test public void floatBufferPreservesSignalsBelowOnePcm16Step() {
        TimedPcmBuffer buffer = new TimedPcmBuffer(1, 1000);
        float[] input = new float[500]; java.util.Arrays.fill(input, 1f / 8388608);
        buffer.append(input, input.length, 0, 1e9 / 48000);
        float[][] output = new float[1][100];
        assertEquals(0, buffer.read(1000000, 48000, output, 0, 100));
        for (float value : output[0]) assertEquals(1f / 8388608, value, 1e-12);
    }

    @Test public void fractionalClockAlignmentPreservesHighFrequencies() {
        TimedPcmBuffer buffer = new TimedPcmBuffer(1, 2048);
        float[] input = new float[2000];
        double frequency = 18000, rate = 48000;
        for (int i = 0; i < input.length; i++) input[i] = (float) (0.5 * Math.sin(2 * Math.PI * frequency * i / rate));
        buffer.append(input, input.length, 0, 1e9 / rate);
        float[][] output = new float[1][1000];
        long first = Math.round(100.5 * 1e9 / rate);
        assertEquals(0, buffer.read(first, 48000, output, 0, 1000));
        double error = 0, reference = 0;
        for (int i = 0; i < output[0].length; i++) {
            double expected = 0.5 * Math.sin(2 * Math.PI * frequency * (first / 1e9 + i / rate));
            error += Math.pow(output[0][i] - expected, 2); reference += expected * expected;
        }
        assertTrue("18 kHz alignment error should remain below 0.5% RMS", Math.sqrt(error / reference) < 0.005);
    }

    @Test public void linkedPairKeepsStereoSeparationAtUnity() {
        PcmMixer mixer = new PcmMixer(2, 48000); mixer.limiterEnabled = false;
        mixer.channels[0].stereoSide = -1; mixer.channels[1].stereoSide = 1;
        float[] output = new float[4];
        mixer.mix(new float[][] {{0.3f, -0.2f}, {-0.1f, 0.4f}}, output, 2);
        assertArrayEquals(new float[] {0.3f, -0.1f, -0.2f, 0.4f}, output, 1e-6f);
    }

    @Test public void stereoBalanceDoesNotMoveRightInputIntoLeft() {
        PcmMixer mixer = new PcmMixer(2, 48000); mixer.limiterEnabled = false;
        mixer.channels[0].stereoSide = -1; mixer.channels[1].stereoSide = 1;
        mixer.channels[0].pan = mixer.channels[1].pan = -1;
        float[] output = new float[2]; mixer.mix(new float[][] {{0.3f}, {0.4f}}, output, 1);
        assertArrayEquals(new float[] {0.3f, 0}, output, 1e-6f);
    }

    @Test public void wavClosesTheDescriptorWhenHeaderCannotBeWritten() throws Exception {
        File file = File.createTempFile("gearcam-header-failure", ".wav");
        final boolean[] closed = {false};
        java.io.FileOutputStream stream = new java.io.FileOutputStream(file) {
            @Override public void write(byte[] bytes) throws java.io.IOException { throw new java.io.IOException("Storage unavailable"); }
            @Override public void close() throws java.io.IOException { closed[0] = true; super.close(); }
        };
        try {
            try { new WavMaster(stream); fail("Expected failed header write"); }
            catch (java.io.IOException expected) { assertEquals("Storage unavailable", expected.getMessage()); }
            assertTrue("Failed construction must close the owned descriptor", closed[0]);
        } finally { stream.close(); file.delete(); }
    }

    @Test public void wavResetsExistingDescriptorPositionBeforeWritingHeader() throws Exception {
        File file = File.createTempFile("gearcam-position", ".wav");
        try {
            java.io.FileOutputStream stream = new java.io.FileOutputStream(file);
            stream.getChannel().position(100);
            try (WavMaster master = new WavMaster(stream)) { master.write(new float[] {0, 0}, 1); }
            try (RandomAccessFile data = new RandomAccessFile(file, "r")) {
                assertEquals(50, data.length()); assertEquals(0x52494646, data.readInt());
            }
        } finally { file.delete(); }
    }

    @Test public void wavMasterKeeps24BitSamplesAndClosesHeader() throws Exception {
        File file = File.createTempFile("gearcam-master", ".wav");
        try {
            try (WavMaster master = new WavMaster(file)) { master.write(new float[] {1f / 8388608, -1f / 8388608, 1, -1}, 2); }
            try (RandomAccessFile data = new RandomAccessFile(file, "r")) {
                assertEquals(56, data.length()); data.seek(24); assertEquals(48000, Integer.reverseBytes(data.readInt()));
                data.seek(34); assertEquals(24, Short.reverseBytes(data.readShort()));
                data.seek(40); assertEquals(12, Integer.reverseBytes(data.readInt()));
                byte[] samples = new byte[12]; data.readFully(samples);
                assertArrayEquals(new byte[] {1, 0, 0, -1, -1, -1, -1, -1, 0x7f, 0, 0, (byte) 0x80}, samples);
            }
        } finally { file.delete(); }
    }
}
