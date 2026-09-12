package net.sourceforge.opencamera.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MixerFaderTest {
    @Test public void zeroDetentHoldsUnityAndKeepsEveryGainReachable() {
        // 168 px of track for 84 dB: 0.5 dB per pixel, with a 6 px detent around 0 dB.
        assertEquals(0, MixerFader.detentGain(6, 6, 168), 0);
        assertEquals(0, MixerFader.detentGain(-5, 6, 168), 0);
        assertEquals(-0.5, MixerFader.detentGain(7, 6, 168), 1e-6); // one pixel past it continues from 0 dB
        assertEquals(0.5, MixerFader.detentGain(-7, 6, 168), 1e-6);
        assertEquals(-60, MixerFader.detentGain(126, 6, 168), 1e-6); // the ends stay reachable
    }
}
