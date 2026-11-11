package com.watchover.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.watchover.BuildConfig
import com.watchover.WatchOverApp
import com.watchover.data.Event
import com.watchover.data.Recipient
import com.watchover.watch.MotionFlag
import com.watchover.watch.WatchService
import kotlinx.coroutines.launch

/**
 * Debug-only entry point so the app can be driven from adb without tapping
 * buttons. Every action is a no-op in release builds.
 *
 *   adb shell am broadcast -a com.watchover.debug.CHECK -n com.watchover/.debug.DebugReceiver
 *
 * See docs/INSTALL.md for the full list.
 */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val action = intent.action ?: return
        if (action !in ACTIONS) return

        val graph = WatchOverApp.from(context)
        val pending = goAsync()

        graph.scope.launch {
            try {
                val now = graph.time.nowEpochMs()
                when (action) {
                    ACTION_CHECK -> graph.controller.onCheckDue(now)

                    ACTION_ACK -> graph.alert.acknowledge()

                    ACTION_DEADLINE -> graph.alert.onDeadline()

                    ACTION_MOVE -> {
                        MotionFlag.markMotion(now)
                        graph.runtime.update {
                            it.copy(movement = it.movement.onMotion(now, graph.bootId()))
                        }
                    }

                    ACTION_ARM -> graph.controller.setEnabled(true)

                    ACTION_DISARM -> graph.controller.setEnabled(false)

                    ACTION_CONFIGURE -> {
                        val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
                        val name = intent.getStringExtra(EXTRA_NAME) ?: "Test contact"
                        graph.settingsStore.update { settings ->
                            settings.copy(recipient = Recipient(name, number))
                        }
                    }

                    ACTION_RESCHEDULE -> graph.controller.refreshSchedule()

                    ACTION_STOP_WATCH -> WatchService.stop(context)

                    ACTION_CLEAR_LOG -> graph.events.clear()

                    ACTION_TEST_SMS -> {
                        val settings = graph.settingsStore.current()
                        val recipient = settings.recipient
                        val ok = recipient != null && graph.sms.send(
                            recipient.phoneNumber,
                            com.watchover.escalate.Messages.test(settings.deviceLabel()),
                        )
                        graph.events.append(
                            Event(now, "DEBUG", "testSms ok=$ok recipient=${recipient?.phoneNumber?.length ?: 0} digits"),
                            graph.retentionDays(),
                        )
                    }

                    ACTION_SET_TIME -> {
                        // Accepts one time or a comma-separated list: "22:20" / "12:00,22:00".
                        val times = intent.getStringExtra(EXTRA_TIME).orEmpty()
                            .split(',')
                            .mapNotNull { runCatching { java.time.LocalTime.parse(it.trim()) }.getOrNull() }
                        if (times.isNotEmpty()) {
                            graph.settingsStore.update { it.copy(checkTimes = times) }
                        }
                        graph.controller.refreshSchedule()
                    }
                }
                graph.events.append(
                    Event(now, "DEBUG", action.substringAfterLast('.')),
                    graph.retentionDays(),
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CHECK = "com.watchover.debug.CHECK"
        const val ACTION_ACK = "com.watchover.debug.ACK"
        const val ACTION_MOVE = "com.watchover.debug.MOVE"
        const val ACTION_ARM = "com.watchover.debug.ARM"
        const val ACTION_DISARM = "com.watchover.debug.DISARM"
        const val ACTION_CONFIGURE = "com.watchover.debug.CONFIGURE"
        const val ACTION_RESCHEDULE = "com.watchover.debug.RESCHEDULE"
        const val ACTION_DEADLINE = "com.watchover.debug.DEADLINE"
        const val ACTION_STOP_WATCH = "com.watchover.debug.STOP_WATCH"
        const val ACTION_SET_TIME = "com.watchover.debug.SET_TIME"
        const val ACTION_TEST_SMS = "com.watchover.debug.TEST_SMS"
        const val ACTION_CLEAR_LOG = "com.watchover.debug.CLEAR_LOG"

        const val EXTRA_NUMBER = "number"
        const val EXTRA_NAME = "name"
        const val EXTRA_TIME = "time"

        val ACTIONS = setOf(
            ACTION_CHECK, ACTION_ACK, ACTION_MOVE, ACTION_ARM,
            ACTION_DISARM, ACTION_CONFIGURE, ACTION_RESCHEDULE, ACTION_DEADLINE,
            ACTION_STOP_WATCH, ACTION_SET_TIME, ACTION_TEST_SMS, ACTION_CLEAR_LOG,
        )
    }
}
