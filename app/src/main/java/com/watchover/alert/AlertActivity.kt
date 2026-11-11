package com.watchover.alert

import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.watchover.WatchOverApp
import com.watchover.data.AlertPhase
import com.watchover.ui.theme.WatchOverTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The full-screen "I'm here" screen (design §9).
 *
 * Shown over the lock screen with the screen turned on, because the whole point
 * is that the owner may be asleep. Back does not dismiss it; only the button, or
 * the escalation timer, ends the alert.
 */
class AlertActivity : ComponentActivity() {

    private var holdJob: Job? = null
    private var lastReminderCount = -1

    /**
     * Hold the display for a few minutes — long enough to be seen and tapped,
     * short enough that a 30-minute window does not burn the screen. Re-armed on
     * every reminder, because a nudge nobody can see is only half a nudge.
     */
    private fun holdScreenOn() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wakeScreen()
        holdJob?.cancel()
        holdJob = lifecycleScope.launch {
            delay(SCREEN_HOLD_MS)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** KEEP_SCREEN_ON keeps a lit screen lit; it cannot light a dark one. */
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        runCatching {
            val power = getSystemService(PowerManager::class.java) ?: return
            power.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "watchover:alert",
            ).acquire(WAKE_LOCK_MS)
        }
    }

    private companion object {
        const val SCREEN_HOLD_MS = 5 * 60_000L
        const val WAKE_LOCK_MS = 10_000L
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // Keep the display on while the alert is up. Without this the screen
        // lights, then sleeps again after the screen timeout — which is useless
        // to someone half asleep, or to a phone lying face down.
        holdScreenOn()

        val graph = WatchOverApp.from(this)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            },
        )

        lifecycleScope.launch {
            val alert = graph.runtime.current().alert
            if (alert.phase != AlertPhase.AWAITING_ACK) {
                finish()
                return@launch
            }
            // If the owner answers from the notification shade (or a reminder
            // re-posts), this screen must not linger showing a stale countdown.
            launch {
                graph.runtime.snapshot.collect { snapshot ->
                    if (snapshot.alert.phase != AlertPhase.AWAITING_ACK) {
                        finish()
                        return@collect
                    }
                    // A reminder just fired: light the screen again and hold it.
                    if (snapshot.alert.remindersSent > lastReminderCount) {
                        lastReminderCount = snapshot.alert.remindersSent
                        holdScreenOn()
                    }
                }
            }
            setContent {
                WatchOverTheme {
                    AlertScreen(
                        deadlineEpochMs = alert.deadlineEpochMs,
                        onAcknowledge = {
                            sendBroadcast(
                                Intent(this@AlertActivity, AckReceiver::class.java)
                                    .setAction(AckReceiver.ACTION_ACK),
                            )
                            finish()
                        },
                    )
                }
            }
        }
    }
}
