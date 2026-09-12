package net.sourceforge.opencamera.audio;

import android.Manifest;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ToggleButton;
import androidx.core.content.ContextCompat;
import net.sourceforge.opencamera.MainActivity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Full-screen console: scrolling input strips and a pinned stereo master. */
public final class MixerDialog {
    private static final String USB_PERMISSION = "app.gearcam.USB_PERMISSION";
    private static final int BG = 0xff0d141c, PANEL = 0xff19232f, TEXT = 0xffe4edf5, MUTED = 0xff96aabb, ACCENT = 0xff66dec0;
    private final MainActivity activity;
    private final MixerSettings settings;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Meter> meters = new ArrayList<>();
    private final AudioManager audioManager;
    private final UsbManager usbManager;
    private boolean usbReceiverRegistered;
    private final Set<String> askedUsb = new HashSet<>(); // one access request per device per visit
    private JamAudioSession monitor;
    private final JamAudioSession recordingSession;
    private final LinearLayout channels;
    private final TextView status, master;
    private final LevelMeterView leftMeter, rightMeter;
    private final LinearLayout busCard, busControls, busMeters;
    private final Button soundcheck;
    private final Dialog dialog;
    private boolean closed, starting;
    private int generation;
    private boolean compact;
    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { devicesChanged(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { devicesChanged(); }
    };
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (closed || monitor != null || starting || recordingSession != null) return;
            rebuild();
            if (USB_PERMISSION.equals(intent.getAction())) status.setText(
                    intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ?
                            "USB connected · Select inputs to record" : "USB access was not granted");
        }
    };

    public MixerDialog(MainActivity activity) {
        this.activity = activity;
        compact = activity.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        settings = new MixerSettings(activity);
        audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        usbManager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        recordingSession = activity.getPreview().getJamAudioSession();
        dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = column(); root.setBackgroundColor(BG); root.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(12) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(),
                    dp(12) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout toolbar = row();
        TextView title = text("GEARCAM MIXER", 16, TEXT); title.setTypeface(null, Typeface.BOLD); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button done = button("Camera"); done.setOnClickListener(v -> dismiss()); toolbar.addView(done);
        root.addView(toolbar);
        status = text(recordingSession == null ? "48 kHz  ·  High-resolution mix  ·  Swipe inputs sideways" : "RECORDING  ·  Faders, balance, mute, filters and gate are live", 12, MUTED);
        root.addView(status);
        // Master bus: a full-width bar above the inputs, so it never covers a channel strip.
        LinearLayout bus = column(); bus.setPadding(dp(12), 0, dp(4), dp(8)); bus.setBackground(card(0xff21312f));
        busCard = bus;
        busControls = row();
        busControls.addView(text("MASTER  L / R", 14, ACCENT), new LinearLayout.LayoutParams(0, -2, 1));
        CheckBox limiter = check("Limiter", settings.preferences.getBoolean("limiter", true));
        limiter.setOnCheckedChangeListener((v, value) -> settings.preferences.edit().putBoolean("limiter", value).apply()); busControls.addView(limiter);
        soundcheck = button(recordingSession == null ? "Soundcheck" : "Recording");
        soundcheck.setEnabled(recordingSession == null);
        soundcheck.setOnClickListener(v -> { if (monitor == null) startMonitor(); else { stopMonitor(); status.setText("Soundcheck stopped"); } }); toolbar.addView(soundcheck, 1, new LinearLayout.LayoutParams(dp(124), dp(44)));
        Button reset = button("Reset clips"); reset.setOnClickListener(v -> { if (active() != null) active().mixer.resetClips(); }); busControls.addView(reset);
        if (recordingSession != null) {
            Button stop = button("Stop take"); stop.setTextColor(0xffff8275);
            stop.setOnClickListener(v -> { dismiss(); activity.getPreview().takePicturePressed(false, false); }); busControls.addView(stop);
        }
        bus.addView(busControls);
        busMeters = column();
        leftMeter = new LevelMeterView(activity); rightMeter = new LevelMeterView(activity);
        LinearLayout.LayoutParams meterSize = new LinearLayout.LayoutParams(-1, dp(8)); meterSize.setMargins(0, dp(2), dp(8), dp(2));
        busMeters.addView(leftMeter, meterSize); busMeters.addView(rightMeter, new LinearLayout.LayoutParams(meterSize));
        master = text("L −∞ dBFS   R −∞ dBFS", 11, TEXT); bus.addView(master);
        layoutBus();
        root.addView(bus);

        // Horizontal scroller outermost: its always-visible bar sits at the bottom of the screen area,
        // not under strips that are taller than the screen.
        HorizontalScrollView horizontal = new HorizontalScrollView(activity);
        horizontal.setFillViewport(true); horizontal.setScrollbarFadingEnabled(false);
        channels = row(); channels.setGravity(Gravity.TOP); channels.setPadding(0, dp(8), dp(8), dp(12));
        ScrollView inputScroll = new ScrollView(activity); inputScroll.setFillViewport(true);
        inputScroll.addView(channels, new android.widget.FrameLayout.LayoutParams(-2, -2));
        horizontal.addView(inputScroll, new android.widget.FrameLayout.LayoutParams(-2, -1));
        root.addView(horizontal, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout options = row();
        CheckBox wav = check("24-bit WAV master", settings.preferences.getBoolean("wav_master", true)); wav.setEnabled(recordingSession == null);
        wav.setOnCheckedChangeListener((v, value) -> settings.preferences.edit().putBoolean("wav_master", value).apply()); options.addView(wav);
        Button settingsButton = button("Sync & help"); settingsButton.setOnClickListener(v -> showOptions()); options.addView(settingsButton);
        root.addView(options);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            boolean landscape = r - l > b - t;
            if (compact != landscape) { compact = landscape; layoutBus(); rebuild(); }
        });
        dialog.setContentView(root); dialog.setOnDismissListener(d -> close()); rebuild();
    }

    public void show() {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(-1, -1); window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, handler);
        IntentFilter filter = new IntentFilter(USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(activity, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        usbReceiverRegistered = true; handler.post(tick);
    }

    public void dismiss() { dialog.dismiss(); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private int dp(int v) { return Math.round(v * activity.getResources().getDisplayMetrics().density); }
    private GradientDrawable card(int color) { GradientDrawable b = new GradientDrawable(); b.setColor(color); b.setCornerRadius(dp(8)); return b; }
    private TextView text(String value, int size, int color) {
        TextView v = new TextView(activity); v.setText(value); v.setTextColor(color); v.setTextSize(size); v.setPadding(0, dp(3), 0, dp(3)); return v;
    }
    private Button button(String label) {
        Button v = new Button(activity); v.setText(label); v.setAllCaps(false); v.setTextSize(12); v.setTextColor(TEXT);
        v.setMinWidth(0); v.setMinimumWidth(0); v.setMinHeight(dp(44)); v.setPadding(dp(8), 0, dp(8), 0);
        v.setBackgroundTintList(ColorStateList.valueOf(0xff30414f)); return v;
    }
    private ToggleButton toggle(String preference, String label, String description) {
        ToggleButton v = new ToggleButton(activity); v.setTextOn(label); v.setTextOff(label);
        v.setChecked(settings.preferences.getBoolean(preference, false)); v.setContentDescription(description);
        v.setAllCaps(false); v.setTextSize(11); v.setMinWidth(0); v.setMinimumWidth(0); v.setMinHeight(0); v.setMinimumHeight(0);
        v.setPadding(dp(2), 0, dp(2), 0);
        int[][] states = {new int[] {android.R.attr.state_checked}, new int[] {}};
        v.setTextColor(new ColorStateList(states, new int[] {BG, TEXT}));
        v.setBackgroundTintList(new ColorStateList(states, new int[] {ACCENT, 0xff30414f}));
        v.setOnCheckedChangeListener((b, on) -> settings.preferences.edit().putBoolean(preference, on).apply());
        return v;
    }
    private CheckBox check(String label, boolean value) {
        CheckBox v = new CheckBox(activity); v.setText(label); v.setTextSize(12); v.setTextColor(TEXT); v.setChecked(value);
        v.setButtonTintList(new ColorStateList(new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}}, new int[] {ACCENT, MUTED}));
        v.setMinHeight(dp(44)); return v;
    }

    /** Landscape puts the master meters on its controls row and drops the readout: the strips need the height. */
    private void layoutBus() {
        LinearLayout parent = (LinearLayout) busMeters.getParent();
        if (parent != null) parent.removeView(busMeters);
        if (compact) busControls.addView(busMeters, 1, new LinearLayout.LayoutParams(0, -2, 1));
        else busCard.addView(busMeters, 1, new LinearLayout.LayoutParams(-1, -2));
        master.setVisibility(compact ? View.GONE : View.VISIBLE);
    }

    private void rebuild() {
        channels.removeAllViews(); meters.clear();
        Set<String> missing = new HashSet<>(settings.preferences.getStringSet("inputs", java.util.Collections.singleton("phone/0")));
        List<MixerSettings.Input> inputs = active() == null ? MixerSettings.inputs(activity) : active().inputs;
        for (MixerSettings.Input input : inputs) {
            for (int c = 0; c < input.channels; c++) missing.remove(input.channelKey(c));
            for (int c = 0; c < input.channels; c++) {
                boolean stereo = settings.linked(input, c);
                if (stereo && c % 2 == 1) continue;
                addStrip(input, c, stereo);
            }
        }
        // A selected interface that is plugged in but not allowed yet: ask once, so gear reconnects on its own.
        if (recordingSession == null && monitor == null && !starting) {
            for (MixerSettings.Input input : inputs) {
                if (!input.directUsb || usbManager.hasPermission(input.usbDevice) || !settings.selected(input)) continue;
                if (askedUsb.add(input.key)) requestUsbPermission(input);
            }
        }
        if (!missing.isEmpty()) {
            LinearLayout strip = strip(); strip.addView(text("DISCONNECTED", 12, 0xffff8275));
            strip.addView(text(MixerSettings.describe(missing) + " is not connected. It returns on its own when "
                    + "plugged back in; takes run without it meanwhile.", 12, TEXT));
            Button clear = button("Clear missing"); clear.setEnabled(recordingSession == null);
            clear.setOnClickListener(v -> { stopMonitor(); Set<String> chosen = new HashSet<>(settings.preferences.getStringSet("inputs", java.util.Collections.singleton("phone/0"))); chosen.removeAll(missing); settings.preferences.edit().putStringSet("inputs", chosen).apply(); rebuild(); }); strip.addView(clear);
        }
    }

    private LinearLayout strip() {
        LinearLayout strip = column(); strip.setPadding(dp(compact ? 8 : 12), dp(8), dp(compact ? 8 : 12), dp(8)); strip.setBackground(card(PANEL));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(compact ? 256 : 166), -2); size.setMargins(0, 0, dp(8), 0); channels.addView(strip, size); return strip;
    }

    private void addStrip(MixerSettings.Input input, int channel, boolean stereo) {
        LinearLayout strip = strip();
        String title = input.phone ? "PHONE MIC" : stereo ? "INPUTS " + (channel + 1) + " / " + (channel + 2) : "INPUT " + (channel + 1);
        TextView heading = text(title, 14, stereo ? ACCENT : TEXT); heading.setTypeface(null, Typeface.BOLD); strip.addView(heading);
        TextView device = text(input.label.replace(" · Direct USB", ""), 10, MUTED); device.setMaxLines(2); device.setMinHeight(dp(32)); strip.addView(device);
        if (input.directUsb && !usbManager.hasPermission(input.usbDevice)) {
            Button permission = button("Connect USB"); permission.setEnabled(recordingSession == null);
            permission.setOnClickListener(v -> requestUsbPermission(input)); strip.addView(permission);
        }
        CheckBox armed = check(stereo ? "Record pair" : "Record", settings.selected(input, channel)); armed.setEnabled(recordingSession == null);
        armed.setOnCheckedChangeListener((v, enabled) -> {
            stopMonitor(); Set<String> chosen = new HashSet<>(settings.preferences.getStringSet("inputs", java.util.Collections.singleton("phone/0")));
            for (int c = channel; c <= channel + (stereo ? 1 : 0); c++) { if (enabled) chosen.add(input.channelKey(c)); else chosen.remove(input.channelKey(c)); }
            settings.preferences.edit().putStringSet("inputs", chosen).apply();
            if (enabled && input.directUsb && !usbManager.hasPermission(input.usbDevice)) requestUsbPermission(input);
        }); strip.addView(armed);
        String key = settings.controlKey(input, channel);
        float defaultPan = stereo ? 0 : input.channels == 2 ? (channel == 0 ? -1 : 1) : 0;
        float pan = settings.preferences.getFloat(key + "/pan", defaultPan);
        TextView panLabel = text(panText(stereo, pan), 11, MUTED); strip.addView(panLabel);
        SeekBar panSlider = new SeekBar(activity); panSlider.setMax(200); panSlider.setProgress(Math.round(pan * 100) + 100);
        panSlider.setContentDescription(title + (stereo ? " balance" : " pan")); panSlider.setProgressTintList(ColorStateList.valueOf(ACCENT)); panSlider.setThumbTintList(ColorStateList.valueOf(ACCENT));
        panSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar v, int progress, boolean user) {
                if (!user) return;
                // Within 6 dp of the middle the control sits at exactly center.
                int track = Math.max(1, v.getWidth() - v.getPaddingLeft() - v.getPaddingRight());
                if (progress != 100 && Math.abs(progress - 100) * track <= dp(6) * 200) { v.setProgress(100); progress = 100; }
                float value = (progress - 100) / 100f;
                if (value == 0 && settings.preferences.getFloat(key + "/pan", defaultPan) != 0) v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                panLabel.setText(panText(stereo, value)); settings.preferences.edit().putFloat(key + "/pan", value).apply();
            }
            public void onStartTrackingTouch(SeekBar v) { v.getParent().requestDisallowInterceptTouchEvent(true); }
            public void onStopTrackingTouch(SeekBar v) { v.getParent().requestDisallowInterceptTouchEvent(false); }
        }); strip.addView(panSlider, new LinearLayout.LayoutParams(-1, dp(40)));
        TextView gain = text(String.format(Locale.getDefault(), "%+.1f dB", settings.preferences.getFloat(key + "/gain", 0)), 18, TEXT);
        gain.setGravity(Gravity.CENTER); strip.addView(gain);
        LinearLayout faders = row();
        // The master bar above the inputs takes ~100 dp of height; size faders so a strip fits a phone screen.
        int faderHeight = Math.max(120, Math.min(220, (int) (activity.getResources().getDisplayMetrics().heightPixels / activity.getResources().getDisplayMetrics().density) - 560));
        MixerFader fader = new MixerFader(activity, settings.preferences.getFloat(key + "/gain", 0), title + " gain", value -> { gain.setText(String.format(Locale.getDefault(), "%+.1f dB", value)); settings.preferences.edit().putFloat(key + "/gain", value).apply(); });
        faders.addView(fader, new LinearLayout.LayoutParams(0, dp(faderHeight), 1));
        LinearLayout values = row();
        for (int c = channel; c <= channel + (stereo ? 1 : 0); c++) {
            LevelMeterView bar = new LevelMeterView(activity, true); LinearLayout.LayoutParams barSize = new LinearLayout.LayoutParams(dp(9), dp(faderHeight - 36)); barSize.setMargins(dp(5), 0, 0, 0); faders.addView(bar, barSize);
            TextView readout = text("−∞ dBFS", 10, MUTED); readout.setMinHeight(dp(32)); readout.setMaxLines(2); values.addView(readout, new LinearLayout.LayoutParams(0, -2, 1)); meters.add(new Meter(input.channelKey(c), readout, bar));
        }
        strip.addView(faders); strip.addView(values);
        CheckBox mute = check("Mute", settings.preferences.getBoolean(key + "/mute", false)); mute.setOnCheckedChangeListener((v, value) -> settings.preferences.edit().putBoolean(key + "/mute", value).apply()); strip.addView(mute);
        LinearLayout processing = row(); // live, like the fader: the same switches a desk strip has
        processing.addView(toggle(key + MixerSettings.HIGH_PASS, "HPF", title + " high-pass 30 Hz"), new LinearLayout.LayoutParams(0, dp(40), 1));
        processing.addView(toggle(key + MixerSettings.LOW_PASS, "LPF", title + " low-pass 20 kHz"), new LinearLayout.LayoutParams(0, dp(40), 1));
        strip.addView(processing);
        CheckBox gate = check("Noise gate", settings.preferences.getBoolean(MixerSettings.GATE, false)); // live
        gate.setOnCheckedChangeListener((v, on) -> settings.preferences.edit().putBoolean(MixerSettings.GATE, on).apply());
        if (input.phone) strip.addView(gate);
        View routing;
        if (!input.phone && channel % 2 == 0 && channel + 1 < input.channels) {
            Button link = button(stereo ? "Unlink stereo" : "Link " + (channel + 1) + " + " + (channel + 2)); link.setTextColor(ACCENT); link.setEnabled(recordingSession == null);
            link.setOnClickListener(v -> { stopMonitor(); settings.link(input, channel, !stereo); rebuild(); }); strip.addView(link); routing = link;
        } else { routing = text(input.phone ? "ROOM / AMBIENCE" : "MONO", 10, MUTED); strip.addView(routing); }
        if (compact) {
            device.setVisibility(View.GONE);
            for (View child : new View[] {armed, panLabel, panSlider, gain, faders, values, mute, processing, gate, routing}) strip.removeView(child);
            LinearLayout controls = column();
            controls.addView(armed); controls.addView(mute); controls.addView(processing);
            if (input.phone) controls.addView(gate);
            controls.addView(panLabel);
            controls.addView(panSlider, new LinearLayout.LayoutParams(-1, dp(40))); controls.addView(routing);
            LinearLayout gainStrip = column(); gainStrip.setPadding(dp(8), 0, 0, 0); gainStrip.addView(gain);
            fader.getLayoutParams().height = dp(120);
            for (int i = 1; i < faders.getChildCount(); i++) faders.getChildAt(i).getLayoutParams().height = dp(84);
            gainStrip.addView(faders); gainStrip.addView(values);
            LinearLayout body = row(); body.setGravity(Gravity.TOP);
            body.addView(controls, new LinearLayout.LayoutParams(dp(114), -2));
            body.addView(gainStrip, new LinearLayout.LayoutParams(0, -2, 1)); strip.addView(body);
        }
    }

    private String panText(boolean stereo, float value) {
        return (stereo ? "Balance" : "Pan") + " · " + (value == 0 ? "Center" : Math.round(Math.abs(value) * 100) + "% " + (value < 0 ? "L" : "R"));
    }

    private void requestUsbPermission(MixerSettings.Input input) {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 410);
            status.setText("Allow microphone access, then connect USB"); return;
        }
        Intent intent = new Intent(USB_PERMISSION).setPackage(activity.getPackageName());
        usbManager.requestPermission(input.usbDevice, PendingIntent.getBroadcast(activity, input.usbDevice.getDeviceId(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
    }

    private void showOptions() {
        LinearLayout content = column(); content.setPadding(dp(20), dp(12), dp(20), dp(12));
        TextView label = text("Audio/video delay: " + settings.preferences.getFloat("sync_ms", 0) + " ms", 14, TEXT); content.addView(label);
        SeekBar sync = new SeekBar(activity); sync.setMax(1000); sync.setProgress(Math.round(settings.preferences.getFloat("sync_ms", 0)) + 500); sync.setEnabled(recordingSession == null); sync.setContentDescription("Audio/video delay");
        sync.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar v, int n, boolean user) { if (user) { settings.preferences.edit().putFloat("sync_ms", n - 500).apply(); label.setText("Audio/video delay: " + (n - 500) + " ms"); } }
            public void onStartTrackingTouch(SeekBar v) { }
            public void onStopTrackingTouch(SeekBar v) { }
        }); content.addView(sync);
        content.addView(text("Positive delay moves audio later. Check sync with a clap.\n\n48 kHz throughout: no upsampling. Direct USB chooses its highest available bit depth. The 24-bit WAV master saves to Music/GearCam (about 1 GB/hour). MP4 audio is AAC.\n\nRed input clipping means the source or adapter overloaded. Lower its hardware gain; a fader cannot repair distortion. Stereo links share gain, balance, mute, filters and recording selection.\n\nHPF removes DC and infrasonic content below 30 Hz, LPF ultrasonic content above 20 kHz: both 3-pole Butterworth (−18 dB/octave), freeing headroom.\n\nNoise gate (phone mic) learns the background and turns it down 20 dB between phrases, so hum and room noise drop out in the pauses. It cannot remove noise under the voice.\n\nDrag a fader vertically. Swipe between strips to see more inputs. Soundcheck uses the recording path without saving.", 13, TEXT));
        new android.app.AlertDialog.Builder(activity, android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Recording settings").setView(content).setPositiveButton("Done", null).show();
    }

    private void startMonitor() {
        if (starting) return;
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 410);
            status.setText("Allow microphone access, then tap Start soundcheck."); return;
        }
        starting = true; soundcheck.setEnabled(false); status.setText("Checking selected inputs…");
        final int request = ++generation;
        new Thread(() -> {
            JamAudioSession session = null;
            try {
                session = new JamAudioSession(activity, false, message -> handler.post(() -> {
                    if (!closed && request == generation) { status.setText(message); stopMonitor(); }
                }));
                session.awaitReady();
                JamAudioSession ready = session;
                handler.post(() -> {
                    if (closed || request != generation) { ready.cancel(); return; }
                    starting = false;
                    monitor = ready; soundcheck.setEnabled(true); soundcheck.setText("Stop soundcheck");
                    status.setText(ready.missingInputs == null ? "SOUNDCHECK  ·  Inputs live  ·  Nothing saved"
                            : "SOUNDCHECK  ·  without " + ready.missingInputs);
                });
            } catch (Exception e) {
                if (session != null) session.cancel();
                handler.post(() -> {
                    if (!closed && request == generation) { starting = false; soundcheck.setEnabled(true); status.setText(e.getMessage()); }
                });
            }
        }, "GearCam soundcheck").start();
    }

    private void stopMonitor() {
        generation++; starting = false;
        if (monitor != null) { monitor.cancel(); monitor = null; }
        if (recordingSession == null) { soundcheck.setText("Start soundcheck"); soundcheck.setEnabled(true); }
    }

    private void devicesChanged() {
        if (!closed && recordingSession == null && monitor == null && !starting) rebuild();
    }

    private JamAudioSession active() { return recordingSession == null ? monitor : recordingSession; }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            JamAudioSession session = active();
            if (session != null) {
                PcmMixer mixer = session.mixer;
                leftMeter.level(mixer.leftPeak, mixer.busClipped); rightMeter.level(mixer.rightPeak, mixer.busClipped);
                master.setText(String.format(Locale.getDefault(), "L %s   R %s   Limit −%.1f dB%s", db(mixer.leftPeak), db(mixer.rightPeak), mixer.reductionDb, mixer.busClipped ? " · MIX OVERLOAD" : ""));
                int index = 0;
                for (MixerSettings.Input input : session.inputs) for (int c = 0; c < input.channels; c++) {
                    PcmMixer.Channel channel = mixer.channels[index++];
                    for (Meter meter : meters) if (meter.key.equals(input.channelKey(c))) {
                        meter.view.setText(db(channel.peak) + (channel.inputClipped ? "\nINPUT CLIP" : channel.gainClipped ? "\nGAIN CLIP" : ""));
                        meter.bar.level(channel.peak, channel.inputClipped || channel.gainClipped);
                        meter.view.setTextColor(channel.inputClipped || channel.gainClipped ? Color.rgb(210, 50, 50) : ACCENT);
                    }
                }
            }
            handler.postDelayed(this, 100);
        }
    };

    private static String db(float amplitude) {
        return amplitude < 0.00001f ? "−∞ dBFS" : String.format(Locale.getDefault(), "%.1f dBFS", 20 * Math.log10(amplitude));
    }

    private void close() {
        closed = true; handler.removeCallbacks(tick); stopMonitor();
        audioManager.unregisterAudioDeviceCallback(deviceCallback);
        if (usbReceiverRegistered) activity.unregisterReceiver(usbReceiver);
    }

    private static final class Meter {
        final String key; final TextView view; final LevelMeterView bar;
        Meter(String key, TextView view, LevelMeterView bar) { this.key = key; this.view = view; this.bar = bar; }
    }
}
