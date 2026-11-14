package com.atakmap.android.lonewolf.core;

/**
 * Pure-Java lone worker state machine. It has no Android or ATAK dependencies
 * so it can be unit tested on the JVM. All times are wall-clock milliseconds
 * supplied by the caller.
 *
 * <pre>
 *   DISARMED --arm--> ARMED --(inactivity | check-in due | fall + stillness)--> PRE_ALARM
 *   PRE_ALARM --(motion >= N s, inactivity/fall only)--> ARMED
 *   PRE_ALARM --(I'm OK)--> ARMED
 *   PRE_ALARM --(pre-alarm timer expires)--> ALARM   (emergency sent to TAK)
 *   ALARM --(manual cancel only)--> ARMED
 *   ARMED / PRE_ALARM --disarm--> DISARMED          (refused while in ALARM)
 * </pre>
 */
public class AlarmStateMachine {

    public enum State { DISARMED, ARMED, PRE_ALARM, ALARM }

    public enum Reason { NONE, INACTIVITY, CHECK_IN, FALL }

    /** Timing configuration. All values in milliseconds. */
    public static class Config {
        public long inactivityMs = 5 * 60_000L;
        public long preAlarmMs = 60_000L;
        /** 0 disables the periodic check-in. */
        public long checkInIntervalMs = 60 * 60_000L;
        public boolean fallEnabled = true;
        /** How long the device must stay still after an impact before pre-alarm. */
        public long fallStillnessMs = 15_000L;
        /** Motion right after an impact (bounce, tumble) is ignored for this long. */
        public long fallSettleMs = 2_000L;

        public Config copy() {
            Config c = new Config();
            c.inactivityMs = inactivityMs;
            c.preAlarmMs = preAlarmMs;
            c.checkInIntervalMs = checkInIntervalMs;
            c.fallEnabled = fallEnabled;
            c.fallStillnessMs = fallStillnessMs;
            c.fallSettleMs = fallSettleMs;
            return c;
        }
    }

    public interface Listener {
        void onStateChanged(State from, State to, Reason reason, String trigger, long now);

        void onFallCandidate(long now);

        void onFallDismissed(long now);
    }

    public static final long NO_DEADLINE = Long.MAX_VALUE;

    private Config config;
    private Listener listener;

    private State state = State.DISARMED;
    private Reason reason = Reason.NONE;
    private long lastMotionMs;
    private long lastCheckInMs;
    private long preAlarmDeadlineMs = NO_DEADLINE;
    private long fallImpactMs = -1;
    private long alarmStartMs = -1;

