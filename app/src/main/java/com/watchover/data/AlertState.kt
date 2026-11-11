package com.watchover.data

/** Where a check-in currently is in its lifecycle (design §6). */
enum class AlertPhase {
    /** Nothing outstanding. */
    IDLE,

    /** The full-screen alert is showing and the owner has not responded yet. */
    AWAITING_ACK,

    /** The deadline passed; an SMS is being sent. */
    ESCALATING,

    /** The recipient has been told. A late acknowledgement still triggers an all-clear. */
    ESCALATED,

    /** Every channel failed; the owner sees a local notice. */
    FAILED,
}

/**
 * Persisted state of the current alert. Keyed by [alertId] so that duplicate
 * alarm broadcasts and stale transitions are no-ops.
 */
data class AlertState(
    val phase: AlertPhase = AlertPhase.IDLE,
    val alertId: Long = 0L,
    val checkId: Long = 0L,
    val alertedAtEpochMs: Long? = null,
    val deadlineEpochMs: Long? = null,
    val remindersSent: Int = 0,
    val escalationAttempts: Int = 0,
    val escalatedAtEpochMs: Long? = null,
) {
    val isOpen: Boolean
        get() = phase == AlertPhase.AWAITING_ACK || phase == AlertPhase.ESCALATED || phase == AlertPhase.ESCALATING

    fun raised(alertId: Long, checkId: Long, nowEpochMs: Long, timeoutMinutes: Int): AlertState =
        AlertState(
            phase = AlertPhase.AWAITING_ACK,
            alertId = alertId,
            checkId = checkId,
            alertedAtEpochMs = nowEpochMs,
            deadlineEpochMs = nowEpochMs + timeoutMinutes * 60_000L,
            remindersSent = 0,
            escalationAttempts = 0,
        )

    fun reminderSent(): AlertState = copy(remindersSent = remindersSent + 1)

    fun escalating(): AlertState = copy(phase = AlertPhase.ESCALATING)

    fun escalated(nowEpochMs: Long): AlertState =
        copy(phase = AlertPhase.ESCALATED, escalatedAtEpochMs = nowEpochMs)

    fun failed(): AlertState = copy(phase = AlertPhase.FAILED)

    fun closed(): AlertState = AlertState()
}
