package com.watchover.schedule

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.watchover.core.TimeProvider
import com.watchover.data.Settings
import com.watchover.ui.MainActivity
import java.time.Instant

/**
 * Owns every alarm the app sets (design §7).
 *
 * Check and reminder alarms use `setExactAndAllowWhileIdle` so they survive Doze;
 * the escalation deadline uses `setAlarmClock`, the strongest class, which is
 * never adjusted and makes the pending deadline visible in the status bar.
 */
@SuppressLint("MissingPermission")
class AlarmScheduler(
    private val context: Context,
    private val engine: ScheduleEngine,
    private val time: TimeProvider,
) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean = alarmManager?.canScheduleExactAlarms() ?: false

    /** (Re)sets the next daily check. Returns the instant it was set for, if any. */
    fun scheduleNextCheck(settings: Settings): Long? {
        val now = Instant.ofEpochMilli(time.nowEpochMs())
        val next = engine.nextCheckAfter(now, settings.sortedCheckTimes()) ?: return null
        val atMs = next.toEpochMilli()
        setExact(checkIntent(atMs), atMs)
        return atMs
    }

    fun scheduleReminder(atMs: Long) {
        setExact(reminderIntent(atMs), atMs)
    }

    fun scheduleDeadline(atMs: Long) {
        val pi = deadlineIntent(atMs)
        if (canScheduleExact()) {
            val show = PendingIntent.getActivity(
                context,
                RC_SHOW,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            runCatching {
                alarmManager?.setAlarmClock(AlarmManager.AlarmClockInfo(atMs, show), pi)
            }.onFailure {
                // Some OEMs reject alarm-clock alarms; a plain exact alarm is still correct.
                setExact(deadlineIntent(atMs), atMs)
            }
        } else {
            setExact(pi, atMs)
        }
    }

    /** Retry attempts for a failed escalation. */
    fun scheduleEscalationRetry(atMs: Long, attempt: Int) {
        val pi = retryIntent(atMs, attempt)
        setExact(pi, atMs)
    }

    fun cancelNextCheck() = cancel(checkIntent(0))

    fun cancelReminder() = cancel(reminderIntent(0))

    fun cancelDeadline() = cancel(deadlineIntent(0))

    fun cancelEscalationRetry() {
        for (attempt in 0..MAX_ATTEMPTS) cancel(retryIntent(0, attempt))
    }

    fun cancelAll() {
        cancelNextCheck()
        cancelReminder()
        cancelDeadline()
        cancelEscalationRetry()
    }

    private fun setExact(pendingIntent: PendingIntent, atMs: Long) {
        val manager = alarmManager ?: return
        try {
            if (canScheduleExact()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pendingIntent)
            } else {
                // Approximate fallback; the UI shows a banner when this happens.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pendingIntent)
            }
        } catch (security: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pendingIntent)
        }
    }

    private fun cancel(pendingIntent: PendingIntent) {
        alarmManager?.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun baseIntent(action: String, atMs: Long): Intent =
        Intent(context, AlarmReceiver::class.java)
            .setAction(action)
            .putExtra(AlarmReceiver.EXTRA_EXPECTED_AT, atMs)

    private fun checkIntent(atMs: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        RC_CHECK,
        baseIntent(AlarmReceiver.ACTION_CHECK, atMs),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun reminderIntent(atMs: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        RC_REMINDER,
        baseIntent(AlarmReceiver.ACTION_REMINDER, atMs),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun deadlineIntent(atMs: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        RC_DEADLINE,
        baseIntent(AlarmReceiver.ACTION_DEADLINE, atMs),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun retryIntent(atMs: Long, attempt: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        RC_RETRY_BASE + attempt,
        baseIntent(AlarmReceiver.ACTION_RETRY, atMs).putExtra(AlarmReceiver.EXTRA_ATTEMPT, attempt),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val RC_CHECK = 100
        private const val RC_REMINDER = 200
        private const val RC_DEADLINE = 300
        private const val RC_SHOW = 400
        private const val RC_RETRY_BASE = 500
        const val MAX_ATTEMPTS = 4
    }
}
