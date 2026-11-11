package com.watchover.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleEngineTest {

    private val zone = ZoneId.of("America/Chicago")
    private val engine = ScheduleEngine(zone)
    private val times = listOf(LocalTime.of(12, 0), LocalTime.of(22, 0))

    private fun instant(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Instant =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant()

    @Test
    fun `next check from just after noon is 22 00 the same day`() {
        val from = instant(2026, 11, 8, 12, 0)
        val next = engine.nextCheckAfter(from, times)
        assertEquals(instant(2026, 11, 8, 22, 0), next)
    }

    @Test
    fun `next check from evening rolls to noon tomorrow`() {
        val from = instant(2026, 11, 8, 22, 0)
        val next = engine.nextCheckAfter(from, times)
        assertEquals(instant(2026, 11, 9, 12, 0), next)
    }

    @Test
    fun `next check from early morning is noon the same day`() {
        val from = instant(2026, 11, 9, 6, 30)
        val next = engine.nextCheckAfter(from, times)
        assertEquals(instant(2026, 11, 9, 12, 0), next)
    }

    @Test
    fun `a check exactly at its time is skipped so it cannot re-schedule itself`() {
        val exactlyNoon = instant(2026, 11, 8, 12, 0)
        val next = engine.nextCheckAfter(exactlyNoon, times)
        assertTrue(next!!.isAfter(exactlyNoon))
        assertEquals(instant(2026, 11, 8, 22, 0), next)
    }

    @Test
    fun `single configured time still works across days`() {
        val only = listOf(LocalTime.of(9, 15))
        val next = engine.nextCheckAfter(instant(2026, 11, 8, 12, 0), only)
        assertEquals(instant(2026, 11, 9, 9, 15), next)
    }

    @Test
    fun `empty schedule yields no next check`() {
        assertNull(engine.nextCheckAfter(instant(2026, 11, 8, 12, 0), emptyList()))
    }

    @Test
    fun `delay is positive and under 24 hours`() {
        val delay = engine.delayUntilNextCheck(instant(2026, 11, 8, 13, 0), times)!!
        assertTrue(delay in 1..(24 * 60 * 60 * 1000))
        assertEquals(9 * 60 * 60 * 1000L, delay)
    }

    @Test
    fun `spring forward day still produces a valid check instant`() {
        // 2026-03-08: 02:00 CST jumps to 03:00 CDT in America/Chicago.
        // A 12:00 check is unaffected, but the machinery must not throw.
        val next = engine.nextCheckAfter(instant(2026, 3, 8, 0, 30), times)
        assertNotNull(next)
        assertEquals(instant(2026, 3, 8, 12, 0), next)
    }

    @Test
    fun `fall back day still produces a valid check instant`() {
        // 2026-11-01: clocks go back at 02:00 CDT to 01:00 CST.
        val next = engine.nextCheckAfter(instant(2026, 11, 1, 0, 30), times)
        assertNotNull(next)
        assertEquals(instant(2026, 11, 1, 12, 0), next)
    }

    @Test
    fun `check spanning midnight uses the next calendar day`() {
        val late = listOf(LocalTime.of(23, 30), LocalTime.of(0, 30))
        val next = engine.nextCheckAfter(instant(2026, 11, 8, 23, 45), late.sorted())
        assertEquals(instant(2026, 11, 9, 0, 30), next)
    }

    @Test
    fun `isNearCheck tolerates a few minutes but not an hour`() {
        assertTrue(engine.isNearCheck(instant(2026, 11, 8, 12, 2), times))
        assertTrue(!engine.isNearCheck(instant(2026, 11, 8, 13, 0), times))
        assertEquals(LocalDate.of(2026, 11, 8), instant(2026, 11, 8, 12, 0).atZone(zone).toLocalDate())
    }
}
