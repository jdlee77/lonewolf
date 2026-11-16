# Lone Wolf: lone worker alarm plugin for ATAK

A lone worker alarm for **ATAK-CIV 5.6**. The worker arms it from a toolbar
button. The phone's accelerometer then watches for **inactivity** and
**man-down (falls)**, and an optional **periodic check-in** timer runs too. If
the worker doesn't respond to a local pre-alarm (notification plus beeping), the
plugin sends **ATAK's native 911 emergency** through the TAK Server, with
location, callsign, time of last motion and battery level.

Target device: Samsung Galaxy XCover 7 Pro, Android 17.

---

## How it works

```
             arm (manual)
 DISARMED ───────────────► ARMED ──── no motion for 5 min ────────┐
    ▲                        │  ──── check-in interval elapsed ───┤
    │ disarm                 │  ──── fall + 15 s stillness ───────┤
    └────────────────────────┤                                    ▼
                             │◄── motion ≥ 2 s (inactivity/fall) ─ PRE_ALARM
                             │◄── "I'M OK" ─────────────────────── (notification, beeps,
                             │                                      vibration, torch, screen)
                             │                                        │ 60 s, no response
                             │                                        ▼
                             └◄── manual "CANCEL ALARM" only ───── ALARM
                                  (sends b-a-o-can)                 b-a-o-tbl to TAK Server,
                                                                    re-sent every 30 s with
                                                                    fresh location/battery
```

| Requirement | Implementation |
|---|---|
| User turns it on | Manual **ARM / DISARM** in the pane. Disarming during an active alarm is refused. |
| Accelerometer | `TYPE_ACCELEROMETER` at 50 Hz on a background thread (`sensor/MotionMonitor`). |
| Inactivity and man-down (both) | Inactivity timer reset by sustained motion. Fall = free-fall followed by an impact, then stillness. |
| Periodic check-in | Selectable 15 min to 4 h. Only **I'M OK** satisfies a check-in; motion does not. |
| Dynamic sensitivity | Adaptive noise floor (see below) plus Low/Medium/High setting. |
| 5 min then 60 s defaults | Both configurable in seconds: no-motion time 10–14400 s (default 300 = 5 min), pre-alarm 10–600 s. |
| Local alert options | Beep, override silent mode, vibrate, torch strobe, wake screen, siren during alarm, silence siren on acknowledgement. Each is a separate checkbox. |
| Motion that lasts 2 s cancels the pre-alarm | Configurable 1–30 s. One short pause inside the motion is tolerated. |
| ATAK built-in emergency | `b-a-o-tbl` "911 Alert" in the same format as ATAK's Emergency tool, so every ATAK/WinTAK/iTAK client raises its native alert. |
| Recipients (default: channel) | Channel (TAK Server groups), my team color, or selected contacts. |
| Payload | Location, callsign, last motion, last check-in, battery (`<remarks>`, `<status battery>`, `<__lonewolf>`). |
| Manual cancel, acknowledgement | Worker must press **CANCEL ALARM** and confirm. Team members or supervisors press **Acknowledge**, and the worker sees "acknowledged by X". |
| Queued when offline | Persisted outbound queue, flushed when the server reconnects (timestamps refreshed). |
| Manual on/off, re-arm after restart | Session state is persisted. If ATAK restarts while armed, monitoring resumes automatically, including an active alarm. |
| Log everything | Daily audit log in `atak/tools/lonewolf/logs/` (90 days), viewable in the **Log** tab. |
| Supervisor list | **Team** tab: armed workers, state, last check-in, last motion, battery, last heard, Pan to, Acknowledge. |

### Motion detection that adapts to activity

Samples are grouped into 0.5 s windows (`sensor/MotionDetector.java`). Each
window is "moving" if either test passes:

* **Energy**: the RMS of linear acceleration is above
  `noiseFloor × multiplier + delta`.
* **Tilt**: the gravity direction rotated more than the tilt threshold inside
  the window, meaning the phone was turned.

The noise floor follows the background vibration level:

* A phone at rest has a low floor, so small movements count.
* **Steady vibration with no tilt** (vehicle, machinery, generator, vibrating
  surface) has high energy that hardly varies. It's classified as `VIBRATION`
  and absorbed into the floor within seconds. Machine vibration therefore can't
  keep resetting the timer while an unconscious worker lies still.
* Human movement is bursty and turns the phone, so it still registers on top of
  vibration.
* When the vibration stops, the floor decays back down.

