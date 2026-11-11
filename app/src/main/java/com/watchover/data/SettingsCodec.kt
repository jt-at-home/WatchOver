package com.watchover.data

import java.time.LocalTime

/**
 * Maps [Settings] to and from a flat string map, and does the same for the
 * check-time list. Pure, so persistence bugs can be caught in unit tests.
 */
object SettingsCodec {

    const val K_ENABLED = "enabled"
    const val K_CHECK_TIMES = "check_times"
    const val K_RESPONSE_TIMEOUT = "response_timeout_min"
    const val K_REMINDERS = "reminders"
    const val K_COUNT_UNLOCK = "count_unlock"
    const val K_FULL_SCREEN = "full_screen_intent"
    const val K_BYPASS_DND = "bypass_dnd"
    const val K_ALL_CLEAR = "send_all_clear"
    const val K_RECIPIENT_LABEL = "recipient_label"
    const val K_RECIPIENT_NUMBER = "recipient_number"
    const val K_DEVICE_NAME = "device_name"
    const val K_LOG_RETENTION = "log_retention_days"

    fun encodeTimes(times: List<LocalTime>): String =
        times.joinToString(",") { "%02d:%02d".format(it.hour, it.minute) }

    fun decodeTimes(raw: String?): List<LocalTime> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',')
            .mapNotNull { part ->
                val pieces = part.trim().split(':')
                if (pieces.size != 2) return@mapNotNull null
                val hour = pieces[0].toIntOrNull() ?: return@mapNotNull null
                val minute = pieces[1].toIntOrNull() ?: return@mapNotNull null
                if (hour !in 0..23 || minute !in 0..59) return@mapNotNull null
                LocalTime.of(hour, minute)
            }
            .distinct()
    }

    fun encode(settings: Settings): Map<String, String> = mapOf(
        K_ENABLED to settings.enabled.toString(),
        K_CHECK_TIMES to encodeTimes(settings.checkTimes),
        K_RESPONSE_TIMEOUT to settings.responseTimeoutMinutes.toString(),
        K_REMINDERS to settings.remindersBeforeEscalation.toString(),
        K_COUNT_UNLOCK to settings.countUnlockAsMotion.toString(),
        K_FULL_SCREEN to settings.fullScreenIntent.toString(),
        K_BYPASS_DND to settings.bypassDnd.toString(),
        K_ALL_CLEAR to settings.sendAllClear.toString(),
        K_RECIPIENT_LABEL to (settings.recipient?.label ?: ""),
        K_RECIPIENT_NUMBER to (settings.recipient?.phoneNumber ?: ""),
        K_DEVICE_NAME to settings.deviceName,
        K_LOG_RETENTION to settings.logRetentionDays.toString(),
    )

    fun decode(map: Map<String, String>): Settings {
        val defaults = Settings()
        val label = map[K_RECIPIENT_LABEL].orEmpty()
        val number = map[K_RECIPIENT_NUMBER].orEmpty()
        val recipient = if (number.isBlank() && label.isBlank()) null else Recipient(label, number)
        return Settings(
            enabled = map[K_ENABLED]?.toBooleanStrictOrNull() ?: defaults.enabled,
            checkTimes = decodeTimes(map[K_CHECK_TIMES]).ifEmpty { defaults.checkTimes },
            responseTimeoutMinutes = map[K_RESPONSE_TIMEOUT]?.toIntOrNull() ?: defaults.responseTimeoutMinutes,
            remindersBeforeEscalation = map[K_REMINDERS]?.toIntOrNull() ?: defaults.remindersBeforeEscalation,
            countUnlockAsMotion = map[K_COUNT_UNLOCK]?.toBooleanStrictOrNull() ?: defaults.countUnlockAsMotion,
            fullScreenIntent = map[K_FULL_SCREEN]?.toBooleanStrictOrNull() ?: defaults.fullScreenIntent,
            bypassDnd = map[K_BYPASS_DND]?.toBooleanStrictOrNull() ?: defaults.bypassDnd,
            sendAllClear = map[K_ALL_CLEAR]?.toBooleanStrictOrNull() ?: defaults.sendAllClear,
            recipient = recipient,
            deviceName = map[K_DEVICE_NAME].orEmpty(),
            logRetentionDays = map[K_LOG_RETENTION]?.toIntOrNull() ?: defaults.logRetentionDays,
        ).sanitized()
    }
}
