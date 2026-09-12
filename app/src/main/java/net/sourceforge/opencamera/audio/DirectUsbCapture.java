package net.sourceforge.opencamera.audio;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import java.io.Closeable;
import java.io.IOException;

/** USB Audio Class input that bypasses Android's single-device audio router. */
final class DirectUsbCapture implements Closeable {
    static {
        System.loadLibrary("gearcam_audio");
    }

    private final UsbDeviceConnection connection;
    private long handle;
    final int channels;

    DirectUsbCapture(Context context, UsbDevice device, int requestedChannels) throws IOException {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (!manager.hasPermission(device))
            throw new IOException("Grant GearCam access to " + MixerSettings.usbLabel(device) + " in the mixer");
        connection = manager.openDevice(device);
        if (connection == null) throw new IOException("Android could not open the USB audio device");
        long opened = 0;
        try {
            opened = nativeOpen(connection.getFileDescriptor(), requestedChannels, MixerSettings.RATE);
            if (opened == 0) throw new IOException("The direct USB driver could not open this device");
            handle = opened;
            channels = nativeChannels(handle);
            nativeStart(handle); // Claim USB before AudioRecord opens the handset mic.
        } catch (IOException | RuntimeException error) {
            if (opened != 0) nativeClose(opened);
            connection.close();
            throw error;
        }
    }

    synchronized int read(float[] samples, int maxFrames, long[] timing) throws IOException {
        return nativeRead(handle, samples, maxFrames, timing);
    }

    synchronized void stop() {
        if (handle != 0) nativeStop(handle);
    }

    @Override public synchronized void close() {
        if (handle != 0) {
            nativeClose(handle);
            handle = 0;
        }
        connection.close();
    }

    private static native long nativeOpen(int fd, int channels, int sampleRate) throws IOException;
    private static native int nativeChannels(long handle);
    private static native void nativeStart(long handle) throws IOException;
    private static native int nativeRead(long handle, float[] samples, int maxFrames, long[] timing) throws IOException;
    private static native void nativeStop(long handle);
    private static native void nativeClose(long handle);
}
