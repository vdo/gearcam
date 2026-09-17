package net.sourceforge.opencamera.audio;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AudioOnlyRecordingTest {
    @Rule public GrantPermissionRule microphone = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO);

    @Test public void encodersProducePlayableStereoFiles() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        for (String format : new String[] {"wav", "mp3", "flac"}) {
            File file = new File(context.getExternalFilesDir(null), "encoder-test." + format);
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_READ_WRITE);
                 AudioFileEncoder encoder = new AudioFileEncoder(fd, format)) {
                float[] block = new float[960];
                for (int b = 0; b < 200; b++) {
                    for (int i = 0; i < 480; i++) {
                        block[i * 2] = (float) (.5 * Math.sin(2 * Math.PI * 440 * (b * 480 + i) / 48000));
                        block[i * 2 + 1] = (float) (.25 * Math.sin(2 * Math.PI * 880 * (b * 480 + i) / 48000));
                    }
                    encoder.write(block, 480);
                }
            }
            assertAudio(context, Uri.fromFile(file), format);
            // Keep deterministic fixtures in external app files for independent decoder verification.
        }
    }

    @Test public void recordsEveryFormatAndPublishesWithoutCamera() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("gearcam_mixer", Context.MODE_PRIVATE).edit()
                .putStringSet("inputs", Collections.singleton("phone/0")).commit();
        RecordingPreferences.prefs(context).edit().putString(RecordingPreferences.MODE, "audio")
                .putBoolean(net.sourceforge.opencamera.PreferenceKeys.UsingSAFPreferenceKey, false).commit();
        try {
            for (String format : new String[] {"wav", "mp3", "flac"}) {
                RecordingPreferences.prefs(context).edit().putString(RecordingPreferences.FORMAT, format).commit();
                try (ActivityScenario<AudioRecorderActivity> scenario = ActivityScenario.launch(new Intent(context, AudioRecorderActivity.class))) {
                    await(scenario, a -> !a.isBusy() && a.getSession() != null);
                    scenario.onActivity(AudioRecorderActivity::toggleRecording);
                    await(scenario, AudioRecorderActivity::isRecording);
                    Thread.sleep(1300);
                    scenario.onActivity(AudioRecorderActivity::toggleRecording);
                    await(scenario, a -> !a.isBusy() && a.getLastSaved() != null);
                    AtomicReference<Uri> result = new AtomicReference<>();
                    scenario.onActivity(a -> result.set(a.getLastSaved()));
                    assertAudio(context, result.get(), format);
                    context.getContentResolver().delete(result.get(), null, null);
                }
            }
        } finally { RecordingPreferences.prefs(context).edit().putString(RecordingPreferences.MODE, "video").commit(); }
    }

    @Test public void backgroundStopsAndSavesTakeThenInputsCanReopen() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("gearcam_mixer", Context.MODE_PRIVATE).edit().putStringSet("inputs", Collections.singleton("phone/0")).commit();
        RecordingPreferences.prefs(context).edit().putString(RecordingPreferences.FORMAT, "wav").commit();
        try (ActivityScenario<AudioRecorderActivity> scenario = ActivityScenario.launch(new Intent(context, AudioRecorderActivity.class))) {
            await(scenario, a -> !a.isBusy() && a.getSession() != null);
            scenario.onActivity(AudioRecorderActivity::toggleRecording); await(scenario, AudioRecorderActivity::isRecording);
            Thread.sleep(1100);
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            Thread.sleep(1000);
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            await(scenario, a -> !a.isBusy() && a.getLastSaved() != null && a.getSession() != null);
            scenario.onActivity(a -> { assertFalse(a.isRecording()); context.getContentResolver().delete(a.getLastSaved(), null, null); });
        }
    }

    @Test public void waveformAndControlsStayVisibleAcrossRotation() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("gearcam_mixer", Context.MODE_PRIVATE).edit().putStringSet("inputs", Collections.singleton("phone/0")).commit();
        try (ActivityScenario<AudioRecorderActivity> scenario = ActivityScenario.launch(new Intent(context, AudioRecorderActivity.class))) {
            await(scenario, a -> !a.isBusy() && a.getSession() != null);
            for (int orientation : new int[] {android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}) {
                scenario.onActivity(a -> a.setRequestedOrientation(orientation));
                Thread.sleep(700);
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("Start audio recording"))
                        .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed()));
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("Live stereo waveform of the selected audio inputs"))
                        .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed()));
                android.graphics.Bitmap screenshot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(context.getExternalFilesDir(null), "audio-ui-" + orientation + ".png"))) {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                }
            }
        }
    }

    /** Run after selecting a test folder in Android's real picker, including on removable storage. */
    @Test public void chosenFolderReceivesAllFormatsAndMaster() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        org.junit.Assume.assumeTrue(RecordingPreferences.prefs(context).getBoolean(net.sourceforge.opencamera.PreferenceKeys.UsingSAFPreferenceKey, false));
        String tree = RecordingPreferences.prefs(context).getString(net.sourceforge.opencamera.PreferenceKeys.SaveLocationSAFPreferenceKey, "");
        assertTrue(context.getContentResolver().getPersistedUriPermissions().stream().anyMatch(p -> p.getUri().toString().equals(tree) && p.isWritePermission()));
        for (String format : new String[] {"wav", "mp3", "flac"}) {
            AudioDestination target = new AudioDestination(context, "folder-test." + format, format);
            try {
                try (AudioFileEncoder encoder = new AudioFileEncoder(target.descriptor, format)) {
                    for (int i = 0; i < 110; i++) encoder.write(new float[960], 480);
                }
                target.publish();
                assertEquals(Uri.parse(tree).getAuthority(), target.uri.getAuthority());
                assertAudio(context, target.uri, format);
            } finally { target.discard(); }
        }
        File source = new File(context.getCacheDir(), "master-test.wav");
        try (WavMaster master = new WavMaster(source)) { for (int i = 0; i < 110; i++) master.write(new float[960], 480); }
        MixedVideoSaver.saveMaster(context, source, "folder-test"); source.delete();
        Uri root = Uri.parse(tree);
        Uri children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(root, android.provider.DocumentsContract.getTreeDocumentId(root));
        boolean found = false;
        try (android.database.Cursor cursor = context.getContentResolver().query(children, new String[] {"document_id", "_display_name"}, null, null, null)) {
            assertNotNull(cursor);
            while (cursor.moveToNext()) if ("GearCam_folder-test_master.wav".equals(cursor.getString(1))) {
                Uri master = android.provider.DocumentsContract.buildDocumentUriUsingTree(root, cursor.getString(0));
                assertAudio(context, master, "wav"); android.provider.DocumentsContract.deleteDocument(context.getContentResolver(), master); found = true;
            }
        }
        assertTrue("WAV master should use the same selected folder", found);
    }

    @Test public void unavailableFolderFailsWithoutChangingDestination() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        android.content.SharedPreferences prefs = RecordingPreferences.prefs(context);
        boolean oldEnabled = prefs.getBoolean(net.sourceforge.opencamera.PreferenceKeys.UsingSAFPreferenceKey, false);
        String oldTree = prefs.getString(net.sourceforge.opencamera.PreferenceKeys.SaveLocationSAFPreferenceKey, "");
        String missing = "content://app.gearcam.nonexistent/tree/missing";
        prefs.edit().putBoolean(net.sourceforge.opencamera.PreferenceKeys.UsingSAFPreferenceKey, true)
                .putString(net.sourceforge.opencamera.PreferenceKeys.SaveLocationSAFPreferenceKey, missing).commit();
        try {
            try { new AudioDestination(context, "must-not-exist.wav", "wav"); fail("Unavailable folder must fail"); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("save location")); }
            assertTrue(prefs.getBoolean(net.sourceforge.opencamera.PreferenceKeys.UsingSAFPreferenceKey, false));
            assertEquals(missing, prefs.getString(net.sourceforge.opencamera.PreferenceKeys.SaveLocationSAFPreferenceKey, ""));
        } finally { prefs.edit().putBoolean(net.sourceforge.opencamera.PreferenceKeys.UsingSAFPreferenceKey, oldEnabled)
                .putString(net.sourceforge.opencamera.PreferenceKeys.SaveLocationSAFPreferenceKey, oldTree).commit(); }
    }

    private void assertAudio(Context context, Uri uri, String extension) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            if ("flac".equals(extension)) try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
                byte[] magic = new byte[4]; new java.io.DataInputStream(in).readFully(magic);
                assertArrayEquals(new byte[] {'f', 'L', 'a', 'C'}, magic);
            }
            extractor.setDataSource(context, uri, null);
            assertEquals(1, extractor.getTrackCount());
            MediaFormat format = extractor.getTrackFormat(0);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if ("flac".equals(extension)) assertTrue("audio/flac".equals(mime) || "audio/raw".equals(mime));
            else assertEquals("mp3".equals(extension) ? "audio/mpeg" : "audio/raw", mime);
            assertEquals(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            assertEquals(48000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
            assertTrue(format.getLong(MediaFormat.KEY_DURATION) >= 1_000_000L);
            extractor.selectTrack(0); assertTrue(extractor.getSampleTime() >= 0);
        } finally { extractor.release(); }
    }
    private interface Condition { boolean test(AudioRecorderActivity activity); }
    private void await(ActivityScenario<AudioRecorderActivity> scenario, Condition condition) throws Exception {
        AtomicBoolean ok = new AtomicBoolean();
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity(a -> ok.set(condition.test(a))); if (ok.get()) return; Thread.sleep(100);
        }
        fail("Timed out waiting for audio recorder");
    }
}
