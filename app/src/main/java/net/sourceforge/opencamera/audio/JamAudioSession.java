package net.sourceforge.opencamera.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTimestamp;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Captures explicitly routed devices and mixes them on a shared monotonic timeline. */
public final class JamAudioSession {
    public interface FailureListener { void failed(String message); }
    public interface SaveListener { void finished(String error); }
    /** videoSaved: the MP4 is complete and playable; error: what went wrong, if anything (audio, WAV master). */
    public interface LiveListener { void finished(boolean videoSaved, String error); }
    private static final int BLOCK = 480;
    // A new take waits for the previous soundcheck/take to release native interfaces completely.
    private static final java.util.concurrent.Semaphore INPUT_LEASE = new java.util.concurrent.Semaphore(1, true);
    private boolean ownsInputs;
    private void acquireInputs() throws IOException {
        try {
            if (!INPUT_LEASE.tryAcquire(2, TimeUnit.SECONDS)) throw new IOException("Previous audio inputs are still closing. Try again in a moment.");
            ownsInputs = true;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Audio startup interrupted", e); }
    }
    private void releaseInputs() { if (ownsInputs) { ownsInputs = false; INPUT_LEASE.release(); } }
    private static final long LATENCY_NS = 250_000_000L;
    /** How long the mix waits for a capture that has fallen behind before calling the hole a real gap. */
    private static final long STALL_LIMIT_NS = 2_000_000_000L;
    private final Context context;
    private final MixerSettings settings;
    private final FailureListener listener;
    public final List<MixerSettings.Input> inputs = new ArrayList<>();
    /** Selected devices that were not connected when this session started, or null if all were. */
    public final String missingInputs;
    public final PcmMixer mixer;
    public final File directory;
    private final File audioFile;
    private final List<Capture> captures = new ArrayList<>();
    private final HeadphoneMonitor headphones;
    private final CountDownLatch ready, finished = new CountDownLatch(1);
    private final AtomicBoolean notified = new AtomicBoolean();
    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesListener;
    private volatile boolean running = true;
    private volatile long originNs = -1, stopNs = -1;
    private volatile String failure;
    private volatile boolean audioFinished;
    private final boolean recording;
    private final long syncOffsetNs;
    private final long audioFrameLimit;
    private AacEncoder encoder;
    private AudioFileEncoder audioOnlyEncoder;
    private AudioDestination destination;
    private final float[] scope = new float[960];
    public synchronized void copyWaveform(float[] target) { System.arraycopy(scope, 0, target, 0, Math.min(scope.length, target.length)); }
    private synchronized void updateWaveform(float[] samples, int frames) {
        System.arraycopy(samples, 0, scope, 0, frames * 2);
        java.util.Arrays.fill(scope, frames * 2, scope.length, 0);
    }
    public interface AudioSaveListener { void finished(Uri uri, String error); }
    public void awaitStopped() throws IOException {
        try { if (!finished.await(6, TimeUnit.SECONDS)) throw new IOException("Previous input session is still closing"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
    private WavMaster master;
    private File masterFile;
    private final LiveMuxer live; // a take written straight into its MP4; null: soundcheck, or a separate .m4a

    public JamAudioSession(Context context, boolean recording, FailureListener listener) throws IOException {
        this(context, recording, listener, null);
    }

    /** @param live the take's MP4, which receives the mix as its audio track while recording. */
    public JamAudioSession(Context context, boolean recording, FailureListener listener, LiveMuxer live) throws IOException {
        this(context, recording, listener, live, null);
    }

    public static JamAudioSession audioOnly(Context context, String format, FailureListener listener) throws IOException {
        return new JamAudioSession(context, true, listener, null, format);
    }

    private JamAudioSession(Context context, boolean recording, FailureListener listener, LiveMuxer live, String audioFormat) throws IOException {
        this.context = context.getApplicationContext(); this.recording = recording; this.live = live;
        headphones = new HeadphoneMonitor(this.context); // before applySettings(), which switches it
        this.listener = listener; settings = new MixerSettings(context);
        audioFrameLimit = "wav".equals(audioFormat) ? (0xfffffff0L - 44) / 6 : Long.MAX_VALUE;
        syncOffsetNs = audioFormat == null ? Math.round(settings.preferences.getFloat("sync_ms", 0) * 1_000_000.0) : 0;
        List<MixerSettings.Input> available = MixerSettings.inputs(context);
        Set<String> missing = new HashSet<>(settings.preferences.getStringSet("inputs", java.util.Collections.singleton("phone/0")));
        int channelCount = 0;
        Set<String> deviceKeys = new HashSet<>();
        // Direct USB must claim the interface before AudioRecord opens the phone mic.
        java.util.Collections.sort(available, (left, right) -> Boolean.compare(right.directUsb, left.directUsb));
        for (MixerSettings.Input input : available) {
            if (!settings.selected(input)) continue;
            if (!deviceKeys.add(input.key)) throw new IOException("Identical input devices cannot be distinguished. Connect one at a time.");
            inputs.add(input); channelCount += input.channels;
            for (int c = 0; c < input.channels; c++) missing.remove(input.channelKey(c));
        }
        // A selected device that is not plugged in stays in the settings and is simply skipped, so it comes
        // back by itself when reconnected. Callers report it: a take is never silently short of an input.
        missingInputs = MixerSettings.describe(missing);
        if (inputs.isEmpty()) throw new IOException(missingInputs == null ? "Select at least one input in the mixer."
                : "No selected input is connected: " + missingInputs);
        mixer = new PcmMixer(channelCount, MixerSettings.RATE);
        applySettings();
        ready = new CountDownLatch(inputs.size());
        File parent = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (parent == null) parent = context.getFilesDir();
        directory = recording ? new File(parent, "recovery/" + System.currentTimeMillis()) : null;
        audioFile = recording && live == null ? new File(directory, "mix.m4a") : null;
        preferencesListener = (prefs, key) -> applySettings();
        try {
            acquireInputs();
            if (recording) {
                if (!directory.mkdirs()) throw new IOException("Cannot create recording recovery folder");
                if (audioFormat != null) {
                    destination = new AudioDestination(context, "GearCam_" + directory.getName() + "." + audioFormat, audioFormat);
                    if (destination.availableBytes() < 20_000_000L) throw new IOException("Not enough free space in the save folder");
                    audioOnlyEncoder = new AudioFileEncoder(destination.descriptor, audioFormat);
                } else encoder = live != null ? new AacEncoder(live) : new AacEncoder(audioFile);
                if (audioFormat == null && settings.preferences.getBoolean("wav_master", true)) {
                    masterFile = new File(directory, "master.wav");
                    master = new WavMaster(masterFile);
                }
            }
            for (MixerSettings.Input input : inputs) captures.add(new Capture(input));
            settings.preferences.registerOnSharedPreferenceChangeListener(preferencesListener);
            for (Capture capture : captures) capture.start();
            new Thread(this::process, "GearCam mix").start();
        } catch (IOException | RuntimeException e) {
            running = false;
            for (Capture capture : captures) capture.close();
            if (encoder != null) encoder.close();
            if (master != null) try { master.close(); } catch (IOException closeError) { audioFinished = false; fail("Cannot finish WAV master: " + closeError.getMessage()); }
            settings.preferences.unregisterOnSharedPreferenceChangeListener(preferencesListener);
            if (audioOnlyEncoder != null) try { audioOnlyEncoder.close(); } catch (IOException ignored) { }
            if (destination != null) destination.discard();
            releaseInputs();
            cleanup();
            throw new IOException("Cannot open selected audio inputs: " + e.getMessage(), e);
        }
    }

    private void applySettings() {
        int offset = 0;
        for (MixerSettings.Input input : inputs) {
            for (int c = 0; c < input.channels; c++) settings.apply(input, c, mixer.channels[offset++]);
        }
        mixer.limiterEnabled = settings.preferences.getBoolean("limiter", true);
        headphones.setEnabled(settings.preferences.getBoolean(HeadphoneMonitor.KEY, false));
        for (Capture capture : captures) capture.applySwitches();
    }

    /** Bounded startup check, before video starts. Device choices are not treated as guarantees. */
    public void awaitReady() throws IOException {
        try {
            if (!ready.await(2, TimeUnit.SECONDS)) throw new IOException("Selected inputs did not start. This phone may not support this combination.");
            if (failure != null) throw new IOException(failure);
            for (Capture capture : captures) capture.checkRoute();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("Audio startup interrupted", e);
        }
    }

    /** Audio time zero, on System.nanoTime(): the first recorded video frame. The first call wins. */
    public synchronized void startVideo(long monotonicNs) { if (originNs < 0) originNs = monotonicNs; }

    /** Video bytes that fit with the audio: a live take is written once; otherwise the finished MP4 is a copy. */
    /** @param destinationFree free bytes on the volume the video is written to, or 0 when that is this
     *                          app's own storage. A take saved to a card must be sized against the card. */
    public long maxVideoBytes(int videoBitRate, long destinationFree) throws IOException {
        long available = (destinationFree > 0 ? destinationFree : directory.getUsableSpace()) - 100_000_000L;
        double audioRatio = (512000.0 + (settings.preferences.getBoolean("wav_master", true) ? 2304000.0 : 0)) / Math.max(128000, videoBitRate);
        // Sharing a volume, the audio has to come out of the same space; on a card, the video has it all.
        long limit = (long) (available * 0.9 / ((live != null ? 1 : 2) * (destinationFree > 0 ? 1 : 1 + audioRatio)));
        if (master != null) {
            // The WAV master stages in this app's storage whatever the video's destination is.
            long staging = (long) ((directory.getUsableSpace() - 100_000_000L) * 0.9 / Math.max(1e-6, audioRatio));
            limit = Math.min(limit, staging);
            limit = Math.min(limit, (long) ((0xfffffff0L - 44) / (48000.0 * 6) * videoBitRate / 8));
        }
        if (limit < 20_000_000L) throw new IOException("Not enough free space for recording and finishing the stereo mix");
        return limit;
    }

    public void stopVideo() { if (stopNs < 0) stopNs = System.nanoTime(); }

    public void cancel() {
        running = false;
        for (Capture capture : captures) capture.stop();
    }

    public void cancelAndDiscard() {
        cancel();
        new Thread(() -> {
            try { if (finished.await(5, TimeUnit.SECONDS)) { if (destination != null) destination.discard(); cleanup(); } }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "GearCam discard").start();
    }

    public void saveAsync(Uri uri, String filename, SaveListener callback) {
        stopVideo();
        new Thread(() -> {
            String error = null;
            try {
                try (java.io.FileWriter recovery = new java.io.FileWriter(new File(directory, "source.txt"))) {
                    recovery.write("Original silent video: " + (uri == null ? filename : uri.toString()) + "\nAudio: mix.m4a\n");
                }
                if (!finished.await(6, TimeUnit.SECONDS)) throw new IOException("Audio encoder is still finishing");
                if (!audioFinished) throw new IOException(failure == null ? "Audio was not completed" : failure);
                File completed = new File(directory, "recording.mp4");
                MixedVideoSaver.save(context, uri, filename, audioFile, completed);
                if (masterFile != null) MixedVideoSaver.saveMaster(context, masterFile, directory.getName());
                cleanup();
            } catch (Exception e) {
                error = e.getMessage() + "\nRecovery files: " + directory.getAbsolutePath();
            }
            callback.finished(error);
        }, "GearCam save").start();
    }

    /** A live take: waits for the audio track and the MP4 to close, then saves the WAV master. No copying. */
    public void finishLiveAsync(LiveListener callback) {
        stopVideo();
        new Thread(() -> {
            boolean videoSaved = false;
            String error = null;
            try {
                if (!finished.await(6, TimeUnit.SECONDS)) throw new IOException("Audio encoder is still finishing");
                live.await(10_000);
                videoSaved = true;
                if (!audioFinished) throw new IOException(failure == null ? "Audio was not completed" : failure);
                if (masterFile != null) MixedVideoSaver.saveMaster(context, masterFile, directory.getName());
                cleanup();
            } catch (Exception e) {
                // Only send the user to the recovery folder when the video really did not survive.
                error = e.getMessage() + (videoSaved ? "" : "\nRecovery files: " + directory.getAbsolutePath());
            }
            callback.finished(videoSaved, error);
        }, "GearCam finish").start();
    }

    /** Audio-only encoders stream directly into the selected destination. */
    public void finishAudioAsync(AudioSaveListener callback) {
        stopVideo();
        new Thread(() -> {
            String error = null; Uri saved = null;
            try {
                awaitStopped();
                if (!audioFinished) throw new IOException(failure == null ? "Audio could not be finalized" : failure);
                destination.publish(); saved = destination.uri;
                error = failure; cleanup();
            } catch (Exception e) {
                error = e.getMessage() + "\nAudio destination: " + destination.uri;
                try { destination.close(); } catch (IOException ignored) { }
            }
            callback.finished(saved, error);
        }, "GearCam finish audio").start();
    }

    private void cleanup() {
        if (directory == null) return;
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) file.delete();
        directory.delete();
    }

    private void fail(String message) {
        if (notified.compareAndSet(false, true)) {
            android.util.Log.e("GearCam", "Audio session failed: " + message);
            failure = message;
            stopVideo();
            listener.failed(message);
        }
    }

    private void process() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        float[][] input = new float[mixer.channels.length][BLOCK];
        float[] output = new float[BLOCK * 2];
        long frame = 0;
        long monitorNs = System.nanoTime();
        int[] missingFrames = new int[captures.size()];
        long lastReportNs = 0, stalledNs = 0;
        boolean gapMode = false;
        boolean writing = false;
        try {
            while (running) {
                if (originNs >= 0 && !writing) { writing = true; frame = 0; }
                if (recording && !writing) {
                    if (failure != null || stopNs >= 0) break; // stopped before the first video frame arrived
                    Thread.sleep(10);
                    continue;
                }
                long time = writing ? originNs + frame * 1_000_000_000L / MixerSettings.RATE : monitorNs;
                if (writing && stopNs >= 0 && time >= stopNs) break;
                if (writing && frame >= audioFrameLimit) { fail("WAV reached its 4 GB limit. Recording stopped."); break; }
                if (!writing && failure != null) break;
                long waitNs = time + LATENCY_NS + (writing ? Math.max(0, -syncOffsetNs) : 0) + BLOCK * 1_000_000_000L / MixerSettings.RATE - System.nanoTime();
                if (waitNs > 0) {
                    Thread.sleep(Math.min(10, Math.max(1, waitNs / 1_000_000)));
                    continue;
                }
                int count = writing && stopNs >= 0 ? (int) Math.min(BLOCK, Math.max(0, (stopNs - time) * MixerSettings.RATE / 1_000_000_000)) : BLOCK;
                if (writing) count = (int) Math.min(count, audioFrameLimit - frame);
                if (count == 0) break;
                // A capture that has fallen behind is waited for, not read past: a hiccup then costs
                // latency rather than the take. Only a real silence gets treated as a gap.
                long needNs = (writing ? time - syncOffsetNs : time) + (long) count * 1_000_000_000L / MixerSettings.RATE;
                boolean behind = false;
                for (Capture capture : captures) {
                    long newest = capture.buffer.newestNs();
                    if (newest != 0 && newest < needNs) behind = true;
                }
                if (behind) {
                    if (stalledNs == 0) stalledNs = System.nanoTime();
                    if (!gapMode && System.nanoTime() - stalledNs < STALL_LIMIT_NS) { Thread.sleep(2); continue; }
                    gapMode = true; // waited long enough: the hole is real, so keep the timeline moving
                }
                else if (stalledNs != 0) {
                    android.util.Log.w("GearCamAudio", "capture caught up after " + (System.nanoTime() - stalledNs) / 1_000_000 + " ms");
                    stalledNs = 0;
                    gapMode = false;
                }
                int offset = 0;
                for (int i = 0; i < captures.size(); i++) {
                    Capture capture = captures.get(i);
                    int missing = capture.buffer.read(writing ? time - syncOffsetNs : time, MixerSettings.RATE, input, offset, count);
                    offset += capture.input.channels;
                    missingFrames[i] = missing == 0 ? 0 : missingFrames[i] + missing;
                    if (writing && frame < MixerSettings.RATE) missingFrames[i] = 0; // Initial capture and user-selected sync padding.
                    if (missing > 0 && missingFrames[i] == missing)
                        android.util.Log.w("GearCamAudio", "gap starts · " + capture.diagnostics(writing ? time - syncOffsetNs : time));
                    if (missingFrames[i] > MixerSettings.RATE * 5 && ready.getCount() == 0)
                        fail(capture.input.label + ": audio stopped arriving after " + (writing ? frame / MixerSettings.RATE : 0)
                                + " s. Recording stopped." + capture.clockNote());
                }
                if (System.nanoTime() - lastReportNs > 2_000_000_000L) {
                    lastReportNs = System.nanoTime();
                    for (Capture capture : captures) {
                        android.util.Log.i("GearCamAudio", (writing ? "recording · " : "monitor · ") + capture.diagnostics(writing ? time - syncOffsetNs : time));
                        capture.resetTiming();
                    }
                }
                mixer.mix(input, output, count);
                updateWaveform(output, count);
                headphones.write(output, count);
                if (writing) {
                    if (encoder != null) encoder.write(output, count);
                    if (audioOnlyEncoder != null) {
                        audioOnlyEncoder.write(output, count);
                        if (frame % MixerSettings.RATE == 0 && destination.availableBytes() < 10_000_000L)
                            fail("Save location is almost full. Recording stopped.");
                    }
                    if (master != null) master.write(output, count); frame += count;
                }
                else monitorNs += count * 1_000_000_000L / MixerSettings.RATE;
            }
            if (writing && running) {
                if (encoder != null) encoder.finish();
                audioFinished = true;
            }
        } catch (Exception e) {
            fail("Audio processing failed: " + e.getMessage());
        } finally {
            running = false;
            headphones.close();
            for (Capture capture : captures) capture.stop();
            for (Capture capture : captures) capture.close();
            if (encoder != null) encoder.close();
            if (master != null) try { master.close(); } catch (IOException closeError) { audioFinished = false; fail("Cannot finish WAV master: " + closeError.getMessage()); }
            settings.preferences.unregisterOnSharedPreferenceChangeListener(preferencesListener);
            if (audioOnlyEncoder != null) try { audioOnlyEncoder.close(); }
            catch (IOException e) { audioFinished = false; fail("Cannot finish audio: " + e.getMessage()); }
            releaseInputs();
            finished.countDown();
        }
    }

    private final class Capture implements Runnable {
        final MixerSettings.Input input;
        final TimedPcmBuffer buffer;
        final AudioRecord recorder;
        final DirectUsbCapture directUsb;
        final NoiseGate gate; // phone mic only
        final UsbWhineFilter[] whine; // one per channel, in the gate's place on every other input
        /** How far the device's sample clock runs from 48 kHz, and how many estimates were unusable. */
        volatile double clockPercent;
        volatile int clockRejects;
        /** Capture-side counters, read by the diagnostic line below. */
        volatile long framesAppended, deviceFrames, overflowFrames;
        volatile double periodNs;
        /** Where the capture loop spends its time, reset at every report. */
        volatile long readMaxNs, appendMaxNs, otherMaxNs, filterMaxNs, loopNs, calls, framesPerCall;
        final Thread thread;
        private boolean started;

        Capture(MixerSettings.Input input) throws IOException {
            this.input = input;
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw new IOException("Microphone permission is required");
            buffer = new TimedPcmBuffer(input.channels, MixerSettings.RATE * 2);
            gate = input.phone ? new NoiseGate(MixerSettings.RATE) : null;
            whine = input.phone ? null : new UsbWhineFilter[input.channels];
            if (whine != null) for (int c = 0; c < whine.length; c++) whine[c] = new UsbWhineFilter();
            applySwitches();
            if (input.directUsb) {
                recorder = null;
                directUsb = new DirectUsbCapture(context, input.usbDevice, input.channels);
                if (directUsb.channels != input.channels) {
                    directUsb.close();
                    throw new IOException(input.label + ": channel count changed; reopen the mixer");
                }
                thread = new Thread(this, "GearCam direct USB");
                return;
            }
            directUsb = null;
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int source = Build.VERSION.SDK_INT >= 24 && "true".equals(manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)) ?
                    MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.VOICE_RECOGNITION;
            AudioFormat.Builder format = new AudioFormat.Builder().setSampleRate(MixerSettings.RATE).setEncoding(AudioFormat.ENCODING_PCM_FLOAT);
            if (input.phone) format.setChannelMask(AudioFormat.CHANNEL_IN_MONO);
            else format.setChannelIndexMask((1 << input.channels) - 1);
            AudioRecord.Builder builder = new AudioRecord.Builder().setAudioSource(source).setAudioFormat(format.build())
                    .setBufferSizeInBytes(MixerSettings.RATE / 5 * input.channels * 4);
            if (Build.VERSION.SDK_INT >= 30) builder.setPrivacySensitive(false);
            recorder = builder.build();
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED || !recorder.setPreferredDevice(input.device)) {
                recorder.release(); throw new IOException(input.label + ": routing request was rejected");
            }
            thread = new Thread(this, "GearCam input " + input.device.getId());
        }

        void start() {
            if (recorder != null) recorder.startRecording();
            started = true;
            thread.start();
        }

        void checkRoute() throws IOException {
            if (directUsb != null) return;
            AudioDeviceInfo actual = recorder.getRoutedDevice();
            if (actual == null || actual.getId() != input.device.getId())
                throw new IOException(input.label + ": Android did not route the requested input. Try this input alone.");
            if (Build.VERSION.SDK_INT >= 29) {
                AudioRecordingConfiguration config = recorder.getActiveRecordingConfiguration();
                if (config != null && config.isClientSilenced())
                    throw new IOException(input.label + ": Android silenced this input. Try this input alone.");
            }
        }

        @Override public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            int maxFrames = directUsb != null ? BLOCK * 10 : BLOCK; // room to drain a backlog in one read
            float[] samples = new float[maxFrames * input.channels];
            AudioTimestamp timestamp = new AudioTimestamp();
            long frames = 0, anchorFrame = 0, anchorNs = 0;
            double period = 1_000_000_000.0 / MixerSettings.RATE;
            long fallbackNs = 0;
            long[] usbTiming = new long[4];
            long usbOverflow = 0;
            boolean clockLocked = false;
            long usbFirstNs = 0;
            boolean announced = false;
            try {
                while (running) {
                    int read;
                    long readStartNs = System.nanoTime();
                    if (directUsb != null) {
                        int usbFrames = directUsb.read(samples, maxFrames, usbTiming);
                        read = usbFrames < 0 ? usbFrames : usbFrames * input.channels;
                    } else {
                        read = recorder.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                    }
                    if (!running) break;
                    if (read < 0) throw new IOException((directUsb == null ? "AudioRecord" : "USB capture") + " stopped (" + read + ")");
                    if (read == 0) continue;
                    if (read % input.channels != 0) throw new IOException("Incomplete multichannel audio frame");
                    long readEndNs = System.nanoTime();
                    checkRoute();
                    int count = read / input.channels;
                    long now = System.nanoTime();
                    if (fallbackNs == 0) fallbackNs = now - Math.round(count * period);
                    long first = fallbackNs + Math.round(frames * period);
                    if (directUsb != null) {
                        if (usbTiming[3] > usbOverflow) throw new IOException("USB capture buffer overflowed");
                        usbOverflow = usbTiming[3];
                        if (anchorNs != 0 && usbTiming[2] - anchorNs >= 500_000_000L && usbTiming[1] > anchorFrame) {
                            double measured = (double) (usbTiming[2] - anchorNs) / (usbTiming[1] - anchorFrame);
                            double nominal = 1_000_000_000.0 / MixerSettings.RATE;
                            if (measured > nominal * 0.88 && measured < nominal * 1.12) {
                                // Lock onto the first estimate, then smooth: easing in from the nominal rate
                                // would spend seconds slipping if the device is far off.
                                period = clockLocked ? period + 0.1 * (measured - period) : measured;
                                clockLocked = true;
                                clockPercent = (period / nominal - 1) * 100;
                            }
                            else clockRejects++;
                            anchorNs = usbTiming[2]; anchorFrame = usbTiming[1];
                        } else if (anchorNs == 0) { anchorNs = usbTiming[2]; anchorFrame = usbTiming[1]; }
                        // Arrival jitter must not reposition each block and discard overlapping frames.
                        if (usbFirstNs == 0) usbFirstNs = usbTiming[2] - Math.round(usbTiming[1] * period);
                        first = fallbackNs;
                        if (frames == 0) first = usbFirstNs + Math.round(usbTiming[0] * period);
                        fallbackNs = first + Math.round(count * period);
                    } else if (Build.VERSION.SDK_INT >= 24 && recorder.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        if (anchorNs != 0 && timestamp.nanoTime - anchorNs >= 500_000_000L && timestamp.framePosition > anchorFrame) {
                            double measured = (double) (timestamp.nanoTime - anchorNs) / (timestamp.framePosition - anchorFrame);
                            double nominal = 1_000_000_000.0 / MixerSettings.RATE;
                            if (measured > nominal * 0.995 && measured < nominal * 1.005) period = measured;
                            anchorNs = timestamp.nanoTime; anchorFrame = timestamp.framePosition;
                        } else if (anchorNs == 0) { anchorNs = timestamp.nanoTime; anchorFrame = timestamp.framePosition; }
                        first = timestamp.nanoTime + Math.round((frames - timestamp.framePosition) * period);
                    }
                    framesAppended = frames + count; periodNs = period;
                    if (readEndNs - readStartNs > readMaxNs) readMaxNs = readEndNs - readStartNs;
                    long otherNs = System.nanoTime() - readEndNs;
                    if (otherNs > otherMaxNs) otherMaxNs = otherNs;
                    if (directUsb != null) { deviceFrames = usbTiming[1]; overflowFrames = usbTiming[3]; }
                    long filterStartNs = System.nanoTime();
                    if (gate != null) gate.process(samples, count);
                    if (whine != null) for (int c = 0; c < whine.length; c++) whine[c].process(samples, count, c, input.channels);
                    long filterNs = System.nanoTime() - filterStartNs;
                    if (filterNs > filterMaxNs) filterMaxNs = filterNs;
                    long appendStartNs = System.nanoTime();
                    buffer.append(samples, count, first, period); frames += count;
                    long appendNs = System.nanoTime() - appendStartNs;
                    if (appendNs > appendMaxNs) appendMaxNs = appendNs;
                    loopNs += System.nanoTime() - readStartNs; calls++; framesPerCall += count;
                    if (!announced) { announced = true; ready.countDown(); }
                }
            } catch (Exception e) {
                if (running) fail(input.label + ": " + e.getMessage());
            } finally { if (!announced) ready.countDown(); }
        }

        /** Everything worth knowing when audio stops arriving: is capture starving, drifting or dropping? */
        String diagnostics(long readNs) {
            return String.format(java.util.Locale.ROOT,
                    "%s: appended=%d behind=%d buffered=%d lead=%+.0fms period=%.2fns (%+.2f%%) rejects=%d overflow=%d",
                    input.label, framesAppended, deviceFrames - framesAppended, buffer.size(),
                    buffer.newestNs() == 0 ? 0 : (buffer.newestNs() - readNs) / 1e6,
                    periodNs, clockPercent, clockRejects, overflowFrames)
                    + String.format(java.util.Locale.ROOT, " | calls=%d frames/call=%d busy=%.0f%% read<=%.0fms filter<=%.0fms append<=%.0fms other<=%.0fms",
                    calls, calls == 0 ? 0 : framesPerCall / calls, loopNs / 2e7, readMaxNs / 1e6, filterMaxNs / 1e6, appendMaxNs / 1e6, otherMaxNs / 1e6);
        }

        /** Start a fresh window after every report, so a stall shows where it happened. */
        void resetTiming() { readMaxNs = appendMaxNs = otherMaxNs = filterMaxNs = loopNs = calls = framesPerCall = 0; }

        /** What the device's clock was doing, for the error the user actually sees. */
        String clockNote() {
            if (directUsb == null) return "";
            return String.format(java.util.Locale.ROOT, " (USB clock %+.2f%%%s)", clockPercent,
                    clockRejects > 0 ? ", " + clockRejects + " estimates unusable" : "");
        }

        /** The strip switches are live: read them at every settings change, as the faders are. */
        void applySwitches() {
            if (gate != null) gate.enabled = settings.preferences.getBoolean(MixerSettings.GATE, false);
            if (whine != null) for (int c = 0; c < whine.length; c++)
                whine[c].enabled = settings.preferences.getBoolean(settings.controlKey(input, c) + MixerSettings.WHINE, false);
        }

        void stop() {
            if (!started) return;
            if (directUsb != null) directUsb.stop();
            else try { recorder.stop(); } catch (IllegalStateException ignored) { }
        }

        void close() {
            stop();
            try { if (thread.isAlive()) thread.join(1000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (directUsb != null) directUsb.close();
            else recorder.release();
        }
    }
}
