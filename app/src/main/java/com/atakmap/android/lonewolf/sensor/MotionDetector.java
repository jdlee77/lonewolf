package com.atakmap.android.lonewolf.sensor;

/**
 * Adaptive motion detector fed with raw accelerometer samples (m/s^2,
 * including gravity). Pure Java, so it can be unit tested on the JVM.
 *
 * <h3>How the sensitivity adapts to activity</h3>
 * Samples are grouped into 0.5 s windows. Each window gets two measurements:
 * <ul>
 *   <li><b>Energy</b>: RMS of linear acceleration (gravity removed by a
 *   low-pass filter).</li>
 *   <li><b>Tilt</b>: the largest change in the gravity direction inside the
 *   window, i.e. how much the phone was turned.</li>
 * </ul>
 * A window counts as "moving" when the energy is above an adaptive threshold
 * or the tilt is above a tilt threshold.
 * <p>
 * The energy threshold is {@code noiseFloor * multiplier + delta}. The noise
 * floor tracks the background vibration level:
 * <ul>
 *   <li>A phone at rest has a very low floor, so small movements count.</li>
 *   <li>A phone in a vehicle, on running machinery or on a vibrating surface
 *   sees steady vibration: high energy, low variability, no tilt. That is
 *   recognised as {@link ActivityLevel#VIBRATION} and absorbed into the noise
 *   floor quickly, so machine vibration cannot keep resetting the inactivity
 *   timer while the worker is actually motionless.</li>
 *   <li>Human movement is bursty and turns the phone, so it stays above the
 *   threshold or trips the tilt test, even on top of vibration.</li>
 * </ul>
 * A window that is not moving slowly pulls the floor back down when the
 * vibration stops.
 * <p>
 * "Sustained motion" is reported once moving windows have run for at least
 * {@code minMotionMs} (default 2 s). A single quiet window inside a run is
 * tolerated.
 */
public class MotionDetector {

    public enum Sensitivity {
        LOW(3.5, 0.60, 25.0),
        MEDIUM(2.5, 0.30, 15.0),
        HIGH(1.8, 0.15, 8.0);

        final double multiplier;
        final double delta;
        final double tiltDeg;

        Sensitivity(double multiplier, double delta, double tiltDeg) {
            this.multiplier = multiplier;
            this.delta = delta;
            this.tiltDeg = tiltDeg;
        }
    }

    public enum ActivityLevel { STILL, LIGHT, ACTIVE, VIBRATION }

    public interface Listener {
        /** Motion has lasted at least the configured duration. Rate limited to 1/s. */
        void onSustainedMotion(long timeMs);

        void onActivityLevelChanged(ActivityLevel level, long timeMs);
    }

    static final long WINDOW_MS = 500;
    private static final int HISTORY = 20; // 10 s of windows
    private static final double GRAVITY_ALPHA = 0.9;
    private static final double MIN_FLOOR = 0.02;
    private static final double MAX_FLOOR = 3.0;
    private static final double VIBRATION_CV = 0.20;
    private static final int LEVEL_HYSTERESIS = 3;

    private Sensitivity sensitivity;
    private long minMotionMs;
    private final Listener listener;

    // gravity estimate
    private double gx, gy, gz;
    private boolean gravityInit;

    // current window
    private long windowStart = -1;
    private double sumSq;
    private int count;
    private double wsx, wsy, wsz; // unit gravity at window start
    private double minDot;

    // adaptive state
    private double noiseFloor = 0.05;
    private final double[] rmsHist = new double[HISTORY];
    private final boolean[] tiltHist = new boolean[HISTORY];
    private int histPos, histCount;

    // run tracking
    private long runStart = -1;
    private int gapWindows;
    private long lastReport = Long.MIN_VALUE / 2;

    private ActivityLevel level = ActivityLevel.STILL;
    private ActivityLevel candidateLevel = ActivityLevel.STILL;
    private int candidateCount;

    private double lastRms;
    private double lastTilt;
    private boolean lastMoving;

    public MotionDetector(Sensitivity sensitivity, long minMotionMs, Listener listener) {
        this.sensitivity = sensitivity;
        this.minMotionMs = minMotionMs;
        this.listener = listener;
    }

    public synchronized void setSensitivity(Sensitivity s) {
        this.sensitivity = s;
    }

    public synchronized void setMinMotionMs(long ms) {
        this.minMotionMs = ms;
    }

