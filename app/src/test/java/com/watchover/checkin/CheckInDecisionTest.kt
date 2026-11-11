package com.watchover.checkin

import org.junit.Assert.assertEquals
import org.junit.Test

class CheckInDecisionTest {

    @Test
    fun `movement while watching passes the check`() {
        assertEquals(
            CheckInDecision.Outcome.PASS,
            CheckInDecision.decide(latchedMovement = true, watchWasRunning = true),
        )
    }

    @Test
    fun `no movement while watching alerts`() {
        assertEquals(
            CheckInDecision.Outcome.ALERT,
            CheckInDecision.decide(latchedMovement = false, watchWasRunning = true),
        )
    }

    @Test
    fun `a stale flag cannot pass an unobserved interval`() {
        assertEquals(
            CheckInDecision.Outcome.ALERT,
            CheckInDecision.decide(latchedMovement = true, watchWasRunning = false),
        )
    }

    @Test
    fun `no flag and no watch still alerts`() {
        assertEquals(
            CheckInDecision.Outcome.ALERT,
            CheckInDecision.decide(latchedMovement = false, watchWasRunning = false),
        )
    }

    @Test
    fun `reason distinguishes the unobserved case`() {
        assertEquals(
            "interval unobserved (watch not running)",
            CheckInDecision.reason(latchedMovement = true, watchWasRunning = false),
        )
    }
}
