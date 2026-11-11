package com.watchover.checkin

/**
 * The whole check-in decision, isolated so it can be exhaustively tested
 * (design §17: "the decision table").
 *
 * The fail-safe rule lives here: if the watch was not running, the interval was
 * never observed, so the answer is ALERT even when a stale flag says otherwise.
 */
object CheckInDecision {

    enum class Outcome { PASS, ALERT }

    fun decide(latchedMovement: Boolean, watchWasRunning: Boolean): Outcome =
        if (latchedMovement && watchWasRunning) Outcome.PASS else Outcome.ALERT

    /** Human-readable reason, used for the event log. */
    fun reason(latchedMovement: Boolean, watchWasRunning: Boolean): String = when {
        !watchWasRunning -> "interval unobserved (watch not running)"
        latchedMovement -> "movement since last check"
        else -> "no movement since last check"
    }
}
