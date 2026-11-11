package com.watchover.core

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * Identifies the current boot so the app can tell that an interval was observed
 * by a previous boot (design §8: after a reboot the flag is treated as movement).
 *
 * Android usually exposes the kernel boot id, but not on every device, so there
 * is a derived fallback that stays constant for the life of a boot.
 */
object BootId {

    fun current(context: Context): String {
        readKernelBootId()?.let { return it }
        val bootWallClock = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        // Round to a minute: computed at two different instants it must still match.
        return "derived-${bootWallClock / 60_000L}"
    }

    private fun readKernelBootId(): String? = runCatching {
        val file = File("/proc/sys/kernel/random/boot_id")
        if (!file.canRead()) return null
        file.readText().trim().ifBlank { null }
    }.getOrNull()
}
