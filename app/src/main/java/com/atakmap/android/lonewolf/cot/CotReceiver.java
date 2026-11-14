package com.atakmap.android.lonewolf.cot;

import android.os.Bundle;
import android.os.Handler;

import com.atakmap.android.lonewolf.log.EventLog;
import com.atakmap.android.maps.MapView;
import com.atakmap.comms.CotServiceRemote;
import com.atakmap.coremap.cot.event.CotEvent;

/**
 * Listens to all CoT received by ATAK and picks out lone worker traffic:
 * status heartbeats, emergencies / cancels with a {@code <__lonewolf>} detail,
 * and acknowledgements.
 * <p>
 * <b>SDK check:</b> {@link CotServiceRemote} is the receive hook used here.
 * If your 5.6 SDK recommends a different listener (e.g. a
 * {@code CommsMapComponent} pre/post-receive hook), only this class needs to
 * change.
 */
public class CotReceiver implements CotServiceRemote.CotEventListener,
        CotServiceRemote.ConnectionListener {

    public interface Callback {
        void onWorkerStatus(WorkerStatus status, String cotType);

        void onAck(CotFactory.Ack ack);
    }

    private final Handler handler;
    private final Callback callback;
    private CotServiceRemote remote;

    public CotReceiver(Handler mainHandler, Callback callback) {
        this.handler = mainHandler;
        this.callback = callback;
    }

    public void start() {
        if (remote != null)
            return;
        remote = new CotServiceRemote();
        remote.setCotEventListener(this);
        remote.connect(this);
    }

    public void stop() {
        if (remote == null)
            return;
        try {
            remote.setCotEventListener(null);
            remote.disconnect();
        } catch (Exception ignored) {
        }
        remote = null;
    }

    @Override
    public void onCotEvent(CotEvent event, Bundle extra) {
        if (event == null || event.getType() == null)
            return;
        final String type = event.getType();
        final boolean ours = CotFactory.TYPE_STATUS.equals(type)
                || CotFactory.TYPE_ACK.equals(type)
                || CotFactory.TYPE_EMERGENCY.equals(type)
                || CotFactory.TYPE_CANCEL.equals(type);
        if (!ours)
            return;
        handler.post(() -> handle(event, type));
    }

    private void handle(CotEvent event, String type) {
        try {
            if (CotFactory.TYPE_ACK.equals(type)) {
                CotFactory.Ack ack = CotFactory.parseAck(event);
                if (ack != null)
                    callback.onAck(ack);
                return;
            }
            WorkerStatus s = CotFactory.parseStatus(event);
            if (s == null || s.uid.equals(MapView.getDeviceUid()))
                return; // not a lone worker message, or our own echo
            callback.onWorkerStatus(s, type);
        } catch (Exception e) {
            EventLog.e("COT", "Failed to handle received " + type, e);
        }
    }

    @Override
    public void onCotServiceConnected(Bundle fullServiceState) {
        EventLog.i("COT", "Receive hook connected");
    }

    @Override
    public void onCotServiceDisconnected() {
        EventLog.w("COT", "Receive hook disconnected");
    }
}
