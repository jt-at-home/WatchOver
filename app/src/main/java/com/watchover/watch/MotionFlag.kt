package com.watchover.watch

/**
 * Process-local cache of the latched movement flag (design §8).
 *
 * The durable flag lives in DataStore; this mirror exists because a sensor
 * callback must be able to record movement without waiting on disk I/O, and
 * because a check must see motion that arrived microseconds earlier.
 */
object MotionFlag {

    @Volatile
    var set: Boolean = false
        private set

    @Volatile
    var lastMotionAtMs: Long = 0L
        private set

    fun markMotion(nowMs: Long) {
        set = true
        lastMotionAtMs = nowMs
    }

    fun consume() {
        set = false
    }

    fun restore(flag: Boolean, lastMotionAtMs: Long) {
        set = flag
        this.lastMotionAtMs = lastMotionAtMs
    }
}
