package com.watchover.escalate

/**
 * Message splitting that does not touch the telephony stack.
 *
 * `SmsManager.divideMessage()` looks harmless but on Android 17 calls
 * `TelephonyManager.getGroupIdLevel1()`, which throws `SecurityException` for
 * an app without `READ_PHONE_STATE`. Doing it here keeps the app to the single
 * SMS permission the design promised.
 */
object SmsText {

    /** Conservative: 160 GSM-7 characters minus a margin for multi-part headers. */
    const val MAX_PART_LENGTH = 140

    fun split(message: String, maxLength: Int = MAX_PART_LENGTH): List<String> {
        require(maxLength > 0) { "maxLength must be positive" }
        if (message.length <= maxLength) return listOf(message)

        val parts = mutableListOf<String>()
        var remaining = message.trim()
        while (remaining.length > maxLength) {
            var cut = remaining.lastIndexOf(' ', maxLength)
            if (cut <= 0) cut = maxLength
            parts += remaining.substring(0, cut).trim()
            remaining = remaining.substring(cut).trim()
        }
        if (remaining.isNotEmpty()) parts += remaining
        return parts.ifEmpty { listOf(message) }
    }
}
