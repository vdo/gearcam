package net.sourceforge.opencamera.audio;

import android.graphics.Canvas;
import android.graphics.Paint;

/** Compact stereo meters drawn in the same rotated coordinate space as ISO and storage. */
public final class CameraAudioMeters {
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);

    public static void draw(Canvas canvas, float x, float y, float scale, JamAudioSession session) {
        PcmMixer mix = session == null ? null : session.mixer;
        boolean clip = mix != null && mix.busClipped;
        if (mix != null) for (PcmMixer.Channel channel : mix.channels)
            clip |= channel.inputClipped || channel.gainClipped;
        PAINT.setColor(0xbb0d141c);
        canvas.drawRoundRect(x, y, x + 96 * scale, y + 32 * scale, 4 * scale, 4 * scale, PAINT);
        for (int c = 0; c < 2; c++) {
            float top = y + (5 + c * 14) * scale;
            PAINT.setColor(0xffe4edf5); PAINT.setTextSize(10 * scale); PAINT.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(c == 0 ? "L" : "R", x + 4 * scale, top + 8 * scale, PAINT);
            float amplitude = mix == null ? 0 : c == 0 ? mix.leftPeak : mix.rightPeak;
            float level = (float) Math.max(0, Math.min(1, (20 * Math.log10(Math.max(0.001, amplitude)) + 60) / 60));
            for (int segment = 0; segment < 16; segment++) {
                PAINT.setColor(segment >= level * 16 ? 0xff34434a : segment >= 15 ? 0xffff8275 : segment >= 13 ? 0xffffce64 : 0xff66dec0);
                float left = x + (16 + segment * 4) * scale;
                canvas.drawRect(left, top, left + 3 * scale, top + 8 * scale, PAINT);
            }
            PAINT.setColor(clip ? 0xffff534e : 0xff34434a);
            canvas.drawCircle(x + 88 * scale, top + 4 * scale, 2 * scale, PAINT);
        }
    }
}
