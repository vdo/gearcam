package net.sourceforge.opencamera.audio;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MidiTransportTest {
    private static boolean play(int... bytes) {
        byte[] data = new byte[bytes.length + 2]; // padding checks offset/count
        for (int i = 0; i < bytes.length; i++) data[i + 1] = (byte) bytes[i];
        data[0] = data[data.length - 1] = (byte) 0xFA;
        return MidiTransport.isPlay(data, 1, bytes.length);
    }

    @Test public void startAndContinuePlay() {
        assertTrue(play(0xFA));
        assertTrue(play(0xFB));
        assertTrue(play(0x90, 0x3C, 0xF8, 0xFA, 0x64)); // interleaved inside a note-on, after clock
    }

    @Test public void otherMessagesDoNot() {
        assertFalse(play(0xFC)); // stop
        assertFalse(play(0xF8)); // clock
        assertFalse(play(0x90, 0x7A, 0x7B)); // note/velocity data bytes, not status
        assertFalse(play());
    }
}
