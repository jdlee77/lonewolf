package com.atakmap.android.lonewolf.sensor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Feeds synthetic 50 Hz accelerometer traces into the detectors.
 */
public class MotionDetectorTest {

    private static final double G = 9.80665;
    private static final long DT = 20; // 50 Hz

    interface Signal {
        /** @return {ax, ay, az} at time t (ms) */
        double[] at(long t);
    }

    private final List<Long> events = new ArrayList<>();
    private final List<MotionDetector.ActivityLevel> levels = new ArrayList<>();

    private MotionDetector detector(MotionDetector.Sensitivity s) {
        return new MotionDetector(s, 2000, new MotionDetector.Listener() {
            @Override
            public void onSustainedMotion(long timeMs) {
                events.add(timeMs);
            }

            @Override
            public void onActivityLevelChanged(MotionDetector.ActivityLevel level, long timeMs) {
                levels.add(level);
            }
        });
    }

    private static void run(MotionDetector d, Signal s, long from, long to) {
        for (long t = from; t < to; t += DT) {
            double[] a = s.at(t);
            d.onSample(t, a[0], a[1], a[2]);
        }
    }

    private static Signal still(long seed) {
        final Random r = new Random(seed);
        return t -> new double[] {
                r.nextGaussian() * 0.03, r.nextGaussian() * 0.03, G + r.nextGaussian() * 0.03
        };
    }

    /** Walking: 2 Hz vertical bounce plus 1 Hz body sway that tilts the phone. */
    private static Signal walking(double bounce, double swayDeg, long seed) {
        final Random r = new Random(seed);
        return t -> {
            double s = t / 1000.0;
            double theta = Math.toRadians(swayDeg) * Math.sin(2 * Math.PI * 1.0 * s);
            double lin = bounce * Math.sin(2 * Math.PI * 2.0 * s);
            return new double[] {
                    G * Math.sin(theta) + r.nextGaussian() * 0.1,
                    r.nextGaussian() * 0.1,
                    G * Math.cos(theta) + lin + r.nextGaussian() * 0.1
            };
        };
    }

    /** Steady machine / vehicle vibration with no tilt. */
    private static Signal vibration(double amp, long seed) {
        final Random r = new Random(seed);
        return t -> {
            double s = t / 1000.0;
            double v = amp * Math.sin(2 * Math.PI * 15.3 * s);
            return new double[] {
                    0.3 * v + r.nextGaussian() * 0.05,
                    0.2 * v + r.nextGaussian() * 0.05,
                    G + v + r.nextGaussian() * 0.05
            };
        };
    }

    private static Signal sum(Signal a, Signal b) {
        return t -> {
            double[] x = a.at(t), y = b.at(t);
            return new double[] { x[0] + y[0], x[1] + y[1], x[2] + y[2] - G };
        };
    }

    @Test
    public void stillPhoneNeverReportsMotion() {
        MotionDetector d = detector(MotionDetector.Sensitivity.HIGH);
        run(d, still(1), 0, 10 * 60_000);
        assertEquals(0, events.size());
        assertEquals(MotionDetector.ActivityLevel.STILL, d.getActivityLevel());
    }

    @Test
    public void walkingIsDetectedWithinAboutTwoSeconds() {
        for (MotionDetector.Sensitivity s : MotionDetector.Sensitivity.values()) {
            events.clear();
            MotionDetector d = detector(s);
            run(d, still(2), 0, 30_000);
            assertEquals(0, events.size());
            run(d, walking(2.0, 30, 3), 30_000, 40_000);
            assertTrue(s + " should detect walking", events.size() > 0);
            long first = events.get(0) - 30_000;
            assertTrue(s + " first detection at " + first + "ms", first >= 2000 && first <= 3500);
        }
    }

    @Test
    public void briefBumpUnderTwoSecondsIsIgnored() {
        MotionDetector d = detector(MotionDetector.Sensitivity.HIGH);
        run(d, still(4), 0, 30_000);
        run(d, walking(3.0, 40, 5), 30_000, 31_000); // 1 s of movement
        run(d, still(6), 31_000, 60_000);
        assertEquals(0, events.size());
    }

    @Test
    public void steadyVibrationIsAbsorbedAfterAdapting() {
        MotionDetector d = detector(MotionDetector.Sensitivity.MEDIUM);
        run(d, still(7), 0, 10_000);
        // Ride in a vehicle for 6 minutes with the worker motionless.
        run(d, vibration(1.2, 8), 10_000, 70_000); // adaptation period
        events.clear();
        run(d, vibration(1.2, 9), 70_000, 370_000);
        assertEquals("vibration must not keep resetting the inactivity timer", 0, events.size());
        assertEquals(MotionDetector.ActivityLevel.VIBRATION, d.getActivityLevel());
    }

    @Test
    public void walkingOnTopOfVibrationIsStillDetected() {
        MotionDetector d = detector(MotionDetector.Sensitivity.MEDIUM);
        run(d, vibration(1.2, 10), 0, 120_000);
        events.clear();
        run(d, sum(vibration(1.2, 11), walking(2.0, 30, 12)), 120_000, 130_000);
        assertTrue(events.size() > 0);
    }

    @Test
    public void floorRecoversAfterVibrationStops() {
        MotionDetector d = detector(MotionDetector.Sensitivity.MEDIUM);
        run(d, vibration(1.2, 13), 0, 120_000);
        double high = d.getNoiseFloor();
        run(d, still(14), 120_000, 420_000);
        assertTrue(d.getNoiseFloor() < high / 5);
        events.clear();
        // gentle movement now counts again
        run(d, walking(1.0, 20, 15), 420_000, 430_000);
        assertTrue(events.size() > 0);
    }

    @Test
    public void sustainedMotionIsRateLimited() {
        MotionDetector d = detector(MotionDetector.Sensitivity.MEDIUM);
        run(d, walking(2.0, 30, 16), 0, 60_000);
        // at most one report per second
        assertTrue(events.size() <= 60);
        for (int i = 1; i < events.size(); i++)
            assertTrue(events.get(i) - events.get(i - 1) >= 1000);
    }
}
