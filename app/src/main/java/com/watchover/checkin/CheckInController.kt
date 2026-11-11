package com.watchover.checkin

import android.content.Context
import com.watchover.data.AlertPhase
import com.watchover.data.Event
import com.watchover.data.EventLogStore
import com.watchover.data.EventTypes
import com.watchover.data.RuntimeStore
import com.watchover.data.Settings
import com.watchover.data.SettingsStore
import com.watchover.core.TimeProvider
import com.watchover.alert.AlertCoordinator
import com.watchover.schedule.AlarmScheduler
import com.watchover.watch.MotionFlag
import com.watchover.watch.WatchService

/**
 * The decision point (design §5, §6): on a scheduled check, ask whether the
 * phone moved since the previous one.
 *
 * Fail-safe rule: if the watch was not running (so the interval was never
 * observed), the check alerts rather than passing, unless the boot handler has
 * already accounted for it.
 */
class CheckInController(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val runtimeStore: RuntimeStore,
    private val events: EventLogStore,
    private val alarms: AlarmScheduler,
    private val alert: AlertCoordinator,
    private val time: TimeProvider,
    private val bootId: () -> String,
) {

    /** Called once when the application starts. */
    suspend fun onAppStart() {
        val settings = settingsStore.current()
        events.load(settings.logRetentionDays)
        if (settings.enabled) {
            ensureWatching(settings)
            val scheduled = alarms.scheduleNextCheck(settings)
            detectMissedCheck(settings, scheduled)
        }
    }

    suspend fun onCheckDue(expectedAtEpochMs: Long) {
        val settings = settingsStore.current()
        if (!settings.enabled) return

        val now = time.nowEpochMs()
        events.append(Event(now, EventTypes.CHECK_STARTED, "expected=$expectedAtEpochMs"), settings.logRetentionDays)

        if (expectedAtEpochMs > 0 && now - expectedAtEpochMs > LATE_THRESHOLD_MS) {
            events.append(
                Event(now, EventTypes.CHECK_LATE, "lateMs=${now - expectedAtEpochMs}"),
                settings.logRetentionDays,
            )
        }

        val wasRunning = WatchService.isRunning
        if (!wasRunning) {
            WatchService.start(context)
            events.append(Event(now, EventTypes.WATCH_RESTARTED, "not running at check"), settings.logRetentionDays)
        }

        val runtime = runtimeStore.current()
        val latched = runtime.movement.flag || MotionFlag.set
        val outcome = CheckInDecision.decide(latchedMovement = latched, watchWasRunning = wasRunning)

        if (outcome == CheckInDecision.Outcome.PASS) {
            runtimeStore.update {
                it.copy(
                    movement = it.movement.onCheckConsumed(now, bootId()),
                    nextCheckAtEpochMs = null,
                )
            }
            MotionFlag.consume()
            events.append(
                Event(now, EventTypes.CHECK_PASSED, CheckInDecision.reason(latched, wasRunning)),
                settings.logRetentionDays,
            )
        } else {
            // The flag is consumed by every check, pass or fail; otherwise the next
            // check would see a stale "moved" and wrongly pass.
            runtimeStore.update {
                it.copy(
                    movement = it.movement.onCheckConsumed(now, bootId()),
                    nextCheckAtEpochMs = null,
                )
            }
            MotionFlag.consume()
            events.append(
                Event(now, EventTypes.CHECK_ALERTING, CheckInDecision.reason(latched, wasRunning)),
                settings.logRetentionDays,
            )
            alert.raise(checkId = now, settings = settings)
        }

        val next = alarms.scheduleNextCheck(settings)
        next?.let { at ->
            runtimeStore.update { it.copy(nextCheckAtEpochMs = at) }
        }
    }

    suspend fun onBoot() {
        val settings = settingsStore.current()
        val now = time.nowEpochMs()
        events.append(Event(now, EventTypes.BOOT, ""), settings.logRetentionDays)
        if (!settings.enabled) return

        // The interval was unobserved by this boot; assume movement so a reboot
        // overnight does not produce a false alert at 12:00 (design §8).
        runtimeStore.update { it.copy(movement = it.movement.afterReboot(now, bootId())) }
        MotionFlag.restore(true, now)

        ensureWatching(settings)
        val next = alarms.scheduleNextCheck(settings)
        next?.let { at ->
            runtimeStore.update { it.copy(nextCheckAtEpochMs = at) }
        }
    }

    suspend fun onClockOrTimezoneChanged() {
        val settings = settingsStore.current()
        events.append(Event(time.nowEpochMs(), EventTypes.BOOT, "time changed"), settings.logRetentionDays)
        if (!settings.enabled) return
        val next = alarms.scheduleNextCheck(settings)
        next?.let { at ->
            runtimeStore.update { it.copy(nextCheckAtEpochMs = at) }
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        val current = settingsStore.current()

        if (enabled) {
            // Arming with a broken configuration would look active while doing nothing.
            val problems = current.validateForArming()
            if (problems.isNotEmpty()) {
                events.append(
                    Event(
                        time.nowEpochMs(),
                        EventTypes.CONFIG_CHANGED,
                        "refused to arm: ${problems.joinToString("; ") { it.message }}",
                    ),
                    current.logRetentionDays,
                )
                return
            }
        }

        val updated = settingsStore.update { it.copy(enabled = enabled) }
        events.append(
            Event(time.nowEpochMs(), EventTypes.CONFIG_CHANGED, "enabled=$enabled"),
            current.logRetentionDays,
        )

        if (enabled) {
            ensureWatching(updated)
            val next = alarms.scheduleNextCheck(updated)
            next?.let { at ->
                runtimeStore.update { it.copy(nextCheckAtEpochMs = at) }
            }
        } else {
            WatchService.stop(context)
            alarms.cancelAll()
            alert.cancelNotification()
            runtimeStore.update {
                it.copy(
                    alert = it.alert.closed(),
                    nextCheckAtEpochMs = null,
                )
            }
        }
    }

    /** Re-applies the schedule after the owner edits times, e.g. on returning to the app. */
    suspend fun refreshSchedule() {
        val settings = settingsStore.current()
        if (!settings.enabled) return
        ensureWatching(settings)
        val next = alarms.scheduleNextCheck(settings)
        next?.let { at ->
            runtimeStore.update { it.copy(nextCheckAtEpochMs = at) }
        }
    }

    suspend fun acknowledge() {
        alert.acknowledge()
    }

    private suspend fun ensureWatching(settings: Settings) {
        if (settings.enabled && !WatchService.isRunning) {
            WatchService.start(context)
        }
    }

    /**
     * A missing alarm is the one silent failure that matters (design §14, #18):
     * if the time we last scheduled has quietly passed, say so in the log.
     */
    private suspend fun detectMissedCheck(settings: Settings, rescheduledFor: Long?) {
        val runtime = runtimeStore.current()
        val stale = runtime.nextCheckAtEpochMs
        val now = time.nowEpochMs()
        if (stale != null && stale < now - MISSED_THRESHOLD_MS) {
            events.append(
                Event(now, EventTypes.CHECK_LATE, "missed scheduled check at $stale"),
                settings.logRetentionDays,
            )
        }
        rescheduledFor?.let { at ->
            runtimeStore.update { it.copy(nextCheckAtEpochMs = at) }
        }
    }

    companion object {
        private const val LATE_THRESHOLD_MS = 2 * 60_000L
        private const val MISSED_THRESHOLD_MS = 5 * 60_000L
    }
}
