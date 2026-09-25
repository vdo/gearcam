package net.sourceforge.opencamera.audio;

import android.Manifest;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** A take must stop itself while there is still room to close the file, and say that is why. */
@RunWith(AndroidJUnit4.class)
public class FullStorageStopTest {
    @Rule public GrantPermissionRule microphone = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO);

    @Test public void stopsWhenTheDestinationIsNearlyFull() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("gearcam_mixer", 0).edit()
                .putStringSet("inputs", Collections.singleton("phone/0")).commit();
        AtomicReference<String> failure = new AtomicReference<>();
        JamAudioSession session = JamAudioSession.audioOnly(context, "wav", failure::set);
        try {
            session.watchFreeSpace(() -> 1_000_000L); // a megabyte left on the card
            session.awaitReady();
            session.startVideo(System.nanoTime());
            for (int i = 0; i < 100 && failure.get() == null; i++) Thread.sleep(100);
        } finally {
            session.cancelAndDiscard();
        }
        assertNotNull("the take kept going with a full destination", failure.get());
        assertTrue("wrong reason given: " + failure.get(), failure.get().contains("almost full"));
        System.out.println("FULL STORAGE TEST: " + failure.get());
    }
}
