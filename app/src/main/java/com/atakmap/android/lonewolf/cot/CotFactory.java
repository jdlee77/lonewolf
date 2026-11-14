package com.atakmap.android.lonewolf.cot;

import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.maps.time.CoordinatedTime;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Builds and parses the Cursor-on-Target messages used by the plugin.
 *
 * <ul>
 *   <li><b>Emergency</b>: ATAK's native "911 Alert" ({@code b-a-o-tbl}), UID
 *   {@code <deviceUid>-9-1-1}, the same format as ATAK's own Emergency tool,
 *   so every ATAK / WinTAK / iTAK client and the TAK Server raise their native
 *   emergency alert. Extra lone worker fields go in {@code <remarks>},
 *   {@code <status battery>} and {@code <__lonewolf>}.</li>
 *   <li><b>Cancel</b>: {@code b-a-o-can} with the same UID and
 *   {@code <emergency cancel="true">}.</li>
 *   <li><b>Status</b>: {@code t-x-lw-status}, a heartbeat used to build the
 *   supervisor's list of armed workers. Ignored by clients without the plugin.</li>
 *   <li><b>Acknowledge</b>: {@code t-x-lw-ack}, sent by a team member or
 *   supervisor to tell the worker that help is coming.</li>
 * </ul>
 */
public final class CotFactory {

    public static final String TYPE_EMERGENCY = "b-a-o-tbl";
    public static final String TYPE_CANCEL = "b-a-o-can";
    public static final String TYPE_STATUS = "t-x-lw-status";
    public static final String TYPE_ACK = "t-x-lw-ack";

    public static final String DETAIL = "__lonewolf";
    public static final String DETAIL_ACK = "__lonewolf_ack";

    // CoT convention for "unknown" hae/ce/le
    private static final double UNKNOWN = 9999999.0;

    private CotFactory() {
    }

    public static String emergencyUid(String deviceUid) {
        return deviceUid + "-9-1-1";
    }

    private static CotEvent base(String uid, String type, String how, int staleSeconds,
            WorkerStatus s) {
        CotEvent e = new CotEvent();
        CoordinatedTime now = new CoordinatedTime();
        e.setVersion("2.0");
        e.setUID(uid);
        e.setType(type);
        e.setHow(how);
        e.setTime(now);
        e.setStart(now);
        e.setStale(now.addSeconds(staleSeconds));
        if (s != null && s.hasLocation())
            e.setPoint(new CotPoint(s.lat, s.lon, UNKNOWN, UNKNOWN, UNKNOWN));
        else
            e.setPoint(new CotPoint(0, 0, UNKNOWN, UNKNOWN, UNKNOWN));
        return e;
    }

    // ---------------------------------------------------------------- build

    public static CotEvent emergency(WorkerStatus s, String reasonLabel, int staleSeconds) {
        CotEvent e = base(emergencyUid(s.uid), TYPE_EMERGENCY, "h-e", staleSeconds, s);
        CotDetail d = new CotDetail("detail");

        CotDetail link = new CotDetail("link");
        link.setAttribute("uid", s.uid);
        link.setAttribute("type", "a-f-G-U-C");
        link.setAttribute("relation", "p-p");
        d.addChild(link);

        CotDetail contact = new CotDetail("contact");
        contact.setAttribute("callsign", s.callsign + "-Alert");
        d.addChild(contact);

        CotDetail emergency = new CotDetail("emergency");
        emergency.setAttribute("type", "911 Alert");
        emergency.setInnerText(s.callsign);
        d.addChild(emergency);

        d.addChild(remarks(s, reasonLabel));
        addStatus(d, s);
        d.addChild(lonewolfDetail(s));
        e.setDetail(d);
        return e;
    }

    public static CotEvent emergencyCancel(WorkerStatus s) {
        CotEvent e = base(emergencyUid(s.uid), TYPE_CANCEL, "h-e", 60, s);
        CotDetail d = new CotDetail("detail");

        CotDetail link = new CotDetail("link");
        link.setAttribute("uid", s.uid);
        link.setAttribute("type", "a-f-G-U-C");
        link.setAttribute("relation", "p-p");
        d.addChild(link);

        CotDetail emergency = new CotDetail("emergency");
        emergency.setAttribute("cancel", "true");
        emergency.setInnerText(s.callsign);
        d.addChild(emergency);

        CotDetail r = new CotDetail("remarks");
        r.setInnerText("Lone worker alarm cancelled by " + s.callsign + " at " + hhmm(System.currentTimeMillis()));
        d.addChild(r);
        d.addChild(lonewolfDetail(s));
        e.setDetail(d);
        return e;
    }

