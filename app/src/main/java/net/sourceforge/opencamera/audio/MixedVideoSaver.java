package net.sourceforge.opencamera.audio;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/** Remuxes without re-encoding video. Keeps the completed recovery copy until publication succeeds. */
final class MixedVideoSaver {
    static void save(Context context, Uri uri, String filename, File audio, File completed) throws IOException {
        MediaExtractor video = new MediaExtractor(), sound = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            if (uri != null) video.setDataSource(context, uri, null);
            else video.setDataSource(filename);
            sound.setDataSource(audio.getAbsolutePath());
            int vi = findTrack(video, "video/"), ai = findTrack(sound, "audio/");
            MediaFormat videoFormat = video.getTrackFormat(vi);
            muxer = new MediaMuxer(completed.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaMetadataRetriever metadata = new MediaMetadataRetriever();
            try {
                if (uri != null) metadata.setDataSource(context, uri); else metadata.setDataSource(filename);
                String rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (rotation != null) muxer.setOrientationHint(Integer.parseInt(rotation));
                String location = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
                if (location != null) {
                    java.util.regex.Matcher match = java.util.regex.Pattern.compile("([+-][0-9.]+)([+-][0-9.]+).*?").matcher(location);
                    if (match.matches()) muxer.setLocation(Float.parseFloat(match.group(1)), Float.parseFloat(match.group(2)));
                }
            } finally { metadata.release(); }
            int vt = muxer.addTrack(videoFormat), at = muxer.addTrack(sound.getTrackFormat(ai));
            video.selectTrack(vi); sound.selectTrack(ai);
            muxer.start(); started = true;
            long duration = videoFormat.containsKey(MediaFormat.KEY_DURATION) ? videoFormat.getLong(MediaFormat.KEY_DURATION) : Long.MAX_VALUE;
            int capacity = Math.max(1024 * 1024, videoFormat.getInteger(MediaFormat.KEY_WIDTH) * videoFormat.getInteger(MediaFormat.KEY_HEIGHT) * 3 / 2);
            if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) capacity = Math.max(capacity, videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean haveVideo = video.getSampleTime() >= 0, haveAudio = sound.getSampleTime() >= 0;
            if (!haveVideo || !haveAudio) throw new IOException("Recording has an empty track");
            while (haveVideo || haveAudio) {
                boolean useVideo = haveVideo && (!haveAudio || video.getSampleTime() <= sound.getSampleTime());
                MediaExtractor source = useVideo ? video : sound;
                long size = android.os.Build.VERSION.SDK_INT >= 28 ? source.getSampleSize() : 0;
                if (size > 64 * 1024 * 1024) throw new IOException("Video sample is too large");
                if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect((int) size);
                buffer.clear();
                info.size = source.readSampleData(buffer, 0);
                if (info.size < 0) throw new IOException("Unexpected end of a recording track");
                info.offset = 0; info.presentationTimeUs = source.getSampleTime();
                info.flags = (source.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                muxer.writeSampleData(useVideo ? vt : at, buffer, info);
                boolean more = source.advance();
                if (useVideo) haveVideo = more;
                else haveAudio = more && sound.getSampleTime() <= duration;
            }
            muxer.stop(); started = false;
            muxer.release(); muxer = null;
        } finally {
            video.release(); sound.release();
            if (muxer != null) {
                if (started) try { muxer.stop(); } catch (RuntimeException ignored) { }
                muxer.release();
            }
        }
        // Only overwrite the destination after the complete two-track MP4 has been closed successfully.
        try (FileInputStream source = new FileInputStream(completed);
             OutputStream target = uri == null ? new FileOutputStream(filename) : context.getContentResolver().openOutputStream(uri, "wt")) {
            if (target == null) throw new IOException("Cannot open video destination");
            byte[] bytes = new byte[256 * 1024];
            int count;
            while ((count = source.read(bytes)) != -1) target.write(bytes, 0, count);
            target.flush();
        }
    }

    static void saveMaster(Context context, File source, String sessionName) throws IOException {
        String name = "GearCam_" + sessionName + "_master.wav";
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, name);
            values.put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
            values.put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Music/GearCam");
            values.put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1);
            Uri destination = context.getContentResolver().insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
            if (destination == null) throw new IOException("Cannot create WAV master in Music/GearCam");
            try {
                try (OutputStream out = context.getContentResolver().openOutputStream(destination, "w")) {
                    if (out == null) throw new IOException("Cannot open WAV master destination");
                    copyMaster(source, out);
                }
                values.clear(); values.put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0);
                context.getContentResolver().update(destination, values, null, null);
            } catch (IOException | RuntimeException e) {
                context.getContentResolver().delete(destination, null, null); throw e;
            }
        } else {
            File folder = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "GearCam");
            if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create Music/GearCam");
            File file = new File(folder, name);
            try (OutputStream out = new FileOutputStream(file)) { copyMaster(source, out); }
            android.media.MediaScannerConnection.scanFile(context, new String[] {file.getAbsolutePath()}, new String[] {"audio/wav"}, null);
        }
    }

    private static void copyMaster(File source, OutputStream target) throws IOException {
        try (FileInputStream in = new FileInputStream(source)) {
            byte[] buffer = new byte[256 * 1024]; int count;
            while ((count = in.read(buffer)) >= 0) target.write(buffer, 0, count);
        }
    }

    private static int findTrack(MediaExtractor extractor, String prefix) throws IOException {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        throw new IOException("Missing " + prefix + " track");
    }
}
