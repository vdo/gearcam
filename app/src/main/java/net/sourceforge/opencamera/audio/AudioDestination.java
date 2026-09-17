package net.sourceforge.opencamera.audio;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import net.sourceforge.opencamera.PreferenceKeys;
import java.io.File;
import java.io.IOException;

/** Writes to the selected folder directly. Never silently substitutes internal storage for an SD card. */
final class AudioDestination implements AutoCloseable {
    final Uri uri;
    final ParcelFileDescriptor descriptor;
    private final Context context;
    private final boolean mediaStore;
    private final File file;
    private final String mime;
    private boolean closed;

    AudioDestination(Context context, String name, String format) throws IOException {
        this.context = context;
        mime = "mp3".equals(format) ? "audio/mpeg" : "flac".equals(format) ? "audio/flac" : "audio/wav";
        Uri created = null; File legacy = null; boolean pending = false;
        try {
            if (RecordingPreferences.prefs(context).getBoolean(PreferenceKeys.UsingSAFPreferenceKey, false)) {
                Uri tree = Uri.parse(RecordingPreferences.prefs(context).getString(PreferenceKeys.SaveLocationSAFPreferenceKey, ""));
                created = DocumentsContract.createDocument(context.getContentResolver(),
                        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), mime, name);
            } else if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Audio.Media.DISPLAY_NAME, name); values.put(MediaStore.Audio.Media.MIME_TYPE, mime);
                values.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/GearCam"); values.put(MediaStore.Audio.Media.IS_PENDING, 1);
                created = context.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values); pending = true;
            } else {
                File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "GearCam");
                if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create Music/GearCam");
                legacy = new File(folder, name); created = Uri.fromFile(legacy);
            }
            if (created == null) throw new IOException("Cannot create audio in the selected folder");
            uri = created; file = legacy; mediaStore = pending;
            descriptor = legacy != null ? ParcelFileDescriptor.open(legacy, ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_TRUNCATE)
                    : context.getContentResolver().openFileDescriptor(created, "rw");
            if (descriptor == null) throw new IOException("Cannot open selected save folder");
        } catch (IOException | RuntimeException e) {
            if (created != null) delete(context, created, legacy);
            throw new IOException("Cannot use save location: " + e.getMessage(), e);
        }
    }
    long availableBytes() {
        try { android.system.StructStatVfs stat = android.system.Os.fstatvfs(descriptor.getFileDescriptor()); return stat.f_bavail * stat.f_frsize; }
        catch (android.system.ErrnoException e) { return Long.MAX_VALUE; }
    }
    void publish() throws IOException {
        close();
        if (mediaStore && Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues(); values.put(MediaStore.Audio.Media.IS_PENDING, 0);
            if (context.getContentResolver().update(uri, values, null, null) != 1) throw new IOException("Cannot publish audio file");
        } else if (file != null) android.media.MediaScannerConnection.scanFile(context, new String[] {file.getAbsolutePath()}, new String[] {mime}, null);
    }
    void discard() { try { close(); } catch (IOException ignored) { } delete(context, uri, file); }
    private static void delete(Context context, Uri uri, File file) {
        try { if (file != null) file.delete(); else if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(context.getContentResolver(), uri); else context.getContentResolver().delete(uri, null, null); }
        catch (Exception ignored) { }
    }
    @Override public void close() throws IOException { if (!closed) { closed = true; descriptor.close(); } }
}
