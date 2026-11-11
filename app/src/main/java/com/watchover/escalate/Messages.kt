package com.watchover.escalate

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The texts the recipient and the owner actually read.
 *
 * Kept pure so the wording can be asserted in tests. The first line always
 * identifies the message as automated and names the phone, because it arrives
 * from the owner's own number (design risk 26).
 */
object Messages {

    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    fun escalation(
        deviceLabel: String,
        lastCheckAtMs: Long?,
        alertedAtMs: Long,
        deadlineMs: Long,
        zone: ZoneId,
    ): String {
        val when_ = lastCheckAtMs?.let { hhmm(it, zone) }
        val deadline = hhmm(deadlineMs, zone)
        val movedPhrase = if (when_ != null) {
            "$deviceLabel has not moved since $when_, and the $when_ check-in was not acknowledged by $deadline."
        } else {
            "$deviceLabel has not been picked up, and the check-in was not acknowledged by $deadline."
        }
        return "WatchOver automated alert from $deviceLabel. $movedPhrase Please check on them."
    }

    fun allClear(deviceLabel: String, acknowledgedAtMs: Long, zone: ZoneId): String =
        "WatchOver: $deviceLabel was acknowledged at ${hhmm(acknowledgedAtMs, zone)}. " +
            "Please disregard the earlier alert."

    fun test(deviceLabel: String): String =
        "WatchOver test message from $deviceLabel. No action is needed; this confirms " +
            "that alerts can reach you."

    fun hhmm(epochMs: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(TIME)
}
