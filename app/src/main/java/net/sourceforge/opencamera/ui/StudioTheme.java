package net.sourceforge.opencamera.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.widget.Button;
import android.widget.ListView;
import net.sourceforge.opencamera.R;

/** Shared native controls for the camera, recorder and mixer. */
public final class StudioTheme {
    public static final int BACKGROUND = 0xff0c1219, PANEL = 0xff17222d, BORDER = 0xff2a3b49;
    public static final int TEXT = 0xffedf4fa, MUTED = 0xffa5b8c8, ACCENT = 0xff72e5c0, RED = 0xffff817e;
    public static final String KEY = "gearcam_color_theme";
    public static final String[] NAMES = {"Mint", "Ocean", "Violet", "Rose", "Amber", "Graphite", "Paper"};
    public static final String[] IDS = {"mint", "ocean", "violet", "rose", "amber", "graphite", "paper"};
    public static final class Palette {
        public final int background, panel, border, text, muted, accent;
        Palette(int background, int panel, int border, int text, int muted, int accent) {
            this.background = background; this.panel = panel; this.border = border;
            this.text = text; this.muted = muted; this.accent = accent;
        }
    }
    private static final Palette[] PALETTES = {
        new Palette(BACKGROUND, PANEL, BORDER, TEXT, MUTED, ACCENT),
        new Palette(0xff091522, 0xff13283b, 0xff29455e, 0xffedf6ff, 0xffa3bdd2, 0xff77caff),
        new Palette(0xff151122, 0xff251e38, 0xff413654, 0xfff5efff, 0xffb9accf, 0xffc2a2ff),
        new Palette(0xff1e1119, 0xff32202c, 0xff513748, 0xffffeff6, 0xffcbb0c0, 0xffff9fc4),
        new Palette(0xff19150d, 0xff2b2418, 0xff493e29, 0xfffff5e6, 0xffc9bda4, 0xffffcf77),
        new Palette(0xff101112, 0xff222426, 0xff3d4145, 0xfff3f4f5, 0xffafb5bb, 0xffd3d9df),
        new Palette(0xfff1f3f4, 0xffffffff, 0xffd1d9df, 0xff182733, 0xff536574, 0xff006c58)
    };
    public static int selected(Context context) {
        String value = android.preference.PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, IDS[0]);
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(value)) return i;
        return 0;
    }
    public static Palette palette(Context context) { return PALETTES[selected(context)]; }
    public static void apply(Context context) {
        int[] styles = {R.style.StudioMint, R.style.StudioOcean, R.style.StudioViolet, R.style.StudioRose,
                R.style.StudioAmber, R.style.StudioGraphite, R.style.StudioPaper};
        context.getTheme().applyStyle(styles[selected(context)], true);
    }
    public static void systemBars(android.view.Window window, Context context) {
        boolean light = selected(context) == 6;
        androidx.core.view.WindowInsetsControllerCompat controller = new androidx.core.view.WindowInsetsControllerCompat(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(light); controller.setAppearanceLightNavigationBars(light);
    }
    public static void choose(android.app.Activity activity) {
        new android.app.AlertDialog.Builder(activity).setTitle("Color theme")
                .setSingleChoiceItems(NAMES, selected(activity), (dialog, index) -> {
                    android.preference.PreferenceManager.getDefaultSharedPreferences(activity).edit().putString(KEY, IDS[index]).apply();
                    dialog.dismiss(); activity.recreate();
                }).setNegativeButton("Cancel", null).show();
    }
    public static void addPreference(PreferenceFragment fragment) {
        if (fragment.findPreference(KEY) != null || fragment.findPreference("preference_about") == null) return;
        android.preference.ListPreference pref = new android.preference.ListPreference(fragment.getActivity());
        pref.setKey(KEY); pref.setTitle("Color theme"); pref.setEntries(NAMES); pref.setEntryValues(IDS);
        pref.setDefaultValue(IDS[0]); pref.setSummary("%s"); pref.setOrder(-1);
        pref.setOnPreferenceChangeListener((p, value) -> {
            android.app.Activity activity = fragment.getActivity();
            android.preference.PreferenceManager.getDefaultSharedPreferences(activity).edit().putString(KEY, (String) value).apply();
            activity.recreate(); return false;
        });
        fragment.getPreferenceScreen().addPreference(pref);
    }
    private StudioTheme() {}

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable card(Context context, int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(context, radius));
        drawable.setStroke(dp(context, 1), palette(context).border);
        return drawable;
    }

    public static RippleDrawable surface(Context context, int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf((palette(context).accent & 0x00ffffff) | 0x30000000), card(context, color, radius), null);
    }

    public static void button(Button button, boolean primary) {
        Context context = button.getContext();
        Palette palette = palette(context);
        button.setAllCaps(false); button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextSize(14); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setMinHeight(dp(context, 48)); button.setMinimumHeight(dp(context, 48));
        button.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        button.setBackgroundTintList(null);
        button.setBackground(surface(context, primary ? palette.accent : palette.panel, 16));
        button.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {palette.muted, primary ? palette.background : palette.text}));
    }

    public static void preferences(PreferenceFragment fragment) {
        if (fragment.getView() == null || fragment.getPreferenceScreen() == null) return;
        styleGroup(fragment.getPreferenceScreen());
        ListView list = fragment.getView().findViewById(android.R.id.list);
        if (list != null) {
            int gap = dp(fragment.getActivity(), 8);
            list.setDivider(new android.graphics.drawable.ColorDrawable(palette(fragment.getActivity()).background)); list.setDividerHeight(gap);
            list.setPadding(gap * 2, gap, gap * 2, gap); list.setClipToPadding(false);
        }
    }

    private static void styleGroup(PreferenceGroup group) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            preference.setLayoutResource(preference instanceof PreferenceCategory
                    ? R.layout.studio_preference_category : R.layout.studio_preference);
            if (preference instanceof PreferenceGroup) styleGroup((PreferenceGroup) preference);
        }
    }
}
