package com.atakmap.android.lonewolf.core;

import android.content.SharedPreferences;

import com.atakmap.android.lonewolf.sensor.MotionDetector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * User configuration and persisted session state, stored in ATAK's shared
 * preferences under the {@code lonewolf.} prefix.
 */
public class LoneWolfSettings {

    public enum RecipientMode { CHANNEL, TEAM, CONTACTS }

    public static final int[] CHECK_IN_CHOICES_MIN = { 15, 30, 45, 60, 90, 120, 180, 240 };

    private static final String P = "lonewolf.";

    // configuration
    public static final String INACTIVITY_MIN = P + "inactivity_min";
    public static final String PRE_ALARM_SEC = P + "pre_alarm_sec";
    public static final String MOTION_SEC = P + "motion_sec";
    public static final String SENSITIVITY = P + "sensitivity";
    public static final String FALL_ENABLED = P + "fall_enabled";
    public static final String FALL_STILL_SEC = P + "fall_still_sec";
    public static final String CHECKIN_ENABLED = P + "checkin_enabled";
    public static final String CHECKIN_MIN = P + "checkin_min";
    public static final String ALERT_SOUND = P + "alert_sound";
    public static final String ALERT_OVERRIDE_SILENT = P + "alert_override_silent";
    public static final String ALERT_VIBRATE = P + "alert_vibrate";
    public static final String ALERT_TORCH = P + "alert_torch";
    public static final String ALERT_WAKE_SCREEN = P + "alert_wake_screen";
    public static final String ALARM_SIREN = P + "alarm_siren";
    public static final String SILENCE_ON_ACK = P + "silence_on_ack";
    public static final String REPEAT_SEC = P + "repeat_sec";
    public static final String STATUS_SEC = P + "status_sec";
    public static final String RECIPIENTS = P + "recipients";
    public static final String CONTACTS = P + "contacts";

    // session state
    public static final String STATE_ARMED = P + "state.armed";
    public static final String STATE_STATE = P + "state.state";
    public static final String STATE_REASON = P + "state.reason";
    public static final String STATE_LAST_CHECKIN = P + "state.last_checkin";
    public static final String STATE_ACK_BY = P + "state.ack_by";
    public static final String STATE_ACK_AT = P + "state.ack_at";
    public static final String STATE_QUEUE = P + "state.queue";

    private final SharedPreferences prefs;

    public LoneWolfSettings(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public SharedPreferences prefs() {
        return prefs;
    }

    // ATAK's own preference screens store numbers as strings, so read both.
    private int getInt(String key, int def) {
        try {
            return prefs.getInt(key, def);
        } catch (ClassCastException e) {
            try {
                return Integer.parseInt(prefs.getString(key, String.valueOf(def)).trim());
            } catch (Exception e2) {
                return def;
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public int inactivityMin() { return clamp(getInt(INACTIVITY_MIN, 5), 1, 240); }
    public int preAlarmSec() { return clamp(getInt(PRE_ALARM_SEC, 60), 10, 600); }
    public int motionSec() { return clamp(getInt(MOTION_SEC, 2), 1, 30); }
    public boolean fallEnabled() { return prefs.getBoolean(FALL_ENABLED, true); }
    public int fallStillSec() { return clamp(getInt(FALL_STILL_SEC, 15), 5, 300); }
    public boolean checkInEnabled() { return prefs.getBoolean(CHECKIN_ENABLED, true); }
    public int checkInMin() { return clamp(getInt(CHECKIN_MIN, 60), 5, 24 * 60); }
    public boolean alertSound() { return prefs.getBoolean(ALERT_SOUND, true); }
    public boolean alertOverrideSilent() { return prefs.getBoolean(ALERT_OVERRIDE_SILENT, true); }
    public boolean alertVibrate() { return prefs.getBoolean(ALERT_VIBRATE, true); }
    public boolean alertTorch() { return prefs.getBoolean(ALERT_TORCH, false); }
    public boolean alertWakeScreen() { return prefs.getBoolean(ALERT_WAKE_SCREEN, true); }
    public boolean alarmSiren() { return prefs.getBoolean(ALARM_SIREN, true); }
    public boolean silenceOnAck() { return prefs.getBoolean(SILENCE_ON_ACK, false); }
    public int repeatSec() { return clamp(getInt(REPEAT_SEC, 30), 10, 600); }
    public int statusSec() { return clamp(getInt(STATUS_SEC, 60), 15, 600); }

    public MotionDetector.Sensitivity sensitivity() {
        try {
            return MotionDetector.Sensitivity.valueOf(prefs.getString(SENSITIVITY, "MEDIUM"));
        } catch (Exception e) {
            return MotionDetector.Sensitivity.MEDIUM;
        }
    }

    public RecipientMode recipientMode() {
        try {
            return RecipientMode.valueOf(prefs.getString(RECIPIENTS, "CHANNEL"));
        } catch (Exception e) {
            return RecipientMode.CHANNEL;
        }
    }

    /** Callsigns selected when the recipient mode is CONTACTS. */
    public List<String> contacts() {
        String s = prefs.getString(CONTACTS, "");
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty())
            return out;
        for (String c : Arrays.asList(s.split("\n"))) {
            if (!c.trim().isEmpty())
                out.add(c.trim());
        }
        return out;
    }

    public void setContacts(List<String> callsigns) {
        StringBuilder sb = new StringBuilder();
        for (String c : callsigns) {
            if (sb.length() > 0)
                sb.append('\n');
            sb.append(c);
        }
        prefs.edit().putString(CONTACTS, sb.toString()).apply();
    }

    public AlarmStateMachine.Config toConfig() {
        AlarmStateMachine.Config c = new AlarmStateMachine.Config();
        c.inactivityMs = inactivityMin() * 60_000L;
        c.preAlarmMs = preAlarmSec() * 1000L;
        c.checkInIntervalMs = checkInEnabled() ? checkInMin() * 60_000L : 0;
        c.fallEnabled = fallEnabled();
        c.fallStillnessMs = fallStillSec() * 1000L;
        return c;
    }

    // ------------------------------------------------------------ session

    public void saveSession(boolean armed, AlarmStateMachine.State state,
            AlarmStateMachine.Reason reason, long lastCheckIn) {
        prefs.edit()
                .putBoolean(STATE_ARMED, armed)
                .putString(STATE_STATE, state.name())
                .putString(STATE_REASON, reason.name())
                .putLong(STATE_LAST_CHECKIN, lastCheckIn)
                .apply();
    }

    public boolean wasArmed() {
        return prefs.getBoolean(STATE_ARMED, false);
    }

    public AlarmStateMachine.State savedState() {
        try {
            return AlarmStateMachine.State.valueOf(prefs.getString(STATE_STATE, "DISARMED"));
        } catch (Exception e) {
            return AlarmStateMachine.State.DISARMED;
        }
    }

    public AlarmStateMachine.Reason savedReason() {
        try {
            return AlarmStateMachine.Reason.valueOf(prefs.getString(STATE_REASON, "NONE"));
        } catch (Exception e) {
            return AlarmStateMachine.Reason.NONE;
        }
    }

    public long savedLastCheckIn() {
        return prefs.getLong(STATE_LAST_CHECKIN, 0);
    }
}
