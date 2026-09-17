package net.sourceforge.opencamera.audio;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import net.sourceforge.opencamera.MainActivity;

/** Routes cold launches before any camera initialization or camera-permission request. */
public final class RecordingLauncherActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Class<? extends Activity> target = RecordingPreferences.audioOnly(this) ? AudioRecorderActivity.class : MainActivity.class;
        startActivity(new Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
}
