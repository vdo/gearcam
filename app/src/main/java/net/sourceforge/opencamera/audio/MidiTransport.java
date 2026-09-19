package net.sourceforge.opencamera.audio;

import android.content.Context;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Handler;
import android.os.Looper;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;

/** Listens to every USB MIDI device while its screen is resumed and the option is on; Start or Continue runs {@code play}. */
public final class MidiTransport {
    public static final String ENABLED = "gearcam_midi_start";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Context context;
    private final MidiManager midi;
    private final Runnable play;
    private final List<Closeable> open = new ArrayList<>();
    private boolean active;
    private static volatile MidiTransport current;
    private final Runnable reopen = () -> { if (active) { pause(); resume(); } };
    private final MidiReceiver receiver = new MidiReceiver() {
        @Override public void onSend(byte[] data, int offset, int count, long timestamp) {
            if (isPlay(data, offset, count)) handler.post(() -> { if (active) play.run(); });
        }
    };
    private final MidiManager.DeviceCallback callback = new MidiManager.DeviceCallback() {
        @Override public void onDeviceAdded(MidiDeviceInfo info) { open(info); }
    };

    public MidiTransport(Context context, Runnable play) {
        this.context = context; this.play = play;
        midi = (MidiManager) context.getSystemService(Context.MIDI_SERVICE); // null without android.software.midi
    }
    public static boolean enabled(Context context) { return RecordingPreferences.prefs(context).getBoolean(ENABLED, false); }

    /** Call when the screen resumes or the option changes. */
    @SuppressWarnings("deprecation") // getDevices/registerDeviceCallback(Handler) list the MIDI 1.0 byte-stream devices on every API level
    public void resume() {
        if (!enabled(context)) { pause(); return; }
        if (active || midi == null) return;
        active = true; current = this;
        midi.registerDeviceCallback(callback, handler);
        for (MidiDeviceInfo info : midi.getDevices()) open(info);
    }
    public void pause() {
        if (!active) return;
        active = false; if (current == this) current = null;
        handler.removeCallbacks(reopen);
        midi.unregisterDeviceCallback(callback);
        for (Closeable c : open) try { c.close(); } catch (java.io.IOException ignored) { }
        open.clear();
    }
    private void open(MidiDeviceInfo info) {
        if (info.getType() != MidiDeviceInfo.TYPE_USB || info.getOutputPortCount() == 0) return;
        midi.openDevice(info, device -> {
            if (device == null) return;
            if (!active) { try { device.close(); } catch (java.io.IOException ignored) { } return; }
            for (int i = 0; i < info.getOutputPortCount(); i++) {
                MidiOutputPort port = device.openOutputPort(i);
                if (port != null) { port.connect(receiver); open.add(port); }
            }
            open.add(device);
        }, handler);
    }

    /** Direct USB capture detaches the kernel audio driver, which also carries a composite device's MIDI; Android's reader
     *  for it dies and never recovers while a port stays open. Reopen once the driver is back after capture ends. */
    static void usbReleased() {
        MidiTransport listening = current;
        if (listening != null) listening.handler.postDelayed(listening.reopen, 1000); // time for the ALSA card to re-probe
    }

    /** Real-time Start (0xFA) or Continue (0xFB). Status bytes never occur as data, so any chunk boundary is safe. */
    static boolean isPlay(byte[] data, int offset, int count) {
        for (int i = offset; i < offset + count; i++) if (data[i] == (byte) 0xFA || data[i] == (byte) 0xFB) return true;
        return false;
    }
}
