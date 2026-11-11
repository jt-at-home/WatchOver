# Test results — first implementation pass

**Environment:** Android 15 (API 35), x86_64 `google_apis` emulator, headless, on this machine.
**Date:** 2026-11-08. **Build:** `assembleDebug` from commit `cf32132` + the debug receiver.

Nothing here is a substitute for running on a real phone, but it does prove the
plumbing works end to end rather than merely compiling.

## Verified

| # | What | Evidence |
|---|---|---|
| 1 | The app installs, launches and does not crash | `Displayed com.watchover/.ui.MainActivity`, no `FATAL` in logcat |
| 2 | Arming starts a `specialUse` foreground service | `isForeground=true foregroundId=1002 types=0x40000000` on channel `watch_service`, `vis=SECRET` |
| 3 | An exact check alarm is registered for the next configured time | `RTC_WAKEUP tag=*walarm*:com.watchover.action.CHECK origWhen=2026-11-08 22:00:00 window=0 exactAllowReason=policy_permission` |
| 4 | Movement latches, the next check passes | `CHECK_PASSED movement since last check` |
| 5 | No movement raises the alert | `CHECK_ALERTING no movement since last check` → `ALERT_RAISED` |
| 6 | The alert notification is high-importance, ongoing, lock-screen visible, with a redacted public version | `id=1001 importance=4 channel=pickup_checkin flags=ONGOING_EVENT\|ONLY_ALERT_ONCE actions=1 vis=PUBLIC`; public text `Pickup check-in — open to respond` |
| 7 | Reminder and escalation-deadline alarms are scheduled with the alert | `tag=…action.REMINDER`, `tag=…action.DEADLINE` |
| 8 | Acknowledging closes the alert and cancels the alarms | Notification `id=1001` gone; dumpsys shows `Reason=alarm_cancelled` for `action.DEADLINE`; `ALERT_ACKNOWLEDGED phase=AWAITING_ACK` |
| 9 | Escalation sends the SMS and records the delivery result | `ESCALATION_STARTED to …4567` → `ESCALATION_SENT attempt=0` → `sent result=-1`, `delivered result=-1` |
| 10 | The alert text changes once the contact has been texted | `Your contact has been texted. Tap "I'm here" to send them an all-clear.` |
| 11 | **A check fires in Doze with the screen off** | Device forced with `dumpsys deviceidle force-idle` (`mState=IDLE`), screen slept; `CHECK_STARTED expected=1794162180000` arrived at `…180005` — 5 ms after the 12:23:00 target, and the alert was raised |
| 12 | A check with the watch stopped fails safe | `STOP_WATCH` → `CHECK`: `WATCH_RESTARTED not running at check`, `CHECK_ALERTING interval unobserved (watch not running)`, service restarted, alert raised |
| 13 | Killing the process restores the watch automatically | `kill -9` → `WATCH_STARTED` two seconds later (`START_STICKY`), no crash |
| 14 | 57 unit tests pass | `testDebugUnitTest`, 0 failures |
| 15 | Android lint passes | `lintDebug`, 0 errors |
| 16 | The full-screen activity closes when the alert is answered from the shade | `ALERT_ACKNOWLEDGED phase=AWAITING_ACK`, no lingering activity, no crash |
| 17 | Instrumented tests exercise the real platform | `connectedDebugAndroidTest`: 5 tests, 0 failures (`CheckInFlowTest`) |

## Not verifiable on an emulator — needs your phone

| Area | Why |
|---|---|
| Real motion sensing | The emulator has no `TYPE_MOTION_DETECT` or `TYPE_SIGNIFICANT_MOTION`, so it exercised only the accelerometer fallback (`source=ACCELEROMETER`). Your phone should report `source=MOTION_DETECT` or `SIGNIFICANT_MOTION`. |
| Real SMS | The emulator accepted the message and reported success through a simulated radio. Only a real carrier proves delivery. |
| Lock-screen appearance and full-screen intent | Needs a real lock screen and, on Android 14+, the full-screen special access grant. |
| OEM battery management | Xiaomi/Samsung/Huawei behaviours cannot be reproduced here. |
| Battery consumption | The emulator has no meaningful power model; `dumpsys batterystats` there is not representative. |
| Reboot handling | `BOOT_COMPLETED` cannot be triggered on this emulator without a full restart cycle. |

## Physical device pass — Pixel 9a, Android 17 (API 37)

Two platform behaviours only showed up on the real phone, not the emulator:

