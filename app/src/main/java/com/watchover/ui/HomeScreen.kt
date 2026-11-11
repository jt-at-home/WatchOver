package com.watchover.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.watchover.data.AlertPhase
import com.watchover.data.RuntimeSnapshot
import com.watchover.data.Settings
import com.watchover.watch.WatchService
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    settings: Settings,
    runtime: RuntimeSnapshot,
    onToggle: (Boolean) -> Unit,
    onTestAlert: () -> Unit,
    onTestSms: () -> Unit,
    onRunCheck: () -> Unit,
    onSimulateMovement: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val issues = remember(settings) { settings.validateForArming() }

    // Platform state is read once per resume rather than on every recomposition:
    // each check is a binder call, and the screen originally recomposed every second.
    var checks by remember {
        mutableStateOf(SelfCheck.items(context, notificationPermissionGranted = true))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        checks = SelfCheck.items(context, notificationPermissionGranted = true)
    }

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        StatusCard(settings, runtime)

        Card {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Watching", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = when {
                            !settings.enabled -> "Off — no checks are scheduled"
                            WatchService.isRunning -> "On — checks at ${summariseTimes(settings)}"
                            else -> "Starting… (if this persists, open the checklist below)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.enabled, onCheckedChange = onToggle)
            }
        }

        if (runtime.alert.phase != AlertPhase.IDLE) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = when (runtime.alert.phase) {
                            AlertPhase.AWAITING_ACK -> "Waiting for you to tap I'm here"
                            AlertPhase.ESCALATING -> "Texting your contact…"
                            AlertPhase.ESCALATED -> "Your contact has been texted"
                            else -> "Escalation could not be sent"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    runtime.alert.deadlineEpochMs?.let {
                        Text(
                            text = "Deadline ${formatClock(it)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        SectionTitle("Setup checklist")
        Card {
            Column(Modifier.padding(vertical = 4.dp)) {
                checks.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(
                                    if (item.ok) Color(0xFF2E7D32) else Color(0xFFC62828),
                                    CircleShape,
                                ),
                        )
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                item.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (item.action != null && item.actionLabel != null) {
                            TextButton(onClick = item.action) { Text(item.actionLabel) }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }

        if (issues.isNotEmpty()) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Needs attention",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    issues.forEach { issue ->
                        Text("• ${issue.message}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onOpenSettings) { Text("Open settings") }
                }
            }
        }

        SectionTitle("Test it")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onTestAlert, modifier = Modifier.weight(1f)) {
                Text("Test alert")
            }
            OutlinedButton(onClick = onTestSms, modifier = Modifier.weight(1f)) {
                Text("Test SMS")
            }
        }
        Text(
            "“Test alert” raises a real alert: if you leave it unacknowledged, it will escalate to your contact.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onRunCheck, modifier = Modifier.weight(1f)) {
                Text("Run check now")
            }
            OutlinedButton(onClick = onSimulateMovement, modifier = Modifier.weight(1f)) {
                Text("Pretend it moved")
            }
        }
        Text(
            "“Run check now” evaluates immediately, exactly as a scheduled check would. " +
                "“Pretend it moved” latches the movement flag so the next check passes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionTitle("About the silent notice")
        Text(
            "While watching, a silent entry stays in the notification shade. " +
                "Android does not allow a background service without one. Hiding it does not stop monitoring, " +
                "and your alerts are on a separate channel.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusCard(settings: Settings, runtime: RuntimeSnapshot) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val next = runtime.nextCheckAtEpochMs
    Card {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = if (settings.enabled) "Armed" else "Not armed",
                style = MaterialTheme.typography.headlineMedium,
                color = if (settings.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    !settings.enabled -> "Turn it on to schedule checks."
                    next == null -> "No check scheduled yet."
                    next > nowMs -> "Next check ${formatClock(next)} (in ${remaining(next - nowMs)})"
                    else -> "A check was due at ${formatClock(next)}."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(4.dp))
            val lastMotion = runtime.movement.lastMotionEpochMs
            Text(
                text = when {
                    lastMotion == null -> "No movement recorded yet."
                    runtime.movement.flag ->
                        "Movement at ${formatClock(lastMotion)} (${ago(lastMotion, nowMs)}) — " +
                            "the next check will pass."
                    else -> "Last movement ${formatClock(lastMotion)} (${ago(lastMotion, nowMs)})."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            runtime.movement.lastCheckEpochMs?.let { lastCheck ->
                Text(
                    text = "Last check ${formatClock(lastCheck)} (${ago(lastCheck, nowMs)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "just now", "4 min ago", "3 h ago" — coarse is fine for a status line. */
private fun ago(epochMs: Long, nowMs: Long): String {
    val seconds = ((nowMs - epochMs) / 1000).coerceAtLeast(0)
    return when {
        seconds < 45 -> "just now"
        seconds < 90 -> "1 min ago"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 7200 -> "1 h ago"
        else -> "${seconds / 3600} h ago"
    }
}

private fun formatClock(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))

private fun remaining(millis: Long): String {
    if (millis <= 0) return "now"
    val totalMinutes = millis / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}
