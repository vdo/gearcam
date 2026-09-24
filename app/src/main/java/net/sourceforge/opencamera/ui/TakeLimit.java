package net.sourceforge.opencamera.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.preference.PreferenceManager;

import net.sourceforge.opencamera.PreferenceKeys;

/** How long a take may run before it stops itself, so an hour can be set going and left alone.
 *  Stored in Open Camera's own maximum-duration preference, which the video encoder already enforces. */
public final class TakeLimit {
    private static final int[] SECONDS = {0, 5 * 60, 10 * 60, 15 * 60, 30 * 60, 45 * 60, 3600, 2 * 3600, 3 * 3600};
    private static final String[] NAMES = {"No limit", "5 minutes", "10 minutes", "15 minutes", "30 minutes",
            "45 minutes", "1 hour", "2 hours", "3 hours"};

    private TakeLimit() {}

    /** Seconds a take may run, or 0 for no limit. */
    public static int seconds(Context context) {
        try {
            return Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(context)
                    .getString(PreferenceKeys.VideoMaxDurationPreferenceKey, "0"));
        }
        catch (NumberFormatException e) { return 0; }
    }

    /** Short label for a button: "1:00:00" while set, or null when there is no limit. */
    public static String label(Context context) {
        int seconds = seconds(context);
        if (seconds <= 0) return null;
        return seconds % 3600 == 0 ? seconds / 3600 + " h" : seconds / 60 + " min";
    }

    public static void choose(Activity activity, Runnable changed) {
        int current = 0, chosen = seconds(activity);
        for (int i = 0; i < SECONDS.length; i++) if (SECONDS[i] == chosen) current = i;
        new AlertDialog.Builder(activity).setTitle("Stop the take after")
                .setSingleChoiceItems(NAMES, current, (dialog, which) -> {
                    PreferenceManager.getDefaultSharedPreferences(activity).edit()
                            .putString(PreferenceKeys.VideoMaxDurationPreferenceKey, String.valueOf(SECONDS[which])).apply();
                    dialog.dismiss();
                    changed.run();
                })
                .setNegativeButton("Cancel", null).show();
    }
}
