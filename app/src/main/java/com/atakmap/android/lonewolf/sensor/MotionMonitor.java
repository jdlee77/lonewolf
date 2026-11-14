package com.atakmap.android.lonewolf.sensor;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.atakmap.android.lonewolf.log.EventLog;

/**
 * Binds the Android accelerometer to {@link MotionDetector} and
 * {@link FallDetector}. Sensor processing happens on a background thread;
 * results are delivered to the supplied main-thread handler.
 */
public class MotionMonitor implements SensorEventListener {

    public interface Callback {
        void onSustainedMotion(long wallTimeMs);

        void onFallCandidate(long wallTimeMs, double peakG);

        void onActivityLevelChanged(MotionDetector.ActivityLevel level);
    }

    private static final int SAMPLING_PERIOD_US = 20_000; // 50 Hz
    private static final int MAX_REPORT_LATENCY_US = 0;   // no batching: we need timely events

    private final SensorManager sensorManager;
    private final Handler mainHandler;
    private final Callback callback;
    private final MotionDetector motion;
    private final FallDetector fall;

    private HandlerThread thread;
    private boolean running;
    private long lastSampleElapsed;

    public MotionMonitor(Context context, Handler mainHandler, Callback callback,
            MotionDetector.Sensitivity sensitivity, long minMotionMs) {
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.mainHandler = mainHandler;
        this.callback = callback;
        this.motion = new MotionDetector(sensitivity, minMotionMs, new MotionDetector.Listener() {
            @Override
            public void onSustainedMotion(long timeMs) {
                final long wall = toWall(timeMs);
                MotionMonitor.this.mainHandler.post(() -> MotionMonitor.this.callback.onSustainedMotion(wall));
            }

            @Override
            public void onActivityLevelChanged(MotionDetector.ActivityLevel level, long timeMs) {
                MotionMonitor.this.mainHandler.post(() -> MotionMonitor.this.callback.onActivityLevelChanged(level));
            }
        });
        this.fall = new FallDetector(FallDetector.impactGFor(sensitivity), (t, peakG) -> {
            final long wall = toWall(t);
            MotionMonitor.this.mainHandler.post(() -> MotionMonitor.this.callback.onFallCandidate(wall, peakG));
        });
    }

    /** Detector timestamps are elapsed-realtime ms; convert to wall clock. */
    private static long toWall(long elapsedMs) {
        return System.currentTimeMillis() - (SystemClock.elapsedRealtime() - elapsedMs);
    }

    public synchronized boolean start() {
        if (running)
            return true;
        Sensor accel = sensorManager == null ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel == null) {
            EventLog.w("SENSOR", "No accelerometer available on this device");
            return false;
        }
        thread = new HandlerThread("lonewolf-sensor");
        thread.start();
        boolean ok = sensorManager.registerListener(this, accel, SAMPLING_PERIOD_US,
                MAX_REPORT_LATENCY_US, new Handler(thread.getLooper()));
        running = ok;
        EventLog.i("SENSOR", "Accelerometer " + (ok ? "started" : "FAILED to start")
                + " (" + accel.getName() + ", " + accel.getVendor() + ")");
        if (!ok) {
            thread.quitSafely();
            thread = null;
        }
        return ok;
    }

    public synchronized void stop() {
        if (!running)
            return;
        sensorManager.unregisterListener(this);
        if (thread != null)
            thread.quitSafely();
        thread = null;
        running = false;
        EventLog.i("SENSOR", "Accelerometer stopped");
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public void setSensitivity(MotionDetector.Sensitivity s) {
        motion.setSensitivity(s);
        fall.setImpactG(FallDetector.impactGFor(s));
    }

    public void setMinMotionMs(long ms) {
        motion.setMinMotionMs(ms);
    }

    public MotionDetector.ActivityLevel getActivityLevel() {
        return motion.getActivityLevel();
    }

    /** Elapsed time since the last sample; used as a sensor watchdog. */
    public long msSinceLastSample() {
        return lastSampleElapsed == 0 ? -1 : SystemClock.elapsedRealtime() - lastSampleElapsed;
    }

    public String diagnostics() {
        return String.format(java.util.Locale.US, "rms=%.2f thr=%.2f floor=%.2f tilt=%.0f° %s",
                motion.getLastRms(), motion.getThreshold(), motion.getNoiseFloor(),
                motion.getLastTiltDeg(), motion.getActivityLevel());
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // event.timestamp is elapsedRealtimeNanos on modern devices
        long t = event.timestamp / 1_000_000L;
        lastSampleElapsed = SystemClock.elapsedRealtime();
        float x = event.values[0], y = event.values[1], z = event.values[2];
        motion.onSample(t, x, y, z);
        fall.onSample(t, x, y, z);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
