package net.sourceforge.opencamera.audio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
import android.widget.Toast;
import net.sourceforge.opencamera.PreferenceKeys;

/** Recording choices shared by the camera, audio recorder and settings. */
public final class RecordingPreferences {
    public static final String MODE = "gearcam_recording_mode", FORMAT = "gearcam_audio_format";
    public static final int FOLDER_REQUEST = 420;
    public static boolean audioOnly(Context context) { return "audio".equals(prefs(context).getString(MODE, "video")); }
    public static String format(Context context) { return prefs(context).getString(FORMAT, "wav"); }
    static SharedPreferences prefs(Context context) { return PreferenceManager.getDefaultSharedPreferences(context); }

    public static String folderLabel(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.getBoolean(PreferenceKeys.UsingSAFPreferenceKey, false)) {
            try { return DocumentsContract.getTreeDocumentId(Uri.parse(prefs.getString(PreferenceKeys.SaveLocationSAFPreferenceKey, ""))); }
            catch (RuntimeException e) { return "Folder unavailable · choose again"; }
        }
        return "Video: DCIM/" + prefs.getString(PreferenceKeys.SaveLocationPreferenceKey, "GearCam") + " · Audio: Music/GearCam";
    }

    public static String audioFolderLabel(Context context) {
        return prefs(context).getBoolean(PreferenceKeys.UsingSAFPreferenceKey, false) ? folderLabel(context) : "Music/GearCam";
    }

    public static void chooseFolder(Activity activity) {
        new AlertDialog.Builder(activity).setTitle("Save location")
                .setMessage(folderLabel(activity) + "\n\nChoose a folder on internal storage or an SD card. Video, audio-only takes and WAV masters will use that folder.")
                .setPositiveButton("Choose folder…", (d, w) -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    try { activity.startActivityForResult(intent, FOLDER_REQUEST); }
                    catch (android.content.ActivityNotFoundException e) { Toast.makeText(activity, "No system folder picker is available", Toast.LENGTH_LONG).show(); }
                })
                .setNeutralButton("Use defaults", (d, w) -> prefs(activity).edit().putBoolean(PreferenceKeys.UsingSAFPreferenceKey, false).apply())
                .setNegativeButton("Cancel", null).show();
    }

    public static boolean folderResult(Activity activity, int request, int result, Intent data) {
        if (request != FOLDER_REQUEST) return false;
        if (result == Activity.RESULT_OK && data != null && data.getData() != null) {
            try {
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) throw new SecurityException("Folder is read-only");
                activity.getContentResolver().takePersistableUriPermission(data.getData(), (flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
                        ? Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION : Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                prefs(activity).edit().putString(PreferenceKeys.SaveLocationSAFPreferenceKey, data.getData().toString())
                        .putBoolean(PreferenceKeys.UsingSAFPreferenceKey, true).apply();
                Toast.makeText(activity, "Saving to " + folderLabel(activity), Toast.LENGTH_LONG).show();
            } catch (RuntimeException e) { Toast.makeText(activity, "Cannot use folder: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
        }
        return true;
    }

    public static void configure(PreferenceFragment fragment) {
        if (fragment.getActivity() == null || fragment.getPreferenceScreen() == null) return;
        if (fragment.findPreference("preference_about") == null && fragment.findPreference("preference_record_audio") == null) return;
        if (fragment.findPreference(MODE) == null) {
            ListPreference mode = new ListPreference(fragment.getActivity());
            mode.setKey(MODE); mode.setTitle("Recording mode"); mode.setOrder(-4);
            mode.setEntries(new String[] {"Video + audio", "Audio only · live waveform"});
            mode.setEntryValues(new String[] {"video", "audio"}); mode.setDefaultValue("video"); mode.setSummary("%s");
            mode.setOnPreferenceChangeListener((p, value) -> {
                if ("audio".equals(value)) fragment.getActivity().startActivity(new Intent(fragment.getActivity(), AudioRecorderActivity.class));
                return true;
            });
            fragment.getPreferenceScreen().addPreference(mode);
            ListPreference format = new ListPreference(fragment.getActivity());
            format.setKey(FORMAT); format.setTitle("Audio-only format"); format.setOrder(-3);
            format.setEntries(new String[] {"WAV · 24-bit lossless", "MP3 · 320 kb/s", "FLAC · 24-bit lossless"});
            format.setEntryValues(new String[] {"wav", "mp3", "flac"}); format.setDefaultValue("wav"); format.setSummary("%s");
            fragment.getPreferenceScreen().addPreference(format);
            Preference folder = new Preference(fragment.getActivity());
            folder.setKey("gearcam_save_folder"); folder.setTitle("Save location · internal storage / SD card"); folder.setOrder(-2);
            folder.setOnPreferenceClickListener(p -> { chooseFolder(fragment.getActivity()); return true; });
            fragment.getPreferenceScreen().addPreference(folder);
        }
        fragment.findPreference("gearcam_save_folder").setSummary(folderLabel(fragment.getActivity()));
    }
}
