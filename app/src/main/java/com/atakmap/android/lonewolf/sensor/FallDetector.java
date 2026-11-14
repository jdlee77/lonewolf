package com.atakmap.android.lonewolf.sensor;

/**
 * Detects the classic "free-fall then impact" signature of a fall from the
 * magnitude of raw acceleration (gravity included). Pure Java.
 * <p>
 * A candidate is only the first half of man-down detection. The state machine
 * then requires the device to remain still for a configurable window before
 * raising the pre-alarm, which filters out a dropped phone that is picked
 * straight back up.
 */
public class FallDetector {

    public interface Listener {
        void onFallCandidate(long timeMs, double peakG);
    }

    public static final double G = 9.80665;

    private static final double FREEFALL_G = 0.45;
    private static final long MIN_FREEFALL_MS = 60;
    private static final long IMPACT_WINDOW_MS = 1000;
    private static final long REFRACTORY_MS = 5000;

    private double impactG;
    private final Listener listener;

    private long freefallStart = -1;
    private long freefallEnd = -1;
    private double peak;
    private long lastFall = Long.MIN_VALUE / 2;

    /** @param impactG impact threshold in g (e.g. 2.0 high, 2.5 medium, 3.0 low sensitivity) */
    public FallDetector(double impactG, Listener listener) {
        this.impactG = impactG;
        this.listener = listener;
    }

    public synchronized void setImpactG(double g) {
        this.impactG = g;
    }

    public static double impactGFor(MotionDetector.Sensitivity s) {
        switch (s) {
            case HIGH:
                return 2.0;
            case LOW:
                return 3.0;
            default:
                return 2.5;
        }
    }

    public synchronized void onSample(long t, double ax, double ay, double az) {
        double g = Math.sqrt(ax * ax + ay * ay + az * az) / G;

        if (g < FREEFALL_G) {
            if (freefallStart < 0)
                freefallStart = t;
            freefallEnd = -1;
            return;
        }

        if (freefallStart >= 0 && freefallEnd < 0) {
            // free-fall just ended
            if (t - freefallStart >= MIN_FREEFALL_MS) {
                freefallEnd = t;
                peak = 0;
            } else {
                freefallStart = -1;
            }
        }

        if (freefallEnd >= 0) {
            if (g > peak)
                peak = g;
            if (g >= impactG && t - lastFall >= REFRACTORY_MS) {
                lastFall = t;
                reset();
                if (listener != null)
                    listener.onFallCandidate(t, g);
            } else if (t - freefallEnd > IMPACT_WINDOW_MS) {
                reset();
            }
        }
    }

    private void reset() {
        freefallStart = -1;
        freefallEnd = -1;
        peak = 0;
    }
}
