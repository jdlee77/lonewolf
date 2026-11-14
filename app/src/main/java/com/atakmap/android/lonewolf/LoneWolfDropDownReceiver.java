package com.atakmap.android.lonewolf;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.gui.PluginSpinner;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.lonewolf.core.AlarmStateMachine;
import com.atakmap.android.lonewolf.core.AlarmStateMachine.State;
import com.atakmap.android.lonewolf.core.LoneWolfController;
import com.atakmap.android.lonewolf.core.LoneWolfSettings;
import com.atakmap.android.lonewolf.cot.CotFactory;
import com.atakmap.android.lonewolf.cot.LinkStatus;
import com.atakmap.android.lonewolf.cot.Recipients;
import com.atakmap.android.lonewolf.cot.WorkerStatus;
import com.atakmap.android.lonewolf.log.EventLog;
import com.atakmap.android.lonewolf.plugin.R;
import com.atakmap.android.lonewolf.sensor.MotionDetector;
import com.atakmap.android.lonewolf.team.WorkerRegistry;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.CameraController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The plugin's drop-down pane with four tabs: Worker (arm / I'm OK / cancel),
 * Team (armed lone workers and acknowledgements), Settings and Log.
 */
public class LoneWolfDropDownReceiver extends DropDownReceiver implements
        DropDown.OnStateListener, LoneWolfController.UiListener, WorkerRegistry.Listener {

    public static final String SHOW = "com.atakmap.android.lonewolf.SHOW";

    private static final int C_DISARMED = Color.parseColor("#455A64");
    private static final int C_ARMED = Color.parseColor("#2E7D32");
    private static final int C_PRE = Color.parseColor("#EF6C00");
    private static final int C_ALARM = Color.parseColor("#C62828");
    private static final int C_DARK = Color.parseColor("#3E2723");

    private final Context plugin;
    private final LoneWolfController ctl;
    private final LoneWolfSettings settings;
    private final View root;
    private boolean visible;
    private long lastTeamRender;

    // tabs
    private final View[] panels = new View[4];
    private final Button[] tabs = new Button[4];

    // worker tab
    private TextView tvState, tvDetail, tvWarning;
    private Button btnArm, btnOk, btnCancel;

    // team tab
    private LinearLayout teamList;
    private TextView tvTeamEmpty;

    // settings tab
    private EditText etInactivity, etPreAlarm, etMotion, etFallStill, etRepeat, etStatus;
    private PluginSpinner spSensitivity, spCheckIn, spRecipients;
    private CheckBox cbFall, cbCheckIn, cbSound, cbOverride, cbVibrate, cbTorch, cbWake,
            cbSiren, cbSilenceAck;
    private TextView tvContacts;
    private final List<String> pendingContacts = new ArrayList<>();

    // log tab
    private TextView tvLog, tvLogPath;

    public LoneWolfDropDownReceiver(MapView mapView, Context pluginContext,
            LoneWolfController controller) {
        super(mapView);
        this.plugin = pluginContext;
        this.ctl = controller;
        this.settings = controller.settings();
        this.root = PluginLayoutInflater.inflate(pluginContext, R.layout.lonewolf_pane, null);
        bindTabs();
        bindWorker();
        bindTeam();
        bindSettings();
        bindLog();
        ctl.addUiListener(this);
        ctl.registry().addListener(this);
    }

    @Override
    protected void disposeImpl() {
        ctl.removeUiListener(this);
        ctl.registry().removeListener(this);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!SHOW.equals(intent.getAction()))
            return;
        if (!visible) {
            showDropDown(root, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, false, this);
            visible = true;
        }
        State s = ctl.stateMachine().getState();
        if (s == State.PRE_ALARM || s == State.ALARM)
            selectTab(0);
        refreshAll();
    }

    // ================================================================ tabs

    private void bindTabs() {
        int[] tabIds = { R.id.tab_worker, R.id.tab_team, R.id.tab_settings, R.id.tab_log };
        int[] panelIds = { R.id.panel_worker, R.id.panel_team, R.id.panel_settings, R.id.panel_log };
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            tabs[i] = root.findViewById(tabIds[i]);
            panels[i] = root.findViewById(panelIds[i]);
            tabs[i].setOnClickListener(v -> selectTab(idx));
        }
        selectTab(0);
    }

    private void selectTab(int idx) {
        for (int i = 0; i < 4; i++) {
            panels[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            tabs[i].setAlpha(i == idx ? 1f : 0.55f);
        }
        if (idx == 1)
            renderTeam();
        else if (idx == 2)
            loadSettings();
        else if (idx == 3)
            renderLog();
    }

    // ================================================================ worker tab

    private void bindWorker() {
        tvState = root.findViewById(R.id.tv_state);
        tvDetail = root.findViewById(R.id.tv_detail);
        tvWarning = root.findViewById(R.id.tv_warning);
        btnArm = root.findViewById(R.id.btn_arm);
        btnOk = root.findViewById(R.id.btn_ok);
        btnCancel = root.findViewById(R.id.btn_cancel_alarm);
        Button btnTest = root.findViewById(R.id.btn_test);

        btnArm.setOnClickListener(v -> {
            State s = ctl.stateMachine().getState();
            if (s == State.DISARMED) {
                ctl.arm();
            } else if (s == State.ALARM) {
                toast(R.string.disarm_refused);
            } else {
                confirm(R.string.confirm_disarm_title, R.string.confirm_disarm_msg, () -> {
                    if (!ctl.disarm())
                        toast(R.string.disarm_refused);
                });
            }
        });
        btnOk.setOnClickListener(v -> ctl.imOk());
        btnCancel.setOnClickListener(v -> confirm(R.string.confirm_cancel_title,
                R.string.confirm_cancel_msg, ctl::cancelAlarm));
        btnTest.setOnClickListener(v -> ctl.testAlert());
    }

    private void renderWorker() {
        AlarmStateMachine sm = ctl.stateMachine();
        long now = System.currentTimeMillis();
        State s = sm.getState();

        String label;
        int color;
        switch (s) {
            case ARMED:
                label = plugin.getString(R.string.state_armed);
                color = C_ARMED;
                break;
            case PRE_ALARM:
                label = plugin.getString(R.string.state_prealarm,
                        LoneWolfController.reasonLabel(sm.getReason()),
                        mmss(sm.preAlarmRemainingMs(now)));
                // flash while counting down
                color = (now / 1000) % 2 == 0 ? C_PRE : C_DARK;
                break;
            case ALARM:
                label = plugin.getString(R.string.state_alarm,
                        LoneWolfController.reasonLabel(sm.getReason()));
                color = C_ALARM;
                break;
            default:
                label = plugin.getString(R.string.state_disarmed);
                color = C_DISARMED;
                break;
        }
        tvState.setText(label);
        tvState.setBackgroundColor(color);

        StringBuilder d = new StringBuilder();
        if (s != State.DISARMED) {
            if (s == State.ARMED) {
                d.append("No-motion alarm in: ").append(mmss(sm.inactivityRemainingMs(now)));
                if (sm.isFallPending())
                    d.append("  (possible fall, waiting for movement)");
                d.append('\n');
            }
            long next = sm.getNextCheckInMs();
            d.append("Next check-in: ")
                    .append(next > 0 ? CotFactory.hhmm(next) + " (" + mmss(Math.max(0, next - now)) + ")"
                            : "off")
                    .append('\n');
            d.append("Last motion: ").append(ago(sm.getLastMotionMs(), now)).append('\n');
            d.append("Last check-in: ").append(ago(sm.getLastCheckInMs(), now)).append('\n');
            d.append("Activity: ").append(ctl.activity()).append('\n');
            if (s == State.ALARM) {
                d.append("Alarm sent to: ").append(Recipients.describe(getMapView(), settings)).append('\n');
                d.append(ctl.ackBy().isEmpty() ? "Waiting for acknowledgement…"
                        : "ACKNOWLEDGED by " + ctl.ackBy() + " at " + CotFactory.hhmm(ctl.ackAt()))
                        .append('\n');
            }
        }
        d.append(LinkStatus.describe(getMapView().getContext()));
        int q = ctl.queuedCount();
        if (q > 0)
            d.append(" • ").append(q).append(" message(s) queued");
        d.append('\n').append("Sensor: ").append(ctl.sensorDiagnostics());
        tvDetail.setText(d.toString());

        btnArm.setText(s == State.DISARMED ? R.string.arm : R.string.disarm);
        btnArm.setEnabled(s != State.ALARM);
        btnOk.setVisibility(s == State.ARMED || s == State.PRE_ALARM ? View.VISIBLE : View.GONE);
        btnCancel.setVisibility(s == State.ALARM ? View.VISIBLE : View.GONE);

        tvWarning.setVisibility(ctl.isIgnoringBatteryOptimizations() ? View.GONE : View.VISIBLE);
    }

    // ================================================================ team tab

    private void bindTeam() {
        teamList = root.findViewById(R.id.team_list);
        tvTeamEmpty = root.findViewById(R.id.tv_team_empty);
    }

    @Override
    public void onRegistryChanged() {
        getMapView().post(() -> {
            if (visible && panels[1].getVisibility() == View.VISIBLE)
                renderTeam();
        });
    }

    private void renderTeam() {
        lastTeamRender = System.currentTimeMillis();
        long now = lastTeamRender;
        List<WorkerStatus> list = ctl.registry().list(now);
        teamList.removeAllViews();
        tvTeamEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        for (WorkerStatus w : list)
            teamList.addView(teamRow(w, now));
    }

    private View teamRow(final WorkerStatus w, long now) {
        LinearLayout row = new LinearLayout(plugin);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(16, 12, 16, 12);
        boolean stale = WorkerRegistry.isStale(w, now);
        int color;
        switch (w.state) {
            case "ALARM":
                color = C_ALARM;
                break;
            case "PRE_ALARM":
                color = C_PRE;
                break;
            case "ARMED":
                color = stale ? Color.DKGRAY : C_ARMED;
                break;
            default:
                color = C_DISARMED;
        }
        row.setBackgroundColor(color);

        TextView title = new TextView(plugin);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(16);
        String state = w.state.replace('_', '-');
        if (!"NONE".equals(w.reason) && !"ARMED".equals(w.state) && !"DISARMED".equals(w.state))
            state += " – " + w.reason;
        title.setText(w.callsign + "   " + state + (stale ? "   (SIGNAL LOST)" : ""));
        row.addView(title);

        TextView info = new TextView(plugin);
        info.setTextColor(Color.WHITE);
        StringBuilder sb = new StringBuilder();
        sb.append("Last check-in: ").append(ago(w.lastCheckInMs, now));
        if (w.nextCheckInMs > 0)
            sb.append("   next ").append(CotFactory.hhmm(w.nextCheckInMs));
        sb.append('\n').append("Last motion: ").append(ago(w.lastMotionMs, now));
        sb.append('\n').append("Battery: ").append(w.battery >= 0 ? w.battery + "%" : "?")
                .append("   Last heard: ").append(ago(w.lastHeardMs, now));
        if (w.ackBy != null && !w.ackBy.isEmpty())
            sb.append('\n').append("Acknowledged by ").append(w.ackBy)
                    .append(w.ackAtMs > 0 ? " at " + CotFactory.hhmm(w.ackAtMs) : "");
        info.setText(sb.toString());
        row.addView(info);

        LinearLayout buttons = new LinearLayout(plugin);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button pan = new Button(plugin);
        pan.setText(R.string.pan_to);
        pan.setOnClickListener(v -> panTo(w));
        buttons.addView(pan);
        if ("ALARM".equals(w.state) || "PRE_ALARM".equals(w.state)) {
            Button ack = new Button(plugin);
            ack.setText(R.string.acknowledge);
            ack.setOnClickListener(v -> confirm(R.string.confirm_ack_title,
                    R.string.confirm_ack_msg, () -> {
                        ctl.acknowledge(w);
                        renderTeam();
                    }));
            buttons.addView(ack);
        }
        row.addView(buttons);

        LinearLayout wrap = new LinearLayout(plugin);
        wrap.setPadding(0, 0, 0, 8);
        wrap.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private void panTo(WorkerStatus w) {
        MapView mv = getMapView();
        if (mv.getRootGroup().deepFindUID(w.uid) != null) {
            Intent i = new Intent("com.atakmap.android.maps.FOCUS");
            i.putExtra("uid", w.uid);
            AtakBroadcast.getInstance().sendBroadcast(i);
        } else if (w.hasLocation()) {
            CameraController.Programmatic.panTo(mv.getRenderer3(), new GeoPoint(w.lat, w.lon), true);
        } else {
            toast(R.string.no_location);
        }
    }

    // ================================================================ settings tab

    private void bindSettings() {
        etInactivity = root.findViewById(R.id.et_inactivity);
        etPreAlarm = root.findViewById(R.id.et_prealarm);
        etMotion = root.findViewById(R.id.et_motion);
        etFallStill = root.findViewById(R.id.et_fall_still);
        etRepeat = root.findViewById(R.id.et_repeat);
        etStatus = root.findViewById(R.id.et_status);
        spSensitivity = root.findViewById(R.id.sp_sensitivity);
        spCheckIn = root.findViewById(R.id.sp_checkin);
        spRecipients = root.findViewById(R.id.sp_recipients);
        cbFall = root.findViewById(R.id.cb_fall);
        cbCheckIn = root.findViewById(R.id.cb_checkin);
        cbSound = root.findViewById(R.id.cb_sound);
        cbOverride = root.findViewById(R.id.cb_override);
        cbVibrate = root.findViewById(R.id.cb_vibrate);
        cbTorch = root.findViewById(R.id.cb_torch);
        cbWake = root.findViewById(R.id.cb_wake);
        cbSiren = root.findViewById(R.id.cb_siren);
        cbSilenceAck = root.findViewById(R.id.cb_silence_ack);
        tvContacts = root.findViewById(R.id.tv_contacts);

        spSensitivity.setAdapter(adapter(new String[] { "Low", "Medium", "High" }));
        String[] checkIn = new String[LoneWolfSettings.CHECK_IN_CHOICES_MIN.length];
        for (int i = 0; i < checkIn.length; i++) {
            int m = LoneWolfSettings.CHECK_IN_CHOICES_MIN[i];
            checkIn[i] = m < 60 ? m + " min" : (m % 60 == 0 ? m / 60 + " h" : m / 60 + " h " + m % 60 + " min");
        }
        spCheckIn.setAdapter(adapter(checkIn));
        spRecipients.setAdapter(adapter(new String[] {
                "Channel (all in my groups)", "My team (same team color)", "Selected contacts" }));

        root.findViewById(R.id.btn_contacts).setOnClickListener(v -> pickContacts());
        root.findViewById(R.id.btn_save).setOnClickListener(v -> saveSettings());
    }

    private ArrayAdapter<String> adapter(String[] items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(plugin, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private void loadSettings() {
        etInactivity.setText(String.valueOf(settings.inactivityMin()));
        etPreAlarm.setText(String.valueOf(settings.preAlarmSec()));
        etMotion.setText(String.valueOf(settings.motionSec()));
        etFallStill.setText(String.valueOf(settings.fallStillSec()));
        etRepeat.setText(String.valueOf(settings.repeatSec()));
        etStatus.setText(String.valueOf(settings.statusSec()));
        for (EditText e : new EditText[] { etInactivity, etPreAlarm, etMotion, etFallStill, etRepeat, etStatus })
            e.setInputType(InputType.TYPE_CLASS_NUMBER);
        spSensitivity.setSelection(settings.sensitivity().ordinal());
        int ci = 0;
        for (int i = 0; i < LoneWolfSettings.CHECK_IN_CHOICES_MIN.length; i++)
            if (LoneWolfSettings.CHECK_IN_CHOICES_MIN[i] <= settings.checkInMin())
                ci = i;
        spCheckIn.setSelection(ci);
        spRecipients.setSelection(settings.recipientMode().ordinal());
        cbFall.setChecked(settings.fallEnabled());
        cbCheckIn.setChecked(settings.checkInEnabled());
        cbSound.setChecked(settings.alertSound());
        cbOverride.setChecked(settings.alertOverrideSilent());
        cbVibrate.setChecked(settings.alertVibrate());
        cbTorch.setChecked(settings.alertTorch());
        cbWake.setChecked(settings.alertWakeScreen());
        cbSiren.setChecked(settings.alarmSiren());
        cbSilenceAck.setChecked(settings.silenceOnAck());
        pendingContacts.clear();
        pendingContacts.addAll(settings.contacts());
        renderContacts();
    }

    private void renderContacts() {
        tvContacts.setText(pendingContacts.isEmpty() ? plugin.getString(R.string.no_contacts)
                : android.text.TextUtils.join(", ", pendingContacts));
    }

    private void pickContacts() {
        final List<String> all = Recipients.allContactCallsigns(getMapView());
        for (String c : pendingContacts)
            if (!all.contains(c))
                all.add(c); // keep selections that are currently offline
        if (all.isEmpty()) {
            toast(R.string.no_contacts_known);
            return;
        }
        final String[] names = all.toArray(new String[0]);
        final boolean[] checked = new boolean[names.length];
        for (int i = 0; i < names.length; i++)
            checked[i] = pendingContacts.contains(names[i]);
        new AlertDialog.Builder(getMapView().getContext())
                .setTitle(plugin.getString(R.string.pick_contacts))
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    pendingContacts.clear();
                    for (int i = 0; i < names.length; i++)
                        if (checked[i])
                            pendingContacts.add(names[i]);
                    renderContacts();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int num(EditText e, int def) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private void saveSettings() {
        LoneWolfSettings.RecipientMode mode =
                LoneWolfSettings.RecipientMode.values()[spRecipients.getSelectedItemPosition()];
        if (mode == LoneWolfSettings.RecipientMode.CONTACTS && pendingContacts.isEmpty()) {
            toast(R.string.no_contacts);
            return;
        }
        settings.prefs().edit()
                .putInt(LoneWolfSettings.INACTIVITY_MIN, num(etInactivity, 5))
                .putInt(LoneWolfSettings.PRE_ALARM_SEC, num(etPreAlarm, 60))
                .putInt(LoneWolfSettings.MOTION_SEC, num(etMotion, 2))
                .putInt(LoneWolfSettings.FALL_STILL_SEC, num(etFallStill, 15))
                .putInt(LoneWolfSettings.REPEAT_SEC, num(etRepeat, 30))
                .putInt(LoneWolfSettings.STATUS_SEC, num(etStatus, 60))
                .putString(LoneWolfSettings.SENSITIVITY,
                        MotionDetector.Sensitivity.values()[spSensitivity.getSelectedItemPosition()].name())
                .putInt(LoneWolfSettings.CHECKIN_MIN,
                        LoneWolfSettings.CHECK_IN_CHOICES_MIN[spCheckIn.getSelectedItemPosition()])
                .putString(LoneWolfSettings.RECIPIENTS, mode.name())
                .putBoolean(LoneWolfSettings.FALL_ENABLED, cbFall.isChecked())
                .putBoolean(LoneWolfSettings.CHECKIN_ENABLED, cbCheckIn.isChecked())
                .putBoolean(LoneWolfSettings.ALERT_SOUND, cbSound.isChecked())
                .putBoolean(LoneWolfSettings.ALERT_OVERRIDE_SILENT, cbOverride.isChecked())
                .putBoolean(LoneWolfSettings.ALERT_VIBRATE, cbVibrate.isChecked())
                .putBoolean(LoneWolfSettings.ALERT_TORCH, cbTorch.isChecked())
                .putBoolean(LoneWolfSettings.ALERT_WAKE_SCREEN, cbWake.isChecked())
                .putBoolean(LoneWolfSettings.ALARM_SIREN, cbSiren.isChecked())
                .putBoolean(LoneWolfSettings.SILENCE_ON_ACK, cbSilenceAck.isChecked())
                .apply();
        settings.setContacts(pendingContacts);
        EventLog.i("USER", "Settings saved");
        ctl.applySettings();
        loadSettings(); // show clamped values
        toast(R.string.settings_saved);
    }

    // ================================================================ log tab

    private void bindLog() {
        tvLog = root.findViewById(R.id.tv_log);
        tvLogPath = root.findViewById(R.id.tv_log_path);
        root.findViewById(R.id.btn_log_refresh).setOnClickListener(v -> renderLog());
    }

    private void renderLog() {
        tvLogPath.setText(EventLog.getDirectory() != null ? EventLog.getDirectory().getAbsolutePath() : "");
        new Thread(() -> {
            final String text = EventLog.tail(300);
            getMapView().post(() -> tvLog.setText(text.isEmpty() ? "(no entries today)" : text));
        }, "lonewolf-log-read").start();
    }

    // ================================================================ refresh

    @Override
    public void onLoneWolfChanged() {
        if (!visible)
            return;
        getMapView().post(this::refreshVisible);
    }

    private void refreshVisible() {
        if (panels[0].getVisibility() == View.VISIBLE)
            renderWorker();
        else if (panels[1].getVisibility() == View.VISIBLE
                && System.currentTimeMillis() - lastTeamRender > 5000)
            renderTeam(); // keep "last heard" ages current
    }

    private void refreshAll() {
        renderWorker();
        if (panels[1].getVisibility() == View.VISIBLE)
            renderTeam();
    }

    // ================================================================ helpers

    private void confirm(int title, int msg, Runnable onYes) {
        new AlertDialog.Builder(getMapView().getContext())
                .setTitle(plugin.getString(title))
                .setMessage(plugin.getString(msg))
                .setPositiveButton(android.R.string.yes, (d, w) -> onYes.run())
                .setNegativeButton(android.R.string.no, null)
                .show();
    }

    private void toast(int res) {
        Toast.makeText(getMapView().getContext(), plugin.getString(res), Toast.LENGTH_LONG).show();
    }

    private static String mmss(long ms) {
        if (ms < 0)
            return "--:--";
        long s = ms / 1000;
        if (s >= 3600)
            return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private static String ago(long t, long now) {
        if (t <= 0)
            return "never";
        long s = Math.max(0, (now - t) / 1000);
        String rel = s < 60 ? s + " s ago" : s < 3600 ? (s / 60) + " min ago"
                : (s / 3600) + " h " + ((s / 60) % 60) + " min ago";
        return CotFactory.hhmm(t) + " (" + rel + ")";
    }

    // ================================================================ DropDown.OnStateListener

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownVisible(boolean v) {
        visible = v;
        if (v)
            refreshAll();
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
        visible = false;
    }
}
