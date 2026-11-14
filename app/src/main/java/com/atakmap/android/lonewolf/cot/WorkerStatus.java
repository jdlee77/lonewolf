package com.atakmap.android.lonewolf.cot;

/**
 * Snapshot of a lone worker, as carried in the {@code <__lonewolf>} CoT detail.
 * Used both for messages we send and for workers we hear about.
 */
public class WorkerStatus {
    public String uid;
    public String callsign;
    public String state = "DISARMED";
    public String reason = "NONE";
    public String activity = "";
    public long lastMotionMs;
    public long lastCheckInMs;
    /** -1 when check-in is disabled. */
    public long nextCheckInMs = -1;
    /** 0..100, or -1 when unknown. */
    public int battery = -1;
    public double lat = Double.NaN;
    public double lon = Double.NaN;
    public String ackBy = "";
    public long ackAtMs;
    /** When we last heard from this worker (receiver side only). */
    public long lastHeardMs;

    public boolean hasLocation() {
        return !Double.isNaN(lat) && !Double.isNaN(lon);
    }
}
