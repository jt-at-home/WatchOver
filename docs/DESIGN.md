# WatchOver — draft design

| | |
|---|---|
| Status | Draft, for review — **no code written yet** |
| Revision | 5 (2026-11-08) |
| Target | Android 13+ (minSdk 33), compile/target 35, sideloaded |
| Author | jt |

**Contents**

1. [Summary](#1-summary) · 2. [Goals and non-goals](#2-goals-and-non-goals) · 3. [Platform constraints](#3-platform-constraints-that-drive-the-design) · 4. [Architecture](#4-architecture) · 5. [The daily cycle](#5-the-daily-cycle) · 6. [State machine](#6-state-machine) · 7. [Scheduling](#7-scheduling) · 8. [Motion detection](#8-motion-detection) · 9. [Alert and acknowledgement](#9-alert-and-acknowledgement) · 10. [Service notification visibility](#10-service-notification-visibility) · 11. [Escalation](#11-escalation) · 12. [Configuration](#12-configuration-schema-defaults-validation) · 13. [Storage](#13-storage) · 14. [Permissions and onboarding](#14-permissions-and-onboarding) · 15. [Risk register](#15-risk-register--issues-and-proposed-solutions) · 16. [Battery budget](#16-battery-budget) · 17. [Test plan and acceptance criteria](#17-test-plan-and-acceptance-criteria) · 18. [Implementation outline](#18-implementation-outline) · 19. [Assumptions and deferred work](#19-assumptions-and-deferred-work) · 20. [Confirmed product decisions](#20-confirmed-product-decisions) · 21. [Implementation notes](#21-implementation-notes) · [Appendix: revision history](#appendix-revision-history)

---

## 1. Summary

WatchOver checks twice a day — default **12:00 and 22:00** — whether the phone has been picked up or moved since the previous check. The owner installs and configures it, and nominates **one family member or friend** to be alerted.

- **Movement detected** → clear the flag, go back to sleep until the next check. No user involvement.
- **No movement** → raise a high-priority, full-screen **"I'm here"** notification and start a **30-minute** timer.
- **No acknowledgement after 30 minutes** → send an SMS to the designated recipient. (Email is deferred to a later version.)
- **Acknowledged late** → optionally send a one-line all-clear to the recipient.

Every number — the two check times, the response window, reminder behaviour, recipients, message text — is configurable and validated.

The app is designed to cost almost nothing: the CPU sleeps continuously, and only a hardware motion sensor (roughly 1 mW in the SoC's sensor hub) and two alarms a day wake anything at all.

**Governing principle: fail safe.** Every uncertainty — process killed, reboot, unobserved interval, late alarm — resolves toward *alerting*, never toward a silent pass. A false alert is one tap to dismiss; a missed one could matter.

## 2. Goals and non-goals

**Goals**

- A truthful "no movement since the last check" verdict, even if the phone was used nine hours ago and left alone since.
- Correct behaviour with the screen off, the device unplugged, and Android in Doze.
- Near-zero background cost: no polling, no periodic jobs, no held wakelocks, no continuous sensor sampling beyond the hardware trigger.
- A false alert is trivial to dismiss (one tap, from the lock screen); a real escalation is retried, reported, and auditable.
- Every interval and message is user-configurable, with validation that explains itself.

**Non-goals (explicit)**

- **Medical alarm, fall detection, vitals monitoring.** Movement is not presence: a person can carry the phone and still need help. This is stated in the app, not buried in a README.
- Location tracking, cloud backend, multi-device sync, Play Store distribution.
- Inbound replies from the recipient ("OK" to stop escalation) — deferred; needs `RECEIVE_SMS`.
- A recipient-side app, account, or dashboard. The recipient only ever receives one SMS.
- Android below 13, tablets, Wear, iOS.

## 3. Platform constraints that drive the design

1. **A sensor registration dies with the process.** No public API lets a sensor listener outlive the process, so continuous observation requires a live foreground service.
2. **Doze defers ordinary alarms indefinitely.** A phone lying still — our exact scenario — is what triggers Doze. Only `setExactAndAllowWhileIdle()` and `setAlarmClock()` fire reliably. Exact alarms need either `SCHEDULE_EXACT_ALARM` (denied by default for newly installed apps targeting 33+, and user-revocable) or `USE_EXACT_ALARM` (granted automatically at install, cannot be revoked by the user, restricted by Play policy — acceptable here because the build is sideloaded).
3. **`TYPE_SIGNIFICANT_MOTION` / `TYPE_MOTION_DETECT` are hardware wake-up triggers** handled by the sensor hub. They wake the application processor only on real motion. This is the one genuinely free motion signal.
4. **Background service starts are restricted on Android 12+**, but exact-alarm launches and battery-exempted apps are exempt — so a killed service can be restarted at check time.
5. **A foreground service must post a notification of at least `PRIORITY_LOW`**; lower priorities cause the system to post its own "this app uses a foreground service" notice. See §10.
6. **A screen-off device may not show a notification**, so the alert needs a full-screen intent (special access on API 34+), and escalation must proceed whether or not the user ever looks at the phone.
7. **Sideloaded apps can sit under "restricted settings"**, greying out permission toggles until the user allows them. Onboarding must handle this gracefully.
8. **An app in the "Restricted" battery state does not receive `BOOT_COMPLETED`** and is throttled, so the self-check must detect and correct it.

## 4. Architecture

Single Gradle module `app`: Kotlin 2.x, Jetpack Compose (Material 3), Hilt, Coroutines/Flow, DataStore, Room, Android Keystore. **No Google Play Services dependency** — no Firebase, no Activity Recognition API.

```
   12:00 / 22:00             ┌────────────────────────────────────┐
   exact alarm ─────────────▶│ CheckInCoordinator                 │
                             │  reads MovementFlag, decides       │
                             └───┬────────────────────────┬───────┘
                                 │ no movement            │ ack
                      ┌──────────▼──────────┐   ┌─────────▼──────────┐
                      │ AlertCoordinator    │   │ Acknowledgement    │
                      │ FSI + 30-min timer  │   │ Receiver (button)  │
                      └──────────┬──────────┘   └─────────┬──────────┘
                                 │ deadline               │
                      ┌──────────▼────────────────────────▼─────────┐
                      │ EscalationDispatcher                        │
                      │  SMS only (email deferred)                 │
                      └──────────┬──────────────────────────────────┘
                                 │
   ┌─────────────────────────────┴───────────────────────────────────┐
   │ WatchService  (permanent foreground service, silent notice)     │
   │   MotionSource: motion-detect → significant-motion → accel      │
   │   on motion: flag = true · lastMotionAt = now · persist · rearm │
   └─────────────────────────────────────────────────────────────────┘
```

| Component | Responsibility |
|---|---|
| `ScheduleEngine` (pure) | Next check instant from `checkInTimes` + zone; DST and date-boundary math |
| `AlarmScheduler` | Two check alarms per day, reminder, escalation deadline; exact-alarm capability check and fallback |
| `WatchService` | Permanent `specialUse` FGS; owns the sensor registration; survives restarts; runs no loop. Posts a silent LOW-importance notice whose visibility is user-controlled (`serviceNotification`, §10) — never the alert channel |
| `MotionSource` | `motion-detect` (preferred, no re-arming) → `significant-motion` (one-shot, re-arm) → `accelerometer` batching fallback |
| `MovementState` | `{flag, lastMotionAt, lastCheckAt, bootId}` persisted |
| `CheckInCoordinator` | The decision; the only place the flag is consumed |
| `AlertCoordinator` | Channel, notification, `AlertActivity`, reminder and deadline alarms, dismissal handling |
| `AcknowledgementReceiver` | Idempotent ack; cancels escalation; optional all-clear |
| `EscalationDispatcher` | Channel ordering, retries, delivery results |
| `SelfCheck` | Permission/access audit and dry run, used by onboarding and diagnostics |

## 5. The daily cycle

```
12:00  check 1    flag set by any motion since 22:00? → yes: clear flag, sleep
 ...
15:37  motion     sensor trigger → flag = true, lastMotionAt = 15:37, sleep
 ...
21:58  no motion since 12:00 — flag still false
22:00  check 2    flag false → full-screen "I'm here" · deadline 22:30
22:10  reminder   stronger repeat, screen woken again
22:30  deadline   no ack → SMS to recipient
22:31  ack        late "I'm here" → optional all-clear SMS
```

Between those events the app does nothing: no timers, no polling, no held wakelocks.

## 6. State machine

Persisted, keyed by `checkId`, every transition idempotent.

```
WATCHING      ── motion ───────────────▶ WATCHING   (flag = true, persisted)
WATCHING      ── check: flag = true ───▶ WATCHING   (flag cleared, lastCheckAt = now)
WATCHING      ── check: flag = false ──▶ ALERT_PENDING
ALERT_PENDING ── ack ──────────────────▶ ACKNOWLEDGED ──▶ WATCHING
ALERT_PENDING ── reminder ─────────────▶ ALERT_PENDING   (stronger repeat)
ALERT_PENDING ── deadline ─────────────▶ ESCALATING
ESCALATING    ── sent ─────────────────▶ ESCALATED ── late ack ──▶ all-clear
ESCALATING    ── all channels failed ──▶ ESCALATION_FAILED  (banner + retry wheel)
```

Rules that matter:

- **Only the button acknowledges.** Movement, unlocking, or dismissing the notification never cancels escalation. A dismissal is detected via `setDeleteIntent`, logged as `ALERT_DISMISSED`, and the notification is re-posted.
- Motion during `ALERT_PENDING` still latches the flag for the *next* check, but has no effect on the current alert.
- An acknowledgement sets `lastCheckAt` and clears the flag, so a check immediately afterwards cannot re-alert.
- Every transition is persisted **before** side effects, so process death mid-alert resumes correctly when the deadline alarm fires.
- Transitions carrying a stale `checkId` are no-ops (duplicate broadcasts).

## 7. Scheduling

| Alarm | API | Why |
|---|---|---|
| Check 1 and Check 2 (daily) | `setExactAndAllowWhileIdle`, RTC, recomputed after each fire | Must fire in Doze; recomputation absorbs drift and config edits |
| Reminder (optional) | `setExactAndAllowWhileIdle` | Short horizon |
| Escalation deadline | `setAlarmClock` | Never adjusted, pulls the device out of low-power modes, and the status-bar alarm icon makes the pending deadline visible |

- Declare **`USE_EXACT_ALARM`** (sideload rationale) *and* guard every call with `canScheduleExactAlarms()`; on failure fall back to `setAndAllowWhileIdle()` and show a persistent banner: "Timing is approximate — grant Alarms & reminders."
- Handle `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED` → reschedule and restart the watch.
- **Late check:** each alarm carries `expectedFireAt`. Lateness ≥ 2 min logs `CHECK_LATE`. The decision still runs immediately — a late check must never be silently skipped.
- **Watchdog at each check:** if the service or the sensor registration is missing, restart it and log `WATCH_RESTARTED`. Because the preceding interval was then unobserved, the check treats the flag as **unknown and alerts** — the safe direction.
- `START_STICKY`, `stopWithTask="false"`, FGS type `specialUse` + `FOREGROUND_SERVICE_SPECIAL_USE` permission + the required `<property>` subtype string. No while-in-use permission is involved, so the Android 14 FGS permission checks do not apply.

## 8. Motion detection

**Flag semantics.** `flag = OR(any motion event since the check that last consumed it)`. Persisted on every transition; cleared only by a check that consumes it, or by an acknowledgement. This *is* "has it moved since the last check".

**Sensor chain, in preference order**

1. `TYPE_MOTION_DETECT` — fires on each motion onset, stays registered, hub-backed. Lowest cost, no re-arming.
2. `TYPE_SIGNIFICANT_MOTION` — one-shot; persist the flag **synchronously and first**, then re-arm inside `onTrigger` before returning. A crash between trigger and persist would lose the only evidence of presence.
3. `TYPE_ACCELEROMETER` with `maxReportLatencyUs = 60 s` and a software variance threshold — highest cost, last resort.

The chosen source is recorded and shown in the self-check screen; a downgrade is logged.

**Cheap supplementary presence signals** (each switchable, default on). These exist because "the phone is still" is not the same as "the owner is absent", and a single missed nudge should not page a caregiver:

- `ACTION_USER_PRESENT` (unlock), delivered to a runtime-registered receiver while the service runs.
- Foreground app resume, and the **I'm here** tap itself.

**Timestamps** are `elapsedRealtimeNanos` (immune to clock changes) with a wall-clock copy for display, plus a stored `bootId`.

**After a reboot the flag is set to `true`.** The interval is unobserved, and the safe reading of "unknown" is "movement happened" — a spurious escalation at the next check is worse than a missed alert here, and the *missing watch* is still surfaced in the log and on the dashboard. This is the one deliberate exception to fail-safe. See risk 17.

## 9. Alert and acknowledgement

- Channel `pickup_checkin`: `IMPORTANCE_HIGH`, alarm-like sound, vibration, `LOCKSCREEN_VISIBILITY_PUBLIC`, category `REMINDER`, optional DND bypass if notification-policy access is granted.
- `setFullScreenIntent` → `AlertActivity` with `setShowWhenLocked` / `setTurnScreenOn`: one very large **I'm here** target, a live countdown to escalation, and a plain-language explanation. TalkBack labels, one-handed reach, high contrast — the person using it may be half-asleep. Wording is calm: *"Check-in — please tap I'm here."*
- The owner is the only user of the app. The recipient never installs anything and never interacts with it; they only receive a text, so the message has to stand alone.
- `setOngoing(true)` so it cannot be swiped away casually, plus `setDeleteIntent` to detect removal and re-post.
- Redacted `setPublicVersion` so the lock screen does not disclose the situation.
- If full-screen access is unavailable (API 34+ special access, checked with `canUseFullScreenIntent()`), degrade to the high-importance notification plus the timed repeat, and show a permanent in-app banner with a link to `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`.

## 10. Service notification visibility

**The question:** can the permanent notification be suppressed?

**No — and the design says so rather than faking it.** The constraints:

| Attempt | Result |
|---|---|
| Run the watch without a foreground service | Process is killed, sensor registration dies, "movement since last check" becomes unanswerable |
| Use `IMPORTANCE_MIN` / `PRIORITY_MIN` for stealth | **The system posts its own drawer message** that the app is using a foreground service — worse than a quiet notification |
| Ask the user to deny `POST_NOTIFICATIONS` | FGS notices vanish from the drawer, but so do the **"I'm here" alerts** — the app's entire purpose. (The FGS task manager still lists the app either way.) |
| Auto-cancel our own notification via a notification-listener hack | Second special permission, visible flicker, OS may re-post, reads as evasion. Rejected; documented as unsupported |
| Check only at the two check-ins, no watch | Zero notification, but no movement data at all — every check would alert |
| Sampled bursts (≤10 s foreground service every N minutes) | Usually invisible, but misses the vast majority of movement → false escalations |
| Root or device-owner provisioning | Out of scope for a sideloaded app |

The notification can be made silent, and the user can hide it. It cannot be omitted.

**Configuration: `serviceNotification`**

| Mode | Behaviour | Trade-off |
|---|---|---|
| `MINIMAL` *(default)* | Channel `watch_service`, `IMPORTANCE_LOW`: no sound, no vibration, no heads-up, no badge, `VISIBILITY_SECRET` (nothing on the lock screen), text is only "Check-in monitoring" — no counts, names, or timers. Tap opens the app. | A silent, collapsed entry in the shade and the foreground-services task manager. Full liveness visibility. |
| `USER_HIDDEN` | Same channel, plus a **Hide this notification** button deep-linking to `ACTION_CHANNEL_NOTIFICATION_SETTINGS`. The app then reads back `getNotificationChannel(id).importance` and displays "Hidden by you — monitoring continues" with a restore action. | No visible sign the app is alive; if an OEM kills the service, the failure is silent until the next check-in. |

Rules for both modes:

- **Never fight the user.** No re-posting after dismissal, no nagging about the notification itself.
- The service notification is **never** used for alerts. Alerts live on the separate `pickup_checkin` channel, so hiding the service entry cannot hide a real alert. Onboarding verifies the two channels are distinct and that the alert channel is not blocked.
- The dashboard is the authoritative "is it working?" surface: watch state, sensor in use, flag value, next check.
- The check-in notifications themselves are the periodic proof of life.

## 11. Escalation

**v1 is SMS only.** Email (SMTP or an HTTPS provider) is deferred behind the same interface, so it can be added later without touching the rest of the design.

```kotlin
interface EscalationChannel { suspend fun send(m: EscalationMessage): SendResult }
```

- `SmsChannel` — `SmsManager`, dual-SIM aware (falls back to the default subscription), 90-second delivery-report timeout on the `sentIntent`. No network permission is required: SMS rides the carrier network, not the data connection.
- **One recipient in v1** — the family member or friend nominated during setup. Retries go to that number; the data model keeps a list so more can be added later without a migration.
- **The message arrives from the owner's own number**, so it must identify itself in the first line: *"WatchOver automated alert from Jane's phone. Jane's phone has not moved since 12:00 and the check-in was not acknowledged by 12:30. Please check on her."* Editable template with `{device}`, `{lastCheck}`, `{alertTime}`, `{deadline}`, `{battery}`.
- **Retries:** immediate attempt (60-second wakelock budget), then +2, +10, +30 minutes via exact retry alarms. No network constraint is needed. After the last attempt, `ESCALATION_FAILED` → persistent local notice and log entry.
- **All-clear** on late acknowledgement (default on): one short SMS, cheaper than a panicked phone call.
- **One escalation per alert instance**; the next check-in is a fresh instance — so a phone nobody touches escalates at noon *and* at 22:00 (risk 05).
- No secrets are stored in v1. When email arrives, the SMTP password or API key goes into an AES-GCM Keystore entry, never into logs.

## 12. Configuration schema, defaults, validation

| Key | Default | Range | Validation |
|---|---|---|---|
| `enabled` | false | — | Requires notifications, exact alarms, one verified channel |
| `checkInTimes` | `[12:00, 22:00]` | 1–4 entries | Unique, ≥ 30 min apart |
| `responseTimeoutMin` | **30** | 5–120 | |
| `remindersBeforeEscalation` | 1 | 0–3 | Spaced evenly across the response time |
| `movementSource` | auto (chain above) | — | Falls back automatically and logs the downgrade |
| `serviceNotification` | `MINIMAL` | `MINIMAL` or `USER_HIDDEN` | `NONE` rejected at the type level; the UI explains why (§10) |
| `countUnlockAsMotion` | true | — | |
| `fullScreenIntent` | true | — | Needs special access |
| `bypassDnd` | false | — | Needs policy access |
| `sendAllClear` | true | — | |
| `recipient` | empty | exactly 1 | `(label, phoneNumber)`; SMS only in v1; format-validated; test SMS required before arming |
| `logRetentionDays` | 30 | 7–365 | |

Values outside range are clamped **with a visible warning**, never silently. Arming is blocked while any onboarding item is red.

## 13. Storage

- **DataStore:** settings, `MovementState{flag, lastMotionAt, lastCheckAt, bootId}`, `AlertState{state, checkId, alertAt, attempts}`.
- **Room:** `event(id, checkId, at, type, detailJson)` — audit trail, 30-day retention, text export from the log screen.
- **Keystore + DataStore:** nothing in v1 (no email credentials); the path is reserved for the deferred SMTP/API-key work.
- **Known limitation:** all storage is credential-encrypted, so after a reboot nothing runs until first unlock. The design resumes on `ACTION_USER_PRESENT` and logs the gap.

## 14. Permissions and onboarding

`POST_NOTIFICATIONS`, `USE_EXACT_ALARM`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `USE_FULL_SCREEN_INTENT`, `SEND_SMS`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `VIBRATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`; optional `ACCESS_NOTIFICATION_POLICY`. There is no `INTERNET` permission in v1: escalation is SMS-only.

Onboarding is a five-step checklist with **live green/red status**, requiring:

1. Notifications allowed (alerts *and* the service notice).
2. Alarms & reminders granted (exact timing).
3. Full-screen notifications granted (wakes the screen), or an explicit accepted downgrade.
4. Battery usage not set to "Restricted" and optimisation exemption granted; an OEM autostart walkthrough where relevant.
5. The nominated recipient verified by a real **Test SMS**, with a prompt to brief them first — they will see a text from the owner's number and should know it is coming.

Plus a **Test alert** button that fires a genuine full-screen notification. A permanent **Self-check** screen repeats the audit and shows the watch state, sensor in use, flag value, channel visibilities, and the next check time.

Plus a plain-language note: while watching, a small silent entry stays in the notification shade; it can be hidden without affecting monitoring; and *alerts* are always on a separate channel.

## 15. Risk register — issues and proposed solutions

| # | Issue | Consequence | Solution |
|---|---|---|---|
| 01 | Continuous watch needs a live process | Conflicts with "mostly asleep"; permanent notification | CPU genuinely sleeps except ~1 ms per motion event; silent LOW channel; user may hide it (§10) |
| 02 | Doze defers alarms | A check never fires — the core promise fails | Exact alarms only; `USE_EXACT_ALARM`; late-check detection; alarm-clock class for the deadline |
| 03 | OEM battery managers kill the service | Silent loss of observation | Exemption + autostart guide; `START_STICKY`; check-time watchdog; unknown interval ≠ pass |
| 04 | Process death loses the motion flag | False "no movement" → spurious alert | Synchronous persist on trigger; fail-safe direction; `WATCH_RESTARTED` logged |
| 05 | **Escalation at 22:30 may still disturb a recipient** | Recipient annoyance | Warn in onboarding with the real timestamps; suggest a recipient who is up late; per-recipient quiet hours in v2 |
| 06 | Movement ≠ presence | False confidence in a safety tool | Stated in-app; recipient guidance during setup; "this is not a medical device" on the alert screen |
| 07 | Phone genuinely still for 10–14 h | Alert at both check-ins, every day | Intended by design; one-tap ack; `checkInTimes` editable |
| 08 | Screen off → notification unseen | Owner misses it | Full-screen intent that wakes the screen; escalating repeat; escalation proceeds regardless |
| 09 | Full-screen special access not granted (34+) | No wake-screen | Onboarding step; degrade to high-importance + repeat; permanent banner with a fix link |
| 10 | Exact-alarm access revoked | `SecurityException`, no alarms | Non-revocable `USE_EXACT_ALARM`; capability guard; permission-change receiver |
| 11 | Significant motion misses gentle nudges | Spurious alert | Unlock and app activity count as motion; sensor-chain fallback; 10–14 hour interval is generous |
| 12 | Dismissal mistaken for acknowledgement | Escalation silently cancelled | Ongoing notification + delete-intent re-post; only the button acknowledges |
| 13 | SMS refused, revoked, or no SIM | Escalation silently fails | Capability check + test-send at setup; retry ladder; `ESCALATION_FAILED` notice; SIM and radio state logged at each check |
| 14 | Email deferred to v2 | No secondary channel in v1 | Deliberate scope decision; the `EscalationChannel` interface keeps the door open |
| 15 | No cellular service at the deadline | SMS is queued by the radio or fails outright | Retry ladder; `ESCALATION_FAILED` notice; radio and SIM state checked and logged; nothing else is possible on-device (risk 25) |
| 16 | Clock, timezone, or DST changes | Wrong check time; missed or doubled checks | `java.time` + `ZonedDateTime`; reschedule on time-change broadcasts; tests for DST gaps and the 22:00 boundary |
| 17 | Reboot before first unlock | Nothing runs until unlock | Resume on user-present; `bootId` invalidation sets the flag true; gap logged |
| 18 | Force-stop clears alarms | Total silent failure | Compare `lastScheduledAt` to now at next launch; explain and recover |
| 19 | Sideload "restricted settings" | Permission toggles unavailable | Documented troubleshooting step, linked from onboarding |
| 20 | PII on device (recipient number, event log) | Disclosure if the device is compromised | No cloud storage, redacted logs; the Keystore secret path is reserved but unused in v1 |
| 21 | User hides or blocks the service channel, or an OEM stops the service | No visible liveness cue; failure is silent until the next check | Check-time watchdog and `WATCH_RESTARTED`; dashboard shows "hidden by you, still watching" and warns if the service is gone |
| 22 | Temptation to use `IMPORTANCE_MIN` for stealth | The OS posts its own callout notice — worse than a quiet entry | Channel pinned to `IMPORTANCE_LOW`; unit test asserts channel importance |
| 23 | User swipes the service notification away | User may believe the app stopped | No re-post; dashboard plus the next check-in make liveness evident; one-time explainer |
| 24 | Battery usage set to "Restricted" | `BOOT_COMPLETED` withheld; app throttled | Self-check detects `isBackgroundRestricted()` and walks the user through correcting it |
| 25 | **Shared failure domain:** if the monitored phone is off, flat, or out of signal, nothing runs and nothing is sent | Total silent failure — the escalation path depends on the very device being watched | Inherent to any on-device design; documented plainly in the app and README. Exact alarms plus the battery exemption reduce the flat-battery case; each check logs battery, SIM and radio state. A cloud heartbeat relay is the only real fix and is out of scope for v1 |
| 26 | The SMS arrives from the owner's own number | The recipient may think the owner texted them | Message opens with "WatchOver automated alert from <name>'s phone"; recipient briefed at setup |

## 16. Battery budget

| | Cost |
|---|---|
| Sensor hub, always armed | ~1 mW → ~24 mWh/day → ≈0.15–0.3 % of a typical 15 Wh battery |
| Idle service/process | No measurable draw; no wakelocks held |
| Two checks plus a handful of motion wakes | Negligible (< 10 s CPU/day) |
| Escalation attempts | Tens of seconds, only when something went wrong |
| **Target** | **< 1 % per 24 h attributable to the app** |

For comparison: hourly accelerometer polling costs roughly 5–10× this; location checks 10–50×.

**The target is a steady-state number.** It covers monitoring with no alerts: the
sensor, the service, and two checks a day. Power spent *while an alert is
happening* is deliberately not optimised — ringing, lighting the screen and
holding it for five minutes per reminder are the feature, not overhead. A day
that contains an unanswered alert will legitimately measure several times
higher, and that is not a regression to fix.

Measurement protocol: pick a quiet 24–48 h with no alerts, then
`dumpsys batterystats` plus a `dumpsys power` wakelock audit.

**Opt-in downgrade** for users who dislike the permanent notice: `SAMPLED` mode arms the sensor only for 60 minutes before each check (60 min × 2 per day of service), at the cost of a "no movement" verdict that covers only the last hour. Not offered in v1; listed here so the trade-off is on record.

## 17. Test plan and acceptance criteria

**Unit tests:** schedule math (DST, the 22:00 boundary, 1–4 times); flag latch/consume logic; the decision table; escalation ladder and dedupe; validation clamps; message templating; channel-importance assertions.

**Instrumented tests:** fake `MotionSource` and fake `Clock` drive the full state machine, including process-death restart and reboot invalidation; `AlarmManager` via Robolectric; Compose tests for onboarding and `AlertActivity`.

**On-device matrix** (recipes documented in the install guide): `dumpsys deviceidle force-idle`, `dumpsys alarm`, `dumpsys batterystats`, `adb emu sensor set acceleration` on the emulator, screen off, charging, airplane mode, DND, lock screen, SMS grant/revoke mid-run, reboot mid-interval, timezone change, channel blocked.

**Acceptance criteria**

1. Motion at 11:59:59 → the 12:00 check passes; motion at 12:00:30 → the next check passes.
2. No motion between 12:00 and 22:00 → the alert fires at 22:00, within ±2 min, in forced Doze with the screen off.
3. Ack at 29:59 → no SMS. Ack at 30:01 → the escalation SMS has already gone out, then an all-clear.
4. Service killed → restarts (`START_STICKY`), previous flag preserved, `WATCH_RESTARTED` logged; if the interval was unobserved, the next check alerts rather than passing.
5. App battery < 1 % per 24 h; zero wakelocks outside the check-ins.
6. Dismissing the notification without acknowledging still escalates.
7. Reboot → nothing runs before first unlock; after unlock the watch resumes and the flag is `true`.
8. Every config edit takes effect at the next check without a reboot.
9. The setup test SMS reaches the nominated number, and the app refuses to arm until it does.
10. With `serviceNotification = MINIMAL`, the notice makes no sound, creates no heads-up, shows nothing on the lock screen and no badge; the alert channel still rings.
11. With the service channel set to Off in Settings, the service still runs and the next check-in still fires on each test device; if an OEM stops it, the watchdog surfaces the failure at the next check rather than passing silently.
12. Swiping the service notice away does not trigger a re-post, and the service keeps running.
13. Blocking the *alert* channel (not the service channel) is detected at the next launch and blocks arming.

## 18. Implementation outline

1. **Skeleton** — Gradle, Compose, Hilt, manifest permissions, `SelfCheck`, onboarding wizard.
2. **Schedule and alarms** — `ScheduleEngine`, `AlarmScheduler`, reschedule receivers, DST handling.
3. **Watch** — `WatchService`, both notification channels, sensor chain, `MovementState` persistence, restart handling, the `serviceNotification` modes with the settings deep link and channel-state read-back.
4. **Check-in and alert** — coordinator, channel, `AlertActivity`, ack receiver, reminders, dismissal handling.
5. **Escalation (SMS only)** — channel interface, `SmsManager` sender, dual-SIM handling, delivery reports, retry alarms, test-send.
6. **Hardening** — Doze matrix, OEM behaviour (including channel-Off behaviour per vendor), battery profiling, event log UI.
7. **Docs** — ADB install and permission walkthrough, troubleshooting.

Packages: `schedule/`, `watch/`, `checkin/`, `alert/`, `escalate/`, `data/`, `perms/`, `ui/`, `di/`. Roughly 20–25 source files.

## 19. Assumptions and deferred work

- The owner installs and configures the app themself and nominates one recipient — a family member or friend reachable by SMS. There is no recipient-side install; the recipient only needs to recognise the number the text comes from.
- Email escalation (SMTP or HTTPS provider) is deferred by decision; `EscalationChannel` keeps it addable without redesign.
- One recipient in v1. The model is a list so a second can be added later, but the escalation policy in v1 is simply "send to that number and retry it".
- "Since the last check" means the latched flag in §8: consumed by a check, or cleared by an acknowledgement. An ack counts as presence for the following interval.
- Sideload-only is permanent. `USE_EXACT_ALARM` would have to be redesigned for Play distribution.
- English-only strings, but locale-ready resource structure.
- Deferred: recipient reply handling, location in messages, per-recipient quiet hours, device-protected-storage boot handling, a wearable companion.

## 20. Confirmed product decisions

Settled during review; recorded here so the reasoning survives.

| Decision | Choice | Rationale |
|---|---|---|
| Service notification | `MINIMAL` | Silent and content-free, but leaves visible proof the watch is alive; the user can still hide it (§10) |
| Recipients | One, nominated by the owner | A family member or friend; the recipient installs nothing and only receives a text |
| Sender identity | The owner's own number, self-identifying first line | Familiar number, but the message must not read as if the owner wrote it (risk 26) |
| All-clear SMS | On | One extra message prevents a panicked phone call |
| Escalation channel | SMS only in v1 | No `INTERNET` permission, no credentials stored; email deferred behind `EscalationChannel` |

## 21. Implementation notes

Written during the first build pass. These are deliberate deviations from the
paragraphs above, each with its reason; the user-facing behaviour is unchanged.

| Design said | Built as | Why |
|---|---|---|
| Room for the event log | Tab-separated file, 30-day retention, and a rolling window of the last 500 entries (oldest dropped, file rewrapped periodically) | A handful of events a day; no schema migrations, no annotation processor, and it can be read with `adb shell run-as` or a text editor |
| Hilt | One hand-written `AppGraph` container | ~25 classes and no generated code; simpler to trace and faster to build |
| WorkManager for network-constrained retries | Exact alarm retries | v1 is SMS-only, which has no network constraint; dropping WorkManager drops a dependency and a second scheduling system |
| Separate onboarding screens | Setup checklist embedded on the home screen | One screen instead of five, and the checklist is the useful artefact — it reads live platform state each time it composes |

Still open before it can be called finished:

1. **No device run.** Everything below the Compose layer is untested on hardware.
   The first session should follow `docs/INSTALL.md` and tick off acceptance
   criteria 1–13 in §17.
2. **Instrumented tests** — none yet; the state machine is covered only by pure
   unit tests plus the manual test hooks.
3. **String resources** — notification text is in `strings.xml`; screen text is
   still literal Kotlin strings.
4. **`AlertCoordinator` is the largest file** (~350 lines) and would be the first
   candidate to split (notification builder, escalation policy, ack handling).
5. **The service's in-memory flag mirror** (`MotionFlag`) is authoritative within
   a process and reconciled from DataStore on service start. Worth a device test
   that kills the process mid-interval.

---

## Appendix: revision history

| Rev | Change |
|---|---|
| 1 | Initial design: one daily monitoring window, movement within a trailing N-minute window, 5-minute response |
| 2 | Your instruction: two check-ins per day (12:00, 00:00); "any movement since the previous check" as a latched flag; 30-minute response window. Required a permanent watch service, which replaced the window-only foreground service |
| 3 | Service notification visibility (§10): `serviceNotification` modes, the `IMPORTANCE_LOW` floor, risks 21–24, acceptance 9–12 |
| 4 | SMS-only escalation (email deferred); check times moved to 12:00 and 22:00; risks 25–26; permissions trimmed |
| 5 | Product decisions confirmed: owner-set-up with one nominated recipient, `MINIMAL` notification, all-clear on. Single-recipient model; acceptance 9 added |
