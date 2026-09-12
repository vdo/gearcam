package net.sourceforge.opencamera;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real Camera/AudioRecord/AAC/MediaStore integration, also runnable with emulator silence. */
@RunWith(AndroidJUnit4.class)
public class GearCamRecordingTest {
    @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);

    @Test public void mixerFaderStaysVisibleAcrossRotation() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("gearcam_mixer", Context.MODE_PRIVATE).edit().clear().commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class).putExtra("test_project", true))) {
            await(scenario, a -> a.getPreview().isPreviewStarted(), 15000);
            scenario.onActivity(MainActivity::showAudioMixer);
            for (int orientation : new int[] {android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}) {
                scenario.onActivity(a -> a.setRequestedOrientation(orientation));
                Thread.sleep(700);
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("PHONE MIC gain"))
                        .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed()));
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Soundcheck"))
                        .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed()));
            }
            scenario.onActivity(MainActivity::dismissAudioMixer);
        }
    }

    @Test public void disconnectedSelectionPreventsSilentFallbackAndPhotoSettingsAreAbsent() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("gearcam_mixer", Context.MODE_PRIVATE).edit().clear()
                .putStringSet("inputs", Collections.singleton("disconnected-usb/0")).commit();
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(PreferenceKeys.RecordAudioPreferenceKey, true).commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class).putExtra("test_project", true))) {
            await(scenario, a -> a.getPreview().isPreviewStarted(), 15000);
            scenario.onActivity(a -> {
                a.getPreview().takePicturePressed(false, false);
                assertFalse(a.getPreview().isVideoRecording());
                assertNull(a.getPreview().getJamAudioSession());
                a.openSettings();
            });
            await(scenario, a -> a.getFragmentManager().findFragmentByTag("PREFERENCE_FRAGMENT") != null, 5000);
            scenario.onActivity(a -> {
                android.preference.PreferenceFragment settings = (android.preference.PreferenceFragment) a.getFragmentManager().findFragmentByTag("PREFERENCE_FRAGMENT");
                assertNull(settings.findPreference("preference_screen_photo_settings"));
                assertNull(settings.findPreference("preference_screen_processing_settings"));
                assertNull(settings.findPreference("preference_burst_mode"));
                assertNotNull(settings.findPreference("gearcam_audio_mixer"));
                assertNotNull(settings.findPreference("preference_screen_video_settings"));
            });
        } finally {
            context.getSharedPreferences("gearcam_mixer", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    @Test public void videoOnlyRecordingContainsVideoAndStereoAac() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("gearcam_mixer", Context.MODE_PRIVATE).edit().clear()
                .putStringSet("inputs", Collections.singleton("phone/0")).commit();
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(PreferenceKeys.RecordAudioPreferenceKey, true).commit();
        Intent intent = new Intent(context, MainActivity.class).putExtra("test_project", true);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
            await(scenario, a -> a.getPreview().isPreviewStarted(), 15000);
            scenario.onActivity(a -> {
                assertTrue(a.getPreview().isVideo());
                a.getPreview().switchVideo(false, true);
                assertTrue(a.getPreview().isVideo());
                a.getPreview().takePicturePressed(true, false);
                assertFalse(a.getPreview().isVideoRecording());
                assertEquals(View.GONE, a.findViewById(R.id.take_photo_when_video_recording).getVisibility());
                a.getPreview().takePicturePressed(false, false);
            });
            await(scenario, a -> a.getPreview().isVideoRecording(), 5000);
            Thread.sleep(2200); // Produce enough media for duration, AAC and finalization checks.
            scenario.onActivity(a -> {
                assertNotNull(a.getPreview().getJamAudioSession());
                assertTrue("Should record straight into the final MP4", a.getPreview().isLiveMixedRecording());
                a.getPreview().stopVideo(false);
            });
            await(scenario, a -> !a.getPreview().isFinishingMix(), 15000);
            AtomicReference<Uri> result = new AtomicReference<>();
            scenario.onActivity(a -> result.set(a.getApplicationInterface().getStorageUtils().getLastMediaScanned()));
            assertNotNull("A completed recording should be published", result.get());
            // The lossless master is published independently of the lossy MP4 audio track.
            try (android.database.Cursor cursor = context.getContentResolver().query(
                    android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[] {"_id", "_display_name"}, "_display_name LIKE ?",
                    new String[] {"GearCam_%_master.wav"}, "date_added DESC")) {
                assertNotNull(cursor); assertTrue("WAV master should be published", cursor.moveToFirst());
                Uri wav = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0));
                try (java.io.InputStream stream = context.getContentResolver().openInputStream(wav)) {
                    byte[] header = new byte[44]; new java.io.DataInputStream(stream).readFully(header);
                    java.nio.ByteBuffer fields = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    assertEquals(48000, fields.getInt(24)); assertEquals(24, fields.getShort(34));
                    assertTrue(fields.getInt(40) > 48000 * 6);
                }
                context.getContentResolver().delete(wav, null, null);
            }
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(context, result.get(), null);
                boolean video = false, audio = false;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime.startsWith("video/")) video = true;
                    if (mime.equals(MediaFormat.MIMETYPE_AUDIO_AAC)) {
                        audio = true;
                        assertEquals(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                        assertEquals(48000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                        assertTrue(format.getLong(MediaFormat.KEY_DURATION) > 1500000);
                    }
                    extractor.selectTrack(i);
                    long previous = -1;
                    do {
                        long timestamp = extractor.getSampleTime();
                        assertTrue("Track timestamps must be monotonic", timestamp >= previous);
                        previous = timestamp;
                    } while (extractor.advance());
                    extractor.unselectTrack(i); extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                }
                assertTrue("Missing video track", video); assertTrue("Missing mixed audio track", audio);
            } finally {
                extractor.release(); context.getContentResolver().delete(result.get(), null, null);
            }
        }
    }

    private interface Condition { boolean test(MainActivity activity); }
    private void await(ActivityScenario<MainActivity> scenario, Condition condition, long timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout;
        AtomicBoolean success = new AtomicBoolean();
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity(a -> success.set(condition.test(a)));
            if (success.get()) return;
            Thread.sleep(100);
        }
        fail("Timed out waiting for camera/recording state");
    }
}
