package net.sourceforge.opencamera.audio;

import android.Manifest;
import android.content.res.Configuration;
import android.graphics.Typeface;
import net.sourceforge.opencamera.ui.StudioTheme;
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
        StudioTheme.apply(this);
        super.onCreate(state);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { moveTaskToBack(true); }
        });
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        buildUi();
        devices = new UsbAudioDevices(this, () -> {
            if (mixer != null) mixer.refreshDevices();
            else if (resumed && !recording && !busy) start(false);
        });
    }
    private void buildUi() {
        CharSequence previousStatus = status == null ? "Checking audio inputs…" : status.getText();
        CharSequence previousTime = elapsed == null ? "00:00" : elapsed.getText();
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout root = column(); root.setBackgroundColor(StudioTheme.palette(this).background);
        root.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(12) + insets.getSystemWindowInsetTop(),
                    dp(20) + insets.getSystemWindowInsetRight(), dp(12) + insets.getSystemWindowInsetBottom()); return insets;
        });
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("GearCam", 26); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = text("AUDIO RECORDER", 11); badge.setLetterSpacing(.12f); badge.setTextColor(StudioTheme.palette(this).accent);
        badge.setPadding(dp(12), dp(8), dp(12), dp(8)); badge.setBackground(StudioTheme.card(this, StudioTheme.palette(this).panel, 20));
        header.addView(badge); root.addView(header);
        status = text(previousStatus.toString(), 12); status.setTextColor(StudioTheme.palette(this).muted); status.setMaxLines(2);
        status.setPadding(0, dp(6), 0, dp(14)); root.addView(status);
        LinearLayout body = new LinearLayout(this); body.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        waveform = new WaveformView(this); waveform.setSession(session);
        LinearLayout.LayoutParams scopeSize = new LinearLayout.LayoutParams(landscape ? 0 : -1, landscape ? -1 : 0, 1);
        if (landscape) scopeSize.setMarginEnd(dp(20)); else scopeSize.bottomMargin = dp(12);
        body.addView(waveform, scopeSize);
        LinearLayout details = column(); details.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(details, new LinearLayout.LayoutParams(landscape ? dp(304) : -1, landscape ? -1 : -2));
        elapsed = text(previousTime.toString(), landscape ? 38 : 44); elapsed.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        elapsed.setGravity(Gravity.CENTER); elapsed.setPadding(0, 0, 0, dp(12)); details.addView(elapsed);
        destination = text("", 13); destination.setTextColor(StudioTheme.palette(this).muted); destination.setMaxLines(3);
        destination.setEllipsize(android.text.TextUtils.TruncateAt.END);
        destination.setPadding(dp(16), dp(12), dp(16), dp(12)); destination.setMinHeight(dp(64)); destination.setGravity(Gravity.CENTER_VERTICAL);
        destination.setBackground(StudioTheme.surface(this, StudioTheme.palette(this).panel, 16));
        destination.setContentDescription("Save location. Choose internal storage or SD card");
        destination.setOnClickListener(v -> { if (!busy && !recording) RecordingPreferences.chooseFolder(this, this::updateControls); });
        details.addView(destination, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout controls = new LinearLayout(this); controls.setGravity(Gravity.CENTER_VERTICAL); controls.setPadding(0, dp(12), 0, 0);
        inputs = button("Mixer", false); inputs.setOnClickListener(v -> openMixer());
        controls.addView(inputs, new LinearLayout.LayoutParams(0, dp(56), 1));
        record = button("Record", true); record.setOnClickListener(v -> toggleRecording());
        LinearLayout.LayoutParams recordSize = new LinearLayout.LayoutParams(0, dp(56), 1.5f); recordSize.setMargins(dp(8), 0, dp(8), 0);
        controls.addView(record, recordSize);
        config = button("Config", false); config.setOnClickListener(v -> showConfig());
        controls.addView(config, new LinearLayout.LayoutParams(0, dp(56), 1));
        details.addView(controls); setContentView(root); StudioTheme.systemBars(getWindow(), this); updateControls();
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration); buildUi();
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private int dp(int value) { return StudioTheme.dp(this, value); }
    private TextView text(String value, int size) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(StudioTheme.palette(this).text); return view; }
    private Button button(String title, boolean primary) {
        Button button = new Button(this); button.setText(title); StudioTheme.button(button, primary); return button;
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
                    status.setText((take ? "Recording" : "Live input · ready to record") + " · 48 kHz stereo"
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
                "Save location · internal storage / SD card", "Color theme", "Switch to video + audio"}, (dialog, which) -> {
            if (which == 0) new AlertDialog.Builder(this).setTitle("Audio-only format").setSingleChoiceItems(
                    new String[] {"WAV · 24-bit lossless", "MP3 · 320 kb/s", "FLAC · 24-bit lossless"},
                    java.util.Arrays.asList("wav", "mp3", "flac").indexOf(RecordingPreferences.format(this)), (d, choice) -> {
                        RecordingPreferences.prefs(this).edit().putString(RecordingPreferences.FORMAT, new String[] {"wav", "mp3", "flac"}[choice]).apply();
                        d.dismiss(); updateControls();
                    }).setNegativeButton("Cancel", null).show();
            else if (which == 1) RecordingPreferences.chooseFolder(this, this::updateControls);
            else if (which == 2) StudioTheme.choose(this);
            else { RecordingPreferences.prefs(this).edit().putString(RecordingPreferences.MODE, "video").apply(); startActivity(new Intent(this, net.sourceforge.opencamera.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish(); }
        }).show();
    }
    private void updateControls() {
        if (record == null) return;
        record.setEnabled(!busy); inputs.setEnabled(!busy); config.setEnabled(!busy && !recording);
        record.setText(recording ? "■  Stop" : "●  Record");
        record.setContentDescription(recording ? "Stop audio recording" : "Start audio recording");
        destination.setText("SAVE LOCATION   /   " + RecordingPreferences.format(this).toUpperCase(Locale.ROOT) + "\n" + RecordingPreferences.audioFolderLabel(this) + "   ›");
        destination.setEnabled(!busy && !recording);
        record.setBackgroundTintList(android.content.res.ColorStateList.valueOf(recording ? StudioTheme.RED : StudioTheme.palette(this).accent));
        record.setAlpha(busy ? .5f : 1f); inputs.setAlpha(busy ? .5f : 1f); config.setAlpha(busy || recording ? .5f : 1f);
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (recording) { long seconds = (SystemClock.elapsedRealtime() - started) / 1000; elapsed.setText(String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)); }
            handler.postDelayed(this, 250);
        }
    };
}
