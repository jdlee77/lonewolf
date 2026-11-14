package com.atakmap.android.lonewolf.team;

import com.atakmap.android.lonewolf.cot.CotFactory;
import com.atakmap.android.lonewolf.cot.WorkerStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Supervisor / team view: lone workers heard on the network, keyed by UID.
 * Pure Java apart from types it shares with the CoT layer.
 */
public class WorkerRegistry {

    /** No heartbeat for this long: shown as "signal lost". */
    public static final long STALE_MS = 3 * 60_000L;
    /** Disarmed or silent workers are removed after this long. */
    public static final long EXPIRE_MS = 60 * 60_000L;

    public interface Listener {
        void onRegistryChanged();
    }

    private final Map<String, WorkerStatus> workers = new LinkedHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** @return the previous state of that worker, or null if new. */
    public synchronized String update(WorkerStatus s, String cotType) {
        WorkerStatus prev = workers.get(s.uid);
        String prevState = prev != null ? prev.state : null;
        if (CotFactory.TYPE_CANCEL.equals(cotType) && "ALARM".equals(s.state))
            s.state = "ARMED"; // cancel carries the pre-cancel snapshot on some paths
        if (prev != null && !s.hasLocation()) {
            s.lat = prev.lat;
            s.lon = prev.lon;
        }
        if (prev != null && (s.ackBy == null || s.ackBy.isEmpty()) && "ALARM".equals(s.state)) {
            s.ackBy = prev.ackBy;
            s.ackAtMs = prev.ackAtMs;
        }
        workers.put(s.uid, s);
        fire();
        return prevState;
    }

    public synchronized void recordAck(CotFactory.Ack ack) {
        WorkerStatus w = workers.get(ack.targetUid);
        if (w == null)
            return;
        w.ackBy = ack.byCallsign;
        w.ackAtMs = ack.atMs;
        fire();
    }

    public synchronized WorkerStatus get(String uid) {
        return workers.get(uid);
    }

    /** Sorted: ALARM first, then PRE_ALARM, ARMED, DISARMED; then by callsign. */
    public synchronized List<WorkerStatus> list(long now) {
        workers.values().removeIf(w -> now - w.lastHeardMs > EXPIRE_MS
                || ("DISARMED".equals(w.state) && now - w.lastHeardMs > 10 * 60_000L));
        List<WorkerStatus> out = new ArrayList<>(workers.values());
        Collections.sort(out, (a, b) -> {
            int c = Integer.compare(rank(a.state), rank(b.state));
            return c != 0 ? c : String.CASE_INSENSITIVE_ORDER.compare(a.callsign, b.callsign);
        });
        return out;
    }

    public static boolean isStale(WorkerStatus w, long now) {
        return !"DISARMED".equals(w.state) && now - w.lastHeardMs > STALE_MS;
    }

    private static int rank(String state) {
        switch (state) {
            case "ALARM":
                return 0;
            case "PRE_ALARM":
                return 1;
            case "ARMED":
                return 2;
            default:
                return 3;
        }
    }

    private void fire() {
        for (Listener l : listeners)
            l.onRegistryChanged();
    }
}
