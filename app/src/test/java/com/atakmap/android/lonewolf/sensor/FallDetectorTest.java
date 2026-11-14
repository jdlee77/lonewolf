package com.atakmap.android.lonewolf.sensor;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class FallDetectorTest {

    private static final double G = FallDetector.G;
    private final List<Long> falls = new ArrayList<>();

    private FallDetector detector(double impactG) {
        return new FallDetector(impactG, (t, peak) -> falls.add(t));
    }

    private static void feedG(FallDetector d, long from, long to, double g) {
        for (long t = from; t < to; t += 20)
            d.onSample(t, 0, 0, g * G);
    }

    @Test
    public void freefallThenImpactIsDetected() {
        FallDetector d = detector(2.5);
        feedG(d, 0, 2000, 1.0);
        feedG(d, 2000, 2300, 0.1);   // 300 ms free-fall
        feedG(d, 2300, 2400, 1.2);
        feedG(d, 2400, 2440, 4.0);   // impact
        feedG(d, 2440, 5000, 1.0);
        assertEquals(1, falls.size());
    }

    @Test
    public void impactWithoutFreefallIsIgnored() {
        FallDetector d = detector(2.5);
        feedG(d, 0, 2000, 1.0);
        feedG(d, 2000, 2040, 5.0);
        feedG(d, 2040, 4000, 1.0);
        assertEquals(0, falls.size());
    }

    @Test
    public void tooShortFreefallIsIgnored() {
        FallDetector d = detector(2.5);
        feedG(d, 0, 1000, 1.0);
        feedG(d, 1000, 1040, 0.1);   // 40 ms
        feedG(d, 1040, 1080, 4.0);
        feedG(d, 1080, 3000, 1.0);
        assertEquals(0, falls.size());
    }

    @Test
    public void impactBelowThresholdIsIgnored() {
        FallDetector d = detector(3.0);
        feedG(d, 0, 1000, 1.0);
        feedG(d, 1000, 1300, 0.1);
        feedG(d, 1300, 1340, 2.5);
        feedG(d, 1340, 3000, 1.0);
        assertEquals(0, falls.size());
    }

    @Test
    public void impactTooLateAfterFreefallIsIgnored() {
        FallDetector d = detector(2.5);
        feedG(d, 0, 1000, 1.0);
        feedG(d, 1000, 1300, 0.1);
        feedG(d, 1300, 2600, 1.0);   // 1.3 s gap
        feedG(d, 2600, 2640, 4.0);
        feedG(d, 2640, 4000, 1.0);
        assertEquals(0, falls.size());
    }

    @Test
    public void walkingDoesNotTriggerFall() {
        FallDetector d = detector(2.0);
        for (long t = 0; t < 60_000; t += 20) {
            double s = t / 1000.0;
            double g = 1.0 + 0.4 * Math.sin(2 * Math.PI * 2 * s);
            d.onSample(t, 0, 0, g * G);
        }
        assertEquals(0, falls.size());
    }
}
