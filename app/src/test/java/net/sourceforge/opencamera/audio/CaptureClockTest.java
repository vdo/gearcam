package net.sourceforge.opencamera.audio;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertTrue;

/** An hour of capture, simulated: the stamps must stay with the host clock whatever the device's rate. */
public class CaptureClockTest {
    private static final int RATE = 48000, BLOCK = 480;

    /** @param deviceRate frames the device really produces per second of host time
     *  @param jitterMs   spread of arrival times, as a real USB stream has
     *  @return the worst drift seen over an hour, in milliseconds */
    private static double worstDriftMs(double deviceRate, double jitterMs, boolean lockToMeasured) {
        CaptureClock clock = new CaptureClock(RATE);
        Random random = new Random(11);
        double hostNs = 0;                       // host clock
        long produced = 0;                       // frames the device has produced
        double worst = 0;
        if (lockToMeasured) clock.lock(1e9 / deviceRate);
        for (int block = 0; block < 3600 * RATE / BLOCK; block++) {
            produced += BLOCK;
            hostNs = produced / deviceRate * 1e9; // the block's first frame arrived here
            long receivedNs = Math.round(hostNs - BLOCK / deviceRate * 1e9 + random.nextGaussian() * jitterMs * 1e6);
            long stamp = clock.stamp(BLOCK, receivedNs);
            double driftMs = (stamp - (hostNs - BLOCK / deviceRate * 1e9)) / 1e6;
            if (block > RATE / BLOCK * 10 && Math.abs(driftMs) > worst) worst = Math.abs(driftMs); // after settling
        }
        return worst;
    }

    @Test public void staysWithTheHostClockForAnHour() {
        for (double error : new double[] {0, 0.0007, -0.0007, 0.01, -0.01, 0.05}) {
            double worst = worstDriftMs(RATE * (1 + error), 0.5, true);
            assertTrue(String.format("device %+.2f%% off drifted %.0f ms in an hour", error * 100, worst), worst < 150);
        }
    }

    @Test public void recoversWhenTheRateIsNotKnownAtTheStart() {
        // no lock(): the clock starts at nominal while the device runs 1% slow
        double worst = worstDriftMs(RATE * 0.99, 0.5, false);
        assertTrue("drifted " + worst + " ms without an initial rate", worst < 150);
    }

    @Test public void survivesRoughArrivalTimes() {
        double worst = worstDriftMs(RATE * 1.002, 5, true); // 5 ms of jitter
        assertTrue("jitter drove it to " + worst + " ms", worst < 200);
    }

    @Test public void stampsNeverGoBackwards() {
        CaptureClock clock = new CaptureClock(RATE);
        Random random = new Random(3);
        long previous = Long.MIN_VALUE;
        double hostNs = 0;
        for (int block = 0; block < 100000; block++) {
            hostNs += BLOCK / (RATE * 0.995) * 1e9;
            long stamp = clock.stamp(BLOCK, Math.round(hostNs + random.nextGaussian() * 2e6));
            assertTrue("stamps went backwards at block " + block, stamp > previous);
            previous = stamp;
        }
    }
}