    public static CotEvent status(WorkerStatus s, int staleSeconds) {
        CotEvent e = base(s.uid + "-lonewolf", TYPE_STATUS, "h-e", staleSeconds, s);
        CotDetail d = new CotDetail("detail");
        CotDetail contact = new CotDetail("contact");
        contact.setAttribute("callsign", s.callsign);
        d.addChild(contact);
        addStatus(d, s);
        d.addChild(lonewolfDetail(s));
        e.setDetail(d);
        return e;
    }

    public static CotEvent ack(String targetUid, String targetCallsign, String byUid,
            String byCallsign) {
        CotEvent e = base(byUid + "-lwack-" + targetUid, TYPE_ACK, "h-e", 300, null);
        CotDetail d = new CotDetail("detail");
        CotDetail a = new CotDetail(DETAIL_ACK);
        a.setAttribute("target", targetUid);
        a.setAttribute("targetCallsign", targetCallsign);
        a.setAttribute("by", byUid);
        a.setAttribute("byCallsign", byCallsign);
        a.setAttribute("at", iso(System.currentTimeMillis()));
        d.addChild(a);
        CotDetail r = new CotDetail("remarks");
        r.setInnerText(byCallsign + " acknowledged lone worker alarm for " + targetCallsign);
        d.addChild(r);
        e.setDetail(d);
        return e;
    }

    /**
     * Route via the TAK Server to specific callsigns. Without a {@code <marti>}
     * element the server delivers to everyone sharing a channel (group).
     */
    public static void addMartiDest(CotEvent e, List<String> callsigns) {
        if (callsigns == null || callsigns.isEmpty())
            return;
        CotDetail d = e.getDetail();
        if (d == null) {
            d = new CotDetail("detail");
            e.setDetail(d);
        }
        CotDetail marti = new CotDetail("marti");
        for (String cs : callsigns) {
            CotDetail dest = new CotDetail("dest");
            dest.setAttribute("callsign", cs);
            marti.addChild(dest);
        }
        d.addChild(marti);
    }

    /** Refresh time/start/stale before re-sending a queued message. */
    public static void refreshTimes(CotEvent e, int staleSeconds) {
        CoordinatedTime now = new CoordinatedTime();
        e.setTime(now);
        e.setStart(now);
        e.setStale(now.addSeconds(staleSeconds));
    }

    private static CotDetail remarks(WorkerStatus s, String reasonLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append("LONE WORKER ALARM - ").append(reasonLabel).append(". ");
        sb.append("Callsign ").append(s.callsign).append(". ");
        if (s.lastMotionMs > 0)
            sb.append("Last motion ").append(hhmm(s.lastMotionMs)).append(". ");
        if (s.lastCheckInMs > 0)
            sb.append("Last check-in ").append(hhmm(s.lastCheckInMs)).append(". ");
        if (s.battery >= 0)
            sb.append("Battery ").append(s.battery).append("%. ");
        if (!s.hasLocation())
            sb.append("Location unknown. ");
        CotDetail r = new CotDetail("remarks");
        r.setInnerText(sb.toString().trim());
        return r;
    }

    private static void addStatus(CotDetail d, WorkerStatus s) {
        if (s.battery >= 0) {
            CotDetail st = new CotDetail("status");
            st.setAttribute("battery", String.valueOf(s.battery));
            d.addChild(st);
        }
    }

