package com.watchover.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.provider.Settings

/**
 * The app uses exactly two channels, deliberately separated (design §10):
 *
 *  - [ALERT] carries the "I'm here" check-in. High importance, audible.
 *  - [WATCH] is the foreground-service notice. Silent, content-free, and safe
 *    for the owner to hide — hiding it never hides an alert.
 */
object NotificationChannels {

    const val ALERT = "pickup_checkin"
    const val WATCH = "watch_service"

    const val ALERT_NOTIFICATION_ID = 1001
    const val WATCH_NOTIFICATION_ID = 1002

    fun ensureCreated(context: Context, bypassDnd: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: Settings.System.DEFAULT_NOTIFICATION_URI

        val alert = NotificationChannel(
            ALERT,
            "Check-in alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Asks you to confirm you're there. Cannot be silenced by accident."
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
            enableLights(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(
                alarmSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            setBypassDnd(bypassDnd)
        }

        val watch = NotificationChannel(
            WATCH,
            "Monitoring",
            // Android shows its own "app is using a foreground service" note if
            // the priority drops below LOW, so LOW is the floor (design §10).
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Silent notice that pickup monitoring is running."
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
        }

        manager.createNotificationChannels(listOf(alert, watch))
    }

    /** True when the user has hidden or blocked the silent watch notice. */
    fun isWatchNoticeHidden(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = manager.getNotificationChannel(WATCH) ?: return false
        return channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    fun isAlertChannelBlocked(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = manager.getNotificationChannel(ALERT) ?: return false
        return channel.importance == NotificationManager.IMPORTANCE_NONE
    }
}
