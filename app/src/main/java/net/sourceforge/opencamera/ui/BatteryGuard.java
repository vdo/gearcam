package net.sourceforge.opencamera.ui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import androidx.core.content.ContextCompat;

/** Stops a take while there is still battery to close the file. A phone that dies mid-recording leaves
 *  an MP4 without its index, which is the one failure no amount of care afterwards can undo. */
public final class BatteryGuard {
    /** Android shuts down around 1%; leave room to finish writing and publish the file. */
    public static final int STOP_PERCENT = 3;

    private final Activity activity;
    private final Runnable stop;
    private boolean registered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (shouldStop(intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                    intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                    intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)))
                stop.run(); // repeats are harmless: stopping checks whether a take is running
        }
    };

    public BatteryGuard(Activity activity, Runnable stop) { this.activity = activity; this.stop = stop; }

    /** True once the battery is down to the last few percent and nothing is charging it. */
    public static boolean shouldStop(int level, int scale, int plugged) {
        if (level < 0 || scale <= 0 || plugged != 0) return false;
        return level * 100 / scale <= STOP_PERCENT;
    }

    public void resume() {
        if (registered) return;
        ContextCompat.registerReceiver(activity, receiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED); // a protected system broadcast, so this is delivered
        registered = true;
    }

    public void pause() {
        if (!registered) return;
        activity.unregisterReceiver(receiver);
        registered = false;
    }
}
