package com.watchover.alert

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.watchover.R
import com.watchover.data.AlertPhase
import com.watchover.data.AlertState
import com.watchover.data.Event
import com.watchover.data.EventLogStore
import com.watchover.data.EventTypes
import com.watchover.data.Settings
import com.watchover.data.SettingsStore
import com.watchover.data.RuntimeStore
import com.watchover.core.TimeProvider
import com.watchover.escalate.Messages
import com.watchover.escalate.SmsEscalator
import com.watchover.schedule.AlarmScheduler
import com.watchover.ui.MainActivity
import com.watchover.watch.MotionFlag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Owns the alert lifecycle (design §6, §9, §11): raise, remind, escalate, close.
 *
 * Every transition re-reads persisted state and checks the phase first, so a
 * duplicate alarm or a broadcast delivered twice cannot double-send an SMS.
 */
class AlertCoordinator(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val runtimeStore: RuntimeStore,
    private val events: EventLogStore,
    private val alarms: AlarmScheduler,
    private val sms: SmsEscalator,
    private val time: TimeProvider,
    private val scope: CoroutineScope,
    private val bootId: () -> String,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {

    // ------------------------------------------------------------------ raise

    suspend fun raise(checkId: Long, settings: Settings) {
        val now = time.nowEpochMs()
        val runtime = runtimeStore.current()
        if (runtime.alert.phase == AlertPhase.AWAITING_ACK) return

        val state = runtime.alert.raised(
            alertId = now,
            checkId = checkId,
            nowEpochMs = now,
            timeoutMinutes = settings.responseTimeoutMinutes,
        )
        runtimeStore.update { it.copy(alert = state) }
        MotionFlag.consume()
        events.append(Event(now, EventTypes.ALERT_RAISED, "checkId=$checkId"), settings.logRetentionDays)

        post(settings, state, reminder = false)
        scheduleReminderIfAny(settings, state, now)

        state.deadlineEpochMs?.let { alarms.scheduleDeadline(it) }
    }

    // --------------------------------------------------------------- reminder

    suspend fun onReminder() {
        val settings = settingsStore.current()
        val state = runtimeStore.current().alert
        if (state.phase != AlertPhase.AWAITING_ACK) return

        val now = time.nowEpochMs()
        val deadline = state.deadlineEpochMs ?: return
        if (now >= deadline) {
            onDeadline()
            return
        }
        if (state.remindersSent >= settings.remindersBeforeEscalation) return

        val updated = state.reminderSent()
        runtimeStore.update { it.copy(alert = updated) }
        events.append(Event(now, EventTypes.REMINDER_SENT, "n=${updated.remindersSent}"), settings.logRetentionDays)

        post(settings, updated, reminder = true)
        scheduleReminderIfAny(settings, updated, now)
    }

    // --------------------------------------------------------------- escalate

    suspend fun onDeadline() {
        val settings = settingsStore.current()
        val state = runtimeStore.current().alert
        if (state.phase != AlertPhase.AWAITING_ACK) return

        runtimeStore.update { it.copy(alert = it.alert.escalating()) }
        events.append(
            Event(time.nowEpochMs(), EventTypes.ESCALATION_STARTED, ""),
            settings.logRetentionDays,
        )
        attemptSend(settings, state, attempt = 0)
    }

    suspend fun onEscalationRetry(attempt: Int) {
        val settings = settingsStore.current()
        val state = runtimeStore.current().alert
        if (state.phase != AlertPhase.ESCALATING) return
        attemptSend(settings, state, attempt)
    }

    private suspend fun attemptSend(settings: Settings, state: AlertState, attempt: Int) {
        val recipient = settings.recipient
        val now = time.nowEpochMs()

        if (recipient == null || recipient.phoneNumber.isBlank()) {
            fail(settings, "no recipient configured")
            return
        }

        val message = Messages.escalation(
            deviceLabel = settings.deviceLabel(),
            lastCheckAtMs = runtimeStore.current().movement.lastCheckEpochMs,
            alertedAtMs = state.alertedAtEpochMs ?: now,
            deadlineMs = state.deadlineEpochMs ?: now,
            zone = zone(),
        )

        val accepted = sms.send(recipient.phoneNumber, message)
        if (accepted) {
            val escalated = runtimeStore.update { it.copy(alert = it.alert.escalated(now)) }.alert
            events.append(
                Event(now, EventTypes.ESCALATION_SENT, "attempt=$attempt"),
                settings.logRetentionDays,
            )
            alarms.cancelEscalationRetry()
            post(settings, escalated, reminder = true, escalated = true)
            return
        }

        val nextAttempt = attempt + 1
        runtimeStore.update {
            it.copy(alert = it.alert.copy(escalationAttempts = nextAttempt))
        }
        if (nextAttempt <= AlarmScheduler.MAX_ATTEMPTS - 1) {
            val delayMs = RETRY_DELAYS_MIN.getOrElse(nextAttempt - 1) { 30 } * 60_000L
            alarms.scheduleEscalationRetry(now + delayMs, nextAttempt)
            events.append(
                Event(now, EventTypes.ESCALATION_FAILED, "attempt=$attempt, retrying"),
                settings.logRetentionDays,
            )
        } else {
            fail(settings, "all attempts failed")
        }
    }

    private suspend fun fail(settings: Settings, detail: String) {
        runtimeStore.update { it.copy(alert = it.alert.failed()) }
        events.append(
            Event(time.nowEpochMs(), EventTypes.ESCALATION_FAILED, detail),
            settings.logRetentionDays,
        )
        alarms.cancelEscalationRetry()
        postFailureNotice()
    }

    // ------------------------------------------------------------------- ack

    /**
     * Returns true when an alert was actually closed.
     *
     * Deliberately not limited to [AlertState.isOpen]: if escalation failed, the
     * owner must still be able to clear the notification by pressing the button.
     */
    suspend fun acknowledge(): Boolean {
        val settings = settingsStore.current()
        val state = runtimeStore.current().alert
        if (state.phase == AlertPhase.IDLE) return false

        val now = time.nowEpochMs()
        val wasEscalated = state.phase == AlertPhase.ESCALATED ||
            state.phase == AlertPhase.ESCALATING

        runtimeStore.update {
            it.copy(
                alert = it.alert.closed(),
                movement = it.movement.onAcknowledged(now, bootId()),
            )
        }
        MotionFlag.consume()

        cancelNotification()
        alarms.cancelReminder()
        alarms.cancelDeadline()
        alarms.cancelEscalationRetry()

        events.append(Event(now, EventTypes.ALERT_ACKNOWLEDGED, "phase=${state.phase}"), settings.logRetentionDays)

        if (wasEscalated && settings.sendAllClear) {
            val recipient = settings.recipient
            if (recipient != null) {
                val text = Messages.allClear(settings.deviceLabel(), now, zone())
                if (sms.send(recipient.phoneNumber, text)) {
                    events.append(Event(now, EventTypes.ALL_CLEAR_SENT, ""), settings.logRetentionDays)
                }
            }
        }
        return true
    }

    /** The notification was removed without the button being pressed. */
    suspend fun onDismissed() {
        val settings = settingsStore.current()
        val state = runtimeStore.current().alert
        if (state.phase != AlertPhase.AWAITING_ACK) return

        events.append(
            Event(time.nowEpochMs(), EventTypes.ALERT_DISMISSED, "re-posting"),
            settings.logRetentionDays,
        )
        // Dismissal is not acknowledgement: put it back.
        post(settings, state, reminder = true)
    }

    // ---------------------------------------------------------------- helpers

    private fun scheduleReminderIfAny(settings: Settings, state: AlertState, now: Long) {
        if (settings.remindersBeforeEscalation <= 0) return
        if (state.remindersSent >= settings.remindersBeforeEscalation) return

        val alertedAt = state.alertedAtEpochMs ?: return
        val deadline = state.deadlineEpochMs ?: return
        val nextAt = ReminderSchedule.nextAt(
            alertedAtMs = alertedAt,
            deadlineMs = deadline,
            totalReminders = settings.remindersBeforeEscalation,
            sent = state.remindersSent,
        ) ?: return
        alarms.scheduleReminder(nextAt)
    }

    private fun post(
        settings: Settings,
        state: AlertState,
        reminder: Boolean,
        escalated: Boolean = false,
    ) {
        NotificationChannels.ensureCreated(context, settings.bypassDnd)

        val device = settings.deviceLabel()
        val alertedAt = state.alertedAtEpochMs ?: time.nowEpochMs()
        val deadline = state.deadlineEpochMs ?: alertedAt
        val title = context.getString(R.string.alert_title)
        val text = if (escalated) {
            context.getString(R.string.alert_text_escalated)
        } else {
            context.getString(
                R.string.alert_text,
                Messages.hhmm(alertedAt, zone()),
                Messages.hhmm(deadline, zone()),
            )
        }

        val ackIntent = PendingIntent.getBroadcast(
            context,
            RC_ACK,
            Intent(context, AckReceiver::class.java).setAction(AckReceiver.ACTION_ACK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val dismissIntent = PendingIntent.getBroadcast(
            context,
            RC_DISMISS,
            Intent(context, AckReceiver::class.java).setAction(AckReceiver.ACTION_DISMISSED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val fullScreenIntent = PendingIntent.getActivity(
            context,
            RC_FULL_SCREEN,
            Intent(context, AlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val contentIntent = PendingIntent.getActivity(
            context,
            RC_CONTENT,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val publicVersion = NotificationCompat.Builder(context, NotificationChannels.ALERT)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.alert_public_text))
            .build()

        val builder = NotificationCompat.Builder(context, NotificationChannels.ALERT)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(publicVersion)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(!reminder)
            .setContentIntent(contentIntent)
            .setDeleteIntent(dismissIntent)
            .addAction(0, context.getString(R.string.action_im_here), ackIntent)

        if (settings.fullScreenIntent) {
            // Attach unconditionally: when the special access is missing the
            // system simply drops the intent and shows a heads-up instead, and
            // the pre-check was unreliable on newer platform versions.
            builder.setFullScreenIntent(fullScreenIntent, true)
        }

        // A fresh notification id per reminder: Android only promotes a
        // full-screen intent for a notification it treats as new, and a reminder
        // that cannot re-light the screen is not much of a reminder.
        val notificationId = ALERT_ID_BASE + state.remindersSent
        if (state.remindersSent > 0) {
            NotificationManagerCompat.from(context).cancel(notificationId - 1)
        }
        notifySafely(notificationId, builder.build())
        openAlertScreenIfForeground()
    }

    /**
     * Android only promotes a full-screen intent while the device is locked or
     * the screen is off; with the app visible it shows a heads-up instead. That
     * is fine in the field but confusing when testing from the app, so open the
     * screen directly in that case (a background launch would be blocked, which
     * is exactly why the intent exists).
     */
    private fun openAlertScreenIfForeground() {
        if (!isAppInForeground()) return
        runCatching {
            context.startActivity(
                Intent(context, AlertActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }

    private fun isAppInForeground(): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        val process = manager.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() } ?: return false
        return process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun postFailureNotice() {
        val builder = NotificationCompat.Builder(context, NotificationChannels.ALERT)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle(context.getString(R.string.escalation_failed_title))
            .setContentText(context.getString(R.string.escalation_failed_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.escalation_failed_text)),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    RC_CONTENT,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        notifySafely(FAILURE_ID, builder.build())
    }

    suspend fun cancelNotification() {
        val manager = NotificationManagerCompat.from(context)
        // Every id a given alert may have used, plus a failure notice.
        for (offset in 0..Settings.MAX_REMINDERS) {
            manager.cancel(ALERT_ID_BASE + offset)
        }
        manager.cancel(FAILURE_ID)
    }

    private fun notifySafely(id: Int, notification: Notification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Nothing to show, but the owner must not be left guessing.
            val at = time.nowEpochMs()
            scope.launch {
                events.append(
                    Event(at, EventTypes.ESCALATION_FAILED, "notifications denied"),
                    DEFAULT_LOG_RETENTION_DAYS,
                )
            }
            return
        }
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    companion object {
        private const val RC_ACK = 700
        private const val RC_DISMISS = 701
        private const val RC_FULL_SCREEN = 702
        private const val RC_CONTENT = 703
        /** Alert ids live in their own range so they never collide with the watch notice. */
        const val ALERT_ID_BASE = 2001
        const val FAILURE_ID = 2100

        /** Minutes after the deadline for successive retries. */
        val RETRY_DELAYS_MIN = listOf(2, 10, 30)
        private const val DEFAULT_LOG_RETENTION_DAYS = 30
    }
}
