package com.watchover.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderScheduleTest {

    private val minute = 60_000L
    private val alertedAt = 1_000_000_000L

    @Test
    fun `three reminders split a thirty minute window into quarters`() {
        val deadline = alertedAt + 30 * minute

        val first = ReminderSchedule.nextAt(alertedAt, deadline, totalReminders = 3, sent = 0)!!
        val second = ReminderSchedule.nextAt(alertedAt, deadline, totalReminders = 3, sent = 1)!!
        val third = ReminderSchedule.nextAt(alertedAt, deadline, totalReminders = 3, sent = 2)!!

        assertEquals(alertedAt + 7 * minute + 30_000L, first)
        assertEquals(alertedAt + 15 * minute, second)
        assertEquals(alertedAt + 22 * minute + 30_000L, third)
    }

    @Test
    fun `a single reminder lands in the middle of the window`() {
        val deadline = alertedAt + 30 * minute
        val only = ReminderSchedule.nextAt(alertedAt, deadline, totalReminders = 1, sent = 0)!!
        assertEquals(alertedAt + 15 * minute, only)
    }

    @Test
    fun `a five minute window puts its one reminder at two and a half minutes`() {
        val deadline = alertedAt + 5 * minute
        val only = ReminderSchedule.nextAt(alertedAt, deadline, totalReminders = 1, sent = 0)!!
        assertEquals(alertedAt + 2 * minute + 30_000L, only)
    }

    @Test
    fun `every requested reminder actually fits before the deadline`() {
        for (total in 1..3) {
            for (timeoutMinutes in listOf(5, 10, 30, 120)) {
                val deadline = alertedAt + timeoutMinutes * minute
                repeat(total) { sent ->
                    val at = ReminderSchedule.nextAt(alertedAt, deadline, total, sent)
                    requireNotNull(at) { "reminder $sent of $total missing in $timeoutMinutes min" }
                    assert(at < deadline) { "reminder $sent of $total lands past the deadline" }
                }
            }
        }
    }

    @Test
    fun `no reminders configured means nothing to schedule`() {
        assertNull(ReminderSchedule.nextAt(alertedAt, alertedAt + 30 * minute, 0, 0))
    }

    @Test
    fun `once all reminders have fired there is nothing left`() {
        val deadline = alertedAt + 30 * minute
        assertNull(ReminderSchedule.nextAt(alertedAt, deadline, totalReminders = 2, sent = 2))
        assertNull(ReminderSchedule.nextAt(alertedAt, deadline, totalReminders = 2, sent = 3))
    }

    @Test
    fun `a zero length window has no room for a reminder`() {
        assertNull(ReminderSchedule.nextAt(alertedAt, alertedAt, totalReminders = 1, sent = 0))
    }
}
