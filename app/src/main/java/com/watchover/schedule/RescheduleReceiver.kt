package com.watchover.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.watchover.WatchOverApp
import kotlinx.coroutines.launch

/**
 * Rebuilds the schedule after anything that invalidates it: a reboot, an app
 * update, a manual clock change, or a timezone change (design §7).
 *
 * Alarms do not survive a reboot, and a wall-clock change silently shifts every
 * RTC alarm, so both must be repaired rather than trusted.
 */
class RescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) return

        val pending = goAsync()
        val graph = WatchOverApp.from(context)
        graph.scope.launch {
            try {
                if (action == Intent.ACTION_BOOT_COMPLETED) {
                    graph.controller.onBoot()
                } else {
                    graph.controller.onClockOrTimezoneChanged()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val HANDLED = setOf(
            // ACTION_LOCKED_BOOT_COMPLETED is deliberately absent: it arrives before
            // the first unlock, when credential-encrypted DataStore is unreadable.
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