| # | Finding | Evidence | Fix |
|---|---|---|---|
| 18 | `NotificationManager.canUseFullScreenIntent()` returned true while the underlying app-op was `default` (denied). The alert was posted with its full-screen intent stripped and nothing woke the screen. | `cmd appops get … USE_FULL_SCREEN_INTENT` = `default; rejectTime=…`; the notification record had no `fullScreenIntent`. After `cmd appops set … allow`, a locked/Dozing device went `Awake` with `AlertActivity` on top. | Attach the full-screen intent unconditionally (the system strips it when not permitted) and check the app-op directly in the self-check (`FullScreenAccess`). |
| 19 | The test SMS threw `SecurityException: getGroupIdLevel1` despite `SEND_SMS` being granted to both the permission and the app-op. | Stack: `SmsManager.divideMessage → SmsMessage.fragmentText → calcUnicodeEncodingDetails → hasEmsSupport → TelephonyManager.getGroupIdLevel1`. | Stop calling `divideMessage`; split the message locally (`SmsText`). No `READ_PHONE_STATE` needed. Send then succeeds: `ESCALATION_SENT sent result=-1`. |
| 20 | Hardware motion sensing works on the device | `WATCH_STARTED source=SIGNIFICANT_MOTION` | — |

Both fixes are in the build installed on the phone. Unit tests rose to 63 with the
new splitter tests.

A third observation from the same session:

| # | Finding | Evidence | Change |
|---|---|---|---|
| 21 | A scheduled check at 22:20:00 fired 29 ms late and correctly alerted, because nothing had moved since the previous check at 22:14:08. `dumpsys sensorservice` showed the significant-motion sensor armed and connected to the service the whole time — it simply had nothing to report, which matches what that sensor is designed for (sustained movement, not a phone held still). | `CHECK_STARTED expected=…000` → `CHECK_ALERTING no movement since last check` | Screen-on now also counts as a movement signal, so lift-to-wake / tap-to-wake is captured even when significant motion is not. |
| 22 | The "last movement" time only updated when the flag flipped, so the UI could show a stale time. | Runtime `last_motion` unchanged across a latched period | Every motion event now updates the timestamp; the watch notification shows it and refreshes within a minute. |
| 25 | Reminders did not re-light the screen once it had slept: the notification kept the same id, so Android treated the update as not-new and never re-fired the full-screen intent. | Screen was `Dozing` at +5:30 (hold expired, 2-minute screen timeout), then still `Dozing` until the reminder | Each reminder now posts a fresh notification id (own id range, so it cannot collide with the watch notice) and the alert screen re-arms its 5-minute hold with a wake lock. Verified: `REMINDER_SENT` then `MOTION screen-on` 87 ms later. |
| 23 | Face down, the alert still woke the screen: pocket mode did **not** suppress the full-screen intent (proximity was pinned at 5 cm throughout). But the screen fell back to Dozing after about 14 seconds, because the alert activity never held it awake. | `mWakefulness` Awake at alert time, Dozing 14 s later | `FLAG_KEEP_SCREEN_ON` on the alert activity; re-verified Awake at +3 s, +15 s and +30 s with the phone face down. |
| 24 | The full-screen alert does not always take over: with the screen on (or the app in the foreground) Android shows a heads-up instead of launching the full-screen intent. This is platform policy — an FSI only takes over when the device is locked or the screen is off. | Heads-up observed while the screen was on; `AlertActivity` on top whenever the screen was off | The alert screen is now opened directly when our own process is in the foreground, so in-app testing shows the real screen; a backgrounded app with the screen on still gets a heads-up, by design. |

## Battery measurement — 45 hours of steady state (Pixel 9a, Android 17)

Measured 2026-11-10, over the window since installation (2026-11-08 21:51 → 2026-11-10 18:56),
during which the app ran three scheduled checks and raised no alerts.

| | |
|---|---|
| App-attributable drain | **0.0593 mAh** over 44.9 h → **≈0.032 mAh/day** |
| As a share of the 5032 mAh battery | **0.0006 % per day** |
| App's share of the phone's total drain (529 mAh) | **0.011 %** |
| CPU | 0.0587 mAh |
| Sensors (attributed) | 0.0005 mAh |
| Wake locks | 35 ms total, none held at measurement time |
| Scheduled checks | 3, each within **44–199 ms** of its exact time |

The un-attributed remainder is the sensor hub, which the OS does not charge to any
UID. At the ~1 mW a low-power significant-motion trigger typically draws, that is
roughly 0.1 %/day — still well inside the <1 %/day design target, and the only
part of the number not directly measured.

## Reproducing all of it

```bash
./gradlew testDebugUnitTest          # 57 pure JVM tests, no device
./gradlew connectedDebugAndroidTest  # 5 instrumented tests, needs a device/emulator
./gradlew lintDebug                  # 0 errors
./gradlew assembleDebug              # APK at app/build/outputs/apk/debug/
```

## Known gaps carried forward

- The instrumented suite covers the decision, acknowledgement, arming and deadline
  paths; it does not cover the sensor itself (needs a real phone).
- The manual checks above were driven through an adb-only debug receiver
  (`com.watchover.debug.*`, no-op in release builds).
- No battery measurement has been taken; that needs a real phone over 24–48 hours.
- The setup screen is functional but plain: times are edited with the system time
  picker, and most labels are literal strings rather than resources.
