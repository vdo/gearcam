package net.sourceforge.opencamera.audio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** A real 10 ms stereo scope of the mixed PCM; it never fabricates input activity. */
public final class WaveformView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float[] samples = new float[960];
    private JamAudioSession session;
    public WaveformView(Context context) {
        super(context); setContentDescription("Live stereo waveform of the selected audio inputs");
    }
    public void setSession(JamAudioSession session) { this.session = session; invalidate(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xff0d141c);
        if (session != null) session.copyWaveform(samples); else java.util.Arrays.fill(samples, 0);
        float density = getResources().getDisplayMetrics().density;
        float left = 28 * density, right = getWidth() - 16 * density;
        float height = getHeight() / 2f;
        for (int channel = 0; channel < 2; channel++) {
            float center = height * (channel + .5f), scale = height * .36f;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(density); paint.setColor(0xff263743);
            for (int i = -2; i <= 2; i++) canvas.drawLine(left, center + i * scale / 2, right, center + i * scale / 2, paint);
            for (int i = 0; i <= 10; i++) { float x = left + (right - left) * i / 10; canvas.drawLine(x, center - scale, x, center + scale, paint); }
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xff96aabb); paint.setTextSize(12 * density);
            canvas.drawText(channel == 0 ? "L" : "R", 6 * density, center, paint);
            path.reset();
            for (int i = 0; i < 480; i++) {
                float x = left + (right - left) * i / 479;
                float y = center - Math.max(-1, Math.min(1, samples[i * 2 + channel])) * scale;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f * density);
            paint.setColor(session == null ? 0xff526473 : channel == 0 ? 0xff66dec0 : 0xff83b9ff);
            canvas.drawPath(path, paint);
        }
        if (session != null && isShown()) postInvalidateDelayed(33);
    }
}
