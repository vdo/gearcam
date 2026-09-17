package net.sourceforge.opencamera.audio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import net.sourceforge.opencamera.ui.StudioTheme;

/** A real 10 ms stereo scope of the mixed PCM; it never fabricates input activity. */
public final class WaveformView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float[] samples = new float[960];
    private JamAudioSession session;
    public WaveformView(Context context) {
        super(context); setBackground(StudioTheme.card(context, StudioTheme.palette(getContext()).panel, 20)); setContentDescription("Live stereo waveform of the selected audio inputs");
    }
    public void setSession(JamAudioSession session) { this.session = session; invalidate(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (session != null) session.copyWaveform(samples); else java.util.Arrays.fill(samples, 0);
        float density = getResources().getDisplayMetrics().density;
        float left = 18 * density, right = getWidth() - 18 * density;
        float laneHeight = getHeight() / 2f;
        boolean compact = laneHeight < 100 * density;
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        for (int channel = 0; channel < 2; channel++) {
            float top = laneHeight * channel, center = top + laneHeight * .58f;
            float scale = Math.max(0, laneHeight * .34f - 8 * density);
            int color = channel == 0 ? StudioTheme.palette(getContext()).accent : StudioTheme.selected(getContext()) == 6 ? 0xff376cb0 : 0xff8dbfff;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(density); paint.setColor(StudioTheme.palette(getContext()).border);
            for (int i = -2; i <= 2; i++) canvas.drawLine(left, center + i * scale / 2, right, center + i * scale / 2, paint);
            for (int i = 0; i <= 10; i++) { float x = left + (right - left) * i / 10; canvas.drawLine(x, center - scale, x, center + scale, paint); }
            paint.setStyle(Paint.Style.FILL); paint.setColor(color); paint.setTextSize(11 * density);
            canvas.drawText(channel == 0 ? "LEFT" : "RIGHT", left, top + 22 * density, paint);
            paint.setColor(StudioTheme.palette(getContext()).muted); paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(compact ? "10 ms" : "LIVE SCOPE  /  10 ms", right, top + 22 * density, paint);
            paint.setTextAlign(Paint.Align.LEFT);
            path.reset();
            for (int i = 0; i < 480; i++) {
                float x = left + (right - left) * i / 479;
                float y = center - Math.max(-1, Math.min(1, samples[i * 2 + channel])) * scale;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f * density);
            paint.setColor(session == null ? 0xff526473 : color);
            canvas.drawPath(path, paint);
        }
        if (session != null && isShown()) postInvalidateDelayed(33);
    }
}
