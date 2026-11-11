package com.watchover.data

import java.time.LocalTime

/**
 * A person the owner nominates to be texted when a check-in goes unanswered.
 *
 * v1 supports exactly one recipient (design §11). The type is kept simple so a
 * list can be introduced later without a data migration.
 */
data class Recipient(
    val label: String = "",
    val phoneNumber: String = "",
)

/** A problem with the current configuration, shown to the owner rather than hidden. */
data class ValidationIssue(val field: String, val message: String)

/**
 * All owner-configurable state (design §12).
 *
 * This type is deliberately free of Android dependencies so the validation rules
 * and defaults can be unit-tested.
 */
data class Settings(
    val enabled: Boolean = false,
    val checkTimes: List<LocalTime> = DEFAULT_CHECK_TIMES,
    val responseTimeoutMinutes: Int = DEFAULT_RESPONSE_TIMEOUT_MIN,
    val remindersBeforeEscalation: Int = DEFAULT_REMINDERS,
    val countUnlockAsMotion: Boolean = true,
    val fullScreenIntent: Boolean = true,
    val bypassDnd: Boolean = false,
    val sendAllClear: Boolean = true,
    val recipient: Recipient? = null,
    val deviceName: String = "",
    val logRetentionDays: Int = 30,
) {

    /** Times in ascending order, de-duplicated. */
    fun sortedCheckTimes(): List<LocalTime> = checkTimes.distinct().sorted()

    /**
     * Clamp every value into its permitted range and repair the schedule.
     * Unlike [validate], this never fails: it is used when reading persisted
     * values so a corrupt preference cannot brick the app.
     */
    fun sanitized(): Settings {
        val times = sanitizeTimes(checkTimes)
        val timeout = responseTimeoutMinutes.coerceIn(MIN_RESPONSE_TIMEOUT_MIN, MAX_RESPONSE_TIMEOUT_MIN)
        val reminders = remindersBeforeEscalation.coerceIn(0, MAX_REMINDERS)
        return copy(
            checkTimes = times,
            responseTimeoutMinutes = timeout,
            remindersBeforeEscalation = reminders,
            logRetentionDays = logRetentionDays.coerceIn(7, 365),
            recipient = recipient?.copy(
                label = recipient.label.trim().take(40),
                phoneNumber = normalizePhoneNumber(recipient.phoneNumber),
            ),
            deviceName = deviceName.trim().take(40),
        )
    }

    /** Human-readable problems that must be fixed before the app can arm. */
    fun validate(): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        if (checkTimes.isEmpty()) {
            issues += ValidationIssue("checkTimes", "Add at least one check time.")
        }
        if (checkTimes.size > MAX_CHECK_TIMES) {
            issues += ValidationIssue("checkTimes", "Use at most $MAX_CHECK_TIMES check times.")
        }
        if (checkTimes.distinct().size != checkTimes.size) {
            issues += ValidationIssue("checkTimes", "Check times must be different from each other.")
        }
        val tooClose = sortedCheckTimes().let { sorted ->
            sorted.indices.any { i ->
                val a = sorted[i]
                val b = sorted[(i + 1) % sorted.size]
                sorted.size > 1 && circularMinutes(a, b) < MIN_MINUTES_BETWEEN_CHECKS
            }
        }
        if (tooClose) {
            issues += ValidationIssue("checkTimes", "Check times must be at least 30 minutes apart.")
        }
        if (responseTimeoutMinutes !in MIN_RESPONSE_TIMEOUT_MIN..MAX_RESPONSE_TIMEOUT_MIN) {
            issues += ValidationIssue(
                "responseTimeout",
                "Response time must be ${MIN_RESPONSE_TIMEOUT_MIN}–${MAX_RESPONSE_TIMEOUT_MIN} minutes.",
            )
        }
        if (remindersBeforeEscalation !in 0..MAX_REMINDERS) {
            issues += ValidationIssue("reminders", "Use 0–$MAX_REMINDERS reminders.")
        }
        if (enabled) {
            val number = recipient?.phoneNumber.orEmpty()
            if (!isPlausiblePhoneNumber(number)) {
                issues += ValidationIssue("recipient", "Add a recipient phone number.")
            }
        }
        return issues
    }

    /** Convenience: true when nothing blocks arming. */
    fun isValid(): Boolean = validate().isEmpty()

    /**
     * Everything that must be true before the app may arm, including the
     * recipient. [validate] alone only requires a recipient once enabled, so
     * that a half-finished setup can still be saved.
     */
    fun validateForArming(): List<ValidationIssue> {
        val base = validate()
        if (isPlausiblePhoneNumber(recipient?.phoneNumber.orEmpty())) return base
        return if (base.any { it.field == "recipient" }) {
            base
        } else {
            base + ValidationIssue("recipient", "Add a recipient phone number.")
        }
    }

    /** Text used in the escalation SMS. */
    fun deviceLabel(): String = deviceName.ifBlank { "this phone" }

    companion object {
        val DEFAULT_CHECK_TIMES: List<LocalTime> = listOf(LocalTime.of(12, 0), LocalTime.of(22, 0))

        const val MAX_CHECK_TIMES = 4
        const val MIN_MINUTES_BETWEEN_CHECKS = 30
        const val DEFAULT_RESPONSE_TIMEOUT_MIN = 30
        const val MIN_RESPONSE_TIMEOUT_MIN = 5
        const val MAX_RESPONSE_TIMEOUT_MIN = 120
        const val DEFAULT_REMINDERS = 1
        const val MAX_REMINDERS = 3

        /** Keeps digits and a leading '+', which is what SmsManager expects. */
        fun normalizePhoneNumber(raw: String): String {
            val trimmed = raw.trim()
            val plus = trimmed.startsWith("+")
            val digits = trimmed.filter { it.isDigit() }
            return if (plus) "+$digits" else digits
        }

        fun isPlausiblePhoneNumber(raw: String): Boolean {
            val digits = raw.filter { it.isDigit() }
            return digits.length in 7..15
        }

        /** Shortest distance in minutes between two wall-clock times, around midnight. */
        fun circularMinutes(a: LocalTime, b: LocalTime): Int {
            val diff = Math.abs(a.toSecondOfDay() - b.toSecondOfDay()) / 60
            return minOf(diff, 24 * 60 - diff)
        }

        /**
         * Sort, drop duplicates, cap the count, and remove times closer than
         * [MIN_MINUTES_BETWEEN_CHECKS]. Never returns an empty list.
         */
        fun sanitizeTimes(times: List<LocalTime>): List<LocalTime> {
            val sorted = times.distinct().sorted()
            if (sorted.isEmpty()) return DEFAULT_CHECK_TIMES
            if (sorted.size == 1) return sorted
            val kept = mutableListOf<LocalTime>()
            for (candidate in sorted) {
                if (kept.all { circularMinutes(it, candidate) >= MIN_MINUTES_BETWEEN_CHECKS }) {
                    kept += candidate
                }
            }
            return kept.take(MAX_CHECK_TIMES).ifEmpty { DEFAULT_CHECK_TIMES.take(1) }
        }
    }
}
