package com.watchover.alert

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.watchover.WatchOverApp
import kotlinx.coroutines.launch

/**
 * Handles the notification's "I'm here" action and its dismissal.
 *
 * This is a receiver rather than a service so the acknowledgement still works
 * when the app process had been killed by the system.
 */
class AckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pending = goAsync()
        val graph = WatchOverApp.from(context)

        graph.scope.launch {
            try {
                when (action) {
                    ACTION_ACK -> graph.alert.acknowledge()
                    ACTION_DISMISSED -> graph.alert.onDismissed()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ACK = "com.watchover.action.ACK"
        const val ACTION_DISMISSED = "com.watchover.action.DISMISSED"

        fun ackIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, AckReceiver::class.java).setAction(ACTION_ACK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