The current activity level (STILL / LIGHT / ACTIVE / VIBRATION) appears in the
pane, in the status heartbeat and in the log.

| Sensitivity | Energy threshold | Tilt | Fall impact |
|---|---|---|---|
| High | floor × 1.8 + 0.15 m/s² | 8° | 2.0 g |
| Medium (default) | floor × 2.5 + 0.30 m/s² | 15° | 2.5 g |
| Low | floor × 3.5 + 0.60 m/s² | 25° | 3.0 g |

**Man-down** (`sensor/FallDetector.java`): free-fall below 0.45 g for at least
60 ms, then an impact above the threshold within 1 s. Motion in the first 2 s
after the impact (bounce or tumble) is ignored. If the phone stays still for the
configured stillness time (15 s by default), the pre-alarm starts straight away
without waiting for the inactivity timer. A dropped phone that gets picked up is
dismissed.

### CoT messages

| Purpose | Type | UID | Notes |
|---|---|---|---|
| Emergency | `b-a-o-tbl` | `<deviceUid>-9-1-1` | Native 911 Alert. Re-sent every 30 s while the alarm is active. |
| Cancel | `b-a-o-can` | `<deviceUid>-9-1-1` | `<emergency cancel="true">`. Sent only on manual cancel. |
| Status heartbeat | `t-x-lw-status` | `<deviceUid>-lonewolf` | Every 60 s while armed, and on every state change. Feeds the Team tab. Ignored by clients without the plugin. |
| Acknowledge | `t-x-lw-ack` | `<ackerUid>-lwack-<workerUid>` | `<__lonewolf_ack target=… byCallsign=…>`. |

Recipient routing: **Channel** sends without a `<marti>` element, so the TAK
Server delivers to everyone in your active groups and mesh peers receive it as
well. **Team** and **Contacts** add `<marti><dest callsign="…"/></marti>`. If
either resolves to nobody, the message falls back to channel. **An alarm is
never dropped for lack of recipients.**

Example emergency:

```xml
<event version="2.0" uid="ANDROID-abc123-9-1-1" type="b-a-o-tbl" how="h-e" time=… start=… stale=…>
  <point lat="38.8977" lon="-77.0365" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
  <detail>
    <link uid="ANDROID-abc123" type="a-f-G-U-C" relation="p-p"/>
    <contact callsign="WOLF1-Alert"/>
    <emergency type="911 Alert">WOLF1</emergency>
    <remarks>LONE WORKER ALARM - No movement detected. Callsign WOLF1. Last motion 14:02:11Z. Last check-in 13:30:00Z. Battery 54%.</remarks>
    <status battery="54"/>
    <__lonewolf v="1" callsign="WOLF1" state="ALARM" reason="INACTIVITY" activity="STILL"
                lastMotion="2026-11-14T14:02:11Z" lastCheckIn="2026-11-14T13:30:00Z"
                nextCheckIn="2026-11-14T14:30:00Z" battery="54" ackBy="" ackAt=""/>
  </detail>
</event>
```

---

## Project layout

```
app/src/main/
  assets/plugin.xml                     plugin entry point (IPlugin)
  AndroidManifest.xml                   ATAK plugin discovery stub + plugin-api meta-data
  java/com/atakmap/android/lonewolf/
    plugin/LoneWolfLifecycle.java       AbstractPlugin: tool + map component
    plugin/LoneWolfTool.java            toolbar button
    LoneWolfMapComponent.java           creates controller and drop-down
    LoneWolfDropDownReceiver.java       UI: Worker / Team / Settings / Log tabs
    core/AlarmStateMachine.java         pure-Java state machine (unit tested)
    core/LoneWolfController.java        wires sensors, timers, alerts, CoT
    core/LoneWolfSettings.java          preferences and persisted session
    sensor/MotionDetector.java          adaptive motion detection (unit tested)
    sensor/FallDetector.java            free-fall and impact (unit tested)
    sensor/MotionMonitor.java           Android SensorManager binding
    alert/LocalAlerter.java             notifications, tones, vibration, torch, screen
    cot/CotFactory.java                 build and parse CoT
    cot/CotSender.java                  dispatch and persisted offline queue
    cot/OutboundQueue.java              queue collapse/order logic (unit tested)
    cot/CotReceiver.java                incoming status / ack / emergency
    cot/LinkStatus.java                 TAK Server connection state
    cot/Recipients.java                 channel / team / contacts routing
    team/WorkerRegistry.java            supervisor list
    log/EventLog.java                   audit log
  res/                                  layout, strings, icon, styles
app/src/test/java/…                     JUnit tests (30)
scripts/run-core-tests.sh               run the tests without any SDK
```

