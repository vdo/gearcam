package net.sourceforge.opencamera.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;

import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Device/channel choices are explicit. A disconnected selected input is never replaced by a mic. */
public final class MixerSettings {
    public static final int RATE = 48000;
    public static final int MAX_CHANNELS = 32;
    public final SharedPreferences preferences;

    public static final class Input {
        public final AudioDeviceInfo device;
        public final UsbDevice usbDevice;
        public final String key, label;
        public final int channels;
        public final boolean phone, directUsb;

        Input(AudioDeviceInfo device, boolean phone) {
            this.device = device; this.usbDevice = null; this.phone = phone; this.directUsb = false;
            label = phone ? "Phone microphone" : device.getProductName().toString();
            String address = Build.VERSION.SDK_INT >= 28 ? device.getAddress() : "";
            key = phone ? "phone" : device.getType() + ":" + label + ":" + address;
            int count = 1;
            if (!phone) for (int n : device.getChannelCounts()) if (n <= MAX_CHANNELS) count = Math.max(count, n);
            channels = count;
        }

        Input(UsbDevice device, int channels) {
            this.device = null; this.usbDevice = device; this.phone = false; this.directUsb = true;
            this.label = usbLabel(device) + " · Direct USB";
            this.key = "usb:" + device.getVendorId() + ":" + device.getProductId() + ":" + usbLabel(device);
            this.channels = Math.max(1, Math.min(MAX_CHANNELS, channels));
        }

        public String channelKey(int channel) { return key + "/" + channel; }
    }

    public MixerSettings(Context context) {
        preferences = context.getSharedPreferences("gearcam_mixer", Context.MODE_PRIVATE);
    }

