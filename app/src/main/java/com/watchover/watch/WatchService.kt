package com.watchover.watch

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.watchover.R
import com.watchover.WatchOverApp
import com.watchover.alert.NotificationChannels
import com.watchover.data.Event
import com.watchover.data.EventTypes
import com.watchover.ui.MainActivity
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The only long-lived component (design §4, §8). It exists solely to keep a
 * hardware motion registration alive; it runs no loop and holds no wakelock.
 *
 * Sensor preference order:
 *  1. TYPE_MOTION_DETECT      - hub-backed, no re-arming, cheapest
 *  2. TYPE_SIGNIFICANT_MOTION - one-shot, must be re-armed after each trigger
 *  3. TYPE_ACCELEROMETER      - batched software threshold, last resort
 */
class WatchService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private val graph by lazy { WatchOverApp.from(this) }

    private var source: Source = Source.NONE
    private var triggerArmed = false
    private var lastNotifiedMotionAt = 0L
    private var screenReceiverRegistered = false
    private var accelerometerHits = 0

    /**
     * Significant motion is a one-shot *trigger* sensor, and TriggerEventListener
     * is an abstract class, so it cannot be added to the service's supertype
     * list. A small delegate owns it instead.
     */
    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            recordMotion("significant-motion")
            rearmTrigger()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!graph.settingsSnapshot().countUnlockAsMotion) return
            val origin = if (intent.action == Intent.ACTION_SCREEN_ON) "screen-on" else "unlock"
            recordMotion(origin)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SensorManager::class.java)
        isRunning = true

        val settings = graph.settingsSnapshot()
        NotificationChannels.ensureCreated(this, settings.bypassDnd)
        startForeground(NotificationChannels.WATCH_NOTIFICATION_ID, buildWatchNotification())

        registerMotion()
        registerScreenReceiverIfNeeded(settings.countUnlockAsMotion)
        restoreFlagFromDisk()

        log(EventTypes.WATCH_STARTED, "source=${source.name}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Sticky: if the system kills us, it should recreate the watch.
        if (!isRegistered()) registerMotion()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterAll()
        isRunning = false
        graph.scope.launch {
            graph.events.append(Event(graph.time.nowEpochMs(), EventTypes.WATCH_STOPPED, ""), graph.retentionDays())
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- sensors

    private fun registerMotion() {
        unregisterAll()

        val motionDetect = sensorManager.getDefaultSensor(Sensor.TYPE_MOTION_DETECT)
        if (motionDetect != null) {
            sensorManager.registerListener(this, motionDetect, SensorManager.SENSOR_DELAY_NORMAL)
            source = Source.MOTION_DETECT
            return
        }

        val significant = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        if (significant != null) {
            triggerArmed = sensorManager.requestTriggerSensor(triggerListener, significant)
            source = if (triggerArmed) Source.SIGNIFICANT_MOTION else Source.NONE
            if (triggerArmed) return
        }

        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer != null) {
            // Batched in the HAL: the array is delivered in chunks, not per sample.
            sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_NORMAL,
                ACCELEROMETER_BATCH_US,
            )
            source = Source.ACCELEROMETER
        }
    }

    private fun isRegistered(): Boolean =
        source != Source.NONE && (source != Source.SIGNIFICANT_MOTION || triggerArmed)

    private fun unregisterAll() {
        runCatching { sensorManager.unregisterListener(this) }
        if (source == Source.SIGNIFICANT_MOTION) {
            val significant = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
            if (significant != null && triggerArmed) {
                runCatching { sensorManager.cancelTriggerSensor(triggerListener, significant) }
            }
            triggerArmed = false
        }
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
    }

    private fun registerScreenReceiverIfNeeded(countUnlockAsMotion: Boolean) {
        if (!countUnlockAsMotion || screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            // Lift-to-wake and tap-to-wake fire this; a phone that lights up is
            // being handled, which is the signal significant motion misses.
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiverRegistered = true
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_MOTION_DETECT -> recordMotion("motion-detect")
            Sensor.TYPE_ACCELEROMETER -> {
                val magnitude = sqrt(
                    event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2],
                )
                if (abs(magnitude - SensorManager.GRAVITY_EARTH) > ACCELEROMETER_THRESHOLD) {
                    accelerometerHits += 1
                    if (accelerometerHits >= ACCELEROMETER_CONSECUTIVE) {
                        accelerometerHits = 0
                        recordMotion("accelerometer")
                    }
                } else {
                    accelerometerHits = 0
                }
            }
        }
    }

    /** One-shot sensor: it disabled itself, so arm it again after every trigger. */
    private fun rearmTrigger() {
        val significant = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        triggerArmed = runCatching {
            sensorManager.requestTriggerSensor(triggerListener, significant)
        }.getOrDefault(false)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ----------------------------------------------------------------- state

    private fun restoreFlagFromDisk() {
        graph.scope.launch {
            val runtime = graph.runtime.current()
            MotionFlag.restore(runtime.movement.flag, runtime.movement.lastMotionEpochMs ?: 0L)
            // The notice is built before this read completes, so refresh it once
            // the real last-movement time is known.
            refreshWatchNotification()
        }
    }

    private fun recordMotion(origin: String) {
        val now = graph.time.nowEpochMs()
        val wasLatched = MotionFlag.set
        MotionFlag.markMotion(now)
        val bootId = graph.bootId()
        graph.scope.launch {
            // Persist every event, not just the first: the owner-facing "last
            // movement" time has to be the real one.
            graph.runtime.update { snapshot ->
                snapshot.copy(movement = snapshot.movement.onMotion(now, bootId))
            }
            if (!wasLatched || now - lastNotifiedMotionAt > NOTICE_REFRESH_MS) {
                lastNotifiedMotionAt = now
                refreshWatchNotification()
            }
            // Every detected movement is recorded. The log is a rolling window
            // of the most recent entries, so there is nothing to thin out here.
            graph.events.append(
                Event(now, EventTypes.MOTION, origin),
                graph.retentionDays(),
            )
        }
    }

    private fun refreshWatchNotification() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NotificationChannels.WATCH_NOTIFICATION_ID, buildWatchNotification())
        }
    }

    private fun buildWatchNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val lastMotion = MotionFlag.lastMotionAtMs
        val text = if (lastMotion > 0L) {
            getString(R.string.watch_notice_text_with_motion, formatClock(lastMotion))
        } else {
            getString(R.string.watch_notice_text)
        }

        return NotificationCompat.Builder(this, NotificationChannels.WATCH)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle(getString(R.string.watch_notice_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setContentIntent(openApp)
            .build()
    }

    private fun formatClock(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

    private fun log(type: String, detail: String) {
        graph.scope.launch {
            graph.events.append(Event(graph.time.nowEpochMs(), type, detail), graph.retentionDays())
        }
    }

    enum class Source { NONE, MOTION_DETECT, SIGNIFICANT_MOTION, ACCELEROMETER }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Last sensor used, for the self-check screen. */
        @Volatile
        var activeSource: Source = Source.NONE
            private set

        private const val ACCELEROMETER_BATCH_US = 60_000_000
        private const val ACCELEROMETER_THRESHOLD = 1.8f
        private const val ACCELEROMETER_CONSECUTIVE = 2
        private const val NOTICE_REFRESH_MS = 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WatchService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }
    }
}
