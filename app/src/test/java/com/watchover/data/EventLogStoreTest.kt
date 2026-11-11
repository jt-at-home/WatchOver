package com.watchover.data

import com.watchover.core.TimeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EventLogStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val clock = object : TimeProvider {
        override fun nowEpochMs(): Long = 1_000_000L
    }

    private fun store(dir: File = temp.newFolder()) = EventLogStore(dir, clock)

    @Test
    fun `keeps only the newest entries once the cap is reached`() = runTest {
        val store = store()
        repeat(EventLogStore.MAX_EVENTS + 100) { index ->
            store.append(Event(index.toLong(), "E", "$index"), retentionDays = 365)
        }

        val kept = store.events.value
        assertEquals(EventLogStore.MAX_EVENTS, kept.size)
        assertEquals(100L, kept.first().atEpochMs)
        assertEquals((EventLogStore.MAX_EVENTS + 99).toLong(), kept.last().atEpochMs)
    }

    @Test
    fun `the file wraps instead of growing without bound`() = runTest {
        val dir = temp.newFolder()
        val store = EventLogStore(dir, clock)
        repeat(EventLogStore.MAX_EVENTS * 3) { index ->
            store.append(Event(index.toLong(), "E", "$index"), retentionDays = 365)
        }

        val lines = File(dir, "events.log").readLines()
        assertTrue("file grew to ${lines.size}", lines.size <= EventLogStore.MAX_EVENTS * 2)
        assertEquals(EventLogStore.MAX_EVENTS, store.events.value.size)
    }

    @Test
    fun `a reload keeps the newest window`() = runTest {
        val dir = temp.newFolder()
        val first = EventLogStore(dir, clock)
        repeat(EventLogStore.MAX_EVENTS + 50) { index ->
            first.append(Event(index.toLong(), "E", "$index"), retentionDays = 365)
        }

        val second = EventLogStore(dir, clock)
        second.load(retentionDays = 365)

        assertEquals(EventLogStore.MAX_EVENTS, second.events.value.size)
        assertEquals(
            (EventLogStore.MAX_EVENTS + 49).toLong(),
            second.events.value.last().atEpochMs,
        )
    }

    @Test
    fun `clearing empties memory and file`() = runTest {
        val dir = temp.newFolder()
        val store = EventLogStore(dir, clock)
        repeat(10) { store.append(Event(it.toLong(), "E", ""), retentionDays = 365) }
        store.clear()

        assertTrue(store.events.value.isEmpty())
        assertEquals("", File(dir, "events.log").readText())
    }

    @Test
    fun `old entries fall outside the retention window`() = runTest {
        val dir = temp.newFolder()
        val store = EventLogStore(dir, clock)
        val day = 24L * 60 * 60 * 1000
        store.append(Event(clock.nowEpochMs() - 40 * day, "OLD", ""), retentionDays = 30)
        store.append(Event(clock.nowEpochMs() - 1 * day, "NEW", ""), retentionDays = 30)

        assertEquals(listOf("NEW"), store.events.value.map { it.type })
    }
}
