package com.atakmap.android.lonewolf.cot;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.lonewolf.core.LoneWolfSettings;
import com.atakmap.android.lonewolf.log.EventLog;
import com.atakmap.coremap.cot.event.CotEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Sends CoT through ATAK's external dispatcher (all configured TAK Server
 * connections plus mesh outputs). When no server is reachable the message is
 * also put in a persisted {@link OutboundQueue}. The queue is flushed when a
 * connection returns, with timestamps refreshed.
 */
public class CotSender {

    private static final long FLUSH_RETRY_MS = 15_000;

    private final Context host;
    private final Handler handler;
    private final LoneWolfSettings settings;
    private final OutboundQueue queue = new OutboundQueue(200);
    private ConnectivityManager.NetworkCallback netCallback;
    private Runnable onQueueChanged;

    public CotSender(Context host, Handler handler, LoneWolfSettings settings) {
        this.host = host;
        this.handler = handler;
        this.settings = settings;
        load();
    }

    public void setOnQueueChanged(Runnable r) {
        this.onQueueChanged = r;
    }

    public void start() {
        ConnectivityManager cm = (ConnectivityManager) host.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && netCallback == null) {
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    // give ATAK a few seconds to reconnect its server streams
                    handler.postDelayed(CotSender.this::flush, 5000);
                }
            };
            try {
                cm.registerDefaultNetworkCallback(netCallback, handler);
            } catch (RuntimeException e) {
                EventLog.e("COT", "Network callback registration failed", e);
                netCallback = null;
            }
        }
        if (!queue.isEmpty()) {
            EventLog.i("COT", "Restored " + queue.size() + " queued message(s) from last session");
            handler.postDelayed(flushTask, FLUSH_RETRY_MS);
        }
    }

    public void stop() {
        handler.removeCallbacks(flushTask);
        ConnectivityManager cm = (ConnectivityManager) host.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && netCallback != null) {
            try {
                cm.unregisterNetworkCallback(netCallback);
            } catch (RuntimeException ignored) {
            }
        }
        netCallback = null;
    }

    public int queuedCount() {
        return queue.size();
    }

    /**
     * Send an event. {@code key} is the collapse key used if it must be queued.
     * The caller has already applied any {@code <marti>} routing.
     */
    public void send(CotEvent e, String key, String what) {
        dispatch(e);
        if (LinkStatus.shouldQueue(host)) {
            queue.add(key, e.toString(), System.currentTimeMillis());
            save();
            EventLog.w("COT", what + " sent but TAK Server unreachable; queued for retry ("
                    + queue.size() + " in queue)");
            handler.removeCallbacks(flushTask);
            handler.postDelayed(flushTask, FLUSH_RETRY_MS);
            notifyChanged();
        } else {
            EventLog.i("COT", what + " sent (" + e.getType() + ", uid " + e.getUID() + ")");
        }
    }

    private final Runnable flushTask = this::flush;

    public void flush() {
        handler.removeCallbacks(flushTask);
        if (queue.isEmpty())
            return;
        if (LinkStatus.shouldQueue(host)) {
            handler.postDelayed(flushTask, FLUSH_RETRY_MS);
            return;
        }
        List<OutboundQueue.Entry> items = queue.drain();
        save();
        for (OutboundQueue.Entry it : items) {
            try {
                CotEvent e = CotEvent.parse(it.xml);
                if (e == null || !e.isValid()) {
                    EventLog.w("COT", "Dropping unparseable queued message (" + it.key + ")");
                    continue;
                }
                CotFactory.refreshTimes(e, staleFor(it.key));
                dispatch(e);
                long delay = (System.currentTimeMillis() - it.queuedAt) / 1000;
                EventLog.i("COT", "Delivered queued " + it.key + " message (" + e.getType()
                        + ") after " + delay + " s");
            } catch (Exception ex) {
                EventLog.e("COT", "Failed to deliver queued message", ex);
            }
        }
        notifyChanged();
    }

    private int staleFor(String key) {
        if (OutboundQueue.KEY_EMERGENCY.equals(key))
            return Math.max(120, settings.repeatSec() * 4);
        if (OutboundQueue.KEY_STATUS.equals(key))
            return settings.statusSec() * 3;
        if (OutboundQueue.KEY_CANCEL.equals(key))
            return 60;
        return 300;
    }

    private void dispatch(CotEvent e) {
        try {
            CotMapComponent.getExternalDispatcher().dispatch(e);
        } catch (Exception ex) {
            EventLog.e("COT", "Dispatch failed for " + e.getType(), ex);
        }
    }

    private void notifyChanged() {
        if (onQueueChanged != null)
            onQueueChanged.run();
    }

    // ------------------------------------------------------------ persistence

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (OutboundQueue.Entry e : queue.snapshot()) {
                JSONObject o = new JSONObject();
                o.put("k", e.key);
                o.put("x", e.xml);
                o.put("t", e.queuedAt);
                a.put(o);
            }
            settings.prefs().edit().putString(LoneWolfSettings.STATE_QUEUE, a.toString()).apply();
        } catch (Exception e) {
            EventLog.e("COT", "Failed to persist queue", e);
        }
    }

    private void load() {
        String s = settings.prefs().getString(LoneWolfSettings.STATE_QUEUE, null);
        if (s == null || s.isEmpty())
            return;
        try {
            JSONArray a = new JSONArray(s);
            List<OutboundQueue.Entry> list = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                list.add(new OutboundQueue.Entry(o.optString("k", null), o.getString("x"),
                        o.optLong("t", System.currentTimeMillis())));
            }
            queue.restore(list);
        } catch (Exception e) {
            EventLog.e("COT", "Failed to restore queue", e);
        }
    }
}
