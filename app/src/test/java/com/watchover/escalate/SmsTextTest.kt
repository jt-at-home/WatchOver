package com.watchover.escalate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsTextTest {

    @Test
    fun `short message stays a single part`() {
        val parts = SmsText.split("WatchOver test message. No action needed.")
        assertEquals(1, parts.size)
    }

    @Test
    fun `long message is split and every part fits`() {
        val message = buildString {
            repeat(12) { append("The quick brown fox jumps over the lazy dog. ") }
        }.trim()
        val parts = SmsText.split(message)
        assertTrue(parts.size >= 3)
        assertTrue(parts.all { it.length <= SmsText.MAX_PART_LENGTH })
    }

    @Test
    fun `splitting prefers word boundaries`() {
        val message = "a".repeat(100) + " " + "b".repeat(100)
        val parts = SmsText.split(message, maxLength = 120)
        assertTrue(parts.first().all { it == 'a' })
        assertTrue(parts.last().all { it == 'b' })
    }

    @Test
    fun `no words are lost`() {
        val words = List(60) { "word$it" }
        val message = words.joinToString(" ")
        val parts = SmsText.split(message, maxLength = 50)
        assertEquals(words, parts.joinToString(" ").split(" "))
    }

    @Test
    fun `text with no spaces is hard cut`() {
        val message = "x".repeat(300)
        val parts = SmsText.split(message, maxLength = 100)
        assertEquals(3, parts.size)
        assertEquals(message, parts.joinToString(""))
    }

    @Test
    fun `exactly at the limit is not split`() {
        val message = "y".repeat(SmsText.MAX_PART_LENGTH)
        assertEquals(1, SmsText.split(message).size)
    }
}
