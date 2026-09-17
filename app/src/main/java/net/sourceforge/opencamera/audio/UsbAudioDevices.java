package net.sourceforge.opencamera.audio;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import androidx.core.content.ContextCompat;
import java.util.HashSet;
import java.util.Set;

/** Enumerates already-connected devices as well as hotplug; permission requests are serialized. */
public final class UsbAudioDevices {
    public static final String PERMISSION = "app.gearcam.USB_PERMISSION";
    private final Activity activity;
    private final UsbManager usb;
    private final AudioManager audio;
    private final Handler handler = new Handler(android.os.Looper.getMainLooper());
    private final Set<String> asked = new HashSet<>();
    private String pending;
    private String inventory;
    private boolean registered;
    private final Runnable changed;
    private final Runnable scan = this::refresh;
    private final AudioDeviceCallback callback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { schedule(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { schedule(); }
    };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (PERMISSION.equals(intent.getAction())) pending = null;
            schedule();
        }
    };
    public UsbAudioDevices(Activity activity, Runnable changed) {
        this.activity = activity; this.changed = changed;
        usb = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
    }
    public void resume() {
        if (!registered) {
            IntentFilter filter = new IntentFilter(PERMISSION);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            audio.registerAudioDeviceCallback(callback, handler); registered = true;
        }
        schedule();
    }
    private void schedule() { handler.removeCallbacks(scan); handler.postDelayed(scan, 250); }
    public void refresh() {
        if (!registered) return;
        Set<String> connected = new HashSet<>(usb.getDeviceList().keySet());
        asked.retainAll(connected);
        if (pending != null && (!connected.contains(pending) || usb.hasPermission(usb.getDeviceList().get(pending)))) pending = null;
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && pending == null) {
            for (UsbDevice device : usb.getDeviceList().values()) {
                if (MixerSettings.hasAudioInterface(device) && !usb.hasPermission(device) && !asked.contains(device.getDeviceName())) {
                    request(device); break;
                }
            }
        }
        java.util.TreeSet<String> state = new java.util.TreeSet<>();
        for (UsbDevice device : usb.getDeviceList().values()) if (MixerSettings.hasAudioInterface(device))
            state.add(device.getDeviceName() + ":" + usb.hasPermission(device));
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_INPUTS))
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC || device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET)
                state.add(device.getType() + ":" + device.getId());
        String current = state.toString();
        if (!current.equals(inventory)) { inventory = current; changed.run(); }
    }
    public void request(UsbDevice device) {
        if (usb.hasPermission(device)) { changed.run(); return; }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 410); return;
        }
        if (pending != null) return;
        pending = device.getDeviceName(); asked.add(pending);
        Intent intent = new Intent(PERMISSION).setPackage(activity.getPackageName());
        try {
            usb.requestPermission(device, PendingIntent.getBroadcast(activity, device.getDeviceId(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
        } catch (RuntimeException e) { pending = null; }
    }
    public void pause() {
        handler.removeCallbacks(scan);
        if (registered) { activity.unregisterReceiver(receiver); audio.unregisterAudioDeviceCallback(callback); registered = false; }
        // A permission dialog may finish while paused. A fresh enumeration checks the grant on resume.
        pending = null;
    }
}
