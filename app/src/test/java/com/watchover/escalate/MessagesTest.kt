package com.watchover.escalate

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class MessagesTest {

    private val zone = ZoneId.of("America/Chicago")

    @Test
    fun `escalation message identifies itself as automated`() {
        val text = Messages.escalation(
            deviceLabel = "Jane's phone",
            lastCheckAtMs = 0L,
            alertedAtMs = 0L,
            deadlineMs = 30 * 60_000L,
            zone = zone,
        )
        assertTrue(text.startsWith("WatchOver automated alert from Jane's phone."))
        assertTrue(text.contains("Please check on them."))
    }

    @Test
    fun `escalation message includes the check and deadline times`() {
        val noon = java.time.ZonedDateTime.of(2026, 11, 8, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val half = noon + 30 * 60_000L
        val text = Messages.escalation("Jane's phone", noon, noon, half, zone)
        assertTrue(text.contains("12:00"))
        assertTrue(text.contains("12:30"))
        assertTrue(text.contains("has not moved since 12:00"))
    }

    @Test
    fun `escalation copes with an unknown last check`() {
        val text = Messages.escalation("Jane's phone", null, 0L, 60_000L, zone)
        assertTrue(text.contains("has not been picked up"))
    }

    @Test
    fun `all clear names the acknowledgement time`() {
        val text = Messages.allClear("Jane's phone", 0L, zone)
        assertTrue(text.contains("acknowledged"))
        assertTrue(text.contains("disregard"))
    }

    @Test
    fun `test message says no action is needed`() {
        assertTrue(Messages.test("Jane's phone").contains("No action is needed"))
    }

    @Test
    fun `time formatting is zero padded`() {
        val sevenOhFive = java.time.ZonedDateTime.of(2026, 11, 8, 7, 5, 0, 0, zone).toInstant().toEpochMilli()
        assertTrue(Messages.hhmm(sevenOhFive, zone) == "07:05")
    }
}
