package com.watchover.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertStateTest {

    @Test
    fun `raising an alert sets the deadline from the timeout`() {
        val state = AlertState().raised(alertId = 1, checkId = 2, nowEpochMs = 1_000L, timeoutMinutes = 30)
        assertEquals(AlertPhase.AWAITING_ACK, state.phase)
        assertEquals(1_000L + 30 * 60_000L, state.deadlineEpochMs)
        assertTrue(state.isOpen)
    }

    @Test
    fun `reminders are counted`() {
        val state = AlertState().raised(1, 2, 0L, 30).reminderSent().reminderSent()
        assertEquals(2, state.remindersSent)
    }

    @Test
    fun `escalation keeps the alert open so a late ack can still clear it`() {
        val escalated = AlertState().raised(1, 2, 0L, 30).escalating().escalated(5_000L)
        assertEquals(AlertPhase.ESCALATED, escalated.phase)
        assertTrue(escalated.isOpen)
        assertEquals(5_000L, escalated.escalatedAtEpochMs)
    }

    @Test
    fun `failure closes the open state`() {
        val failed = AlertState().raised(1, 2, 0L, 30).escalating().failed()
        assertEquals(AlertPhase.FAILED, failed.phase)
        assertFalse(failed.isOpen)
    }

    @Test
    fun `closing resets everything`() {
        val closed = AlertState().raised(1, 2, 0L, 30).escalated(1L).closed()
        assertEquals(AlertState(), closed)
        assertEquals(AlertPhase.IDLE, closed.phase)
    }
}
