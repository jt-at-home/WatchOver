package com.watchover.escalate

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.watchover.WatchOverApp
import com.watchover.data.Event
import com.watchover.data.EventTypes
import kotlinx.coroutines.launch

/** Records what the carrier actually did with the escalation SMS. */
class SmsDeliveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val graph = WatchOverApp.from(context)
        val resultCode = resultCode
        val pending = goAsync()

        graph.scope.launch {
            try {
                when (action) {
                    ACTION_SENT -> {
                        val ok = resultCode == Activity.RESULT_OK
                        graph.events.append(
                            Event(
                                graph.time.nowEpochMs(),
                                if (ok) EventTypes.ESCALATION_SENT else EventTypes.ESCALATION_FAILED,
                                "sent result=$resultCode",
                            ),
                            graph.retentionDays(),
                        )
                    }

                    ACTION_DELIVERED -> {
                        graph.events.append(
                            Event(
                                graph.time.nowEpochMs(),
                                EventTypes.ESCALATION_SENT,
                                "delivered result=$resultCode",
                            ),
                            graph.retentionDays(),
                        )
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SENT = "com.watchover.action.SMS_SENT"
        const val ACTION_DELIVERED = "com.watchover.action.SMS_DELIVERED"
    }
}
