package com.watchover.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.watchover.WatchOverApp
import com.watchover.data.RuntimeSnapshot
import com.watchover.data.Settings
import com.watchover.escalate.Messages
import com.watchover.ui.theme.WatchOverTheme
import com.watchover.watch.MotionFlag
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WatchOverTheme {
                val graph = remember { WatchOverApp.from(applicationContext) }
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val snackbar = remember { SnackbarHostState() }

                val settings by graph.settings.collectAsStateWithLifecycle()
                val runtime by graph.runtime.snapshot.collectAsStateWithLifecycle(initialValue = RuntimeSnapshot())
                val events by graph.events.events.collectAsStateWithLifecycle()
                var tab by remember { mutableIntStateOf(0) }

                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    scope.launch {
                        snackbar.showSnackbar(
                            if (granted) "Notifications allowed" else "Notifications were not allowed",
                        )
                    }
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                                label = { Text("Home") },
                            )
                            NavigationBarItem(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                label = { Text("Settings") },
                            )
                            NavigationBarItem(
                                selected = tab == 2,
                                onClick = { tab = 2 },
                                icon = { Icon(Icons.Filled.List, contentDescription = null) },
                                label = { Text("Log") },
                            )
                        }
                    },
                ) { padding ->
                    when (tab) {
                        0 -> HomeScreen(
                            modifier = Modifier.padding(padding),
                            settings = settings,
                            runtime = runtime,
                            onToggle = { enabled ->
                                scope.launch { graph.controller.setEnabled(enabled) }
                            },
                            onTestAlert = {
                                scope.launch {
                                    graph.alert.raise(
                                        checkId = System.currentTimeMillis(),
                                        settings = settings,
                                    )
                                    snackbar.showSnackbar("Test alert shown — acknowledge it or it will escalate")
                                }
                            },
                            onTestSms = {
                                scope.launch {
                                    val recipient = settings.recipient
                                    val ok = recipient != null && graph.sms.send(
                                        recipient.phoneNumber,
                                        Messages.test(settings.deviceLabel()),
                                    )
                                    snackbar.showSnackbar(
                                        if (ok) "Test SMS handed to the carrier" else "Could not send — check SMS permission and number",
                                    )
                                }
                            },
                            onRunCheck = {
                                scope.launch {
                                    graph.controller.onCheckDue(System.currentTimeMillis())
                                    snackbar.showSnackbar("Check evaluated — see the log")
                                }
                            },
                            onSimulateMovement = {
                                scope.launch {
                                    val now = System.currentTimeMillis()
                                    MotionFlag.markMotion(now)
                                    graph.runtime.update {
                                        it.copy(movement = it.movement.onMotion(now, graph.bootId()))
                                    }
                                    snackbar.showSnackbar("Movement recorded — the next check will pass")
                                }
                            },
                            onRequestNotificationPermission = {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            onOpenSettings = { tab = 1 },
                        )

                        1 -> SettingsScreen(
                            modifier = Modifier.padding(padding),
                            settings = settings,
                            onSave = { updated ->
                                scope.launch {
                                    graph.settingsStore.set(updated)
                                    graph.controller.refreshSchedule()
                                    snackbar.showSnackbar("Saved")
                                }
                            },
                            onInvalid = { problems ->
                                scope.launch { snackbar.showSnackbar(problems.first().message) }
                            },
                        )

                        else -> LogScreen(
                            modifier = Modifier.padding(padding),
                            events = events,
                            onClear = { scope.launch { graph.events.clear() } },
                            onOpenAppSettings = { SelfCheck.openAppSettings(context) },
                        )
                    }
                }

                // Ask once, so the first alert can actually be seen.
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val graph = WatchOverApp.from(this)
        lifecycleScope.launch { graph.controller.refreshSchedule() }
    }
}

/** Small helper shared by the screens for consistent section headers. */
@Composable
fun SectionTitle(text: String) {
    androidx.compose.material3.Text(
        text = text,
        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    )
}

/** Turns a settings object into a compact summary used on the home screen. */
fun summariseTimes(settings: Settings): String =
    settings.sortedCheckTimes().joinToString(" · ") { it.toString() }