    public static List<Input> inputs(Context context) {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        List<Input> result = new ArrayList<>();
        boolean havePhone = false;
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            boolean phone = device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC;
            boolean external = device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET;
            if (phone && havePhone) continue;
            if (phone || external) result.add(new Input(device, phone));
            havePhone |= phone;
        }
        for (UsbDevice usb : usbManager.getDeviceList().values()) {
            if (!hasAudioInterface(usb)) continue;
            int channels = advertisedUsbChannels(manager, usb);
            if (usbManager.hasPermission(usb)) {
                UsbDeviceConnection connection = usbManager.openDevice(usb);
                if (connection != null) {
                    // The descriptor is what direct USB opens; Android's advertised count can differ.
                    int capture = usbCaptureChannels(connection.getRawDescriptors());
                    if (capture > 0) channels = capture;
                    connection.close();
                }
            }
            result.add(new Input(usb, channels));
        }
        return result;
    }

    public static String usbLabel(UsbDevice device) {
        String value = device.getProductName();
        return value == null || value.trim().isEmpty() ?
                String.format("USB audio %04x:%04x", device.getVendorId(), device.getProductId()) : value;
    }

    private static boolean hasAudioInterface(UsbDevice device) {
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_AUDIO) return true;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO) return true;
        }
        return false;
    }

    private static int advertisedUsbChannels(AudioManager manager, UsbDevice usb) {
        int channels = 1;
        String product = usbLabel(usb);
        for (AudioDeviceInfo audio : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            boolean usbType = audio.getType() == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    audio.getType() == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                    (Build.VERSION.SDK_INT >= 26 && audio.getType() == AudioDeviceInfo.TYPE_USB_HEADSET);
            if (!usbType || !product.contentEquals(audio.getProductName())) continue;
            for (int count : audio.getChannelCounts()) if (count <= MAX_CHANNELS) channels = Math.max(channels, count);
        }
        return channels;
    }

    /** Largest capture channel count in UAC1/UAC2 descriptors, read without claiming the device. 0 if none. */
    static int usbCaptureChannels(byte[] raw) {
        int audioClass = -1, audioSubclass = -1, version = 0, pending = 0, result = 0;
        for (int p = 0; raw != null && p + 2 <= raw.length; ) {
            int length = raw[p] & 0xff;
            if (length < 2 || p + length > raw.length) break;
            int type = raw[p + 1] & 0xff;
            boolean streaming = audioClass == UsbConstants.USB_CLASS_AUDIO && audioSubclass == 2;
            if (type == 4 && length >= 9) {
                audioClass = raw[p + 5] & 0xff;
                audioSubclass = raw[p + 6] & 0xff;
                pending = 0;
            } else if (type == 0x24 && audioClass == UsbConstants.USB_CLASS_AUDIO && audioSubclass == 1 &&
                    length >= 5 && (raw[p + 2] & 0xff) == 1) {
                version = (raw[p + 3] & 0xff) | (raw[p + 4] & 0xff) << 8;
            } else if (version == 0x0200 && streaming && type == 0x24 && length >= 11 && (raw[p + 2] & 0xff) == 1) {
                // UAC2 AS_GENERAL: Type I PCM or float. The rate lives on the clock; the native open checks it.
                if ((raw[p + 5] & 0xff) == 1 && (raw[p + 6] & 0x05) != 0) pending = raw[p + 10] & 0xff;
            } else if (type == 5 && streaming && length >= 7) {
                // The data endpoint comes first and IN means capture. A later IN endpoint is playback feedback.
                if ((raw[p + 2] & 0x80) != 0) result = Math.max(result, pending);
                pending = 0;
            } else if (version == 0x0100 && streaming && type == 0x24 &&
                    length >= 11 && (raw[p + 2] & 0xff) == 2 && (raw[p + 3] & 0xff) == 1) {
                int channels = raw[p + 4] & 0xff;
                int frequencyCount = raw[p + 7] & 0xff;
                boolean supports48k = false;
                if (frequencyCount == 0 && length >= 14) {
                    int low = uint24(raw, p + 8), high = uint24(raw, p + 11);
                    supports48k = low <= RATE && RATE <= high;
                } else for (int i = 0; i < frequencyCount && p + 10 + i * 3 < p + length; i++)
                    supports48k |= uint24(raw, p + 8 + i * 3) == RATE;
                if (supports48k) pending = channels;
            }
            p += length;
        }
        return result;
    }

    private static int uint24(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8) | ((bytes[offset + 2] & 0xff) << 16);
    }

    /** Per-strip processing switches, stored under the strip's control key. */
    public static final String HIGH_PASS = "/hpf", LOW_PASS = "/lpf";
    /** Phone-mic noise gate (see NoiseGate). */
    public static final String GATE = "phone/gate";

    /** Readable device names behind stored channel keys ("usb:vid:pid:Name/3"); null if there are none. */
    public static String describe(java.util.Set<String> channelKeys) {
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        for (String key : channelKeys) {
            String device = key.substring(0, Math.max(0, key.lastIndexOf('/')));
            if (device.equals("phone")) names.add("Phone microphone");
            else if (device.startsWith("usb:")) { String[] parts = device.split(":", 4); names.add(parts.length > 3 ? parts[3] : device); }
            else { String[] parts = device.split(":"); names.add(parts.length > 1 ? parts[1] : device); }
        }
        return names.isEmpty() ? null : android.text.TextUtils.join(", ", names);
    }

    public boolean selected(Input input, int channel) {
        if (linked(input, channel)) channel -= channel % 2;
        return preferences.getStringSet("inputs", Collections.singleton("phone/0")).contains(input.channelKey(channel));
    }

    public boolean linked(Input input, int channel) {
        int first = channel - channel % 2;
        return !input.phone && first + 1 < input.channels && preferences.getBoolean(input.channelKey(first) + "/linked", false);
    }

    public String controlKey(Input input, int channel) {
        return linked(input, channel) ? input.channelKey(channel - channel % 2) + "/stereo" : input.channelKey(channel);
    }

    public void link(Input input, int first, boolean enabled) {
        if (first % 2 != 0 || first + 1 >= input.channels) return;
        String key = input.channelKey(first);
        java.util.Set<String> chosen = new java.util.HashSet<>(preferences.getStringSet("inputs", Collections.singleton("phone/0")));
        if (enabled && (chosen.contains(key) || chosen.contains(input.channelKey(first + 1)))) {
            chosen.add(key); chosen.add(input.channelKey(first + 1));
        }
        SharedPreferences.Editor edit = preferences.edit().putBoolean(key + "/linked", enabled).putStringSet("inputs", chosen);
        if (enabled) {
            edit.putFloat(key + "/stereo/gain", preferences.getFloat(key + "/gain", 0))
                    .putFloat(key + "/stereo/pan", 0).putBoolean(key + "/stereo/mute", preferences.getBoolean(key + "/mute", false));
            for (String option : new String[] {HIGH_PASS, LOW_PASS})
                edit.putBoolean(key + "/stereo" + option, preferences.getBoolean(key + option, false));
        } else {
            for (int c = first; c <= first + 1; c++) {
                edit.putFloat(input.channelKey(c) + "/gain", preferences.getFloat(key + "/stereo/gain", 0))
                        .putBoolean(input.channelKey(c) + "/mute", preferences.getBoolean(key + "/stereo/mute", false))
                        .putFloat(input.channelKey(c) + "/pan", c == first ? -1 : 1);
                for (String option : new String[] {HIGH_PASS, LOW_PASS})
                    edit.putBoolean(input.channelKey(c) + option, preferences.getBoolean(key + "/stereo" + option, false));
            }
        }
        edit.apply();
    }

    public boolean selected(Input input) {
        for (int c = 0; c < input.channels; c++) if (selected(input, c)) return true;
        return false;
    }

    public void apply(Input input, int c, PcmMixer.Channel channel) {
        String key = controlKey(input, c);
        channel.stereoSide = linked(input, c) ? (c % 2 == 0 ? -1 : 1) : 0;
        channel.gainDb = preferences.getFloat(key + "/gain", 0);
        channel.pan = preferences.getFloat(key + "/pan", linked(input, c) ? 0 : input.channels == 2 ? (c == 0 ? -1 : 1) : 0);
        channel.mute = !selected(input, c) || preferences.getBoolean(key + "/mute", false);
        channel.highPass = preferences.getBoolean(key + HIGH_PASS, false);
        channel.lowPass = preferences.getBoolean(key + LOW_PASS, false);
    }

    public static void configurePreferences(PreferenceFragment fragment) {
        removePreferences(fragment.getPreferenceScreen(), new String[] {
                "preference_burst_mode", "preference_burst_interval", "preference_screen_photo_settings",
                "preference_screen_processing_settings", "preference_show_auto_level", "preference_show_cycle_raw",
                "preference_show_stamp", "preference_show_textstamp", "preference_show_preview_shots",
                "preference_ghost_image", "ghost_image_alpha", "preference_thumbnail_animation",
                "preference_take_photo_border", "preference_show_video_max_amp", "preference_touch_capture",
                "preference_pause_preview", "preference_shutter_sound", "preference_audio_control",
                "preference_audio_noise_control_sensitivity", "preference_camera2_photo_video_recording",
                "preference_record_audio_src", "preference_record_audio_channels", "preference_video_restart",
                "preference_video_restart_max_filesize", "preference_save_photo_prefix", "preference_show_take_photo", "preference_preview_size"
        });
        Preference format = fragment.findPreference("preference_video_output_format");
        if (format instanceof ListPreference) {
            ((ListPreference) format).setEntries(new String[] {"MP4 · H.264", "MP4 · HEVC"});
            ((ListPreference) format).setEntryValues(new String[] {"preference_video_output_format_mpeg4_h264", "preference_video_output_format_mpeg4_hevc"});
        }
        Preference volume = fragment.findPreference("preference_volume_keys");
        if (volume instanceof ListPreference) {
            ((ListPreference) volume).setEntries(new String[] {"Start/stop video recording", "Focus", "Zoom in/out", "Change exposure", "Change device volume", "Do nothing"});
            ((ListPreference) volume).setEntryValues(new String[] {"volume_take_photo", "volume_focus", "volume_zoom", "volume_exposure", "volume_nothing", "volume_really_nothing"});
        }
        if (fragment.findPreference("preference_record_audio") != null || fragment.findPreference("preference_about") != null)
            addMixerPreference(fragment);
    }

    public static void removePreferences(PreferenceGroup group, String[] keys) {
        for (int i = group.getPreferenceCount() - 1; i >= 0; i--) {
            Preference pref = group.getPreference(i);
            boolean remove = false;
            for (String key : keys) if (key.equals(pref.getKey())) remove = true;
            if (remove) group.removePreference(pref);
            else if (pref instanceof PreferenceGroup) removePreferences((PreferenceGroup) pref, keys);
        }
    }

    public static void addMixerPreference(PreferenceFragment fragment) {
        if (fragment.findPreference("gearcam_audio_mixer") != null) return;
        Preference preference = new Preference(fragment.getActivity());
        preference.setKey("gearcam_audio_mixer"); preference.setTitle(R.string.audio_mixer);
        preference.setSummary(R.string.audio_mixer_summary); preference.setIcon(R.drawable.ic_mixer);
        preference.setOrder(-1);
        preference.setOnPreferenceClickListener(p -> { ((MainActivity) fragment.getActivity()).showAudioMixer(); return true; });
        fragment.getPreferenceScreen().addPreference(preference);
    }

    public static void enforceVideoOnly(Context context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("is_video", true).putString("preference_photo_mode", "preference_photo_mode_std")
                .putString("preference_audio_control", "none").putString("preference_burst_mode", "1")
                .putBoolean("preference_pause_preview", false).putBoolean("preference_show_whats_new", false)
                .putBoolean("preference_show_take_photo", true)
                .putBoolean("preference_camera2_photo_video_recording", false).apply();
    }
}
