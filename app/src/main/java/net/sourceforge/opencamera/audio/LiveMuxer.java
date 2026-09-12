package net.sourceforge.opencamera.audio;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** An MP4 written while recording, fed by independent encoders (one track each, from any thread).
 *  MediaMuxer can start only once every track's format is known, so the first samples of the quicker
 *  encoder wait in memory until then. The file is complete when every track has finished. */
public final class LiveMuxer {
    public static final int VIDEO = 0, AUDIO = 1;
    private static final long MAX_PENDING_BYTES = 64L << 20;
    private final MediaMuxer muxer;
    private final MediaFormat[] formats;
    private final int[] tracks;
    private final boolean[] finished, absent;
    private final List<Object[]> pending = new ArrayList<>(); // {slot, ByteBuffer, BufferInfo}
    private final CountDownLatch done = new CountDownLatch(1);
    private long pendingBytes, written;
    private boolean started, closed;
    private String failure;

    public LiveMuxer(String path, int trackCount) throws IOException {
        this(new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), trackCount);
    }

    @androidx.annotation.RequiresApi(26)
    public LiveMuxer(FileDescriptor fd, int trackCount) throws IOException {
        this(new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), trackCount);
    }

    private LiveMuxer(MediaMuxer muxer, int trackCount) {
        this.muxer = muxer;
        formats = new MediaFormat[trackCount]; tracks = new int[trackCount];
        finished = new boolean[trackCount]; absent = new boolean[trackCount];
    }

    /** Before any format arrives. */
    public void setOrientationHint(int degrees) { muxer.setOrientationHint(degrees); }
    public void setLocation(float latitude, float longitude) { muxer.setLocation(latitude, longitude); }

    public synchronized long bytesWritten() { return written; }

    synchronized void format(int slot, MediaFormat format) throws IOException {
        if (formats[slot] != null) throw new IOException("Encoder format changed while recording");
        formats[slot] = format;
        startIfReady();
    }

    synchronized void write(int slot, ByteBuffer data, MediaCodec.BufferInfo info) throws IOException {
        if (closed) return;
        if (started) {
            muxer.writeSampleData(tracks[slot], data, info);
        } else {
            if (pendingBytes + info.size > MAX_PENDING_BYTES) throw new IOException("An encoder never started");
            ByteBuffer copy = ByteBuffer.allocateDirect(info.size);
            copy.put(data).flip();
            MediaCodec.BufferInfo held = new MediaCodec.BufferInfo();
            held.set(0, info.size, info.presentationTimeUs, info.flags);
            pending.add(new Object[] {slot, copy, held});
            pendingBytes += info.size;
        }
        written += info.size;
    }

    /** The track's encoder is done. A track that never produced a format is left out. */
    synchronized void finish(int slot) {
        if (finished[slot] || closed) return;
        finished[slot] = true;
        if (formats[slot] == null) absent[slot] = true;
        try {
            startIfReady();
            for (boolean f : finished) if (!f) return;
            if (!started) throw new IOException("No video or audio was recorded");
            muxer.stop();
        } catch (IOException | RuntimeException e) {
            failure = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        close();
    }

    /** Gives up on the file (a start failure); the caller deletes it. */
    public synchronized void abandon() {
        if (closed) return;
        if (failure == null) failure = "Recording was abandoned";
        close();
    }

    /** Waits until the file is complete. */
    public void await(long timeoutMs) throws IOException {
        try {
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new IOException("The recording did not finish writing");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("Interrupted while finishing the recording");
        }
        synchronized (this) { if (failure != null) throw new IOException(failure); }
    }

    private void startIfReady() throws IOException {
        if (started) return;
        for (int s = 0; s < formats.length; s++) if (formats[s] == null && !absent[s]) return;
        for (int s = 0; s < formats.length; s++) if (!absent[s]) tracks[s] = muxer.addTrack(formats[s]);
        muxer.start();
        started = true;
        for (Object[] sample : pending) muxer.writeSampleData(tracks[(int) sample[0]], (ByteBuffer) sample[1], (MediaCodec.BufferInfo) sample[2]);
        pending.clear(); pendingBytes = 0;
    }

    private void close() {
        if (closed) return;
        closed = true;
        pending.clear();
        try { muxer.release(); } catch (RuntimeException ignored) { }
        done.countDown();
    }
}
