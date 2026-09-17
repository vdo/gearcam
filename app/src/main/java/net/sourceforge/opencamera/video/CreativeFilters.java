package net.sourceforge.opencamera.video;

import android.content.Context;
import android.preference.PreferenceManager;

/** Creative looks, shared by the preview and recording shaders. Original is always the default. */
public final class CreativeFilters {
    public static final String KEY = "gearcam_creative_filter";
    public static final String[] NAMES = {"Original · no filter", "Black & white", "B&W Noir", "Warm Film", "Cool Chrome", "Sepia", "Fade"};
    public static final String[] IDS = {"original", "bw", "noir", "warm", "cool", "sepia", "fade"};
    private CreativeFilters() {}
    public static int selected(Context context) {
        String value = PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, "original");
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(value)) return i;
        return 0;
    }
    public static void select(Context context, int index) {
        if (index < 0 || index >= IDS.length) throw new IllegalArgumentException("Unknown filter");
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY, IDS[index]).apply();
    }
    static final String FRAGMENT = "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float; uniform samplerExternalOES image; uniform int look; varying vec2 uv; varying vec2 position;\n"
            + "void main() { vec3 c = texture2D(image, uv).rgb; float y = dot(c, vec3(0.2126,0.7152,0.0722));\n"
            + "if (look == 1) c = vec3(y);\n"
            + "else if (look == 2) { c = vec3(smoothstep(0.16,0.86,y)); c *= 1.0 - 0.40*smoothstep(0.25,0.72,length(position-0.5)); }\n"
            + "else if (look == 3) c = (c*vec3(1.08,1.01,0.88)-0.5)*1.08+0.5;\n"
            + "else if (look == 4) c = (mix(vec3(y),c,0.72)*vec3(0.88,1.03,1.12)-0.5)*1.12+0.5;\n"
            + "else if (look == 5) c = vec3(dot(c,vec3(0.393,0.769,0.189)), dot(c,vec3(0.349,0.686,0.168)), dot(c,vec3(0.272,0.534,0.131)));\n"
            + "else if (look == 6) c = mix(vec3(y),c,0.65)*vec3(0.84,0.81,0.78)+vec3(0.09,0.085,0.10);\n"
            + "gl_FragColor = vec4(clamp(c,0.0,1.0),1.0); }\n";
}
