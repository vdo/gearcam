package net.sourceforge.opencamera.audio;

import android.os.ParcelFileDescriptor;
import java.io.IOException;

/** Bundled LAME / libFLAC encoders and PCM24 WAV, all fed from the same stereo mix. */
final class AudioFileEncoder implements AutoCloseable {
    static { System.loadLibrary("gearcam_audio"); }
    private WavMaster wav;
    private long nativeHandle;
    AudioFileEncoder(ParcelFileDescriptor fd, String format) throws IOException {
        if ("wav".equals(format)) wav = new WavMaster(new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(fd.getFileDescriptor())));
        else if ("mp3".equals(format) || "flac".equals(format)) nativeHandle = nativeOpen(fd.getFd(), "flac".equals(format));
        else throw new IOException("Unknown audio format: " + format);
    }
    void write(float[] samples, int frames) throws IOException {
        if (wav != null) wav.write(samples, frames); else nativeWrite(nativeHandle, samples, frames);
    }
    @Override public void close() throws IOException {
        if (wav != null) { WavMaster closing = wav; wav = null; closing.close(); }
        if (nativeHandle != 0) { long closing = nativeHandle; nativeHandle = 0; nativeFinish(closing); }
    }
    private static native long nativeOpen(int descriptor, boolean flac) throws IOException;
    private static native void nativeWrite(long handle, float[] pcm, int frames) throws IOException;
    private static native void nativeFinish(long handle) throws IOException;
}