    /** Feed one accelerometer sample. */
    public synchronized void onSample(long timeMs, double ax, double ay, double az) {
        if (!gravityInit) {
            gx = ax;
            gy = ay;
            gz = az;
            gravityInit = true;
        } else {
            gx = GRAVITY_ALPHA * gx + (1 - GRAVITY_ALPHA) * ax;
            gy = GRAVITY_ALPHA * gy + (1 - GRAVITY_ALPHA) * ay;
            gz = GRAVITY_ALPHA * gz + (1 - GRAVITY_ALPHA) * az;
        }
        double lx = ax - gx, ly = ay - gy, lz = az - gz;
        double gn = Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (gn < 1e-6)
            gn = 1e-6;
        double ux = gx / gn, uy = gy / gn, uz = gz / gn;

        if (windowStart < 0) {
            startWindow(timeMs, ux, uy, uz);
        } else if (timeMs - windowStart >= WINDOW_MS) {
            closeWindow(timeMs);
            startWindow(timeMs, ux, uy, uz);
        }

        sumSq += lx * lx + ly * ly + lz * lz;
        count++;
        double dot = ux * wsx + uy * wsy + uz * wsz;
        if (dot < minDot)
            minDot = dot;
    }

    private void startWindow(long t, double ux, double uy, double uz) {
        windowStart = t;
        sumSq = 0;
        count = 0;
        wsx = ux;
        wsy = uy;
        wsz = uz;
        minDot = 1.0;
    }

    private void closeWindow(long windowEnd) {
        if (count == 0)
            return;
        double rms = Math.sqrt(sumSq / count);
        double tilt = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, minDot))));
        boolean tilted = tilt > sensitivity.tiltDeg;

        rmsHist[histPos] = rms;
        tiltHist[histPos] = tilted;
        histPos = (histPos + 1) % HISTORY;
        if (histCount < HISTORY)
            histCount++;

        double threshold = noiseFloor * sensitivity.multiplier + sensitivity.delta;
        boolean energetic = rms > threshold;
        boolean vibration = false;

        if (energetic && !tilted && histCount == HISTORY && !anyTilt()
                && coefficientOfVariation() < VIBRATION_CV) {
            // Steady, tilt-free vibration: absorb it into the floor quickly.
            vibration = true;
            energetic = false;
            noiseFloor += (rms - noiseFloor) * 0.10;
        } else if (!energetic && !tilted) {
            noiseFloor += (rms - noiseFloor) * 0.02;
        }
        noiseFloor = Math.max(MIN_FLOOR, Math.min(MAX_FLOOR, noiseFloor));

        boolean moving = energetic || tilted;
        lastRms = rms;
        lastTilt = tilt;
        lastMoving = moving;

        if (moving) {
            if (runStart < 0)
                runStart = windowStart;
            gapWindows = 0;
            if (windowEnd - runStart >= minMotionMs && windowEnd - lastReport >= 1000) {
                lastReport = windowEnd;
                if (listener != null)
                    listener.onSustainedMotion(windowEnd);
            }
        } else {
            gapWindows++;
            if (gapWindows > 1)
                runStart = -1;
        }

        updateLevel(vibration, windowEnd);
    }

    private boolean anyTilt() {
        for (int i = 0; i < histCount; i++)
            if (tiltHist[i])
                return true;
        return false;
    }

    private double mean() {
        double s = 0;
        for (int i = 0; i < histCount; i++)
            s += rmsHist[i];
        return histCount == 0 ? 0 : s / histCount;
    }

    private double coefficientOfVariation() {
        double m = mean();
        if (m < 1e-9)
            return 0;
        double v = 0;
        for (int i = 0; i < histCount; i++) {
            double d = rmsHist[i] - m;
            v += d * d;
        }
        return Math.sqrt(v / histCount) / m;
    }

    private void updateLevel(boolean vibration, long t) {
        ActivityLevel l;
        double m = mean();
        if (vibration)
            l = ActivityLevel.VIBRATION;
        else if (m > 2.0 || (anyTilt() && m > 1.0))
            l = ActivityLevel.ACTIVE;
        else if (anyTilt() || m > Math.max(0.15, noiseFloor * sensitivity.multiplier + sensitivity.delta))
            l = ActivityLevel.LIGHT;
        else if (level == ActivityLevel.VIBRATION && m > 0.15)
            l = ActivityLevel.VIBRATION;
        else
            l = ActivityLevel.STILL;

        if (l == candidateLevel) {
            candidateCount++;
        } else {
            candidateLevel = l;
            candidateCount = 1;
        }
        if (candidateCount >= LEVEL_HYSTERESIS && l != level) {
            level = l;
            if (listener != null)
                listener.onActivityLevelChanged(l, t);
        }
    }

    // ---------------------------------------------------------- diagnostics

    public synchronized ActivityLevel getActivityLevel() {
        return level;
    }

    public synchronized double getNoiseFloor() {
        return noiseFloor;
    }

    public synchronized double getThreshold() {
        return noiseFloor * sensitivity.multiplier + sensitivity.delta;
    }

    public synchronized double getLastRms() {
        return lastRms;
    }

    public synchronized double getLastTiltDeg() {
        return lastTilt;
    }

    public synchronized boolean isLastWindowMoving() {
        return lastMoving;
    }
}
