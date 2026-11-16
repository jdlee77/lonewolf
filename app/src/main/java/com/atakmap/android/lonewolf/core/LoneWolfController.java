package com.atakmap.android.lonewolf.core;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.preference.PreferenceManager;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.lonewolf.LoneWolfDropDownReceiver;
import com.atakmap.android.lonewolf.alert.LocalAlerter;
import com.atakmap.android.lonewolf.core.AlarmStateMachine.Reason;
import com.atakmap.android.lonewolf.core.AlarmStateMachine.State;
import com.atakmap.android.lonewolf.cot.CotFactory;
import com.atakmap.android.lonewolf.cot.CotReceiver;
import com.atakmap.android.lonewolf.cot.CotSender;
import com.atakmap.android.lonewolf.cot.OutboundQueue;
import com.atakmap.android.lonewolf.cot.Recipients;
import com.atakmap.android.lonewolf.cot.WorkerStatus;
import com.atakmap.android.lonewolf.log.EventLog;
import com.atakmap.android.lonewolf.sensor.MotionDetector;
import com.atakmap.android.lonewolf.sensor.MotionMonitor;
import com.atakmap.android.lonewolf.team.WorkerRegistry;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wires the lone worker state machine to the device and to TAK:
 * accelerometer → state machine → local alerts / CoT emergency.
 * One instance per ATAK session, owned by the map component. All public
 * methods must be called on the main thread.
 */