---

## Building

### Prerequisites

* ATAK-CIV **5.6** SDK from tak.gov. It contains `atak-gradle-takdev.jar`,
  `main.jar`, `android_keystore` and `samples/plugintemplate`.
* Android Studio (recent) with Android SDK platform 35, and JDK 17.
* The **developer build** of ATAK-CIV 5.6 installed on the XCover. A dev
  plugin build is signed with the SDK debug key and only loads in the matching
  developer ATAK.

### Steps

1. Clone this repo **inside the SDK folder**, next to the template, so the
   default paths resolve:
   ```bash
   cd ATAK-CIV-5.6.x.x-SDK/samples
   git clone https://github.com/jdlee77/lonewolf.git
   ```
   If you keep it elsewhere, set `takdev.plugin` in `local.properties`
   (step 2).

2. Create `local.properties` (git-ignored). Copy it from the SDK's
   `samples/plugintemplate/local.properties` if present, or write:
   ```properties
   sdk.dir=/path/to/Android/Sdk
   takDebugKeyFile=/path/to/ATAK-CIV-5.6-SDK/android_keystore
   takDebugKeyFilePassword=android
   takDebugKeyAlias=androiddebugkey
   takDebugKeyPassword=android
   takReleaseKeyFile=/path/to/ATAK-CIV-5.6-SDK/android_keystore
   takReleaseKeyFilePassword=android
   takReleaseKeyAlias=androiddebugkey
   takReleaseKeyPassword=android
   # only if the repo is not at <SDK>/samples/lonewolf:
   # takdev.plugin=/path/to/ATAK-CIV-5.6-SDK/atak-gradle-takdev.jar
   ```

3. **Compare `app/build.gradle` with the SDK's
   `samples/plugintemplate/app/build.gradle`.** The build script here is
   modelled on the template but could not be built without the SDK. If the
   AGP, Gradle, compileSdk or takdev versions differ, use the template's values,
   or copy the template file over and re-apply the `LONEWOLF` lines (namespace,
   applicationId, versions). Do the same for `gradle/wrapper` if the template
   uses a different Gradle version.

4. Build and install:
   ```bash
   ./gradlew assembleCivDebug
   ```
   ```bash
   adb install -r app/build/outputs/apk/civ/debug/*.apk
   ```

5. In ATAK, open **Settings → Tool Preferences → Plugins** (or the Plugins
   tool) and load **Lone Wolf**. The wolf/person icon appears in the toolbar
   overflow.

### Running the unit tests

Without any SDK (JDK only):

```bash
./scripts/run-core-tests.sh
```

With the SDK configured:

```bash
./gradlew testCivDebugUnitTest
```

These cover the state machine (inactivity, check-in, fall, cancel/disarm rules,
restore), motion detection against synthetic walking / still / vehicle-vibration
traces, fall detection, and offline queue ordering.

### Points to verify against the 5.6 SDK

These ATAK APIs were written from the SDK's documented patterns but could not
be compiled here. If the build complains, these are the places to look. Each
one is isolated in a single class:

| API | Where | If it differs |
|---|---|---|
| `AbstractPlugin`, `AbstractPluginTool`, `PluginContextProvider` | `plugin/` | Mirror `samples/plugintemplate` (or `helloworld`) lifecycle classes. |
| `CotServiceRemote` (receive hook) | `cot/CotReceiver` | Swap in the SDK's recommended CoT receive listener. Only this class changes. |
| `CommsMapComponent.getAllPortsBundle()` | `cot/LinkStatus` | Called by reflection. If missing, it falls back to the Android network check (still queues when offline). |
| `Contacts.getAllContacts()`, marker `team` meta | `cot/Recipients` | Only affects Team/Contacts modes. Channel mode doesn't use it. |
| `CameraController.Programmatic.panTo` | Team tab "Pan to" | Cosmetic. |
| `com.atakmap.android.gui.PluginSpinner` | layout | Replace with plain `Spinner` if absent. |

---

## Device setup (XCover 7 Pro)

The plugin runs inside ATAK's process and uses ATAK's permissions and
foreground service. For reliable monitoring with the screen off:

1. **Battery: Unrestricted.** Settings → Apps → ATAK → Battery →
   **Unrestricted**. Also make sure ATAK is not in Settings → Battery → Background
   usage limits → **Sleeping / Deep sleeping apps**. The pane shows a warning
   banner while ATAK is battery-optimized.
