package net.sourceforge.opencamera.audio;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;

/** The AAC track must not lag the video: a click written at 0.5 s must decode at 0.5 s. */
@RunWith(AndroidJUnit4.class)
public class AacDelayTest {
    @Test public void clickDecodesWhereItWasWritten() throws Exception {
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "delay.m4a");
        AacEncoder encoder = new AacEncoder(file);
        float[] block = new float[480 * 2];
        for (int n = 0; n < 100; n++) {
            java.util.Arrays.fill(block, 0);
            if (n == 50) { block[0] = 0.9f; block[1] = 0.9f; } // 24000 samples in
            encoder.write(block, 480);
        }
        encoder.finish(); encoder.close();

        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(file.getPath());
        extractor.selectTrack(0);
        MediaFormat format = extractor.getTrackFormat(0);
        MediaCodec decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        decoder.configure(format, null, null, 0);
        decoder.start();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false, floatOutput = false;
        double peak = 0, peakUs = -1;
        while (true) {
            if (!inputDone) {
                int in = decoder.dequeueInputBuffer(10000);
                if (in >= 0) {
                    int size = extractor.readSampleData(decoder.getInputBuffer(in), 0);
                    if (size < 0) { decoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; }
                    else { decoder.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0); extractor.advance(); }
                }
            }
            int out = decoder.dequeueOutputBuffer(info, 10000);
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat outFormat = decoder.getOutputFormat();
                floatOutput = outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        && outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
            } else if (out >= 0) {
                ByteBuffer pcm = decoder.getOutputBuffer(out).order(ByteOrder.nativeOrder());
                int frames = info.size / (floatOutput ? 8 : 4);
                for (int f = 0; f < frames; f++) {
                    double value = Math.abs(floatOutput ? pcm.getFloat(info.offset + f * 8) : pcm.getShort(info.offset + f * 4) / 32768.0);
                    if (value > peak) { peak = value; peakUs = info.presentationTimeUs + f * 1e6 / 48000; }
                }
                decoder.releaseOutputBuffer(out, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
        decoder.release(); extractor.release(); file.delete();
        double delayMs = peakUs / 1000 - 500;
        Log.i("AacDelayTest", "click decodes at " + peakUs / 1000 + " ms: AAC delay " + delayMs + " ms; track format " + format);
        assertEquals("AAC delay", 0, delayMs, 2);
    }
}
