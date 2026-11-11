package com.watchover.core

/** Injectable time source, so the check-in state machine can be tested deterministically. */
interface TimeProvider {
    fun nowEpochMs(): Long
}

object SystemTimeProvider : TimeProvider {
    override fun nowEpochMs(): Long = System.currentTimeMillis()
}
