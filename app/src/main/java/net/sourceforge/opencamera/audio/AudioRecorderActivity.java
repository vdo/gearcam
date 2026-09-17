package net.sourceforge.opencamera.audio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.net.Uri;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Audio-only recorder: no camera is opened and every format shares the mixer capture pipeline. */
public final class AudioRecorderActivity extends androidx.activity.ComponentActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private JamAudioSession session;
    private UsbAudioDevices devices;
    private MixerDialog mixer;
    private WaveformView waveform;
    private TextView status, elapsed, destination;
    private Button record, config, inputs;
    private volatile boolean resumed;
    private boolean busy, recording, finishing, askedMicrophone;
    private volatile int generation;
    private long started;
    private Uri lastSaved;
    public boolean isRecording() { return recording; }
    public boolean isBusy() { return busy; }
    public Uri getLastSaved() { return lastSaved; }
    public JamAudioSession getSession() { return session; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { moveTaskToBack(true); }
        });
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff0d141c); root.setPadding(dp(20), dp(16), dp(20), dp(16));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(),
                    dp(20) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom()); return insets;
        });
        TextView title = text("GEARCAM  /  AUDIO", 20); root.addView(title);
        status = text("Checking audio inputs…", 13); root.addView(status);
        waveform = new WaveformView(this); root.addView(waveform, new LinearLayout.LayoutParams(-1, 0, 1));
        elapsed = text("00:00", 30); elapsed.setGravity(Gravity.CENTER); root.addView(elapsed);
        destination = text("", 12); destination.setMaxLines(2); destination.setPadding(0, dp(8), 0, dp(8));
        destination.setOnClickListener(v -> { if (!busy && !recording) RecordingPreferences.chooseFolder(this); });
        root.addView(destination);
        LinearLayout controls = new LinearLayout(this); controls.setGravity(Gravity.CENTER_VERTICAL);
        inputs = button("Mixer"); inputs.setOnClickListener(v -> openMixer()); controls.addView(inputs, new LinearLayout.LayoutParams(0, dp(56), 1));
        record = button("Record audio"); record.setContentDescription("Start audio recording");
        record.setOnClickListener(v -> toggleRecording()); controls.addView(record, new LinearLayout.LayoutParams(0, dp(56), 2));
        config = button("Config"); config.setOnClickListener(v -> showConfig()); controls.addView(config, new LinearLayout.LayoutParams(0, dp(56), 1));
        root.addView(controls); setContentView(root);
        devices = new UsbAudioDevices(this, () -> {
            if (mixer != null) mixer.refreshDevices();
            else if (resumed && !recording && !busy) start(false);
        });
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(0xffe4edf5); return view; }
    private Button button(String title) {
        Button button = new Button(this); button.setText(title); button.setAllCaps(false); button.setTextSize(14);
        button.setTextColor(0xff66dec0); button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff263743));
        return button;
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true; devices.resume(); updateControls(); handler.post(tick);
        if (!busy && mixer == null) start(false);
    }
    @Override protected void onPause() {
        resumed = false; handler.removeCallbacks(tick); devices.pause();
        if (mixer != null) { mixer.dismiss(); mixer = null; }
        if (recording) stop(); else if (!finishing) releaseMonitor(null);
        super.onPause();
    }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); RecordingPreferences.folderResult(this, request, result, data); updateControls();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        devices.refresh();
        if (resumed && !busy && mixer == null && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start(false);
    }
    public void toggleRecording() { if (busy) return; if (recording) stop(); else start(true); }
    private boolean permitted(boolean requested) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.setText("Allow microphone access to show inputs and record audio");
            if (requested || !askedMicrophone) { askedMicrophone = true; requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 410); }
            return false;
        }
        if (android.os.Build.VERSION.SDK_INT <= 28 && !RecordingPreferences.prefs(this).getBoolean(net.sourceforge.opencamera.PreferenceKeys.UsingSAFPreferenceKey, false)
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED && requested) {
            requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, 411); return false;
        }
        return true;
    }
    private void start(boolean take) {
        if (!resumed || busy || recording || !permitted(take)) return;
        busy = true; final int token = ++generation; final String format = RecordingPreferences.format(this);
        JamAudioSession previous = session; session = null; waveform.setSession(null);
        status.setText("Checking selected inputs…"); updateControls();
        worker.execute(() -> {
            JamAudioSession opened = null;
            try {
                if (previous != null) { previous.cancel(); previous.awaitStopped(); }
                JamAudioSession.FailureListener failed = message -> handler.post(() -> {
                    if (token != generation) return;
                    status.setText(message);
                    if (recording) stop(); else if (!busy) releaseMonitor(null);
                });
                opened = take ? JamAudioSession.audioOnly(this, format, failed) : new JamAudioSession(this, false, failed);
                opened.awaitReady();
                JamAudioSession ready = opened;
                java.util.concurrent.CountDownLatch assigned = new java.util.concurrent.CountDownLatch(1);
                handler.post(() -> {
                    try {
                    if (token != generation || !resumed) { ready.cancelAndDiscard(); return; }
                    session = ready; busy = false; recording = take;
                    if (!take) elapsed.setText("00:00");
                    if (take) { started = SystemClock.elapsedRealtime(); ready.startVideo(System.nanoTime()); }
                    waveform.setSession(ready);
                    status.setText((take ? "RECORDING" : "LIVE INPUT · nothing saved") + " · 48 kHz stereo"
                            + (ready.missingInputs == null ? "" : " · without " + ready.missingInputs));
                    updateControls();
                    } finally { assigned.countDown(); }
                });
                assigned.await();
                if (token != generation || !resumed) { ready.cancel(); ready.awaitStopped(); }
            } catch (Exception e) {
                if (opened != null) opened.cancelAndDiscard();
                handler.post(() -> { if (token == generation) { busy = false; status.setText(e.getMessage()); updateControls(); } });
            }
        });
    }
    private void releaseMonitor(Runnable after) {
        ++generation; JamAudioSession previous = session; session = null; waveform.setSession(null);
        busy = after != null; updateControls();
        worker.execute(() -> {
            try { if (previous != null) { previous.cancel(); previous.awaitStopped(); } }
            catch (Exception ignored) { }
            if (after != null) handler.post(() -> { busy = false; updateControls(); if (resumed) after.run(); });
        });
    }
    private void stop() {
        if (!recording || session == null) return;
        JamAudioSession take = session; session = null; recording = false; busy = true; finishing = true;
        waveform.setSession(null); status.setText("Finishing audio…"); updateControls();
        take.finishAudioAsync((uri, error) -> handler.post(() -> {
            lastSaved = uri; busy = false; finishing = false;
            status.setText(error != null ? error : "Saved · " + RecordingPreferences.folderLabel(this));
            updateControls();
            if (resumed && error == null) {
                android.widget.Toast.makeText(this, "Audio saved", android.widget.Toast.LENGTH_SHORT).show();
                handler.postDelayed(() -> { if (resumed && !busy && !recording && mixer == null && session == null) start(false); }, 500);
            }
            if (error != null && resumed) new AlertDialog.Builder(this).setTitle("Audio recording").setMessage(error).setPositiveButton("OK", null).show();
        }));
    }
    private void openMixer() {
        if (busy) return;
        if (recording) showMixer(); else { busy = true; updateControls(); releaseMonitor(this::showMixer); }
    }
    private void showMixer() {
        mixer = new MixerDialog(this, recording ? session : null, devices, this::stop, () -> {
            mixer = null; if (resumed && !recording && !busy) start(false);
        }); mixer.show();
    }
    private void showConfig() {
        new AlertDialog.Builder(this).setTitle("Recording settings").setItems(new String[] {
                "Audio format · " + RecordingPreferences.format(this).toUpperCase(Locale.ROOT),
                "Save location · internal storage / SD card", "Switch to video + audio"}, (dialog, which) -> {
            if (which == 0) new AlertDialog.Builder(this).setTitle("Audio-only format").setSingleChoiceItems(
                    new String[] {"WAV · 24-bit lossless", "MP3 · 320 kb/s", "FLAC · 24-bit lossless"},
                    java.util.Arrays.asList("wav", "mp3", "flac").indexOf(RecordingPreferences.format(this)), (d, choice) -> {
                        RecordingPreferences.prefs(this).edit().putString(RecordingPreferences.FORMAT, new String[] {"wav", "mp3", "flac"}[choice]).apply();
                        d.dismiss(); updateControls();
                    }).setNegativeButton("Cancel", null).show();
            else if (which == 1) RecordingPreferences.chooseFolder(this);
            else { RecordingPreferences.prefs(this).edit().putString(RecordingPreferences.MODE, "video").apply(); startActivity(new Intent(this, net.sourceforge.opencamera.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish(); }
        }).show();
    }
    private void updateControls() {
        if (record == null) return;
        record.setEnabled(!busy); inputs.setEnabled(!busy); config.setEnabled(!busy && !recording);
        record.setText(recording ? "Stop recording" : "Record audio");
        record.setContentDescription(recording ? "Stop audio recording" : "Start audio recording");
        destination.setText(RecordingPreferences.format(this).toUpperCase(Locale.ROOT) + " · Save to " + RecordingPreferences.audioFolderLabel(this) + "  ›");
        destination.setEnabled(!busy && !recording);
        record.setTextColor(recording ? 0xffff8275 : 0xff66dec0);
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (recording) { long seconds = (SystemClock.elapsedRealtime() - started) / 1000; elapsed.setText(String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)); }
            updateControls(); handler.postDelayed(this, 250);
        }
    };
}