public class LoneWolfController implements AlarmStateMachine.Listener,
        MotionMonitor.Callback, CotReceiver.Callback {

    public interface UiListener {
        void onLoneWolfChanged();
    }

    private static final String ACTION_WAKE = "com.atakmap.android.lonewolf.WAKE";
    private static final long TICK_MS = 1000;
    private static final long SENSOR_WATCHDOG_MS = 30_000;

    private final MapView mapView;
    private final Context host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LoneWolfSettings settings;
    private final AlarmStateMachine sm;
    private final MotionMonitor motion;
    private final LocalAlerter alerter;
    private final CotSender sender;
    private final CotReceiver receiver;
    private final WorkerRegistry registry = new WorkerRegistry();
    private final List<UiListener> uiListeners = new CopyOnWriteArrayList<>();

    private final PowerManager power;
    private final AlarmManager alarms;
    private PowerManager.WakeLock cpuLock;
    private boolean receiverRegistered;
    private boolean exactAlarmWarned;

    private MotionDetector.ActivityLevel activity = MotionDetector.ActivityLevel.STILL;
    private long lastLoggedMotion;
    private long lastWatchdogRestart;
    private String ackBy = "";
    private long ackAt;
    private long nextRepeatAt;

    public LoneWolfController(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.host = mapView.getContext();
        this.settings = new LoneWolfSettings(PreferenceManager.getDefaultSharedPreferences(host));
        this.sm = new AlarmStateMachine(settings.toConfig());
        this.sm.setListener(this);
        this.motion = new MotionMonitor(host, handler, this, settings.sensitivity(),
                settings.motionSec() * 1000L);
        this.alerter = new LocalAlerter(host, pluginContext, handler);
        this.sender = new CotSender(host, handler, settings);
        this.sender.setOnQueueChanged(this::fireUi);
        this.receiver = new CotReceiver(handler, this);
        this.power = (PowerManager) host.getSystemService(Context.POWER_SERVICE);
        this.alarms = (AlarmManager) host.getSystemService(Context.ALARM_SERVICE);
    }

    // ================================================================ lifecycle

    public void start(File logDir) {
        EventLog.init(logDir);
        EventLog.i("PLUGIN", "Lone Wolf plugin started (callsign " + mapView.getDeviceCallsign()
                + ", uid " + MapView.getDeviceUid() + ")");
        logSettings();
        if (!isIgnoringBatteryOptimizations())
            EventLog.w("PLUGIN", "ATAK is subject to battery optimization; set ATAK battery usage "
                    + "to Unrestricted so monitoring keeps running with the screen off");
        registerActionReceiver();
        sender.start();
        receiver.start();

        if (settings.wasArmed()) {
            State saved = settings.savedState();
            ackBy = settings.prefs().getString(LoneWolfSettings.STATE_ACK_BY, "");
            ackAt = settings.prefs().getLong(LoneWolfSettings.STATE_ACK_AT, 0);
            EventLog.i("STATE", "Previous session was armed (" + saved + "); re-arming automatically");
            long now = System.currentTimeMillis();
            if (saved == State.DISARMED)
                sm.arm(now);
            else
                sm.restore(saved, settings.savedReason(), settings.savedLastCheckIn(), now);
        }
    }

    public void dispose() {
        EventLog.i("PLUGIN", "Lone Wolf plugin stopping (state " + sm.getState()
                + "); state is kept and restored on next start");
        handler.removeCallbacksAndMessages(null);
        motion.stop();
        alerter.dispose();
        receiver.stop();
        sender.stop();
        cancelWake();
        releaseCpu();
        if (receiverRegistered) {
            try {
                host.unregisterReceiver(actionReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiverRegistered = false;
        }
    }

    // ================================================================ user actions

    public void arm() {
        sm.arm(System.currentTimeMillis());
    }

    /** @return false if refused because an alarm is active. */
    public boolean disarm() {
        boolean ok = sm.disarm(System.currentTimeMillis());
        if (!ok)
            EventLog.w("USER", "Disarm refused: an alarm is active and must be cancelled first");
        return ok;
    }

    public void imOk() {
        boolean wasPreAlarm = sm.getState() == State.PRE_ALARM;
        if (sm.imOk(System.currentTimeMillis())) {
            EventLog.i("USER", "I'm OK pressed (check-in recorded)");
            settings.saveSession(true, sm.getState(), sm.getReason(), sm.getLastCheckInMs());
            if (!wasPreAlarm) {
                // a PRE_ALARM -> ARMED transition already refreshed everything
                alerter.showArmed(armedText());
                sendStatus();
                reschedule();
                fireUi();
            }
        }
    }

    public void cancelAlarm() {
        if (sm.cancelAlarm(System.currentTimeMillis()))
            EventLog.i("USER", "Alarm cancelled manually by worker");
    }

    public void acknowledge(WorkerStatus w) {
        CotEvent e = CotFactory.ack(w.uid, w.callsign, MapView.getDeviceUid(),
                mapView.getDeviceCallsign());
        sender.send(e, "ack-" + w.uid, "Acknowledgement to " + w.callsign);
        EventLog.i("TEAM", "Acknowledged alarm of " + w.callsign + " (" + w.uid + ")");
        CotFactory.Ack local = new CotFactory.Ack();
        local.targetUid = w.uid;
        local.byCallsign = mapView.getDeviceCallsign();
        local.atMs = System.currentTimeMillis();
        registry.recordAck(local);
    }

    public void testAlert() {
        EventLog.i("USER", "Local alert test");
        alerter.test(settings);
    }

    /** Re-read settings after the user saved them. */
    public void applySettings() {
        sm.updateConfig(settings.toConfig());
        motion.setSensitivity(settings.sensitivity());
        motion.setMinMotionMs(settings.motionSec() * 1000L);
        logSettings();
        if (sm.getState() != State.DISARMED) {
            alerter.showArmed(armedText());
            sendStatus();
        }
        reschedule();
        fireUi();
    }

    // ================================================================ state machine

    @Override
    public void onStateChanged(State from, State to, Reason reason, String trigger, long now) {
        EventLog.i("STATE", from + " -> " + to + (reason != Reason.NONE ? " (" + reason + ")" : "")
                + ": " + trigger);
        settings.saveSession(to != State.DISARMED, to, reason, sm.getLastCheckInMs());

        if (from == State.DISARMED && to != State.DISARMED)
            startMonitoring();

        switch (to) {
            case ARMED:
                if (from == State.ALARM) {
                    stopAlarmRepeat();
                    sendCancel();
                    setAck("", 0);
                }
                alerter.stopAll();
                alerter.showArmed(armedText());
                break;
            case PRE_ALARM:
                alerter.startPreAlarm(reason, now + sm.getConfig().preAlarmMs, settings);
                openUi();
                break;
            case ALARM:
                alerter.startAlarm(reason, Recipients.describe(mapView, settings), settings);
                startAlarmRepeat();
                openUi();
                break;
            case DISARMED:
                stopMonitoring();
                alerter.stopAll();
                alerter.clearArmed();
                break;
        }
        sendStatus();
        reschedule();
        fireUi();
    }

    @Override
    public void onFallCandidate(long now) {
        EventLog.w("FALL", "Possible fall; pre-alarm in " + settings.fallStillSec()
                + " s unless the worker moves");
        reschedule();
        fireUi();
    }

    @Override
    public void onFallDismissed(long now) {
        EventLog.i("FALL", "Movement after possible fall; fall alert dismissed");
        reschedule();
        fireUi();
    }

    // ================================================================ sensors

    @Override
    public void onSustainedMotion(long wallTimeMs) {
        long prevMotion = sm.getLastMotionMs();
        sm.onSustainedMotion(wallTimeMs);
        if (wallTimeMs - lastLoggedMotion > 60_000) {
            long stillFor = (wallTimeMs - prevMotion) / 1000;
            EventLog.i("MOTION", "Sustained motion (" + settings.motionSec() + " s+) detected"
                    + (stillFor > 5 ? " after " + stillFor + " s without motion" : "")
                    + "; " + motion.diagnostics());
            lastLoggedMotion = wallTimeMs;
        }
        reschedule();
    }

    @Override
    public void onFallCandidate(long wallTimeMs, double peakG) {
        if (sm.getState() == State.ARMED && settings.fallEnabled())
            EventLog.w("FALL", String.format(java.util.Locale.US,
                    "Free-fall + impact detected (peak %.1f g)", peakG));
        sm.onFallCandidate(wallTimeMs);
    }

    @Override
    public void onActivityLevelChanged(MotionDetector.ActivityLevel level) {
        EventLog.i("ACTIVITY", activity + " -> " + level + " (" + motion.diagnostics() + ")");
        activity = level;
        fireUi();
    }

    // ================================================================ received CoT

    @Override
    public void onWorkerStatus(WorkerStatus s, String cotType) {
        String prev = registry.update(s, cotType);
        if (prev == null || !prev.equals(s.state)) {
            String msg = "Worker " + s.callsign + " (" + s.uid + "): "
                    + (prev == null ? "first heard" : prev) + " -> " + s.state
                    + ("NONE".equals(s.reason) ? "" : " (" + s.reason + ")");
            if ("ALARM".equals(s.state))
                EventLog.w("TEAM", msg);
            else
                EventLog.i("TEAM", msg);
        }
    }

    @Override
    public void onAck(CotFactory.Ack ack) {
        registry.recordAck(ack);
        if (!ack.targetUid.equals(MapView.getDeviceUid())) {
            EventLog.i("TEAM", ack.byCallsign + " acknowledged alarm of " + ack.targetCallsign);
            return;
        }
        if (sm.getState() != State.ALARM) {
            EventLog.i("ACK", "Acknowledgement from " + ack.byCallsign + " received while " + sm.getState());
            return;
        }
        EventLog.i("ACK", "Alarm acknowledged by " + ack.byCallsign);
        setAck(ack.byCallsign, ack.atMs);
        alerter.onAcknowledged(ack.byCallsign, settings);
        sendStatus();
        fireUi();
    }

    private void setAck(String by, long at) {
        ackBy = by;
        ackAt = at;
        settings.prefs().edit()
                .putString(LoneWolfSettings.STATE_ACK_BY, by)
                .putLong(LoneWolfSettings.STATE_ACK_AT, at)
                .apply();
    }

    // ================================================================ monitoring

    private void startMonitoring() {
        acquireCpu();
        if (!motion.start())
            EventLog.w("SENSOR", "Motion monitoring unavailable; only timers will work");
        handler.removeCallbacks(tick);
        handler.post(tick);
        handler.removeCallbacks(heartbeat);
        handler.postDelayed(heartbeat, settings.statusSec() * 1000L);
    }

    private void stopMonitoring() {
        motion.stop();
        handler.removeCallbacks(tick);
        handler.removeCallbacks(heartbeat);
        stopAlarmRepeat();
        cancelWake();
        releaseCpu();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            sm.tick(now);
            // Sensor watchdog: some devices stop delivering events in deep idle.
            if (sm.getState() != State.DISARMED && motion.isRunning()
                    && motion.msSinceLastSample() > SENSOR_WATCHDOG_MS
                    && now - lastWatchdogRestart > 60_000) {
                lastWatchdogRestart = now;
                EventLog.w("SENSOR", "No accelerometer samples for "
                        + motion.msSinceLastSample() / 1000 + " s; restarting sensor");
                motion.stop();
                motion.start();
            }
            if (sm.getState() != State.DISARMED) {
                fireUi();
                handler.postDelayed(this, TICK_MS);
            }
        }
    };

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (sm.getState() == State.DISARMED)
                return;
            sendStatus();
            if (sm.getState() == State.ARMED)
                alerter.showArmed(armedText());
            handler.postDelayed(this, settings.statusSec() * 1000L);
        }
    };

    private void startAlarmRepeat() {
        handler.removeCallbacks(alarmRepeat);
        handler.post(alarmRepeat);
    }

    private void stopAlarmRepeat() {
        handler.removeCallbacks(alarmRepeat);
        nextRepeatAt = 0;
    }

    /** Sends the emergency now and every repeat interval with fresh location / battery. */
    private final Runnable alarmRepeat = new Runnable() {
        @Override
        public void run() {
            if (sm.getState() != State.ALARM)
                return;
            sendEmergency();
            long period = settings.repeatSec() * 1000L;
            nextRepeatAt = System.currentTimeMillis() + period;
            handler.postDelayed(this, period);
            reschedule();
        }
    };

    // ================================================================ CoT out

    private WorkerStatus selfStatus() {
        WorkerStatus s = new WorkerStatus();
        s.uid = MapView.getDeviceUid();
        s.callsign = mapView.getDeviceCallsign();
        s.state = sm.getState().name();
        s.reason = sm.getReason().name();
        s.activity = activity.name();
        s.lastMotionMs = sm.getLastMotionMs();
        s.lastCheckInMs = sm.getLastCheckInMs();
        s.nextCheckInMs = sm.getNextCheckInMs();
        s.battery = batteryPercent();
        s.ackBy = ackBy;
        s.ackAtMs = ackAt;
        Marker self = mapView.getSelfMarker();
        if (self != null) {
            GeoPoint p = self.getPoint();
            if (p != null && p.isValid() && !(p.getLatitude() == 0 && p.getLongitude() == 0)) {
                s.lat = p.getLatitude();
                s.lon = p.getLongitude();
            }
        }
        return s;
    }

    private void sendStatus() {
        WorkerStatus s = selfStatus();
        CotEvent e = CotFactory.status(s, settings.statusSec() * 3);
        CotFactory.addMartiDest(e, Recipients.resolve(mapView, settings));
        sender.send(e, OutboundQueue.KEY_STATUS, "Status (" + s.state + ")");
    }

    private void sendEmergency() {
        WorkerStatus s = selfStatus();
        CotEvent e = CotFactory.emergency(s, reasonLabel(sm.getReason()),
                Math.max(120, settings.repeatSec() * 4));
        CotFactory.addMartiDest(e, Recipients.resolve(mapView, settings));
        sender.send(e, OutboundQueue.KEY_EMERGENCY, "EMERGENCY (" + s.reason + ", to "
                + Recipients.describe(mapView, settings) + ", battery " + s.battery + "%, "
                + (s.hasLocation() ? String.format(java.util.Locale.US, "%.5f,%.5f", s.lat, s.lon)
                        : "no location") + ")");
    }

    private void sendCancel() {
        WorkerStatus s = selfStatus();
        CotEvent e = CotFactory.emergencyCancel(s);
        CotFactory.addMartiDest(e, Recipients.resolve(mapView, settings));
        sender.send(e, OutboundQueue.KEY_CANCEL, "Emergency CANCEL");
    }

    public static String reasonLabel(Reason r) {
        switch (r) {
            case CHECK_IN:
                return "Missed check-in";
            case FALL:
                return "Possible fall (man down)";
            case INACTIVITY:
                return "No movement detected";
            default:
                return "Alarm";
        }
    }

    // ================================================================ wake-ups

    private PendingIntent wakeIntent() {
        Intent i = new Intent(ACTION_WAKE).setPackage(host.getPackageName());
        return PendingIntent.getBroadcast(host, 10, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Backstop for the 1 s tick loop: an AlarmManager wake-up at the next
     * deadline so escalation still happens if the CPU sleeps despite the wake
     * lock (e.g. Doze when ATAK is battery-optimized).
     */
    private void reschedule() {
        if (alarms == null)
            return;
        long next = sm.nextDeadline();
        if (sm.getState() == State.ALARM && nextRepeatAt > 0)
            next = Math.min(next, nextRepeatAt);
        if (next == AlarmStateMachine.NO_DEADLINE) {
            cancelWake();
            return;
        }
        PendingIntent pi = wakeIntent();
        try {
            if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) {
                if (!exactAlarmWarned) {
                    exactAlarmWarned = true;
                    EventLog.w("PLUGIN", "Exact alarms not permitted for ATAK; using inexact wake-ups");
                }
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
            } else {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
            }
        } catch (SecurityException e) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
        }
    }

    private void cancelWake() {
        if (alarms != null)
            alarms.cancel(wakeIntent());
    }

    private final BroadcastReceiver actionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (ACTION_WAKE.equals(a)) {
                sm.tick(System.currentTimeMillis());
                if (sm.getState() == State.ALARM && nextRepeatAt > 0
                        && System.currentTimeMillis() >= nextRepeatAt) {
                    handler.removeCallbacks(alarmRepeat);
                    handler.post(alarmRepeat);
                }
                reschedule();
            } else if (LocalAlerter.ACTION_IM_OK.equals(a)) {
                EventLog.i("USER", "I'm OK pressed from notification");
                imOk();
            } else if (LocalAlerter.ACTION_OPEN.equals(a)) {
                openUi();
            }
        }
    };

    private void registerActionReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_WAKE);
        f.addAction(LocalAlerter.ACTION_IM_OK);
        f.addAction(LocalAlerter.ACTION_OPEN);
        if (Build.VERSION.SDK_INT >= 33)
            host.registerReceiver(actionReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else
            host.registerReceiver(actionReceiver, f);
        receiverRegistered = true;
    }

    // ================================================================ helpers

    private void acquireCpu() {
        if (power == null || (cpuLock != null && cpuLock.isHeld()))
            return;
        cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lonewolf:monitor");
        cpuLock.setReferenceCounted(false);
        cpuLock.acquire();
    }

    private void releaseCpu() {
        if (cpuLock != null && cpuLock.isHeld())
            cpuLock.release();
        cpuLock = null;
    }

    public boolean isIgnoringBatteryOptimizations() {
        return power == null || power.isIgnoringBatteryOptimizations(host.getPackageName());
    }

    private int batteryPercent() {
        BatteryManager bm = (BatteryManager) host.getSystemService(Context.BATTERY_SERVICE);
        if (bm == null)
            return -1;
        int v = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return v >= 0 && v <= 100 ? v : -1;
    }

    private String armedText() {
        long next = sm.getNextCheckInMs();
        return "Inactivity " + settings.inactivityMin() + " min"
                + (next > 0 ? " • next check-in " + CotFactory.hhmm(next) : "");
    }

    private void openUi() {
        AtakBroadcast.getInstance().sendBroadcast(new Intent(LoneWolfDropDownReceiver.SHOW));
    }

    private void logSettings() {
        EventLog.i("SETTINGS", "inactivity=" + settings.inactivityMin() + "min preAlarm="
                + settings.preAlarmSec() + "s motion=" + settings.motionSec() + "s sensitivity="
                + settings.sensitivity() + " fall=" + settings.fallEnabled() + "/"
                + settings.fallStillSec() + "s checkIn=" + (settings.checkInEnabled()
                        ? settings.checkInMin() + "min" : "off")
                + " sound=" + settings.alertSound() + " overrideSilent=" + settings.alertOverrideSilent()
                + " vibrate=" + settings.alertVibrate() + " torch=" + settings.alertTorch()
                + " wakeScreen=" + settings.alertWakeScreen() + " siren=" + settings.alarmSiren()
                + " silenceOnAck=" + settings.silenceOnAck() + " repeat=" + settings.repeatSec()
                + "s status=" + settings.statusSec() + "s recipients=" + settings.recipientMode()
                + (settings.recipientMode() == LoneWolfSettings.RecipientMode.CONTACTS
                        ? " " + settings.contacts() : ""));
    }

    // ================================================================ UI access

    public void addUiListener(UiListener l) {
        uiListeners.add(l);
    }

    public void removeUiListener(UiListener l) {
        uiListeners.remove(l);
    }

    private void fireUi() {
        for (UiListener l : uiListeners)
            l.onLoneWolfChanged();
    }

    public AlarmStateMachine stateMachine() {
        return sm;
    }

    public LoneWolfSettings settings() {
        return settings;
    }

    public WorkerRegistry registry() {
        return registry;
    }

    public MapView mapView() {
        return mapView;
    }

    public MotionDetector.ActivityLevel activity() {
        return activity;
    }

    public String sensorDiagnostics() {
        return motion.isRunning() ? motion.diagnostics() : "sensor off";
    }

    public int queuedCount() {
        return sender.queuedCount();
    }

    public String ackBy() {
        return ackBy;
    }

    public long ackAt() {
        return ackAt;
    }
}
