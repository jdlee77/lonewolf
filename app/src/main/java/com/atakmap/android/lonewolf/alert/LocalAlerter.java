package com.atakmap.android.lonewolf.alert;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import com.atakmap.android.lonewolf.core.AlarmStateMachine.Reason;
import com.atakmap.android.lonewolf.core.LoneWolfSettings;
import com.atakmap.android.lonewolf.log.EventLog;
import com.atakmap.android.lonewolf.plugin.R;

/**
 * Everything the worker sees, hears and feels on the local device:
 * notifications, beeping, siren, vibration, torch strobe and screen wake.
 * <p>
 * Runs inside the ATAK process. {@code hostContext} is ATAK's context (used
 * for system services, notifications and pending intents, which must belong
 * to the ATAK package); {@code pluginContext} is used only for plugin
 * resources.
 */
public class LocalAlerter {

    public static final String ACTION_IM_OK = "com.atakmap.android.lonewolf.IM_OK";
    public static final String ACTION_OPEN = "com.atakmap.android.lonewolf.OPEN";

    private static final String CH_STATUS = "lonewolf_status";
    private static final String CH_ALARM = "lonewolf_alarm";
    private static final int NOTIF_STATUS = 0x4C5701;
    private static final int NOTIF_ALERT = 0x4C5702;

    private final Context host;
    private final Context plugin;
    private final Handler handler;
    private final NotificationManager nm;
    private final AudioManager audio;
    private final Vibrator vibrator;
    private final PowerManager power;
    private final CameraManager camera;
    private Icon smallIcon;

    private ToneGenerator tone;
    private PowerManager.WakeLock screenLock;
    private int savedAlarmVolume = -1;
    private String torchId;
    private boolean torchOn;

    private boolean beeping;
    private boolean siren;
    private boolean strobing;
    private long preAlarmDeadline;

