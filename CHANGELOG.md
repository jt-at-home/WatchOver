# Changelog

All notable changes to WatchOver. Dates are local (America/Chicago).

## 0.1.0 — 2026-11-10

First working version, tested end to end on a Pixel 9a running Android 17.

**Includes**

- Two configurable check-ins per day (default 12:00 and 22:00) with a latched
  "has the phone moved since the last check" flag.
- Motion detection through `TYPE_SIGNIFICANT_MOTION`, falling back to
  motion-detect and then a batched accelerometer; screen-on and unlock also count
  as the phone being handled.
- Exact alarms that survive Doze, an alarm-clock-class escalation deadline, and
  rescheduling after boot, package update, clock change or timezone change.
- Full-screen alert that lights up a locked or dark screen and holds it for
  five minutes; dismissing it does not count as acknowledgement.
- Reminders spaced evenly across the response window, each re-lighting the screen.
- SMS escalation with a retry ladder, delivery reports, and an all-clear when the
  owner acknowledges late.
- Setup checklist over real platform state, event log with a 500-entry rolling
  window, and an adb driver for testing.

**Measured**

- Battery: 0.0593 mAh attributed over 45 hours (~0.0006%/day of a 5032 mAh pack).
- 74 unit tests, 5 instrumented tests, lint clean.

**Not in this version**

- Email escalation (the `EscalationChannel` seam is in place).
- Recipient replies, per-recipient quiet hours, a wearable companion.
- Play Store distribution — the exact-alarm strategy is sideload-only by design.