2. **Notifications** allowed for ATAK, including the "Lone Wolf alarms"
   channel. Set it to pop up and show on the lock screen.
3. **Alarms & reminders** (exact alarms) allowed for ATAK if Android offers
   the toggle. Without it, the backstop wake-up is inexact (see below).
4. Optional: grant ATAK **Do Not Disturb access** so the alarm notification
   can break through DND. The beeps play on the *alarm* stream, which DND allows
   by default.
5. Optional: map the XCover **side key** to open ATAK so the worker can reach
   I'M OK quickly with gloves on.

**How it keeps running:**

* A partial wake lock is held while armed, and the accelerometer runs at 50 Hz.
* A 1 s tick drives the timers.
* An `AlarmManager` wake-up is set at the next deadline as a backstop in case
  the CPU sleeps anyway.
* A watchdog restarts the accelerometer if samples stop for 30 s.

Expect some extra battery drain while armed. Measure it on the XCover over a
full shift.

---

## Using it

* **Worker tab**: ARM, then carry on working.
  * **I'M OK**: check in now (also on the armed notification).
  * During a pre-alarm the state banner flashes with a countdown, and moving
    for 2 s or pressing I'M OK clears it. A check-in pre-alarm needs I'M OK.
  * After the alarm is sent, the banner shows who was alerted and any
    acknowledgement. **CANCEL ALARM** (with confirmation) is the only way out.
  * **Test local alert** previews the sound, vibration and torch settings.
* **Team tab** (supervisors and teammates): every worker running the plugin,
  sorted ALARM, PRE-ALARM, ARMED, then DISARMED, with last check-in, last motion,
  battery and last heard. "SIGNAL LOST" appears after 3 min without a heartbeat.
  **Acknowledge** tells the worker help is aware.
* **Settings tab**: all timers, sensitivity, alert outputs and recipients.
  Changes apply immediately, even while armed.
* **Log tab**: today's log. Files are in `atak/tools/lonewolf/logs/` for
  export.

Clients **without** the plugin (WinTAK, iTAK, other ATAKs, TAK Server) still
receive and display the native emergency and its cancel. They just don't get
the Team list or the Acknowledge button.

---

## Field test checklist

Run these with a test TAK Server, a second device or WinTAK as the supervisor,
and short timers (no-motion time 60 s, pre-alarm 20 s, check-in 15 min).

- [ ] Arm, leave the phone on a desk: pre-alarm at 1 min, alarm at 1:20. The
      supervisor sees the native 911 alert with remarks and battery.
- [ ] Pre-alarm, then pick up the phone and walk for 2 s: the pre-alarm clears.
      A 1 s bump does not clear it.
- [ ] Check-in pre-alarm: walking does **not** clear it. I'M OK does.
- [ ] Alarm: walking does not cancel it. CANCEL ALARM sends a cancel and the
      supervisor's alert clears.
- [ ] Acknowledge from the supervisor: the worker gets a "HELP IS AWARE"
      notification and banner.
- [ ] Drop the phone onto a cushion from about 1 m and leave it: fall pre-alarm
      after 15 s.
- [ ] Phone on the dashboard of an idling or moving vehicle, worker still: the
      pre-alarm still triggers (vibration absorbed). Check the log for
      `ACTIVITY -> VIBRATION`.
- [ ] Airplane mode, then let it alarm: "queued" shows in the pane. Turn
      airplane mode off and the emergency arrives at the server.
- [ ] Screen off, phone in pocket, 30 min: escalation still on time.
- [ ] Force-stop ATAK while armed, then relaunch: it re-arms automatically
      (log shows "re-arming automatically").
- [ ] Silent mode and DND on: the pre-alarm is still audible with "Override
      silent" checked.
- [ ] Team / Contacts recipient modes deliver only to the intended users.

---

## Limitations / notes

* Phone-based detection is a safety aid, not a certified lone worker device.
  Validate it against your organization's lone worker policy.
* If the phone is in a vehicle and the worker is unconscious, road bumps and
  turns (tilt) can still occasionally count as motion. Use the check-in timer
  as the second layer.
* If ATAK is force-stopped or the phone is powered off, nothing runs. The Team
  tab shows "SIGNAL LOST" after 3 min without heartbeats. That's the supervisor's
  indicator.
* A release (non-dev) build must be submitted to the TAK Product Center for
  signing to load on production ATAK.
