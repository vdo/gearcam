package net.sourceforge.opencamera;

import net.sourceforge.opencamera.video.CreativeFilters;
import net.sourceforge.opencamera.video.FilterSurface;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;
import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.PreferenceKeys;
import net.sourceforge.opencamera.R;
import net.sourceforge.opencamera.audio.LiveMuxer;
import net.sourceforge.opencamera.audio.LiveVideoEncoder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CreativeFiltersTest {
    @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);

    @Test public void originalIsDefaultAndEveryLookIsEncodedIntoVideo() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(CreativeFilters.KEY).commit();
        assertEquals("New installs must not apply a filter", 0, CreativeFilters.selected(context));
        int[][] patches = new int[CreativeFilters.NAMES.length][4];
        CreativeFilters.setAccentHue(context, 120); // green, matching the second test patch
        try {
            for (int look = 0; look < CreativeFilters.NAMES.length; look++) {
                CreativeFilters.select(context, look);
                File file = new File(context.getCacheDir(), "creative-test-" + look + ".mp4");
                AtomicReference<String> error = new AtomicReference<>();
                LiveMuxer muxer = new LiveMuxer(file.getAbsolutePath(), 1);
                LiveVideoEncoder encoder = new LiveVideoEncoder(MediaRecorder.VideoEncoder.H264, 320, 240, 30, 2000000,
                        muxer, 0, 0, new LiveVideoEncoder.Listener() {
                    public void firstFrame(long timestamp) { assertTrue(timestamp > 0); }
                    public void limitReached(int what) { error.set("Unexpected recording limit"); }
                    public void failed(String message) { error.set(message); }
                });
                FilterSurface filter = null;
                try {
                    filter = new FilterSurface(context, encoder.surface, 0, 0, true, () -> error.set("Filter failed"));
                    Paint paint = new Paint();
                    for (int frame = 0; frame < 24; frame++) {
                        Canvas canvas = filter.input().lockCanvas(null);
                        int[] colors = {Color.rgb(200,40,30), Color.rgb(40,190,50), Color.rgb(30,60,210), Color.rgb(100,100,100)};
                        for (int i = 0; i < colors.length; i++) {
                            paint.setColor(colors[i]); canvas.drawRect(i * 80, 0, (i + 1) * 80, 240, paint);
                        }
                        filter.input().unlockCanvasAndPost(canvas); Thread.sleep(35);
                    }
                    Thread.sleep(100); filter.close(); filter = null; encoder.finish(); muxer.await(5000);
                    assertNull(error.get());
                    try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                        retriever.setDataSource(file.getAbsolutePath());
                        Bitmap bitmap = retriever.getFrameAtTime(300000, MediaMetadataRetriever.OPTION_CLOSEST);
                        assertNotNull("Saved video frame for " + CreativeFilters.NAMES[look], bitmap);
                        for (int i = 0; i < 4; i++) patches[look][i] = bitmap.getPixel(40 + 80 * i, 120);
                        if (look == 1 || look == 2) for (int color : patches[look]) {
                            assertTrue("Saved B&W must have equal color channels", Math.abs(Color.red(color) - Color.green(color)) <= 5);
                            assertTrue(Math.abs(Color.green(color) - Color.blue(color)) <= 5);
                        }
                        if (look == 2) { // Noir grain must move: two frames of a static scene cannot match
                            Bitmap later = retriever.getFrameAtTime(700000, MediaMetadataRetriever.OPTION_CLOSEST);
                            assertNotNull("Second noir frame", later);
                            double sum = 0;
                            int count = 0;
                            for (int x = 250; x < 310; x += 2) for (int y = 60; y < 180; y += 2) {
                                sum += Math.abs(Color.red(bitmap.getPixel(x, y)) - Color.red(later.getPixel(x, y)));
                                count++;
                            }
                            double moved = sum / count;
                            later.recycle();
                            assertTrue("Noir grain should differ frame to frame, saw " + moved, moved > 0.4);
                            assertTrue("Noir grain should stay subtle, saw " + moved, moved < 14);
                        }
                        bitmap.recycle();
                    }
                } finally { if (filter != null) filter.close(); encoder.release(); file.delete(); }
            }
            assertTrue("Original preserves red", Color.red(patches[0][0]) > Color.green(patches[0][0]) + 120);
            assertTrue("Noir gives deeper shadows than B&W", Color.red(patches[2][0]) < Color.red(patches[1][0]) - 8);
            assertTrue("Warm film adds warm balance", Color.red(patches[3][3]) > Color.blue(patches[3][3]) + 10);
            assertTrue("Cool chrome adds blue balance", Color.blue(patches[4][3]) > Color.red(patches[4][3]) + 15);
            assertTrue("Sepia has amber tones", Color.red(patches[5][3]) > Color.blue(patches[5][3]) + 20);
            assertTrue("Fade softens saturation", Color.red(patches[6][0]) - Color.green(patches[6][0]) < Color.red(patches[0][0]) - Color.green(patches[0][0]) - 30);
            int green = patches[CreativeFilters.ACCENT][1], red = patches[CreativeFilters.ACCENT][0], grey = patches[CreativeFilters.ACCENT][3];
            assertTrue("Color Accent keeps the chosen hue", Color.green(green) > Color.red(green) + 60);
            assertTrue("Color Accent drops every other hue", Math.abs(Color.red(red) - Color.blue(red)) <= 8);
            assertTrue("Color Accent leaves neutrals neutral", Math.abs(Color.red(grey) - Color.blue(grey)) <= 8);
        } finally { CreativeFilters.select(context, 0); }
    }

    @Test public void cameraPreviewAndSavedTakeBothUseBlackAndWhite() throws Exception { checkCameraTake(true); }
    @Test public void silentVideoAlsoKeepsTheCreativeFilter() throws Exception { checkCameraTake(false); }

    private void checkCameraTake(boolean recordAudio) throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString("gearcam_recording_mode", "video")
                .putString(PreferenceKeys.CameraAPIPreferenceKey, "preference_camera_api_camera2")
                .putBoolean(PreferenceKeys.UsingSAFPreferenceKey, false).putBoolean(PreferenceKeys.RecordAudioPreferenceKey, recordAudio).commit();
        context.getSharedPreferences("gearcam_mixer", 0).edit().putStringSet("inputs", java.util.Collections.singleton("phone/0")).commit();
        CreativeFilters.select(context, 1);
        AtomicReference<android.net.Uri> uri = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class).putExtra("test_project", true))) {
            await(scenario, a -> a.getPreview().isPreviewStarted()); Thread.sleep(700);
            scenario.onActivity(a -> {
                assertNotNull(a.findViewById(R.id.creative_filter));
                assertFalse(a.getApplicationInterface().getFaceDetectionPref());
                android.view.TextureView view = findTexture(a.findViewById(android.R.id.content));
                assertNotNull(view); Bitmap bitmap = view.getBitmap(128, 128); assertMonochrome(bitmap); bitmap.recycle();
                a.getPreview().takePicturePressed(false, false);
            });
            await(scenario, a -> a.getPreview().isVideoRecording()); Thread.sleep(1300);
            scenario.onActivity(a -> a.getPreview().stopVideo(false));
            await(scenario, a -> !a.getPreview().isFinishingMix());
            scenario.onActivity(a -> uri.set(a.getApplicationInterface().getStorageUtils().getLastMediaScanned()));
            assertNotNull(uri.get());
            try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                retriever.setDataSource(context, uri.get()); Bitmap bitmap = retriever.getFrameAtTime(500000);
                assertMonochrome(bitmap); bitmap.recycle();
            }
        } finally { CreativeFilters.select(context, 0); PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(PreferenceKeys.RecordAudioPreferenceKey, true).commit(); if (uri.get() != null) context.getContentResolver().delete(uri.get(), null, null); }
    }
    private static android.view.TextureView findTexture(android.view.View view) {
        if (view instanceof android.view.TextureView) return (android.view.TextureView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { android.view.TextureView found = findTexture(group.getChildAt(i)); if (found != null) return found; }
        }
        return null;
    }
    private static void assertMonochrome(Bitmap bitmap) {
        assertNotNull(bitmap); int min = 255, max = 0;
        for (int y = 0; y < bitmap.getHeight(); y += Math.max(1, bitmap.getHeight() / 30))
            for (int x = 0; x < bitmap.getWidth(); x += Math.max(1, bitmap.getWidth() / 30)) {
                int c = bitmap.getPixel(x, y), r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                assertTrue("Frame contains color", Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 6);
                min = Math.min(min, r); max = Math.max(max, r);
            }
        assertTrue("Frame must contain a camera image, not a solid black frame", max - min > 50);
    }
    private interface Condition { boolean test(MainActivity activity); }
    private void await(ActivityScenario<MainActivity> scenario, Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 15000; AtomicBoolean success = new AtomicBoolean();
        while (System.currentTimeMillis() < deadline) { scenario.onActivity(a -> success.set(condition.test(a))); if (success.get()) return; Thread.sleep(100); }
        fail("Timed out waiting for camera");
    }
}
