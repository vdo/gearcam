package net.sourceforge.opencamera.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class UsbAudioDescriptorTest {
    private static final byte[] UAC1_PREFIX = {
            9, 4, 0, 0, 0, 1, 1, 0, 0,
            9, 0x24, 1, 0, 1, 0, 0, 0, 0,
            9, 4, 1, 1, 1, 1, 2, 0, 0
    };
    private static final byte[] UAC1_IN = {9, 5, (byte) 0x81, 5, 64, 0, 1, 0, 0};
    private static final byte[] UAC1_OUT = {9, 5, 1, 9, 64, 0, 1, 0, 0};
    private static final byte[] STEREO_48K = {11, 0x24, 2, 1, 2, 2, 16, 1, (byte) 0x80, (byte) 0xbb, 0};

    /** 1010music Bluebox (368e:0017), UAC2: AC header, then 12-channel playback with an IN feedback endpoint. */
    private static final byte[] BLUEBOX_PLAYBACK = hex(
            "09 04 00 00 00 01 01 20 00  09 24 01 00 02 08 53 00 00"             // AC interface, UAC2 header
            + "09 04 01 00 00 01 02 20 00  09 04 01 01 02 01 02 20 00"           // AS alt 0, alt 1
            + "10 24 01 03 00 01 01 00 00 00 0c 00 00 00 00 00  06 24 02 01 03 18" // 12 ch PCM, 24-bit
            + "07 05 01 05 00 01 01  08 25 01 00 00 00 00 00  07 05 81 11 04 00 04"); // OUT data, IN feedback
    /** Its 18-channel, 24-bit capture interface. */
    private static final byte[] BLUEBOX_CAPTURE = hex(
            "09 04 02 00 00 01 02 20 00  09 04 02 01 01 01 02 20 00"
            + "10 24 01 02 00 01 01 00 00 00 12 00 00 00 00 00  06 24 02 01 03 18"
            + "07 05 82 05 80 01 01  08 25 01 00 00 00 00 00");

    @Test public void detectsStereo48kDiscreteFormat() {
        assertEquals(2, MixerSettings.usbCaptureChannels(join(UAC1_PREFIX, STEREO_48K, UAC1_IN)));
    }

    @Test public void rejectsFormatWithout48k() {
        byte[] format = {11, 0x24, 2, 1, 2, 2, 16, 1, 0x44, (byte) 0xac, 0};
        assertEquals(0, MixerSettings.usbCaptureChannels(join(UAC1_PREFIX, format, UAC1_IN)));
    }

    @Test public void ignoresUac1Playback() {
        assertEquals(0, MixerSettings.usbCaptureChannels(join(UAC1_PREFIX, STEREO_48K, UAC1_OUT)));
    }

    @Test public void readsUac2CaptureNotPlaybackFeedback() {
        assertEquals(18, MixerSettings.usbCaptureChannels(join(BLUEBOX_PLAYBACK, BLUEBOX_CAPTURE)));
        assertEquals(0, MixerSettings.usbCaptureChannels(BLUEBOX_PLAYBACK));
    }

    private static byte[] join(byte[]... parts) {
        java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) result.write(part, 0, part.length);
        return result.toByteArray();
    }

    private static byte[] hex(String value) {
        value = value.replaceAll("\\s", "");
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(value.substring(2 * i, 2 * i + 2), 16);
        return result;
    }
}
