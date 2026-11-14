package com.atakmap.android.lonewolf.cot;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Parcelable;

import com.atakmap.comms.CommsMapComponent;

/**
 * Works out whether messages can currently reach a TAK Server.
 * <p>
 * The ATAK stream state comes from {@code CommsMapComponent.getAllPortsBundle()},
 * called by reflection. That method and its bundle layout are not a documented
 * public contract, so any failure falls back to Android network availability.
 * <b>SDK check:</b> confirm the "streams" / "connected" / "enabled" keys against
 * {@code CommsMapComponent} in your 5.6 SDK.
 */
public final class LinkStatus {

    public enum State {
        /** At least one TAK Server stream is connected. */
        CONNECTED,
        /** Server streams are configured but none are connected. */
        DISCONNECTED,
        /** No TAK Server configured (mesh / multicast only). */
        NO_SERVERS,
        /** Could not read ATAK stream state. */
        UNKNOWN
    }

    private LinkStatus() {
    }

    public static State serverState() {
        try {
            CommsMapComponent cmc = CommsMapComponent.getInstance();
            if (cmc == null)
                return State.UNKNOWN;
            // Reflection so the plugin still compiles (and falls back) if this
            // method changes between SDK versions.
            Object r = cmc.getClass().getMethod("getAllPortsBundle").invoke(cmc);
            Bundle all = r instanceof Bundle ? (Bundle) r : null;
            if (all == null)
                return State.UNKNOWN;
            Parcelable[] streams = all.getParcelableArray("streams");
            if (streams == null || streams.length == 0)
                return State.NO_SERVERS;
            boolean anyEnabled = false;
            for (Parcelable p : streams) {
                if (!(p instanceof Bundle))
                    continue;
                Bundle b = (Bundle) p;
                if (!b.getBoolean("enabled", true))
                    continue;
                anyEnabled = true;
                if (b.getBoolean("connected", false))
                    return State.CONNECTED;
            }
            return anyEnabled ? State.DISCONNECTED : State.NO_SERVERS;
        } catch (Throwable t) {
            return State.UNKNOWN;
        }
    }

    public static boolean networkAvailable(Context host) {
        try {
            ConnectivityManager cm = (ConnectivityManager) host.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null)
                return true;
            Network n = cm.getActiveNetwork();
            if (n == null)
                return false;
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (SecurityException e) {
            return true;
        }
    }

    /** True when a message sent now would probably not reach the server. */
    public static boolean shouldQueue(Context host) {
        switch (serverState()) {
            case CONNECTED:
            case NO_SERVERS:
                return false;
            case DISCONNECTED:
                return true;
            default:
                return !networkAvailable(host);
        }
    }

    public static String describe(Context host) {
        switch (serverState()) {
            case CONNECTED:
                return "TAK Server connected";
            case DISCONNECTED:
                return "TAK Server DISCONNECTED";
            case NO_SERVERS:
                return "No TAK Server (mesh only)";
            default:
                return networkAvailable(host) ? "Network up (server state unknown)" : "No network";
        }
    }
}
