package com.watchover.di

import android.app.Application
import android.util.Log
import com.watchover.alert.AlertCoordinator
import com.watchover.checkin.CheckInController
import com.watchover.core.BootId
import com.watchover.core.SystemTimeProvider
import com.watchover.core.TimeProvider
import com.watchover.data.EventLogStore
import com.watchover.data.RuntimeStore
import com.watchover.data.Settings
import com.watchover.data.SettingsStore
import com.watchover.escalate.SmsEscalator
import com.watchover.schedule.AlarmScheduler
import com.watchover.schedule.ScheduleEngine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Small hand-written dependency container.
 *
 * The app is ~20 classes deep and has no generated-code requirements, so a
 * container is simpler to reason about than a DI framework, and it keeps the
 * build free of annotation processors.
 */
class AppGraph(private val app: Application) {

    /**
     * A failure inside one background job must never take down the process:
     * the app is the thing standing between the owner and an unanswered check.
     */
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            Log.e("WatchOver", "Unhandled background failure", error)
        },
    )
    val time: TimeProvider = SystemTimeProvider
    val settingsStore = SettingsStore(app)
    val runtime = RuntimeStore(app)
    val events = EventLogStore(app.filesDir, time)
    val engine = ScheduleEngine(ZoneId.systemDefault())
    val alarms = AlarmScheduler(app, engine, time)
    val sms = SmsEscalator(app, events)

    private val settingsCache = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = settingsCache.asStateFlow()

    val alert = AlertCoordinator(
        context = app,
        settingsStore = settingsStore,
        runtimeStore = runtime,
        events = events,
        alarms = alarms,
        sms = sms,
        time = time,
        scope = scope,
        bootId = ::bootId,
    )

    val controller = CheckInController(
        context = app,
        settingsStore = settingsStore,
        runtimeStore = runtime,
        events = events,
        alarms = alarms,
        alert = alert,
        time = time,
        bootId = ::bootId,
    )

    fun bootId(): String = BootId.current(app)

    /** Non-suspending view of the settings, for services that cannot wait on disk. */
    fun settingsSnapshot(): Settings = settingsCache.value

    fun retentionDays(): Int = settingsCache.value.logRetentionDays

    fun start() {
        scope.launch {
            settingsStore.settings.collect { settingsCache.value = it }
        }
        scope.launch {
            controller.onAppStart()
        }
    }
}
