package net.sourceforge.opencamera.audio;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Camera frames → H.264/HEVC → a {@link LiveMuxer} track, drained on its own thread. The camera renders
 *  into {@link #surface}; each frame keeps its sensor time, and the first encoded frame is time zero. */
public final class LiveVideoEncoder {
    public interface Listener {
        /** The first encoded frame, on the camera's sensor clock: the recording's time zero. */
        void firstFrame(long sensorTimeNs);
        /** A MediaRecorder "info" limit: MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED or _MAX_DURATION_REACHED. */
        void limitReached(int what);
        void failed(String message);
    }

    public final Surface surface;
    private final MediaCodec codec;
    private final LiveMuxer muxer;
    private final Listener listener;
    private final long maxBytes, maxDurationUs;
    private final Thread thread;
    private long firstUs = -1;
    private boolean limitReported;

    /** @param codec MediaRecorder.VideoEncoder.H264 or HEVC. maxBytes and maxDurationMs of 0 mean no limit. */
    public LiveVideoEncoder(int codec, int width, int height, int frameRate, int bitRate, LiveMuxer muxer,
                            long maxBytes, long maxDurationMs, Listener listener) throws IOException {
        String mime = codec == MediaRecorder.VideoEncoder.HEVC ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        this.muxer = muxer; this.listener = listener; this.maxBytes = maxBytes; this.maxDurationUs = maxDurationMs * 1000;
        this.codec = MediaCodec.createEncoderByType(mime);
        try {
            this.codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = this.codec.createInputSurface();
            this.codec.start();
        } catch (RuntimeException e) {
            this.codec.release();
            throw new IOException("This phone cannot encode " + width + "×" + height + " " + mime + ": " + e.getMessage(), e);
        }
        thread = new Thread(this::drain, "GearCam video encoder");
        thread.start();
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (!Thread.currentThread().isInterrupted()) { // interrupted: finish() gave up waiting for the end
                int index = codec.dequeueOutputBuffer(info, 100_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { muxer.format(LiveMuxer.VIDEO, codec.getOutputFormat()); continue; }
                if (index < 0) continue;
                boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                try {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        if (firstUs < 0) { firstUs = info.presentationTimeUs; listener.firstFrame(firstUs * 1000); }
                        info.presentationTimeUs -= firstUs;
                        ByteBuffer data = codec.getOutputBuffer(index);
                        data.position(info.offset); data.limit(info.offset + info.size);
                        muxer.write(LiveMuxer.VIDEO, data, info);
                        checkLimits(info.presentationTimeUs);
                    }
                } finally { codec.releaseOutputBuffer(index, false); }
                if (end) break;
            }
        } catch (IOException | RuntimeException e) {
            if (!Thread.currentThread().isInterrupted()) listener.failed("Video encoder: " + e.getMessage());
        } finally {
            muxer.finish(LiveMuxer.VIDEO);
        }
    }

    private void checkLimits(long ptsUs) {
        if (limitReported) return;
        if (maxBytes > 0 && muxer.bytesWritten() >= maxBytes) { limitReported = true; listener.limitReached(MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED); }
        else if (maxDurationUs > 0 && ptsUs >= maxDurationUs) { limitReported = true; listener.limitReached(MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED); }
    }

    /** Ends the video track with the frames the camera has delivered so far. The camera may keep rendering into
     *  the surface until its session changes; those frames are dropped. Call release() after the session change. */
    public void finish() {
        try { codec.signalEndOfInputStream(); } catch (RuntimeException ignored) { }
        try { thread.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (thread.isAlive()) thread.interrupt(); // stuck encoder: finish() the track without its last frames
    }

    public void release() {
        thread.interrupt(); // a failed start: stop draining quietly (after finish() the thread is already done)
        try { thread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { codec.stop(); } catch (RuntimeException ignored) { }
        codec.release();
        surface.release();
    }
}
