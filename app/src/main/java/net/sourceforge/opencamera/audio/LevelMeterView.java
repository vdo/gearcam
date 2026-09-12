package net.sourceforge.opencamera.audio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Logarithmic −60..0 dBFS bar, with an independent latched overload indicator. */
final class LevelMeterView extends View {
    private final Paint paint = new Paint();
    private float level;
    private boolean clipped;
    private boolean vertical;

    LevelMeterView(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
    LevelMeterView(Context context, boolean vertical) { this(context); this.vertical = vertical; }

    void level(float amplitude, boolean clipped) {
        level = (float) Math.max(0, Math.min(1, (20 * Math.log10(Math.max(0.001, amplitude)) + 60) / 60));
        this.clipped = clipped; invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        paint.setColor(Color.rgb(32, 43, 40)); canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setColor(level > 0.95f ? Color.rgb(255, 105, 95) : level > 0.8f ? Color.rgb(255, 206, 100) : Color.rgb(100, 225, 194));
        if (vertical) canvas.drawRect(0, getHeight() * (1 - level), getWidth(), getHeight(), paint);
        else canvas.drawRect(0, 0, getWidth() * level, getHeight(), paint);
        if (clipped) {
            paint.setColor(Color.rgb(255, 105, 95));
            if (vertical) canvas.drawRect(0, 0, getWidth(), getWidth(), paint);
            else canvas.drawRect(getWidth() - getHeight(), 0, getWidth(), getHeight(), paint);
        }
    }
}
