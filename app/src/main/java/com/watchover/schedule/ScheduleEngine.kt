package com.watchover.schedule

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure schedule arithmetic (design §7). No Android dependencies.
 *
 * "When is the next check?" is answered against wall-clock time in a named zone,
 * so DST transitions and the date boundary are handled by [java.time] rather
 * than by hand.
 */
class ScheduleEngine(private val zone: ZoneId) {

    /**
     * The first check instant strictly after [after]. Times equal to [after]
     * are skipped so a check that has just fired does not schedule itself again.
     */
    fun nextCheckAfter(after: Instant, times: List<LocalTime>): Instant? {
        val usable = times.distinct().sorted()
        if (usable.isEmpty()) return null

        val localDate: LocalDate = after.atZone(zone).toLocalDate()
        for (offset in 0L..2L) {
            val date = localDate.plusDays(offset)
            for (time in usable) {
                val candidate = at(date, time)
                if (candidate.isAfter(after)) return candidate
            }
        }
        return null
    }

    /** The check instant for a given local date and time, DST-safe. */
    fun at(date: LocalDate, time: LocalTime): Instant =
        ZonedDateTime.of(date, time, zone).toInstant()

    /**
     * True when [now] falls within [tolerance] of one of the configured times.
     * Used by the notification action to explain "this was the 22:00 check".
     */
    fun isNearCheck(now: Instant, times: List<LocalTime>, toleranceMinutes: Long = 5): Boolean {
        val local = now.atZone(zone)
        return times.any { time ->
            val target = at(local.toLocalDate(), time)
            val delta = Math.abs(now.toEpochMilli() - target.toEpochMilli())
            delta <= toleranceMinutes * 60_000
        }
    }

    /** Milliseconds from [now] until the next check, or null when none is configured. */
    fun delayUntilNextCheck(now: Instant, times: List<LocalTime>): Long? =
        nextCheckAfter(now, times)?.let { it.toEpochMilli() - now.toEpochMilli() }
}
