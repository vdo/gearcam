package net.sourceforge.opencamera.audio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** Vertical gain fader with a 48dp touch target and accessible range actions. */
final class MixerFader extends View {
    interface Listener { void changed(float db); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Listener listener;
    private float gain;
    private final float density;

    MixerFader(Context context, float gain, String label, Listener listener) {
        super(context); this.gain = gain; this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        setContentDescription(label); setFocusable(true); setClickable(true);
    }

    private float y(float db) { return 18 * density + (24 - db) / 84 * (getHeight() - 36 * density); }

    @Override protected void onDraw(Canvas canvas) {
        float center = getWidth() * 0.55f;
        paint.setStrokeWidth(2 * density); paint.setTextSize(10 * density);
        for (int db : new int[] {24, 12, 0, -12, -24, -48, -60}) {
            paint.setColor(db == 0 ? 0xff66dec0 : 0xff788c9e);
            canvas.drawText(String.valueOf(db), 0, y(db) + 3 * density, paint);
            canvas.drawLine(center - 12 * density, y(db), getWidth(), y(db), paint);
        }
        paint.setColor(0xff080e15); canvas.drawRoundRect(center - 3 * density, y(24), center + 3 * density, y(-60), 3, 3, paint);
        paint.setColor(0xff66dec0); canvas.drawRect(center - density, y(gain), center + density, y(-60), paint);
        paint.setColor(isPressed() ? 0xff66dec0 : 0xffd8e1e9);
        canvas.drawRoundRect(center - 18 * density, y(gain) - 12 * density, center + 18 * density, y(gain) + 12 * density, 4 * density, 4 * density, paint);
        paint.setColor(Color.BLACK); canvas.drawLine(center - 12 * density, y(gain), center + 12 * density, y(gain), paint);
    }

    private void setGain(float value) {
        gain = Math.max(-60, Math.min(24, Math.round(value * 2) / 2f));
        listener.changed(gain); invalidate();
    }

    /** Gain for a finger this far below (+) or above (−) the 0 dB line, in pixels. Within the detent it holds
     *  unity; travel beyond continues from there, so every gain stays reachable by touch. */
    static float detentGain(float offset, float detent, float track) {
        float travel = Math.abs(offset) <= detent ? 0 : offset - Math.signum(offset) * detent;
        return -travel / track * 84;
    }

    private void touch(float y) {
        float before = gain;
        setGain(detentGain(y - y(0), 6 * density, Math.max(1, getHeight() - 36 * density)));
        if (gain == 0 && before != 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true); setPressed(true);
            case MotionEvent.ACTION_MOVE:
                touch(event.getY()); return true;
            case MotionEvent.ACTION_UP:
                setPressed(false); getParent().requestDisallowInterceptTouchEvent(false); performClick(); return true;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false); getParent().requestDisallowInterceptTouchEvent(false); return true;
            default: return true;
        }
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, -60, 24, gain));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        if (android.os.Build.VERSION.SDK_INT >= 24) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { setGain(gain + 1); return true; }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { setGain(gain - 1); return true; }
        if (android.os.Build.VERSION.SDK_INT >= 24 && action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() && args != null) {
            setGain(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)); return true;
        }
        return super.performAccessibilityAction(action, args);
    }
}