    public LocalAlerter(Context host, Context plugin, Handler handler) {
        this.host = host;
        this.plugin = plugin;
        this.handler = handler;
        this.nm = (NotificationManager) host.getSystemService(Context.NOTIFICATION_SERVICE);
        this.audio = (AudioManager) host.getSystemService(Context.AUDIO_SERVICE);
        this.power = (PowerManager) host.getSystemService(Context.POWER_SERVICE);
        this.camera = (CameraManager) host.getSystemService(Context.CAMERA_SERVICE);
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) host.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            this.vibrator = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            this.vibrator = (Vibrator) host.getSystemService(Context.VIBRATOR_SERVICE);
        }
        createChannels();
    }

    private void createChannels() {
        if (nm == null)
            return;
        NotificationChannel status = new NotificationChannel(CH_STATUS,
                plugin.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW);
        status.setShowBadge(false);
        nm.createNotificationChannel(status);

        NotificationChannel alarm = new NotificationChannel(CH_ALARM,
                plugin.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH);
        // Sound and vibration are produced by this class so they follow the
        // user's settings and can play on the alarm stream.
        alarm.setSound(null, null);
        alarm.enableVibration(false);
        alarm.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        alarm.setBypassDnd(true); // only honoured if the user grants DND access to ATAK
        nm.createNotificationChannel(alarm);
    }

    /** Notification icons must be resolvable by the system; render ours to a bitmap. */
    private Icon icon() {
        if (smallIcon == null) {
            Drawable d = plugin.getResources().getDrawable(R.drawable.ic_lonewolf, plugin.getTheme());
            Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 96, 96);
            d.draw(c);
            smallIcon = Icon.createWithBitmap(b);
        }
        return smallIcon;
    }

    private PendingIntent broadcast(String action, int code) {
        Intent i = new Intent(action).setPackage(host.getPackageName());
        return PendingIntent.getBroadcast(host, code, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent launchAtak() {
        Intent i = host.getPackageManager().getLaunchIntentForPackage(host.getPackageName());
        if (i == null)
            return broadcast(ACTION_OPEN, 3);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(host, 4, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // ------------------------------------------------------------ status

    public void showArmed(String text) {
        if (nm == null)
            return;
        Notification n = new Notification.Builder(host, CH_STATUS)
                .setSmallIcon(icon())
                .setContentTitle(plugin.getString(R.string.notif_armed_title))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(launchAtak())
                .addAction(new Notification.Action.Builder(icon(),
                        plugin.getString(R.string.im_ok), broadcast(ACTION_IM_OK, 1)).build())
                .build();
        post(NOTIF_STATUS, n);
    }

    public void clearArmed() {
        if (nm != null)
            nm.cancel(NOTIF_STATUS);
    }

    private void post(int id, Notification n) {
        try {
            nm.notify(id, n);
        } catch (SecurityException e) {
            EventLog.w("ALERT", "Notification blocked (POST_NOTIFICATIONS not granted to ATAK?)");
        }
    }

    // ------------------------------------------------------------ pre-alarm

    public void startPreAlarm(Reason reason, long deadline, LoneWolfSettings s) {
        stopAll();
        preAlarmDeadline = deadline;
        String title = plugin.getString(R.string.notif_prealarm_title);
        String text;
        switch (reason) {
            case CHECK_IN:
                text = plugin.getString(R.string.notif_prealarm_checkin);
                break;
            case FALL:
                text = plugin.getString(R.string.notif_prealarm_fall);
                break;
            default:
                text = plugin.getString(R.string.notif_prealarm_inactivity);
                break;
        }
        if (nm != null) {
            Notification n = new Notification.Builder(host, CH_ALARM)
                    .setSmallIcon(icon())
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setWhen(deadline)
                    .setContentIntent(launchAtak())
                    .setFullScreenIntent(launchAtak(), true)
                    .addAction(new Notification.Action.Builder(icon(),
                            plugin.getString(R.string.im_ok), broadcast(ACTION_IM_OK, 2)).build())
                    .build();
            post(NOTIF_ALERT, n);
        }
        if (s.alertOverrideSilent())
            raiseAlarmVolume();
        if (s.alertSound()) {
            beeping = true;
            handler.post(beepRunnable);
        }
        if (s.alertVibrate())
            vibrate(new long[] { 0, 400, 600 });
        if (s.alertTorch())
            startStrobe();
        if (s.alertWakeScreen())
            wakeScreen(deadline - System.currentTimeMillis() + 5000);
    }

    private final Runnable beepRunnable = new Runnable() {
        @Override
        public void run() {
            if (!beeping)
                return;
            long remaining = preAlarmDeadline - System.currentTimeMillis();
            playTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250);
            // escalate: faster beeps in the final 15 seconds
            handler.postDelayed(this, remaining < 15_000 ? 500 : 1000);
        }
    };

    // ------------------------------------------------------------ alarm

    public void startAlarm(Reason reason, String recipientsLabel, LoneWolfSettings s) {
        stopAll();
        String text = plugin.getString(R.string.notif_alarm_text, recipientsLabel);
        if (nm != null) {
            Notification n = new Notification.Builder(host, CH_ALARM)
                    .setSmallIcon(icon())
                    .setContentTitle(plugin.getString(R.string.notif_alarm_title))
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setContentIntent(launchAtak())
                    .setFullScreenIntent(launchAtak(), true)
                    .build();
            post(NOTIF_ALERT, n);
        }
        if (s.alarmSiren()) {
            if (s.alertOverrideSilent())
                raiseAlarmVolume();
            startSiren();
        }
        if (s.alertVibrate())
            vibrate(new long[] { 0, 1000, 500 });
        if (s.alertTorch())
            startStrobe();
        if (s.alertWakeScreen())
            wakeScreen(60_000);
    }

    private void startSiren() {
        siren = true;
        handler.post(sirenRunnable);
    }

    private final Runnable sirenRunnable = new Runnable() {
        @Override
        public void run() {
            if (!siren)
                return;
            playTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1800);
            handler.postDelayed(this, 2000);
        }
    };

    public void onAcknowledged(String byCallsign, LoneWolfSettings s) {
        if (s.silenceOnAck()) {
            siren = false;
            handler.removeCallbacks(sirenRunnable);
            if (vibrator != null)
                vibrator.cancel();
            stopStrobe();
        }
        playTone(ToneGenerator.TONE_PROP_ACK, 600);
        if (nm != null) {
            String text = plugin.getString(R.string.notif_ack_text, byCallsign);
            Notification n = new Notification.Builder(host, CH_ALARM)
                    .setSmallIcon(icon())
                    .setContentTitle(plugin.getString(R.string.notif_ack_title))
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setOngoing(true)
                    .setContentIntent(launchAtak())
                    .build();
            post(NOTIF_ALERT, n);
        }
    }

    /** Short sample of the pre-alarm outputs so the user can test settings. */
    public void test(LoneWolfSettings s) {
        if (s.alertSound()) {
            if (s.alertOverrideSilent())
                raiseAlarmVolume();
            playTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250);
            handler.postDelayed(() -> playTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250), 700);
            handler.postDelayed(this::restoreAlarmVolume, 2000);
        }
        if (s.alertVibrate())
            vibrateOnce(500);
        if (s.alertTorch()) {
            startStrobe();
            handler.postDelayed(this::stopStrobe, 2000);
        }
    }

    // ------------------------------------------------------------ stop

    public void stopAll() {
        beeping = false;
        siren = false;
        handler.removeCallbacks(beepRunnable);
        handler.removeCallbacks(sirenRunnable);
        if (tone != null) {
            tone.stopTone();
        }
        if (vibrator != null)
            vibrator.cancel();
        stopStrobe();
        releaseScreen();
        restoreAlarmVolume();
        if (nm != null)
            nm.cancel(NOTIF_ALERT);
    }

    public void dispose() {
        stopAll();
        clearArmed();
        if (tone != null) {
            tone.release();
            tone = null;
        }
    }

    // ------------------------------------------------------------ helpers

    private void playTone(int t, int durationMs) {
        try {
            if (tone == null)
                tone = new ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME);
            tone.startTone(t, durationMs);
        } catch (RuntimeException e) {
            EventLog.e("ALERT", "ToneGenerator failed", e);
        }
    }

    private void raiseAlarmVolume() {
        if (audio == null || savedAlarmVolume >= 0)
            return;
        try {
            savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM);
            audio.setStreamVolume(AudioManager.STREAM_ALARM,
                    audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        } catch (SecurityException e) {
            savedAlarmVolume = -1;
            EventLog.w("ALERT", "Cannot raise alarm volume (Do Not Disturb policy)");
        }
    }

    private void restoreAlarmVolume() {
        if (audio == null || savedAlarmVolume < 0)
            return;
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0);
        } catch (SecurityException ignored) {
        }
        savedAlarmVolume = -1;
    }

    private void vibrate(long[] pattern) {
        if (vibrator == null || !vibrator.hasVibrator())
            return;
        VibrationEffect effect = VibrationEffect.createWaveform(pattern, 1);
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
        } else {
            vibrator.vibrate(effect, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM).build());
        }
    }

    private void vibrateOnce(long ms) {
        if (vibrator != null && vibrator.hasVibrator())
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    private void startStrobe() {
        if (camera == null || strobing)
            return;
        try {
            for (String id : camera.getCameraIdList()) {
                Boolean flash = camera.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) {
                    torchId = id;
                    break;
                }
            }
        } catch (Exception e) {
            EventLog.e("ALERT", "Torch lookup failed", e);
        }
        if (torchId == null)
            return;
        strobing = true;
        handler.post(strobeRunnable);
    }

    private final Runnable strobeRunnable = new Runnable() {
        @Override
        public void run() {
            if (!strobing)
                return;
            setTorch(!torchOn);
            handler.postDelayed(this, 400);
        }
    };

    private void stopStrobe() {
        strobing = false;
        handler.removeCallbacks(strobeRunnable);
        if (torchOn)
            setTorch(false);
    }

    private void setTorch(boolean on) {
        try {
            camera.setTorchMode(torchId, on);
            torchOn = on;
        } catch (Exception e) {
            strobing = false;
            EventLog.w("ALERT", "Torch unavailable: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void wakeScreen(long ms) {
        if (power == null)
            return;
        releaseScreen();
        screenLock = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                | PowerManager.ON_AFTER_RELEASE, "lonewolf:screen");
        screenLock.acquire(Math.max(5000, ms));
    }

    private void releaseScreen() {
        if (screenLock != null && screenLock.isHeld())
            screenLock.release();
        screenLock = null;
    }
}
