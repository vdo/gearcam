package net.sourceforge.opencamera.ui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BatteryGuardTest {
    @Test public void stopsOnTheLastFewPercent() {
        assertTrue(BatteryGuard.shouldStop(3, 100, 0));
        assertTrue(BatteryGuard.shouldStop(1, 100, 0));
        assertTrue(BatteryGuard.shouldStop(30, 1000, 0)); // scales other than 100 exist
    }

    @Test public void keepsRecordingOtherwise() {
        assertFalse(BatteryGuard.shouldStop(4, 100, 0));
        assertFalse(BatteryGuard.shouldStop(80, 100, 0));
        assertFalse(BatteryGuard.shouldStop(2, 100, 1)); // charging: it is going back up
        assertFalse(BatteryGuard.shouldStop(-1, -1, 0)); // no reading yet
    }
}
