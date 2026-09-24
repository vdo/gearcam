package net.sourceforge.opencamera.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioTrack;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class HeadphoneMonitorTest {
    /** The monitor's output format: 48 kHz stereo float, as the mix bus produces it. */
    @Test public void theMixFormatPlaysBack() {
        int minimum = AudioTrack.getMinBufferSize(MixerSettings.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
        assertTrue("48 kHz stereo float output must be supported", minimum > 0);
    }

    /** Whatever is or is not connected, feeding the monitor must never throw on the mix thread. */
    @Test public void survivesEveryOutputState() {
        Context context = ApplicationProvider.getApplicationContext();
        HeadphoneMonitor monitor = new HeadphoneMonitor(context);
        float[] block = new float[960];
        for (int i = 0; i < 480; i++) block[i * 2] = block[i * 2 + 1] = (float) (0.1 * Math.sin(i * 0.1));
        try {
            for (int pass = 0; pass < 3; pass++) {
                monitor.setEnabled(pass % 2 == 0);
                for (int b = 0; b < 120; b++) monitor.write(block, 480); // past the headphone re-check interval
            }
        } finally { monitor.close(); }
    }

    /** On a phone with nothing plugged in, monitoring stays shut: the speaker would feed back into the mic. */
    @Test public void speakerOnlyDeviceIsNotTreatedAsHeadphones() {
        Context context = ApplicationProvider.getApplicationContext();
        android.media.AudioManager manager = (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        boolean headset = false;
        for (android.media.AudioDeviceInfo device : manager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS))
            headset |= device.getType() != android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    && device.getType() != android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    && device.getType() != android.media.AudioDeviceInfo.TYPE_TELEPHONY;
        if (headset) return; // something is plugged in on this device: nothing to assert
        assertTrue("Speaker-only output must not enable monitoring", !HeadphoneMonitor.headphonesConnected(context));
    }
}
