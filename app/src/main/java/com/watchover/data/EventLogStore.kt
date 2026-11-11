package com.watchover.data

import com.watchover.core.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Rolling audit trail on disk (design §13).
 *
 * The log keeps the **most recent [MAX_EVENTS] entries** and drops the oldest as
 * new ones arrive — a fixed window rather than an ever-growing file. Age-based
 * retention still applies on top, so a log that is never used does not keep
 * entries from months ago.
 *
 * Appends go straight to the end of the file. Every [WRAP_AFTER] appends the
 * file is rewritten from the in-memory window, so it never grows past roughly
 * twice the cap while keeping writes amortised (a rewrite is a few tens of KB,
 * not something that should happen on every motion event).
 *
 * Deliberately not a database: a text file is simpler to inspect, export, and
 * test than Room.
 */
class EventLogStore(
    private val directory: File,
    private val time: TimeProvider,
) {

    private val file = File(directory, "events.log")
    private val mutex = Mutex()
    private val _events = MutableStateFlow<List<Event>>(emptyList())

    /** Oldest first; never longer than [MAX_EVENTS]. */
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    private var appendedSinceRewrite = 0

    suspend fun load(retentionDays: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val loaded = readAll()
            val kept = prune(loaded, retentionDays)
            _events.value = kept
            if (kept.size != loaded.size) {
                rewrite(kept)
                appendedSinceRewrite = 0
            }
        }
    }

    suspend fun append(event: Event, retentionDays: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { file.appendText(EventCodec.encode(event) + "\n") }
            appendedSinceRewrite += 1

            val merged = prune(_events.value + event, retentionDays)
            _events.value = merged

            if (appendedSinceRewrite >= WRAP_AFTER) {
                rewrite(merged)
                appendedSinceRewrite = 0
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { file.writeText("") }
            _events.value = emptyList()
            appendedSinceRewrite = 0
        }
    }

    /** Plain-text export for the log screen. */
    suspend fun export(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            _events.value.joinToString("\n") { event ->
                "${formatTime(event.atEpochMs)}\t${event.type}\t${event.detail}"
            }
        }
    }

    private fun readAll(): List<Event> = runCatching {
        if (!file.exists()) return emptyList()
        file.readLines().mapNotNull(EventCodec::decode)
    }.getOrDefault(emptyList())

    /** Age limit first, then the fixed-size window. */
    private fun prune(events: List<Event>, retentionDays: Int): List<Event> {
        val cutoff = time.nowEpochMs() - retentionDays.coerceIn(1, 365) * 24L * 60 * 60 * 1000
        return events.filter { it.atEpochMs >= cutoff }.takeLast(MAX_EVENTS)
    }

    private fun rewrite(events: List<Event>) {
        runCatching {
            file.writeText(events.joinToString("") { EventCodec.encode(it) + "\n" })
        }
    }

    private fun formatTime(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    companion object {
        /** Visible window and the hard cap on entries kept. */
        const val MAX_EVENTS = 500

        /** Rewrite the file after this many appends, so it wraps instead of growing. */
        private const val WRAP_AFTER = MAX_EVENTS
    }
}
