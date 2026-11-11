package com.watchover.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.watchover.alert.FullScreenAccess
import com.watchover.alert.NotificationChannels

/** One row of the setup/self-check list. */
data class CheckItem(
    val title: String,
    val detail: String,
    val ok: Boolean,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
)

/**
 * The audit that both onboarding and the diagnostics screen use (design §14).
 * Every item is a real, checkable platform state rather than a remembered flag.
 */
object SelfCheck {

    fun items(context: Context, notificationPermissionGranted: Boolean): List<CheckItem> {
        val manager = context.getSystemService(NotificationManager::class.java)
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val powerManager = context.getSystemService(PowerManager::class.java)

        val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val alertBlocked = NotificationChannels.isAlertChannelBlocked(context)
        val watchHidden = NotificationChannels.isWatchNoticeHidden(context)
        val exactAllowed = alarmManager?.canScheduleExactAlarms() ?: false
        val batteryIgnored = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        val smsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val fullScreenOk = FullScreenAccess.isGranted(context)

        return listOf(
            CheckItem(
                title = "Notifications",
                detail = when {
                    !notificationsEnabled -> "Not allowed — the alert cannot be shown."
                    !notificationPermissionGranted -> "Not allowed — the alert cannot be shown."
                    else -> "Allowed"
                },
                ok = notificationsEnabled && notificationPermissionGranted,
                actionLabel = "Open settings",
                action = { openAppNotificationSettings(context) },
            ),
            CheckItem(
                title = "Alert channel",
                detail = if (alertBlocked) "Blocked — unblock the “Check-in alerts” channel." else "Not blocked",
                ok = !alertBlocked,
                actionLabel = "Channels",
                action = { openChannelSettings(context, NotificationChannels.ALERT) },
            ),
            CheckItem(
                title = "Exact timing",
                detail = if (exactAllowed) "Allowed" else "Not allowed — checks may run late.",
                ok = exactAllowed,
                actionLabel = "Allow",
                action = { openExactAlarmSettings(context) },
            ),
            CheckItem(
                title = "Battery optimisation",
                detail = if (batteryIgnored) "Unrestricted" else "Optimised — the watch may be stopped.",
                ok = batteryIgnored,
                actionLabel = "Unrestrict",
                action = { requestIgnoreBatteryOptimisation(context) },
            ),
            CheckItem(
                title = "Full-screen alert",
                detail = if (fullScreenOk) "Allowed" else "Not allowed — the screen may stay dark.",
                ok = fullScreenOk,
                actionLabel = "Allow",
                action = { openFullScreenSettings(context) },
            ),
            CheckItem(
                title = "SMS permission",
                detail = if (smsGranted) "Granted" else "Not granted — escalation cannot be sent.",
                ok = smsGranted,
                actionLabel = "Settings",
                action = { openAppSettings(context) },
            ),
            CheckItem(
                title = "Watch notice",
                detail = if (watchHidden) {
                    "Hidden by you — monitoring continues."
                } else {
                    "Shown quietly in the notification shade."
                },
                ok = true,
                actionLabel = if (watchHidden) null else "Hide it",
                action = if (watchHidden) null else ({ openChannelSettings(context, NotificationChannels.WATCH) }),
            ),
        )
    }

    fun openAppNotificationSettings(context: Context) {
        context.startActivity(
            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openChannelSettings(context: Context, channelId: String) {
        context.startActivity(
            Intent(AndroidSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(AndroidSettings.EXTRA_CHANNEL_ID, channelId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openExactAlarmSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun requestIgnoreBatteryOptimisation(context: Context) {
        runCatching {
            context.startActivity(
                Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun openAppSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun openFullScreenSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        runCatching {
            context.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
