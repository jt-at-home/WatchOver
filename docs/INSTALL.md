# Installing and testing WatchOver

The app is sideloaded. Nothing here needs a Play Store account, and the build is
deliberately not Play-Store compliant (see `docs/DESIGN.md` §3 on exact alarms).

## What you need

- An Android **13 or newer** phone with USB debugging enabled.
- `adb` on this machine (`/usr/bin/adb`).
- The debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Build

```bash
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # 54 unit tests, no device needed
```

`local.properties` points at the Android SDK and is intentionally not committed;
create it with `sdk.dir=$HOME/Android/Sdk` (or your own path) on another machine.
The Gradle wrapper downloads Gradle 9.9.1 on first use.

The build needs **JDK 17 or newer** (we use 21). Point `org.gradle.java.home` at a
JDK installation in your own `~/.gradle/gradle.properties`, or export `JAVA_HOME`
before building.

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If an older copy is installed, `adb install -r -d` or uninstall first:

```bash
adb uninstall com.watchover
```

### If permission toggles are greyed out

Android 13+ puts sideloaded apps under "restricted settings" until you allow
them: **Settings → Apps → WatchOver → ⋮ → Allow restricted settings**. Then grant
permissions normally. This is the single most common first-run snag.

## First run

The home screen has a live checklist. All of these must be green before the app
is trustworthy:

| Item | What it means |
|---|---|
| Notifications | The "I'm here" alert can be shown at all |
| Alert channel | The high-importance channel is not blocked |
| Exact timing | `Alarms & reminders` granted, so checks fire during Doze |
| Battery optimisation | Unrestricted, so the watch is not killed |
| Full-screen alert | Android 14+: allows the screen to light up for the alert |
| SMS permission | The escalation can actually be sent |
| Contact | A name and phone number for the person to text |

Then:

1. **Settings** → set your contact (use your own number for testing), the phone's
   name, and optionally the check times.
2. **Test SMS** — confirms the number and the carrier path. You should get a text
   from your own number starting "WatchOver test message from …".
3. **Armed** — turn the switch on. The silent "Check-in monitoring" notice appears
   in the notification shade; that is expected and can be hidden.

## Testing without waiting until noon

The home screen has two shortcuts, plus a deliberately blunt one:

- **Run check now** — evaluates immediately, exactly as a scheduled check would.
  With no movement latched this raises a *real* alert.
- **Pretend it moved** — latches the movement flag, so the next check passes.
- **Test alert** — raises a real full-screen alert. If you ignore it, it will
  really text your contact after the response window. Use it to check the
  lock-screen behaviour and the acknowledgement path.
- Or set the two check times a couple of minutes ahead in Settings and wait.

A sensible first pass: *Pretend it moved* → *Run check now* (should log
`CHECK_PASSED`) → *Run check now* again (should raise the alert) → tap **I'm
here** → check the Log screen.

## Watching it work

```bash
# What the app itself logged (also visible in the Log tab)
adb logcat | grep -iE "watchover|WatchService|AlarmReceiver"

# Is the check alarm actually registered?
adb shell dumpsys alarm | grep -A4 com.watchover

# Force Doze to prove the check still fires
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle unforce

# Battery attribution after a day
adb shell dumpsys batterystats com.watchover | head -40

# Is the foreground service alive?
adb shell dumpsys activity services com.watchover
```

The Log tab is the authoritative history: `CHECK_STARTED`, `CHECK_PASSED`,
`ALERT_RAISED`, `ALERT_ACKNOWLEDGED`, `ESCALATION_SENT`, `WATCH_RESTARTED`.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| No check at the scheduled time | Exact alarms not granted, or app battery state "Restricted" | Checklist items 3 and 4 |
| Alert appears but no sound | Alert channel set to silent/blocked | Notification settings → WatchOver → Check-in alerts |
| Screen stays dark when the alert fires | Full-screen notifications not granted (Android 14+) | Checklist item 5 |
| No escalation SMS | `SEND_SMS` denied, or the number is not a mobile | Checklist item 6; use E.164 (`+1…`) if in doubt |
| Watch stops after a while (Xiaomi, Huawei, Samsung) | OEM battery manager | Enable Autostart, set battery to "No restrictions", keep the app un-optimised |
| Checklist says "Starting…" forever | Service blocked from starting | Reboot the phone; confirm battery state is not Restricted |

## Removing it

Disarm in the app first (stops the watch and cancels alarms), then:

```bash
adb uninstall com.watchover
```

## If the screen does not light up for an alert

The full-screen *permission* is granted at install, but the special access is a
separate app-op that some Android versions report as "allowed" while it is still
denied. Check the state the system actually enforces:

```bash
adb shell cmd appops get com.watchover USE_FULL_SCREEN_INTENT   # want: allow
adb shell cmd appops set com.watchover USE_FULL_SCREEN_INTENT allow
```

The in-app "Allow" button opens the right settings screen
(`AppManageFullScreenIntent`).

## Driving it from adb (debug builds only)

The debug build exposes a test receiver so you can exercise the app without
tapping through the UI. In release builds every action is a no-op.

```bash
P=com.watchover
R=$P/.debug.DebugReceiver

# Have a contact, so the app can arm
adb shell am broadcast -a com.watchover.debug.CONFIGURE -n $R --es number 5551234567 --es name Sam

adb shell am broadcast -a com.watchover.debug.ARM        -n $R
adb shell am broadcast -a com.watchover.debug.CHECK      -n $R   # evaluate now
adb shell am broadcast -a com.watchover.debug.MOVE       -n $R   # latch movement
adb shell am broadcast -a com.watchover.debug.ACK        -n $R   # press "I'm here"
adb shell am broadcast -a com.watchover.debug.DEADLINE   -n $R   # force the escalation timer
adb shell am broadcast -a com.watchover.debug.DISARM     -n $R
adb shell am broadcast -a com.watchover.debug.STOP_WATCH -n $R   # simulate a lost watch
adb shell am broadcast -a com.watchover.debug.SET_TIME   -n $R --es time 12:23   # one-off check time

# Read the event log the app writes
adb shell run-as com.watchover cat files/events.log
```

A useful Doze rehearsal:

```bash
adb shell am broadcast -a com.watchover.debug.SET_TIME -n $R --es time "$(date -d '+3 minutes' +%H:%M)"
adb shell dumpsys deviceidle force-idle
adb shell input keyevent KEYCODE_SLEEP
# wait for the time to pass, then
adb shell run-as com.watchover cat files/events.log | tail
adb shell dumpsys deviceidle unforce
```
