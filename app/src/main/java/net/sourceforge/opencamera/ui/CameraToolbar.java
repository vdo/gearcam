package net.sourceforge.opencamera.ui;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.preference.PreferenceManager;
import android.graphics.Rect;
import android.view.View;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.RelativeLayout;
import android.widget.Toast;
import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.PreferenceKeys;
import net.sourceforge.opencamera.R;
import net.sourceforge.opencamera.video.CreativeFilters;
import java.util.List;

/** A compact backing bar around the existing camera controls, preserving their camera actions. */
public final class CameraToolbar {
    private CameraToolbar() {}
    public static void arrange(MainActivity activity, List<View> buttons) {
        View bar = activity.findViewById(R.id.camera_toolbar);
        StudioTheme.Palette palette = StudioTheme.palette(activity);
        bar.setBackground(StudioTheme.card(activity, (palette.panel & 0x00ffffff) | 0xee000000, 14));
        activity.findViewById(R.id.switch_video).setBackground(new android.graphics.drawable.InsetDrawable(
                StudioTheme.surface(activity, (palette.panel & 0x00ffffff) | 0xd9000000, 18), StudioTheme.dp(activity, 3)));
        ImageButton filter = activity.findViewById(R.id.creative_filter);
        filter.setOnClickListener(v -> showFilters(activity, filter));
        filter.setVisibility(activity.findViewById(R.id.settings).getVisibility());
        ImageButton overlay = activity.findViewById(R.id.overlay);
        overlay.setOnClickListener(v -> cycleOverlay(activity, overlay));
        overlay.setVisibility(filter.getVisibility());
        for (View button : buttons) {
            if (button.getId() == R.id.gallery) continue;
            button.setPadding(StudioTheme.dp(activity, 11), StudioTheme.dp(activity, 11), StudioTheme.dp(activity, 11), StudioTheme.dp(activity, 11));
            button.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf((palette.accent & 0xffffff) | 0x40000000), null,
                    StudioTheme.card(activity, 0xffffffff, 10)));
            if (button instanceof ImageButton) ((ImageButton) button).setImageTintList(ColorStateList.valueOf(palette.text));
        }
        updateFilter(activity, filter);
        updateOverlay(activity, overlay);
        bar.post(() -> {
            Rect bounds = new Rect(); boolean first = true;
            for (View button : buttons) {
                if (button.getVisibility() != View.VISIBLE || button.getId() == R.id.gallery) continue;
                Rect rectangle = new Rect(button.getLeft(), button.getTop(), button.getRight(), button.getBottom());
                if (first) { bounds.set(rectangle); first = false; } else bounds.union(rectangle);
            }
            bar.setVisibility(first || activity.isCameraInBackground() ? View.GONE : View.VISIBLE);
            if (first) return;
            int gap = StudioTheme.dp(activity, 3);
            RelativeLayout.LayoutParams size = new RelativeLayout.LayoutParams(bounds.width() + gap * 2, bounds.height() + gap * 2);
            size.leftMargin = Math.max(0, bounds.left - gap); size.topMargin = Math.max(0, bounds.top - gap); bar.setLayoutParams(size);
        });
    }
    private static void updateFilter(MainActivity activity, ImageButton button) {
        int selected = CreativeFilters.selected(activity);
        StudioTheme.Palette palette = StudioTheme.palette(activity);
        button.setContentDescription("Creative filters · " + CreativeFilters.NAMES[selected]);
        button.setImageTintList(ColorStateList.valueOf(selected == 0 ? palette.text : palette.accent));
    }
    private static final String[] OVERLAYS = {"Overlays off", "Grid", "Histogram"};
    private static int currentOverlay(SharedPreferences prefs) {
        if (!prefs.getString(PreferenceKeys.HistogramPreferenceKey, "preference_histogram_off").equals("preference_histogram_off")) return 2;
        return prefs.getString(PreferenceKeys.ShowGridPreferenceKey, "preference_grid_none").equals("preference_grid_none") ? 0 : 1;
    }
    private static void updateOverlay(MainActivity activity, ImageButton button) {
        int selected = currentOverlay(PreferenceManager.getDefaultSharedPreferences(activity));
        StudioTheme.Palette palette = StudioTheme.palette(activity);
        button.setContentDescription(activity.getString(R.string.overlays) + " · " + OVERLAYS[selected]);
        button.setImageTintList(ColorStateList.valueOf(selected == 0 ? palette.text : palette.accent));
    }
    private static void cycleOverlay(MainActivity activity, ImageButton button) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        // the histogram needs preview bitmaps (Camera2 + TextureView), so only offer grid otherwise
        int next = (currentOverlay(prefs) + 1) % (activity.supportsPreviewBitmaps() ? OVERLAYS.length : 2);
        prefs.edit()
                .putString(PreferenceKeys.ShowGridPreferenceKey, next == 1 ? "preference_grid_3x3" : "preference_grid_none")
                .putString(PreferenceKeys.HistogramPreferenceKey, next == 2 ? "preference_histogram_rgb" : "preference_histogram_off")
                .apply();
        activity.getApplicationInterface().getDrawPreview().updateSettings();
        updateOverlay(activity, button);
        activity.getPreview().showToast(OVERLAYS[next], true);
    }
    private static void showFilters(MainActivity activity, ImageButton anchor) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        for (int i = 0; i < CreativeFilters.NAMES.length; i++) menu.getMenu().add(0, i, i, CreativeFilters.NAMES[i]).setCheckable(true).setChecked(i == CreativeFilters.selected(activity));
        menu.getMenu().setGroupCheckable(0, true, true);
        menu.setOnMenuItemClickListener(item -> {
            int selected = item.getItemId();
            if (selected != 0 && (activity.getPreview().isVideoHighSpeed() || !activity.supportsCamera2())) {
                Toast.makeText(activity, "Creative filters need Camera2 at a normal video frame rate", Toast.LENGTH_LONG).show(); return true;
            }
            boolean camera2 = activity.getPreview().getCameraController() != null && activity.getPreview().getCameraController().supportsVideoSurface();
            if (selected != 0 && !camera2 && activity.getPreview().isVideoRecording()) {
                Toast.makeText(activity, "Stop this take before enabling Camera2 filters", Toast.LENGTH_LONG).show(); return true;
            }
            CreativeFilters.select(activity, selected); updateFilter(activity, anchor);
            if (selected != 0 && !camera2) {
                android.preference.PreferenceManager.getDefaultSharedPreferences(activity).edit()
                        .putString(PreferenceKeys.CameraAPIPreferenceKey, "preference_camera_api_camera2").apply();
                activity.updateForSettings(true);
            }
            return true;
        });
        menu.show();
    }
}
