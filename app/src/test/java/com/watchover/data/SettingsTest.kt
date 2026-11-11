package com.watchover.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class SettingsTest {

    private fun valid() = Settings(
        enabled = true,
        recipient = Recipient(label = "Sam", phoneNumber = "5551234567"),
    )

    @Test
    fun `defaults match the design`() {
        val s = Settings()
        assertEquals(listOf(LocalTime.of(12, 0), LocalTime.of(22, 0)), s.checkTimes)
        assertEquals(30, s.responseTimeoutMinutes)
        assertEquals(1, s.remindersBeforeEscalation)
        assertTrue(s.sendAllClear)
        assertFalse(s.enabled)
    }

    @Test
    fun `defaults validate`() {
        assertTrue(Settings().validate().isEmpty())
    }

    @Test
    fun `enabling without a recipient is rejected`() {
        val issues = Settings(enabled = true).validate()
        assertTrue(issues.any { it.field == "recipient" })
    }

    @Test
    fun `valid configuration has no issues`() {
        assertTrue(valid().validate().isEmpty())
    }

    @Test
    fun `check times closer than thirty minutes are rejected`() {
        val s = Settings(checkTimes = listOf(LocalTime.of(12, 0), LocalTime.of(12, 10)))
        assertTrue(s.validate().any { it.field == "checkTimes" })
    }

    @Test
    fun `check times across midnight are compared circularly`() {
        val s = Settings(checkTimes = listOf(LocalTime.of(23, 50), LocalTime.of(0, 10)))
        assertTrue(s.validate().any { it.field == "checkTimes" })
    }

    @Test
    fun `empty check times are rejected`() {
        assertTrue(Settings(checkTimes = emptyList()).validate().any { it.field == "checkTimes" })
    }

    @Test
    fun `duplicate check times are rejected`() {
        val s = Settings(checkTimes = listOf(LocalTime.of(12, 0), LocalTime.of(12, 0)))
        assertTrue(s.validate().any { it.field == "checkTimes" })
    }

    @Test
    fun `sanitize clamps a wild response timeout`() {
        val s = Settings(responseTimeoutMinutes = 5000).sanitized()
        assertEquals(Settings.MAX_RESPONSE_TIMEOUT_MIN, s.responseTimeoutMinutes)
    }

    @Test
    fun `sanitize repairs an empty schedule with the defaults`() {
        assertEquals(Settings.DEFAULT_CHECK_TIMES, Settings(checkTimes = emptyList()).sanitized().checkTimes)
    }

    @Test
    fun `sanitize drops check times that are too close together`() {
        val s = Settings(
            checkTimes = listOf(LocalTime.of(12, 0), LocalTime.of(12, 5), LocalTime.of(22, 0)),
        ).sanitized()
        assertEquals(listOf(LocalTime.of(12, 0), LocalTime.of(22, 0)), s.checkTimes)
    }

    @Test
    fun `phone numbers keep a leading plus and lose formatting`() {
        assertEquals("+15551234567", Settings.normalizePhoneNumber("+1 (555) 123-4567"))
        assertEquals("5551234567", Settings.normalizePhoneNumber("555-123-4567"))
    }

    @Test
    fun `implausible phone numbers are rejected`() {
        assertFalse(Settings.isPlausiblePhoneNumber("123"))
        assertFalse(Settings.isPlausiblePhoneNumber(""))
        assertTrue(Settings.isPlausiblePhoneNumber("+15551234567"))
    }

    @Test
    fun `a disabled draft may be saved without a recipient`() {
        assertTrue(Settings().validate().isEmpty())
    }

    @Test
    fun `arming requires a recipient even though saving does not`() {
        val issues = Settings().validateForArming()
        assertTrue(issues.any { it.field == "recipient" })
    }

    @Test
    fun `arming passes for a complete configuration`() {
        assertTrue(valid().validateForArming().isEmpty())
    }

    @Test
    fun `device label falls back to a sensible phrase`() {
        assertEquals("this phone", Settings().deviceLabel())
        assertEquals("Jane's phone", Settings(deviceName = "Jane's phone").deviceLabel())
    }
}
