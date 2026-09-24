package net.sourceforge.opencamera.video;

import android.content.Context;
import android.preference.PreferenceManager;

/** Creative looks, shared by the preview and recording shaders. Original is always the default. */
public final class CreativeFilters {
    public static final String KEY = "gearcam_creative_filter", HUE_KEY = "gearcam_accent_hue";
    public static final String[] NAMES = {"Original · no filter", "Black & white", "B&W Noir", "Warm Film", "Cool Chrome", "Sepia", "Fade", "Color Accent"};
    public static final String[] IDS = {"original", "bw", "noir", "warm", "cool", "sepia", "fade", "accent"};
    /** Index of the look whose one kept colour the hue slider chooses. */
    public static final int ACCENT = 7;
    private CreativeFilters() {}
    public static int selected(Context context) {
        String value = PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, "original");
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(value)) return i;
        return 0;
    }
    /** The kept hue, in degrees around the colour wheel: 0 red, 120 green, 240 blue. */
    public static float accentHue(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getFloat(HUE_KEY, 120); // green, as a starting point
    }
    public static void setAccentHue(Context context, float degrees) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putFloat(HUE_KEY, (degrees % 360 + 360) % 360).apply();
    }
    public static void select(Context context, int index) {
        if (index < 0 || index >= IDS.length) throw new IllegalArgumentException("Unknown filter");
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY, IDS[index]).apply();
    }
    /** Removes the buffer's rotation/mirror from a SurfaceTexture transform, keeping its crop and GL flip.
     *  Camera2 rotates SurfaceTexture output so previews are upright, but encoders expect raw sensor frames
     *  plus an orientation hint, exactly as a camera writing straight into the encoder surface produces.
     *  The transform is FlipV * Crop * X (column-major, as SurfaceTexture builds it); X is a symmetry of the
     *  unit square, whose sign pattern the positive crop scales leave visible. This multiplies by X⁻¹. */
    static void stripBufferTransform(float[] m) {
        float a = sign(m[0]), b = sign(m[4]), c = -sign(m[1]), d = -sign(m[5]); // X = [[a, b], [c, d]]
        if (a == 1 && b == 0 && c == 0 && d == 1) return;
        // X⁻¹ = Xᵀ about the centre: linear [[a, c], [b, d]], translation 0.5 - 0.5 * (row sums)
        float tx = 0.5f - 0.5f * (a + c), ty = 0.5f - 0.5f * (b + d);
        float m0 = m[0], m1 = m[1], m4 = m[4], m5 = m[5];
        m[12] += m0 * tx + m4 * ty; m[13] += m1 * tx + m5 * ty;
        m[0] = m0 * a + m4 * b; m[4] = m0 * c + m4 * d;
        m[1] = m1 * a + m5 * b; m[5] = m1 * c + m5 * d;
    }
    private static float sign(float v) { return Math.abs(v) < 1e-4f ? 0 : Math.signum(v); }

    static final String FRAGMENT = "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float; uniform samplerExternalOES image; uniform int look; uniform float accent;\n"
            + "varying vec2 uv; varying vec2 position;\n"
            + "void main() { vec3 c = texture2D(image, uv).rgb; float y = dot(c, vec3(0.2126,0.7152,0.0722));\n"
            + "if (look == 1) c = vec3(y);\n"
            + "else if (look == 2) { c = vec3(smoothstep(0.16,0.86,y)); c *= 1.0 - 0.40*smoothstep(0.25,0.72,length(position-0.5)); }\n"
            + "else if (look == 3) c = (c*vec3(1.08,1.01,0.88)-0.5)*1.08+0.5;\n"
            + "else if (look == 4) c = (mix(vec3(y),c,0.72)*vec3(0.88,1.03,1.12)-0.5)*1.12+0.5;\n"
            + "else if (look == 5) c = vec3(dot(c,vec3(0.393,0.769,0.189)), dot(c,vec3(0.349,0.686,0.168)), dot(c,vec3(0.272,0.534,0.131)));\n"
            + "else if (look == 6) c = mix(vec3(y),c,0.65)*vec3(0.84,0.81,0.78)+vec3(0.09,0.085,0.10);\n"
            // Colour accent: mono with a firmer curve than plain B&W, keeping what matches the chosen hue.
            + "else if (look == 7) {\n"
            + "  float mx = max(c.r,max(c.g,c.b)), mn = min(c.r,min(c.g,c.b)), d = mx - mn; float h = 0.0;\n"
            + "  if (d > 0.0001) { if (mx == c.r) h = mod((c.g-c.b)/d, 6.0); else if (mx == c.g) h = (c.b-c.r)/d + 2.0; else h = (c.r-c.g)/d + 4.0; h /= 6.0; }\n"
            + "  float away = abs(h - accent); away = min(away, 1.0 - away);\n"
            + "  float keep = smoothstep(0.11,0.05,away) * smoothstep(0.12,0.30,d/max(mx,0.0001)) * smoothstep(0.04,0.11,mx);\n"
            + "  c = mix(vec3(clamp((y-0.5)*1.22+0.5,0.0,1.0)), clamp(mix(vec3(y),c,1.2),0.0,1.0), keep);\n"
            + "}\n"
            + "gl_FragColor = vec4(clamp(c,0.0,1.0),1.0); }\n";
}
