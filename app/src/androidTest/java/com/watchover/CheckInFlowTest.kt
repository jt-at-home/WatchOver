package com.watchover

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.watchover.data.AlertPhase
import com.watchover.data.AlertState
import com.watchover.data.MovementState
import com.watchover.data.Recipient
import com.watchover.data.RuntimeSnapshot
import com.watchover.data.Settings
import com.watchover.watch.MotionFlag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end checks of the parts that unit tests cannot reach: persistence,
 * the real notification manager, the real alarm manager and the real service.
 *
 * Runs on a device or emulator. Each test resets stored state first, so they are
 * independent of whatever the app was doing before the run.
 */
@RunWith(AndroidJUnit4::class)
class CheckInFlowTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.SEND_SMS,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val graph = WatchOverApp.from(context)

    @Before
    fun reset() = runBlocking {
        graph.controller.setEnabled(false)
        graph.settingsStore.set(
            Settings(
                enabled = false,
                recipient = Recipient("Test contact", "5551234567"),
                deviceName = "Test phone",
            ),
        )
        graph.runtime.update { RuntimeSnapshot(movement = MovementState(), alert = AlertState()) }
        graph.alert.cancelNotification()
        MotionFlag.consume()
    }

    @Test
    fun aCheckWithNoMovementRaisesAnAlert() = runBlocking {
        graph.controller.setEnabled(true)
        assertTrue(graph.settingsStore.current().enabled)

        graph.controller.onCheckDue(System.currentTimeMillis())

        val snapshot = graph.runtime.current()
        assertEquals(AlertPhase.AWAITING_ACK, snapshot.alert.phase)
        assertTrue(snapshot.alert.deadlineEpochMs!! > snapshot.alert.alertedAtEpochMs!!)
    }

    @Test
    fun acknowledgingClosesTheAlert() = runBlocking {
        graph.controller.setEnabled(true)
        graph.controller.onCheckDue(System.currentTimeMillis())
        assertEquals(AlertPhase.AWAITING_ACK, graph.runtime.current().alert.phase)

        assertTrue(graph.alert.acknowledge())

        assertEquals(AlertPhase.IDLE, graph.runtime.current().alert.phase)
        assertFalse(graph.runtime.current().movement.flag)
    }

    @Test
    fun movementLatchedBeforeTheCheckMakesItPass() = runBlocking {
        graph.controller.setEnabled(true)

        val now = System.currentTimeMillis()
        MotionFlag.markMotion(now)
        graph.runtime.update { it.copy(movement = it.movement.onMotion(now, graph.bootId())) }

        graph.controller.onCheckDue(now)

        val snapshot = graph.runtime.current()
        assertEquals(AlertPhase.IDLE, snapshot.alert.phase)
        // The flag is consumed by the check, whether it passes or alerts.
        assertFalse(snapshot.movement.flag)
    }

    @Test
    fun armingWithNoRecipientIsRefused() = runBlocking {
        graph.settingsStore.set(Settings(enabled = false, recipient = null))
        graph.controller.setEnabled(true)
        assertFalse(graph.settingsStore.current().enabled)
    }

    @Test
    fun theDeadlineEscalatesAndClosesTheAcknowledgementWindow() = runBlocking {
        graph.controller.setEnabled(true)
        graph.controller.onCheckDue(System.currentTimeMillis())

        graph.alert.onDeadline()

        val phase = graph.runtime.current().alert.phase
        // The emulator's radio usually reports success; a failure also leaves the
        // window closed, which is the property that matters here.
        assertTrue(phase == AlertPhase.ESCALATED || phase == AlertPhase.ESCALATING || phase == AlertPhase.FAILED)
    }
}
