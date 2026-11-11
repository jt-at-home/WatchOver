package com.watchover.data

/** One line in the audit log (design §13). */
data class Event(
    val atEpochMs: Long,
    val type: String,
    val detail: String = "",
)

/**
 * Tab-separated, escaped, one event per line. Chosen over JSON so the format has
 * no dependencies and can be unit-tested on the JVM.
 */
object EventCodec {

    private const val SEP = '\t'

    fun encode(event: Event): String =
        "${event.atEpochMs}$SEP${escape(event.type)}$SEP${escape(event.detail)}"

    fun decode(line: String): Event? {
        if (line.isEmpty()) return null
        val firstSep = line.indexOf(SEP)
        if (firstSep <= 0) return null
        val secondSep = line.indexOf(SEP, firstSep + 1)
        val at = line.substring(0, firstSep).toLongOrNull() ?: return null
        val type = if (secondSep < 0) {
            line.substring(firstSep + 1)
        } else {
            line.substring(firstSep + 1, secondSep)
        }
        val detail = if (secondSep < 0) "" else line.substring(secondSep + 1)
        return Event(at, unescape(type), unescape(detail))
    }

    private fun escape(value: String): String {
        if (value.none { it == '\\' || it == '\t' || it == '\n' || it == '\r' }) return value
        val out = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '\\' -> out.append("\\\\")
                '\t' -> out.append("\\t")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    '\\' -> out.append('\\')
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(c)
                i += 1
            }
        }
        return out.toString()
    }
}

/** Event type constants, kept in one place so the log stays greppable. */
object EventTypes {
    const val CHECK_STARTED = "CHECK_STARTED"
    const val CHECK_PASSED = "CHECK_PASSED"
    const val CHECK_ALERTING = "CHECK_ALERTING"
    const val CHECK_LATE = "CHECK_LATE"
    const val ALERT_RAISED = "ALERT_RAISED"
    const val ALERT_DISMISSED = "ALERT_DISMISSED"
    const val ALERT_ACKNOWLEDGED = "ALERT_ACKNOWLEDGED"
    const val REMINDER_SENT = "REMINDER_SENT"
    const val ESCALATION_STARTED = "ESCALATION_STARTED"
    const val ESCALATION_SENT = "ESCALATION_SENT"
    const val ESCALATION_FAILED = "ESCALATION_FAILED"
    const val ALL_CLEAR_SENT = "ALL_CLEAR_SENT"
    const val MOTION = "MOTION"
    const val WATCH_STARTED = "WATCH_STARTED"
    const val WATCH_STOPPED = "WATCH_STOPPED"
    const val WATCH_RESTARTED = "WATCH_RESTARTED"
    const val BOOT = "BOOT"
    const val CONFIG_CHANGED = "CONFIG_CHANGED"
    const val TEST_ALERT = "TEST_ALERT"
    const val TEST_SMS = "TEST_SMS"
}
