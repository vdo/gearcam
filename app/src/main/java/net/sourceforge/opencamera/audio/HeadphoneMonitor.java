package net.sourceforge.opencamera.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

import java.io.Closeable;

/** Plays the stereo mix out while soundcheck or a take runs, so the take can be heard as it is recorded.
 *  Only ever to headphones: on the speaker the phone microphone would feed straight back. Bluetooth output
 *  is a fifth of a second late, which is fine for listening and useless for playing along. */
final class HeadphoneMonitor implements Closeable {
    static final String KEY = "monitor";
    private static final int CHECK_BLOCKS = 50; // headphones can go away mid-take: about twice a second

    private final Context context;
    private AudioTrack track;
    private volatile boolean wanted;
    private int blocks;

    HeadphoneMonitor(Context context) { this.context = context; }

    /** True while an output that is not the loudspeaker is connected. */
    static boolean headphonesConnected(Context context) {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (device.getType()) {
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                    return true;
                default:
                    if (Build.VERSION.SDK_INT >= 31 && device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) return true;
            }
        }
        return false;
    }

    void setEnabled(boolean enabled) { wanted = enabled; }

    /** Sends one mixed block on, from the mix thread; opens and closes the output as the switch and the
     *  headphones come and go. Never blocks that thread: a sink that cannot keep up drops audio instead. */
    void write(float[] interleaved, int frames) {
        if (wanted && (blocks++ % CHECK_BLOCKS != 0 || headphonesConnected(context))) {
            if (track == null) open();
            if (track != null) track.write(interleaved, 0, frames * 2, AudioTrack.WRITE_NON_BLOCKING);
        }
        else if (track != null) close();
    }

    private void open() {
        int minimum = AudioTrack.getMinBufferSize(MixerSettings.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
        if (minimum <= 0) return;
        try {
            AudioTrack opened = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(MixerSettings.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(Math.max(minimum, MixerSettings.RATE * 2 * 4 / 10)) // 100 ms of slack
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            opened.play();
            track = opened;
        } catch (RuntimeException e) { // a busy or unavailable output must not stop the take
            android.util.Log.w("GearCamMonitor", "Cannot open headphone monitoring", e);
            wanted = false;
        }
    }

    @Override public void close() {
        AudioTrack open = track;
        track = null;
        if (open == null) return;
        try { open.pause(); open.flush(); open.stop(); } catch (RuntimeException ignored) { }
        open.release();
    }
}
