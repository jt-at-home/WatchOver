package com.watchover.data

/**
 * The latched "has the phone moved since the last check?" flag (design §8).
 *
 * The flag is the whole of the movement test: any motion event between checks
 * sets it, and the next check that consumes it clears it. [bootId] lets the app
 * notice that an interval was observed by a previous boot (see [isSameBoot]).
 */
data class MovementState(
    val flag: Boolean = false,
    val lastMotionEpochMs: Long? = null,
    val lastCheckEpochMs: Long? = null,
    val bootId: String = "",
) {
    fun isSameBoot(currentBootId: String): Boolean = bootId == currentBootId

    fun onMotion(nowEpochMs: Long, bootId: String): MovementState =
        copy(flag = true, lastMotionEpochMs = nowEpochMs, bootId = bootId)

    /** A check consumed the flag. */
    fun onCheckConsumed(nowEpochMs: Long, bootId: String): MovementState =
        copy(flag = false, lastCheckEpochMs = nowEpochMs, bootId = bootId)

    /** The owner acknowledged an alert: that is presence for the next interval. */
    fun onAcknowledged(nowEpochMs: Long, bootId: String): MovementState =
        copy(flag = false, lastCheckEpochMs = nowEpochMs, bootId = bootId)

    /** After a reboot the interval was unobserved; treat it as movement (design §8). */
    fun afterReboot(nowEpochMs: Long, newBootId: String): MovementState =
        copy(flag = true, lastCheckEpochMs = nowEpochMs, bootId = newBootId)
}
