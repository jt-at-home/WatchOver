package com.watchover.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class CodecTest {

    @Test
    fun `flat map survives separators and newlines in values`() {
        val original = mapOf(
            "plain" to "value",
            "equals" to "a=b=c",
            "newline" to "line1\nline2",
            "backslash" to "c:\\path",
        )
        val decoded = FlatMap.decode(FlatMap.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `flat map ignores malformed lines`() {
        val decoded = FlatMap.decode("good=value\nnotapair\n\nother=x")
        assertEquals(mapOf("good" to "value", "other" to "x"), decoded)
    }

    @Test
    fun `event survives tabs and newlines in the detail`() {
        val event = Event(1700000000000, EventTypes.CHECK_PASSED, "detail\twith\ttabs\nand newline")
        val decoded = EventCodec.decode(EventCodec.encode(event))
        assertEquals(event, decoded)
    }

    @Test
    fun `event with empty detail survives`() {
        val event = Event(1, EventTypes.BOOT, "")
        assertEquals(event, EventCodec.decode(EventCodec.encode(event)))
    }

    @Test
    fun `garbage event lines are rejected rather than throwing`() {
        assertEquals(null, EventCodec.decode(""))
        assertEquals(null, EventCodec.decode("not-a-number\ttype"))
        assertEquals(null, EventCodec.decode("no-separator"))
    }

    @Test
    fun `settings survive a full encode decode round trip`() {
        val settings = Settings(
            enabled = true,
            checkTimes = listOf(LocalTime.of(7, 15), LocalTime.of(21, 45)),
            responseTimeoutMinutes = 45,
            remindersBeforeEscalation = 2,
            countUnlockAsMotion = false,
            fullScreenIntent = false,
            bypassDnd = true,
            sendAllClear = false,
            recipient = Recipient("Sam", "+15551234567"),
            deviceName = "Jane's phone",
            logRetentionDays = 60,
        )
        val decoded = SettingsCodec.decode(SettingsCodec.encode(settings))
        assertEquals(settings, decoded)
    }

    @Test
    fun `empty settings map falls back to defaults`() {
        val decoded = SettingsCodec.decode(emptyMap())
        assertEquals(Settings(), decoded)
    }

    @Test
    fun `corrupt values are repaired rather than trusted`() {
        val decoded = SettingsCodec.decode(
            mapOf(
                SettingsCodec.K_RESPONSE_TIMEOUT to "not-a-number",
                SettingsCodec.K_CHECK_TIMES to "99:99,12:00",
            ),
        )
        assertEquals(Settings.DEFAULT_RESPONSE_TIMEOUT_MIN, decoded.responseTimeoutMinutes)
        assertEquals(listOf(LocalTime.of(12, 0)), decoded.checkTimes)
    }

    @Test
    fun `check times are encoded in a stable sortable form`() {
        assertEquals(
            "07:15,21:45",
            SettingsCodec.encodeTimes(listOf(LocalTime.of(7, 15), LocalTime.of(21, 45))),
        )
        assertEquals(
            listOf(LocalTime.of(7, 15), LocalTime.of(21, 45)),
            SettingsCodec.decodeTimes("07:15,21:45"),
        )
    }

    @Test
    fun `runtime snapshot round trips every field`() {
        val snapshot = RuntimeSnapshot(
            movement = MovementState(
                flag = true,
                lastMotionEpochMs = 111L,
                lastCheckEpochMs = 222L,
                bootId = "boot-1",
            ),
            alert = AlertState(
                phase = AlertPhase.ESCALATED,
                alertId = 5L,
                checkId = 6L,
                alertedAtEpochMs = 333L,
                deadlineEpochMs = 444L,
                remindersSent = 1,
                escalationAttempts = 2,
                escalatedAtEpochMs = 555L,
            ),
            nextCheckAtEpochMs = 666L,
        )
        val decoded = RuntimeCodec.decode(RuntimeCodec.encode(snapshot))
        assertEquals(snapshot, decoded)
    }

    @Test
    fun `unknown alert phase decodes as idle`() {
        val decoded = RuntimeCodec.decode(mapOf("phase" to "NONSENSE"))
        assertEquals(AlertPhase.IDLE, decoded.alert.phase)
    }

    @Test
    fun `movement state transitions`() {
        val initial = MovementState(bootId = "b1")
        val moved = initial.onMotion(100L, "b1")
        assertTrue(moved.flag)
        assertEquals(100L, moved.lastMotionEpochMs)

        val consumed = moved.onCheckConsumed(200L, "b1")
        assertEquals(false, consumed.flag)
        assertEquals(200L, consumed.lastCheckEpochMs)

        val rebooted = initial.afterReboot(300L, "b2")
        assertTrue(rebooted.flag)
        assertEquals("b2", rebooted.bootId)
    }
}