    private static CotDetail lonewolfDetail(WorkerStatus s) {
        CotDetail lw = new CotDetail(DETAIL);
        lw.setAttribute("v", "1");
        lw.setAttribute("callsign", s.callsign);
        lw.setAttribute("state", s.state);
        lw.setAttribute("reason", s.reason);
        lw.setAttribute("activity", s.activity == null ? "" : s.activity);
        lw.setAttribute("lastMotion", s.lastMotionMs > 0 ? iso(s.lastMotionMs) : "");
        lw.setAttribute("lastCheckIn", s.lastCheckInMs > 0 ? iso(s.lastCheckInMs) : "");
        lw.setAttribute("nextCheckIn", s.nextCheckInMs > 0 ? iso(s.nextCheckInMs) : "");
        lw.setAttribute("battery", String.valueOf(s.battery));
        lw.setAttribute("ackBy", s.ackBy == null ? "" : s.ackBy);
        lw.setAttribute("ackAt", s.ackAtMs > 0 ? iso(s.ackAtMs) : "");
        return lw;
    }

    // ---------------------------------------------------------------- parse

    /** Parse a received event carrying a {@code <__lonewolf>} detail, or null. */
    public static WorkerStatus parseStatus(CotEvent e) {
        CotDetail d = e.getDetail();
        if (d == null)
            return null;
        CotDetail lw = d.getFirstChildByName(0, DETAIL);
        if (lw == null)
            return null;
        WorkerStatus s = new WorkerStatus();
        String type = e.getType();
        if (TYPE_EMERGENCY.equals(type) || TYPE_CANCEL.equals(type)) {
            CotDetail link = d.getFirstChildByName(0, "link");
            s.uid = link != null ? link.getAttribute("uid") : null;
        } else {
            String uid = e.getUID();
            s.uid = uid != null && uid.endsWith("-lonewolf")
                    ? uid.substring(0, uid.length() - "-lonewolf".length()) : uid;
        }
        if (s.uid == null)
            return null;
        s.callsign = nz(lw.getAttribute("callsign"), s.uid);
        s.state = nz(lw.getAttribute("state"), "ARMED");
        s.reason = nz(lw.getAttribute("reason"), "NONE");
        s.activity = nz(lw.getAttribute("activity"), "");
        s.lastMotionMs = parseIso(lw.getAttribute("lastMotion"));
        s.lastCheckInMs = parseIso(lw.getAttribute("lastCheckIn"));
        long next = parseIso(lw.getAttribute("nextCheckIn"));
        s.nextCheckInMs = next > 0 ? next : -1;
        try {
            s.battery = Integer.parseInt(nz(lw.getAttribute("battery"), "-1"));
        } catch (NumberFormatException ex) {
            s.battery = -1;
        }
        s.ackBy = nz(lw.getAttribute("ackBy"), "");
        s.ackAtMs = parseIso(lw.getAttribute("ackAt"));
        CotPoint p = e.getCotPoint();
        if (p != null && !(p.getLat() == 0 && p.getLon() == 0)) {
            s.lat = p.getLat();
            s.lon = p.getLon();
        }
        s.lastHeardMs = System.currentTimeMillis();
        return s;
    }

    public static final class Ack {
        public String targetUid, targetCallsign, byUid, byCallsign;
        public long atMs;
    }

    public static Ack parseAck(CotEvent e) {
        CotDetail d = e.getDetail();
        if (d == null)
            return null;
        CotDetail a = d.getFirstChildByName(0, DETAIL_ACK);
        if (a == null)
            return null;
        Ack ack = new Ack();
        ack.targetUid = a.getAttribute("target");
        ack.targetCallsign = nz(a.getAttribute("targetCallsign"), "");
        ack.byUid = a.getAttribute("by");
        ack.byCallsign = nz(a.getAttribute("byCallsign"), "unknown");
        ack.atMs = parseIso(a.getAttribute("at"));
        if (ack.atMs <= 0)
            ack.atMs = System.currentTimeMillis();
        return ack.targetUid == null ? null : ack;
    }

    // ---------------------------------------------------------------- util

    private static String nz(String v, String def) {
        return v == null || v.isEmpty() ? def : v;
    }

    private static SimpleDateFormat isoFormat() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    public static String iso(long t) {
        return isoFormat().format(new Date(t));
    }

    public static long parseIso(String s) {
        if (s == null || s.isEmpty())
            return 0;
        try {
            Date d = isoFormat().parse(s);
            return d == null ? 0 : d.getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    public static String hhmm(long t) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(t));
    }
}
