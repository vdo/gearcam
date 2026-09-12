package net.sourceforge.opencamera.audio;

import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** On the phone: an 18-channel USB interface plus the phone mic must mix well ahead of real time,
 *  and the native loops must give exactly what the Java loops (covered by JVM unit tests) give. */
@RunWith(AndroidJUnit4.class)
public class MixerSpeedTest {
    private static final int RATE = 48000, BLOCK = 480, USB = 18;

    @Test public void nativeInterpolationMatchesJava() {
        assertTrue("native audio library did not load", TimedPcmBuffer.NATIVE);
        TimedPcmBuffer nativeBuffer = filled(), javaBuffer = filled();
        float[][] nativeOut = new float[USB][BLOCK], javaOut = new float[USB][BLOCK];
        for (int n = 0; n < 30; n++) { // linear at the start, sinc in the middle, gaps past the end
            long time = 1_000_000 + n * BLOCK * 1_000_000_000L / RATE;
            assertEquals(javaBuffer.read(time, RATE, javaOut, 0, BLOCK, false),
                    nativeBuffer.read(time, RATE, nativeOut, 0, BLOCK, true));
            for (int c = 0; c < USB; c++) assertArrayEquals(javaOut[c], nativeOut[c], 0);
        }
    }

    @Test public void nativeMixMatchesJava() {
        PcmMixer nativeMixer = new PcmMixer(USB, RATE), javaMixer = new PcmMixer(USB, RATE);
        float[][] input = new float[USB][BLOCK];
        float[] nativeOut = new float[BLOCK * 2], javaOut = new float[BLOCK * 2];
        for (int n = 0; n < 5; n++) {
            for (int c = 0; c < USB; c++) {
                for (int f = 0; f < BLOCK; f++) input[c][f] = (float) Math.sin(f * 0.05 * (c + 1) + n) * (c == 3 ? 1.1f : 0.4f);
                for (PcmMixer mixer : new PcmMixer[] {nativeMixer, javaMixer}) { // gain and pan ramps between blocks
                    mixer.channels[c].gainDb = n * 3 - c; mixer.channels[c].pan = (c % 3 - 1) * 0.5f;
                    mixer.channels[c].stereoSide = c == 4 ? -1 : c == 5 ? 1 : 0; mixer.channels[c].mute = c == 7;
                    // filters switch on mid-stream: their state starts clean
                    mixer.channels[c].highPass = c % 2 == 0 && n >= 1; mixer.channels[c].lowPass = c % 3 == 0 && n >= 2;
                }
            }
            nativeMixer.mix(input, nativeOut, BLOCK, true);
            javaMixer.mix(input, javaOut, BLOCK, false);
            assertArrayEquals(javaOut, nativeOut, 0);
            for (int c = 0; c < USB; c++) {
                assertEquals(javaMixer.channels[c].peak, nativeMixer.channels[c].peak, 0);
                assertEquals(javaMixer.channels[c].inputClipped, nativeMixer.channels[c].inputClipped);
                assertEquals(javaMixer.channels[c].gainClipped, nativeMixer.channels[c].gainClipped);
            }
        }
    }

    private static TimedPcmBuffer filled() {
        TimedPcmBuffer buffer = new TimedPcmBuffer(USB, RATE);
        float[] data = new float[RATE / 4 * USB];
        for (int i = 0; i < data.length; i++) data[i] = (float) Math.sin(i * 0.37 + (i % USB)) * 0.5f;
        buffer.append(data, RATE / 4, 0, 1e9 / RATE * 1.00007);
        return buffer;
    }

    /** The mix thread's work per block: read both devices, mix, encode AAC, write the WAV master. */
    @Test public void mixes19ChannelsFasterThanRealTime() throws Exception {
        File dir = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        File aac = new File(dir, "speed.m4a"), wav = new File(dir, "speed.wav");
        AacEncoder encoder = new AacEncoder(aac);
        WavMaster master = new WavMaster(wav);
        TimedPcmBuffer usb = new TimedPcmBuffer(USB, RATE * 2), phone = new TimedPcmBuffer(1, RATE * 2);
        PcmMixer mixer = new PcmMixer(USB + 1, RATE);
        for (PcmMixer.Channel channel : mixer.channels) { channel.highPass = true; channel.lowPass = true; } // worst case
        float[] usbBlock = new float[BLOCK * USB], phoneBlock = new float[BLOCK];
        for (int i = 0; i < usbBlock.length; i++) usbBlock[i] = (float) Math.sin(i * 0.01) * 0.5f;
        float[][] input = new float[USB + 1][BLOCK];
        float[] output = new float[BLOCK * 2];
        double usbPeriod = 1e9 / RATE * 1.00007, phonePeriod = 1e9 / RATE * 0.99995; // independent clocks
        long written = 0, readNs = 0, elapsed = 0;
        for (int n = 0; n < 10 * RATE / BLOCK; n++) {
            usb.append(usbBlock, BLOCK, Math.round(written * usbPeriod), usbPeriod);
            phone.append(phoneBlock, BLOCK, Math.round(written * phonePeriod), phonePeriod);
            written += BLOCK;
            if (written < RATE / 5) continue; // read ~200 ms behind the newest samples, like the app
            long start = System.nanoTime();
            usb.read(readNs, RATE, input, 0, BLOCK);
            phone.read(readNs, RATE, input, USB, BLOCK);
            mixer.mix(input, output, BLOCK);
            encoder.write(output, BLOCK);
            master.write(output, BLOCK);
            elapsed += System.nanoTime() - start;
            readNs += BLOCK * 1_000_000_000L / RATE;
        }
        encoder.close(); master.close(); aac.delete(); wav.delete();
        double realtime = (double) readNs / elapsed;
        Log.i("MixerSpeedTest", "19-channel mix runs at " + realtime + "x real time");
        assertTrue("19-channel mix runs at only " + realtime + "x real time", realtime > 3);
    }
}