    public AlarmStateMachine(Config config) {
        this.config = config.copy();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public synchronized void updateConfig(Config c) {
        this.config = c.copy();
        if (!config.fallEnabled)
            fallImpactMs = -1;
    }

    public synchronized Config getConfig() {
        return config.copy();
    }

    // ------------------------------------------------------------------ inputs

    public synchronized boolean arm(long now) {
        if (state != State.DISARMED)
            return false;
        lastMotionMs = now;
        lastCheckInMs = now;
        fallImpactMs = -1;
        transition(State.ARMED, Reason.NONE, "armed by user", now);
        return true;
    }

    /**
     * Restore a previously persisted session (for example after ATAK restarts).
     * A session that was in PRE_ALARM restarts its pre-alarm countdown; a
     * session in ALARM resumes the alarm.
     */
    public synchronized void restore(State s, Reason r, long lastCheckIn, long now) {
        lastMotionMs = now;
        lastCheckInMs = lastCheckIn > 0 && lastCheckIn <= now ? lastCheckIn : now;
        fallImpactMs = -1;
        switch (s) {
            case ALARM:
                alarmStartMs = now;
                transition(State.ALARM, r, "restored after restart", now);
                break;
            case PRE_ALARM:
                preAlarmDeadlineMs = now + config.preAlarmMs;
                transition(State.PRE_ALARM, r, "restored after restart", now);
                break;
            case ARMED:
                transition(State.ARMED, Reason.NONE, "restored after restart", now);
                break;
            default:
                break;
        }
    }

    /** @return false if refused (an active alarm must be cancelled first). */
    public synchronized boolean disarm(long now) {
        if (state == State.ALARM)
            return false;
        if (state == State.DISARMED)
            return true;
        fallImpactMs = -1;
        preAlarmDeadlineMs = NO_DEADLINE;
        transition(State.DISARMED, Reason.NONE, "disarmed by user", now);
        return true;
    }

    /** Called when motion has been sustained for the configured duration. */
    public synchronized void onSustainedMotion(long now) {
        if (state == State.DISARMED)
            return;
        lastMotionMs = now;
        if (fallImpactMs >= 0 && now - fallImpactMs >= config.fallSettleMs) {
            fallImpactMs = -1;
            if (listener != null)
                listener.onFallDismissed(now);
        }
        if (state == State.PRE_ALARM
                && (reason == Reason.INACTIVITY || reason == Reason.FALL)) {
            preAlarmDeadlineMs = NO_DEADLINE;
            transition(State.ARMED, Reason.NONE, "motion detected", now);
        }
    }

    public synchronized void onFallCandidate(long now) {
        if (state != State.ARMED || !config.fallEnabled)
            return;
        fallImpactMs = now;
        if (listener != null)
            listener.onFallCandidate(now);
    }

    /** The user pressed "I'm OK". Counts as a check-in and as motion. */
    public synchronized boolean imOk(long now) {
        if (state == State.DISARMED || state == State.ALARM)
            return false;
        lastCheckInMs = now;
        lastMotionMs = now;
        fallImpactMs = -1;
        if (state == State.PRE_ALARM) {
            preAlarmDeadlineMs = NO_DEADLINE;
            transition(State.ARMED, Reason.NONE, "I'm OK pressed", now);
        }
        return true;
    }

    /** Manual cancel of an active alarm. Returns the worker to ARMED. */
    public synchronized boolean cancelAlarm(long now) {
        if (state != State.ALARM)
            return false;
        lastCheckInMs = now;
        lastMotionMs = now;
        fallImpactMs = -1;
        alarmStartMs = -1;
        transition(State.ARMED, Reason.NONE, "alarm cancelled by worker", now);
        return true;
    }

    /** Evaluate timers. Safe to call as often as desired. */
    public synchronized void tick(long now) {
        switch (state) {
            case ARMED:
                if (fallImpactMs >= 0
                        && now >= fallImpactMs + config.fallStillnessMs) {
                    fallImpactMs = -1;
                    enterPreAlarm(Reason.FALL, "possible fall, no movement", now);
                } else if (now >= lastMotionMs + config.inactivityMs) {
                    enterPreAlarm(Reason.INACTIVITY, "no movement", now);
                } else if (config.checkInIntervalMs > 0
                        && now >= lastCheckInMs + config.checkInIntervalMs) {
                    enterPreAlarm(Reason.CHECK_IN, "check-in due", now);
                }
                break;
            case PRE_ALARM:
                if (now >= preAlarmDeadlineMs) {
                    preAlarmDeadlineMs = NO_DEADLINE;
                    alarmStartMs = now;
                    transition(State.ALARM, reason, "pre-alarm expired", now);
                }
                break;
            default:
                break;
        }
    }

    private void enterPreAlarm(Reason r, String trigger, long now) {
        preAlarmDeadlineMs = now + config.preAlarmMs;
        transition(State.PRE_ALARM, r, trigger, now);
    }

    private void transition(State to, Reason r, String trigger, long now) {
        State from = state;
        state = to;
        reason = r;
        if (listener != null)
            listener.onStateChanged(from, to, r, trigger, now);
    }

    // ----------------------------------------------------------------- queries

    /** Earliest time at which {@link #tick(long)} could change state. */
    public synchronized long nextDeadline() {
        switch (state) {
            case ARMED: {
                long d = lastMotionMs + config.inactivityMs;
                if (config.checkInIntervalMs > 0)
                    d = Math.min(d, lastCheckInMs + config.checkInIntervalMs);
                if (fallImpactMs >= 0)
                    d = Math.min(d, fallImpactMs + config.fallStillnessMs);
                return d;
            }
            case PRE_ALARM:
                return preAlarmDeadlineMs;
            default:
                return NO_DEADLINE;
        }
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized Reason getReason() {
        return reason;
    }

    public synchronized long getLastMotionMs() {
        return lastMotionMs;
    }

    public synchronized long getLastCheckInMs() {
        return lastCheckInMs;
    }

    public synchronized long getAlarmStartMs() {
        return alarmStartMs;
    }

    public synchronized boolean isFallPending() {
        return fallImpactMs >= 0;
    }

    /** Time of the next check-in, or -1 when check-in is disabled / not armed. */
    public synchronized long getNextCheckInMs() {
        if (state == State.DISARMED || config.checkInIntervalMs <= 0)
            return -1;
        return lastCheckInMs + config.checkInIntervalMs;
    }

    public synchronized long inactivityRemainingMs(long now) {
        if (state != State.ARMED)
            return -1;
        return Math.max(0, lastMotionMs + config.inactivityMs - now);
    }

    public synchronized long preAlarmRemainingMs(long now) {
        if (state != State.PRE_ALARM)
            return -1;
        return Math.max(0, preAlarmDeadlineMs - now);
    }
}
