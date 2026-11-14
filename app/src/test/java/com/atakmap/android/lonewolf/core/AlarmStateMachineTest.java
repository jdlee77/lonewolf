package com.atakmap.android.lonewolf.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.lonewolf.core.AlarmStateMachine.Reason;
import com.atakmap.android.lonewolf.core.AlarmStateMachine.State;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class AlarmStateMachineTest {

    private static final long MIN = 60_000L;
    private AlarmStateMachine sm;
    private final List<String> transitions = new ArrayList<>();
    private int fallCandidates, fallDismissed;

    @Before
    public void setUp() {
        AlarmStateMachine.Config c = new AlarmStateMachine.Config();
        c.inactivityMs = 5 * MIN;
        c.preAlarmMs = 60_000;
        c.checkInIntervalMs = 30 * MIN;
        c.fallEnabled = true;
        c.fallStillnessMs = 15_000;
        c.fallSettleMs = 2_000;
        sm = new AlarmStateMachine(c);
        sm.setListener(new AlarmStateMachine.Listener() {
            @Override
            public void onStateChanged(State from, State to, Reason reason, String trigger, long now) {
                transitions.add(from + "->" + to + ":" + reason);
            }

            @Override
            public void onFallCandidate(long now) {
                fallCandidates++;
            }

            @Override
            public void onFallDismissed(long now) {
                fallDismissed++;
            }
        });
    }

    @Test
    public void inactivityEscalatesToPreAlarmThenAlarm() {
        sm.arm(0);
        sm.tick(5 * MIN - 1);
        assertEquals(State.ARMED, sm.getState());
        sm.tick(5 * MIN);
        assertEquals(State.PRE_ALARM, sm.getState());
        assertEquals(Reason.INACTIVITY, sm.getReason());
        assertEquals(5 * MIN + 60_000, sm.nextDeadline());
        sm.tick(5 * MIN + 59_999);
        assertEquals(State.PRE_ALARM, sm.getState());
        sm.tick(5 * MIN + 60_000);
        assertEquals(State.ALARM, sm.getState());
        assertEquals(Reason.INACTIVITY, sm.getReason());
    }

    @Test
    public void motionResetsInactivityTimer() {
        sm.arm(0);
        sm.onSustainedMotion(4 * MIN);
        sm.tick(5 * MIN);
        assertEquals(State.ARMED, sm.getState());
        assertEquals(9 * MIN, sm.nextDeadline());
    }

    @Test
    public void motionCancelsInactivityPreAlarm() {
        sm.arm(0);
        sm.tick(5 * MIN);
        assertEquals(State.PRE_ALARM, sm.getState());
        sm.onSustainedMotion(5 * MIN + 10_000);
        assertEquals(State.ARMED, sm.getState());
        sm.tick(5 * MIN + 70_000);
        assertEquals(State.ARMED, sm.getState());
    }

    @Test
    public void motionDoesNotSatisfyCheckIn() {
        sm.arm(0);
        // keep moving every minute; inactivity never fires
        for (long t = MIN; t < 30 * MIN; t += MIN) {
            sm.onSustainedMotion(t);
            sm.tick(t);
            assertEquals(State.ARMED, sm.getState());
        }
        sm.onSustainedMotion(30 * MIN);
        sm.tick(30 * MIN);
        assertEquals(State.PRE_ALARM, sm.getState());
        assertEquals(Reason.CHECK_IN, sm.getReason());
        // motion does not clear a check-in pre-alarm
        sm.onSustainedMotion(30 * MIN + 5_000);
        assertEquals(State.PRE_ALARM, sm.getState());
        // I'm OK does
        assertTrue(sm.imOk(30 * MIN + 10_000));
        assertEquals(State.ARMED, sm.getState());
        assertEquals(30 * MIN + 10_000 + 30 * MIN, sm.getNextCheckInMs());
    }

    @Test
    public void checkInDisabled() {
        AlarmStateMachine.Config c = sm.getConfig();
        c.checkInIntervalMs = 0;
        sm.updateConfig(c);
        sm.arm(0);
        for (long t = MIN; t < 120 * MIN; t += MIN) {
            sm.onSustainedMotion(t);
            sm.tick(t);
        }
        assertEquals(State.ARMED, sm.getState());
        assertEquals(-1, sm.getNextCheckInMs());
    }

    @Test
    public void alarmOnlyCancelledManually() {
        sm.arm(0);
        sm.tick(5 * MIN);
        sm.tick(6 * MIN);
        assertEquals(State.ALARM, sm.getState());
        sm.onSustainedMotion(6 * MIN + 1000);
        assertEquals(State.ALARM, sm.getState());
        assertFalse(sm.imOk(6 * MIN + 2000));
        assertEquals(State.ALARM, sm.getState());
        assertFalse(sm.disarm(6 * MIN + 3000));
        assertEquals(State.ALARM, sm.getState());
        assertTrue(sm.cancelAlarm(6 * MIN + 4000));
        assertEquals(State.ARMED, sm.getState());
        // timers restart from cancel
        assertEquals(6 * MIN + 4000 + 5 * MIN, sm.nextDeadline());
    }

    @Test
    public void disarmFromPreAlarm() {
        sm.arm(0);
        sm.tick(5 * MIN);
        assertTrue(sm.disarm(5 * MIN + 1));
        assertEquals(State.DISARMED, sm.getState());
        sm.tick(60 * MIN);
        assertEquals(State.DISARMED, sm.getState());
        assertEquals(AlarmStateMachine.NO_DEADLINE, sm.nextDeadline());
    }

    @Test
    public void fallFollowedByStillnessRaisesPreAlarmEarly() {
        sm.arm(0);
        sm.onFallCandidate(MIN);
        assertEquals(1, fallCandidates);
        assertTrue(sm.isFallPending());
        assertEquals(MIN + 15_000, sm.nextDeadline());
        sm.tick(MIN + 14_999);
        assertEquals(State.ARMED, sm.getState());
        sm.tick(MIN + 15_000);
        assertEquals(State.PRE_ALARM, sm.getState());
        assertEquals(Reason.FALL, sm.getReason());
    }

    @Test
    public void motionRightAfterImpactIsIgnoredButLaterMotionDismissesFall() {
        sm.arm(0);
        sm.onFallCandidate(MIN);
        sm.onSustainedMotion(MIN + 1_000); // tumble/bounce within settle window
        assertTrue(sm.isFallPending());
        sm.onSustainedMotion(MIN + 5_000); // worker gets up
        assertFalse(sm.isFallPending());
        assertEquals(1, fallDismissed);
        sm.tick(MIN + 20_000);
        assertEquals(State.ARMED, sm.getState());
    }

    @Test
    public void fallDisabledIgnoresCandidates() {
        AlarmStateMachine.Config c = sm.getConfig();
        c.fallEnabled = false;
        sm.updateConfig(c);
        sm.arm(0);
        sm.onFallCandidate(MIN);
        assertFalse(sm.isFallPending());
        assertEquals(0, fallCandidates);
    }

    @Test
    public void restoreAlarmAndPreAlarm() {
        sm.restore(State.ALARM, Reason.FALL, 0, 1000);
        assertEquals(State.ALARM, sm.getState());
        assertEquals(Reason.FALL, sm.getReason());

        setUp();
        sm.restore(State.PRE_ALARM, Reason.INACTIVITY, 0, 1000);
        assertEquals(State.PRE_ALARM, sm.getState());
        assertEquals(1000 + 60_000, sm.nextDeadline());

        setUp();
        sm.restore(State.ARMED, Reason.NONE, 500, 1000);
        assertEquals(State.ARMED, sm.getState());
        assertEquals(500, sm.getLastCheckInMs());
    }

    @Test
    public void armTwiceIsNoop() {
        assertTrue(sm.arm(0));
        assertFalse(sm.arm(10));
        assertEquals(1, transitions.size());
    }
}
