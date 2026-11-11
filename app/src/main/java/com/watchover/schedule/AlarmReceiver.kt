package com.watchover.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.watchover.WatchOverApp
import kotlinx.coroutines.launch

/**
 * Entry point for every scheduled wake-up. Work is handed to the application
 * scope and the broadcast is finished asynchronously, so the process is not kept
 * on the main thread.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val expectedAt = intent.getLongExtra(EXTRA_EXPECTED_AT, 0L)
        val attempt = intent.getIntExtra(EXTRA_ATTEMPT, 0)

        val pending = goAsync()
        val graph = WatchOverApp.from(context)
        graph.scope.launch {
            try {
                when (action) {
                    ACTION_CHECK -> graph.controller.onCheckDue(expectedAt)
                    ACTION_REMINDER -> graph.alert.onReminder()
                    ACTION_DEADLINE -> graph.alert.onDeadline()
                    ACTION_RETRY -> graph.alert.onEscalationRetry(attempt)
                    else -> Unit
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CHECK = "com.watchover.action.CHECK"
        const val ACTION_REMINDER = "com.watchover.action.REMINDER"
        const val ACTION_DEADLINE = "com.watchover.action.DEADLINE"
        const val ACTION_RETRY = "com.watchover.action.RETRY"
        const val EXTRA_EXPECTED_AT = "expected_at"
        const val EXTRA_ATTEMPT = "attempt"
    }
}
