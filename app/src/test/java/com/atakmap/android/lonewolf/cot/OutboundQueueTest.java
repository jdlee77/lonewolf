package com.atakmap.android.lonewolf.cot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class OutboundQueueTest {

    private static String keys(List<OutboundQueue.Entry> l) {
        StringBuilder sb = new StringBuilder();
        for (OutboundQueue.Entry e : l)
            sb.append(e.xml).append(' ');
        return sb.toString().trim();
    }

    @Test
    public void statusCollapsesToNewest() {
        OutboundQueue q = new OutboundQueue(10);
        q.add(OutboundQueue.KEY_STATUS, "s1", 1);
        q.add(OutboundQueue.KEY_STATUS, "s2", 2);
        q.add(OutboundQueue.KEY_STATUS, "s3", 3);
        assertEquals("s3", keys(q.drain()));
        assertTrue(q.isEmpty());
    }

    @Test
    public void emergencyThenCancelKeepsOrder() {
        OutboundQueue q = new OutboundQueue(10);
        q.add(OutboundQueue.KEY_STATUS, "s1", 1);
        q.add(OutboundQueue.KEY_EMERGENCY, "e1", 2);
        q.add(OutboundQueue.KEY_EMERGENCY, "e2", 3);
        q.add(OutboundQueue.KEY_STATUS, "s2", 4);
        q.add(OutboundQueue.KEY_CANCEL, "c1", 5);
        assertEquals("e2 s2 c1", keys(q.drain()));
    }

    @Test
    public void newAlarmAfterCancelEndsInAlarm() {
        OutboundQueue q = new OutboundQueue(10);
        q.add(OutboundQueue.KEY_EMERGENCY, "e1", 1);
        q.add(OutboundQueue.KEY_CANCEL, "c1", 2);
        q.add(OutboundQueue.KEY_EMERGENCY, "e2", 3);
        assertEquals("c1 e2", keys(q.drain()));
    }

    @Test
    public void overflowNeverDropsEmergencyOrCancel() {
        OutboundQueue q = new OutboundQueue(3);
        q.add(OutboundQueue.KEY_EMERGENCY, "e", 1);
        q.add(OutboundQueue.KEY_CANCEL, "c", 2);
        q.add("ack-a", "a", 3);
        q.add("ack-b", "b", 4);
        assertEquals("e c b", keys(q.snapshot()));
    }

    @Test
    public void restoreRoundTrip() {
        OutboundQueue q = new OutboundQueue(10);
        q.add(OutboundQueue.KEY_EMERGENCY, "e", 1);
        q.add(OutboundQueue.KEY_STATUS, "s", 2);
        OutboundQueue q2 = new OutboundQueue(10);
        q2.restore(q.snapshot());
        assertEquals(2, q2.size());
        assertEquals("e s", keys(q2.drain()));
    }
}
