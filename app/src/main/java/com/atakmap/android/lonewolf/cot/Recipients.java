package com.atakmap.android.lonewolf.cot;

import android.content.SharedPreferences;

import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.lonewolf.core.LoneWolfSettings;
import com.atakmap.android.lonewolf.log.EventLog;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resolves who receives alarms and status updates.
 * <ul>
 *   <li>CHANNEL (default): no explicit destination. The TAK Server delivers
 *   to everyone in the sender's active channels (groups), and mesh peers get it
 *   by broadcast.</li>
 *   <li>TEAM: callsigns of known contacts whose team color matches ours.</li>
 *   <li>CONTACTS: callsigns the user picked.</li>
 * </ul>
 * If TEAM or CONTACTS resolves to nobody, the message falls back to CHANNEL.
 * An alarm is never dropped because the recipient list is empty.
 */
public final class Recipients {

    private Recipients() {
    }

    /** @return callsigns for {@code <marti><dest/>}, or empty list for channel broadcast. */
    public static List<String> resolve(MapView mapView, LoneWolfSettings s) {
        switch (s.recipientMode()) {
            case TEAM: {
                List<String> team = teamCallsigns(mapView);
                if (team.isEmpty()) {
                    EventLog.w("COT", "No team members found; falling back to channel broadcast");
                    return Collections.emptyList();
                }
                return team;
            }
            case CONTACTS: {
                List<String> c = s.contacts();
                if (c.isEmpty()) {
                    EventLog.w("COT", "No contacts selected; falling back to channel broadcast");
                    return Collections.emptyList();
                }
                return c;
            }
            default:
                return Collections.emptyList();
        }
    }

    public static String describe(MapView mapView, LoneWolfSettings s) {
        switch (s.recipientMode()) {
            case TEAM:
                return "team " + myTeam(mapView);
            case CONTACTS:
                return s.contacts().size() + " selected contact(s)";
            default:
                return "channel";
        }
    }

    public static String myTeam(MapView mapView) {
        SharedPreferences p = android.preference.PreferenceManager
                .getDefaultSharedPreferences(mapView.getContext());
        return p.getString("locationTeam", "Cyan");
    }

    /** All individual contacts known to ATAK (excluding ourselves), sorted by callsign. */
    public static List<String> allContactCallsigns(MapView mapView) {
        List<String> out = new ArrayList<>();
        try {
            String self = MapView.getDeviceUid();
            for (Contact c : Contacts.getInstance().getAllContacts()) {
                if (c instanceof IndividualContact && !c.getUID().equals(self)
                        && c.getName() != null && !out.contains(c.getName()))
                    out.add(c.getName());
            }
        } catch (Throwable t) {
            EventLog.e("COT", "Unable to read ATAK contacts", t);
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static List<String> teamCallsigns(MapView mapView) {
        List<String> out = new ArrayList<>();
        String team = myTeam(mapView);
        try {
            String self = MapView.getDeviceUid();
            for (Contact c : Contacts.getInstance().getAllContacts()) {
                if (!(c instanceof IndividualContact) || c.getUID().equals(self))
                    continue;
                MapItem item = mapView.getRootGroup().deepFindUID(c.getUID());
                if (item != null && team.equalsIgnoreCase(item.getMetaString("team", ""))
                        && c.getName() != null)
                    out.add(c.getName());
            }
        } catch (Throwable t) {
            EventLog.e("COT", "Unable to resolve team members", t);
        }
        return out;
    }
}
