package com.watchover.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.watchover.data.Recipient
import com.watchover.data.Settings
import com.watchover.data.ValidationIssue
import java.time.LocalTime

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    settings: Settings,
    onSave: (Settings) -> Unit,
    onInvalid: (List<ValidationIssue>) -> Unit = {},
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    val context = LocalContext.current
    val issues = remember(draft) { draft.validate() }

    // A time the user picked that clashes with an existing one, awaiting a decision.
    var pendingTime by remember { mutableStateOf<LocalTime?>(null) }

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        SectionTitle("Check times")
        Card {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "The app checks twice a day by default. It asks whether the phone has moved since the previous check.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                draft.sortedCheckTimes().forEach { time ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            time.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { draft = draft.copy(checkTimes = draft.checkTimes - time) }) {
                            Text("Remove")
                        }
                    }
                    HorizontalDivider()
                }
                TextButton(
                    onClick = {
                        TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                val picked = LocalTime.of(hour, minute)
                                val conflict = draft.checkTimes
                                    .filter { it != picked }
                                    .minByOrNull { Settings.circularMinutes(it, picked) }
                                    ?.takeIf { Settings.circularMinutes(it, picked) < Settings.MIN_MINUTES_BETWEEN_CHECKS }
                                if (conflict != null) {
                                    pendingTime = picked
                                } else {
                                    draft = draft.copy(
                                        checkTimes = (draft.checkTimes + picked).distinct().sorted(),
                                    )
                                }
                            },
                            draft.sortedCheckTimes().firstOrNull()?.hour ?: 12,
                            0,
                            true,
                        ).show()
                    },
                ) { Text("Add a check time") }
            }
        }

        SectionTitle("Response")
        Card {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Time to respond: ${draft.responseTimeoutMinutes} min",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = draft.responseTimeoutMinutes.toFloat(),
                    onValueChange = {
                        draft = draft.copy(responseTimeoutMinutes = it.toInt().coerceIn(5, 120))
                    },
                    valueRange = 5f..120f,
                    steps = 22,
                )

                Text(
                    "Reminders before escalating: ${draft.remindersBeforeEscalation}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = draft.remindersBeforeEscalation.toFloat(),
                    onValueChange = {
                        draft = draft.copy(remindersBeforeEscalation = it.toInt().coerceIn(0, 3))
                    },
                    valueRange = 0f..3f,
                    steps = 2,
                )

                Text(
                    "Reminders are spaced evenly across the response time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = draft.sendAllClear,
                        onCheckedChange = { draft = draft.copy(sendAllClear = it) },
                    )
                    Text("Text an all-clear if I respond late", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = draft.countUnlockAsMotion,
                        onCheckedChange = { draft = draft.copy(countUnlockAsMotion = it) },
                    )
                    Text("Count waking or unlocking the phone as movement", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        SectionTitle("Your contact")
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The text comes from your own number, so it starts by saying it is automated.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft.recipient?.label ?: "",
                    onValueChange = {
                        draft = draft.copy(recipient = Recipient(it, draft.recipient?.phoneNumber ?: ""))
                    },
                    label = { Text("Name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.recipient?.phoneNumber ?: "",
                    onValueChange = {
                        draft = draft.copy(recipient = Recipient(draft.recipient?.label ?: "", it))
                    },
                    label = { Text("Phone number") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.deviceName,
                    onValueChange = { draft = draft.copy(deviceName = it) },
                    label = { Text("Name of this phone (used in the message)") },
                    singleLine = true,
                )
            }
        }

        if (issues.isNotEmpty()) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Fix before saving", color = MaterialTheme.colorScheme.error)
                    issues.forEach { Text("• ${it.message}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                // Always tappable: a dead button that silently does nothing is
                // worse than an explanation.
                onClick = {
                    if (issues.isEmpty()) onSave(draft) else onInvalid(issues)
                },
                modifier = Modifier.weight(1f),
            ) { Text("Save") }
            OutlinedButton(
                onClick = { draft = settings },
                modifier = Modifier.weight(1f),
            ) { Text("Revert") }
        }

        Spacer(Modifier.height(24.dp))
    }

    pendingTime?.let { picked ->
        val conflict = draft.checkTimes
            .filter { it != picked }
            .minByOrNull { Settings.circularMinutes(it, picked) }
        if (conflict != null) {
            AlertDialog(
                onDismissRequest = { pendingTime = null },
                title = { Text("Times are too close") },
                text = {
                    Text(
                        "$conflict and $picked are " +
                            "${Settings.circularMinutes(conflict, picked)} minutes apart, and checks " +
                            "must be at least ${Settings.MIN_MINUTES_BETWEEN_CHECKS} minutes apart. " +
                            "Replace $conflict with $picked?",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            draft = draft.copy(
                                checkTimes = (draft.checkTimes - conflict + picked).distinct().sorted(),
                            )
                            pendingTime = null
                        },
                    ) { Text("Replace") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingTime = null }) { Text("Cancel") }
                },
            )
        }
    }
}
