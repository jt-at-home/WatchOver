package com.watchover.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Movement flag plus the current alert lifecycle, as one consistent snapshot. */
data class RuntimeSnapshot(
    val movement: MovementState = MovementState(),
    val alert: AlertState = AlertState(),
    /** When the next check is expected; lets the app notice a lost alarm. */
    val nextCheckAtEpochMs: Long? = null,
)

/** Pure encode/decode of [RuntimeSnapshot] so it can be unit-tested. */
object RuntimeCodec {

    private const val K_FLAG = "flag"
    private const val K_LAST_MOTION = "last_motion"
    private const val K_LAST_CHECK = "last_check"
    private const val K_BOOT = "boot"
    private const val K_PHASE = "phase"
    private const val K_ALERT_ID = "alert_id"
    private const val K_CHECK_ID = "check_id"
    private const val K_ALERTED_AT = "alerted_at"
    private const val K_DEADLINE = "deadline"
    private const val K_REMINDERS = "reminders"
    private const val K_ATTEMPTS = "attempts"
    private const val K_ESCALATED_AT = "escalated_at"
    private const val K_NEXT_CHECK = "next_check"

    fun encode(snapshot: RuntimeSnapshot): Map<String, String> = buildMap {
        put(K_FLAG, snapshot.movement.flag.toString())
        snapshot.movement.lastMotionEpochMs?.let { put(K_LAST_MOTION, it.toString()) }
        snapshot.movement.lastCheckEpochMs?.let { put(K_LAST_CHECK, it.toString()) }
        put(K_BOOT, snapshot.movement.bootId)
        put(K_PHASE, snapshot.alert.phase.name)
        put(K_ALERT_ID, snapshot.alert.alertId.toString())
        put(K_CHECK_ID, snapshot.alert.checkId.toString())
        snapshot.alert.alertedAtEpochMs?.let { put(K_ALERTED_AT, it.toString()) }
        snapshot.alert.deadlineEpochMs?.let { put(K_DEADLINE, it.toString()) }
        put(K_REMINDERS, snapshot.alert.remindersSent.toString())
        put(K_ATTEMPTS, snapshot.alert.escalationAttempts.toString())
        snapshot.alert.escalatedAtEpochMs?.let { put(K_ESCALATED_AT, it.toString()) }
        snapshot.nextCheckAtEpochMs?.let { put(K_NEXT_CHECK, it.toString()) }
    }

    fun decode(map: Map<String, String>): RuntimeSnapshot {
        val movement = MovementState(
            flag = map[K_FLAG]?.toBooleanStrictOrNull() ?: false,
            lastMotionEpochMs = map[K_LAST_MOTION]?.toLongOrNull(),
            lastCheckEpochMs = map[K_LAST_CHECK]?.toLongOrNull(),
            bootId = map[K_BOOT].orEmpty(),
        )
        val phase = runCatching {
            AlertPhase.valueOf(map[K_PHASE] ?: AlertPhase.IDLE.name)
        }.getOrDefault(AlertPhase.IDLE)
        val alert = AlertState(
            phase = phase,
            alertId = map[K_ALERT_ID]?.toLongOrNull() ?: 0L,
            checkId = map[K_CHECK_ID]?.toLongOrNull() ?: 0L,
            alertedAtEpochMs = map[K_ALERTED_AT]?.toLongOrNull(),
            deadlineEpochMs = map[K_DEADLINE]?.toLongOrNull(),
            remindersSent = map[K_REMINDERS]?.toIntOrNull() ?: 0,
            escalationAttempts = map[K_ATTEMPTS]?.toIntOrNull() ?: 0,
            escalatedAtEpochMs = map[K_ESCALATED_AT]?.toLongOrNull(),
        )
        return RuntimeSnapshot(movement, alert, map[K_NEXT_CHECK]?.toLongOrNull())
    }
}

private val Context.runtimeDataStore: DataStore<Preferences> by preferencesDataStore(name = "runtime")

class RuntimeStore(private val context: Context) {

    private val key = stringPreferencesKey("runtime_v1")

    val snapshot: Flow<RuntimeSnapshot> = context.runtimeDataStore.data.map { prefs ->
        RuntimeCodec.decode(FlatMap.decode(prefs[key].orEmpty()))
    }

    suspend fun current(): RuntimeSnapshot = snapshot.first()

    suspend fun update(transform: (RuntimeSnapshot) -> RuntimeSnapshot): RuntimeSnapshot {
        var updated = RuntimeSnapshot()
        context.runtimeDataStore.edit { prefs ->
            val current = RuntimeCodec.decode(FlatMap.decode(prefs[key].orEmpty()))
            updated = transform(current)
            prefs[key] = FlatMap.encode(RuntimeCodec.encode(updated))
        }
        return updated
    }
}
