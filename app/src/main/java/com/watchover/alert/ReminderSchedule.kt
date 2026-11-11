package com.watchover.alert

/**
 * When reminders should fire (design §9).
 *
 * Reminders are spread evenly between the alert and the escalation deadline:
 * with N reminders the k-th lands at k/(N+1) of the response window. That always
 * places every reminder strictly before the deadline, so the number the owner
 * asks for is the number they actually get — the old fixed-interval scheme
 * silently dropped any reminder that fell on or after the deadline.
 */
object ReminderSchedule {

    /**
     * The next reminder time, or null when there is none left to schedule.
     *
     * @param sent how many reminders have already fired for this alert.
     */
    fun nextAt(
        alertedAtMs: Long,
        deadlineMs: Long,
        totalReminders: Int,
        sent: Int,
    ): Long? {
        if (totalReminders <= 0) return null
        if (sent >= totalReminders) return null
        val window = deadlineMs - alertedAtMs
        if (window <= 0) return null

        val index = sent + 1
        val at = alertedAtMs + (window * index) / (totalReminders + 1)
        return at.takeIf { it < deadlineMs }
    }
}
