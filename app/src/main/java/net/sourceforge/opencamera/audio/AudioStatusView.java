package net.sourceforge.opencamera.audio;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.R;

/** Shows save progress; live levels are drawn beside the camera information. */
public final class AudioStatusView {
    private final MainActivity activity;
    private final TextView view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;

    public AudioStatusView(MainActivity activity) {
        this.activity = activity;
        view = activity.findViewById(R.id.audio_status);
        view.setOnClickListener(v -> activity.showAudioMixer());
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> align());
    }

    public void resume() { running = true; handler.removeCallbacks(update); handler.post(update); }
    public void pause() { running = false; handler.removeCallbacks(update); }

    private void align() {
        int rotation = activity.getPreview().getUIRotation();
        view.setRotation(rotation);
        // Open Camera rotates controls independently of the Activity. Keep the rotated bar clear
        // of the shutter, accounting for its visual height rather than its unrotated layout box.
        view.setTranslationY(rotation == 90 || rotation == 270 ? -Math.max(0, (view.getWidth() - view.getHeight()) / 2f) : 0);
    }

    private final Runnable update = new Runnable() {
        @Override public void run() {
            if (!running) return;
            view.setVisibility(activity.isCameraInBackground() || activity.getMainUI().inImmersiveMode() ? View.GONE : View.VISIBLE);
            if (activity.getPreview().isFinishingMix()) {
                view.setText(R.string.gearcam_finalizing);
            } else {
                // Levels now live beside the camera's ISO/storage readouts.
                view.setVisibility(View.GONE);
                view.setText("");
            }
            align();
            handler.postDelayed(this, 100);
        }
    };

}
