package net.sourceforge.opencamera.audio;

/** Stamps captured audio with host time.
 *
 *  <p>A device's sample clock is never exactly the rate it claims, so stamping by counting frames
 *  ("another 48,000 samples, another second") drifts without limit: 0.07% is invisible for minutes and
 *  two seconds out by three quarters of an hour, which walks a take out of the capture buffer and ends
 *  it. The driver records when it actually received each frame, so the stamps are held against that.
 *
 *  <p>The correction adjusts the rate rather than moving a stamp: timestamps must keep increasing, or
 *  the buffer discards the frames that arrive out of order. Stamps running ahead of real time mean each
 *  block advances the clock too far, so the step shrinks; running behind, it grows. */
final class CaptureClock {
    /** Per-block rate change, in nanoseconds per frame. Enough to correct 1% in a few seconds. */
    private static final double MAX_STEP = 0.5;
    /** Rates further from nominal than this are not believed; a device claiming 48 kHz is near it. */
    private static final double TOLERANCE = 0.12;

    private final double nominal;
    private double period;
    private long stampNs;
    private boolean locked;
    /** How far the stamps sit from host time; positive means they run ahead. */
    long driftNs;

    CaptureClock(int rate) { nominal = 1_000_000_000.0 / rate; period = nominal; }

    double period() { return period; }
    boolean locked() { return locked; }

    /** Take the device's own rate once at the start, rather than easing in from nominal. */
    void lock(double measuredPeriodNs) {
        if (locked || !believable(measuredPeriodNs)) return;
        period = measuredPeriodNs;
        locked = true;
    }

    boolean believable(double periodNs) {
        return periodNs > nominal * (1 - TOLERANCE) && periodNs < nominal * (1 + TOLERANCE);
    }

    /** @param count frames in this block
     *  @param receivedNs when the driver received this block's first frame, by the host clock
     *  @return the timestamp for the block's first frame */
    long stamp(int count, long receivedNs) {
        if (stampNs == 0) stampNs = receivedNs; // the take starts where the audio actually arrived
        long first = stampNs;
        driftNs = first - receivedNs;
        period -= Math.max(-MAX_STEP, Math.min(MAX_STEP, driftNs * 1e-7));
        period = Math.max(nominal * (1 - TOLERANCE), Math.min(nominal * (1 + TOLERANCE), period));
        stampNs = first + Math.round(count * period);
        return first;
    }
}
