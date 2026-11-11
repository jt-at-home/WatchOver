# Contributing

Issues and pull requests are welcome. This is a small personal project, so please
open an issue before starting anything larger than a fix — it saves us both time.

## Building

You need **JDK 17 or newer** — the project is built and tested on 21 — and an
Android SDK with platform 35 and build-tools 35.0.0 installed.

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug
```

`local.properties` is ignored by git and should stay that way.

## Checks before opening a pull request

```bash
./gradlew testDebugUnitTest          # 74 pure JVM tests, no device needed
./gradlew lintDebug                  # must stay at zero errors
./gradlew connectedDebugAndroidTest  # 5 instrumented tests, needs a device
```

If you change behaviour, say which device and Android version you tested on. This
app depends on platform details that differ between vendors — a change that works
on a Pixel may not work on a Xiaomi.

## Where things live

| Path | What it does |
|---|---|
| `schedule/` | Schedule arithmetic, exact alarms, rescheduling after boot and time changes |
| `watch/` | The foreground service and its sensor chain |
| `checkin/` | The decision — should this check pass or alert? |
| `alert/` | Notification, full-screen activity, reminders |
| `escalate/` | SMS, message text, delivery results |
| `data/` | Settings, runtime state, event log |
| `ui/` | Compose screens and the setup checklist |

The pure Kotlin pieces (`ScheduleEngine`, `CheckInDecision`, `ReminderSchedule`,
`SmsText`, the codecs) have no Android dependencies and are where tests belong
first. Anything touching sensors, alarms or notifications needs a device.

## Design changes

[`docs/DESIGN.md`](docs/DESIGN.md) is the source of truth for *why* the app works
the way it does — the risk register there exists to stop us re-introducing problems
that were already solved once. If a change alters behaviour, update that document
in the same pull request, and add a line to the revision history at the end.

## Reporting a bug

Useful:

- Phone model and Android version
- Which checklist rows are red, if any
- The relevant lines from the **Log** tab (or `adb shell run-as com.watchover cat files/events.log`)
- Whether the phone was charging, locked, or in Doze

Please don't include your contact's real phone number in an issue.
