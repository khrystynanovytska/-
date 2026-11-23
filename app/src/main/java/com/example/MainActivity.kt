package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.notifications.OutageNotificationHelper
import com.example.notifications.ScheduleSyncWorker
import com.example.ui.MainScreen
import com.example.ui.ScheduleViewModel
import com.example.ui.theme.SvitloTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: ScheduleViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        viewModel.refreshIfStale()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScheduleSyncWorker.enqueuePeriodic(this)
        enableEdgeToEdge()
        setContent {
            SvitloTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }
                val coroutineScope = rememberCoroutineScope()

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    viewModel.refreshPermissionState()
                    if (granted) {
                        viewModel.setNotificationsEnabled(true)
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                getString(R.string.notify_enabled_banner)
                            )
                        }
                    } else {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                getString(R.string.notify_permission_needed)
                            )
                        }
                    }
                }

                // Request POST_NOTIFICATIONS on first launch for Android 13+ if notifications are enabled
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        uiState.notificationsEnabled &&
                        !OutageNotificationHelper.hasNotificationPermission(this@MainActivity)
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                MainScreen(
                    uiState = uiState,
                    snackbarHostState = snackbarHostState,
                    todayDateLabel = viewModel.todayDateLabel(),
                    tomorrowDateLabel = viewModel.tomorrowDateLabel(),
                    onRefresh = { viewModel.refresh(isManual = true) },
                    onSelectDay = { tomorrow -> viewModel.selectDay(tomorrow) },
                    onSelectQueue = { queueId, address ->
                        viewModel.selectQueue(queueId, address)
                    },
                    onSetManualLiveStatus = { state ->
                        viewModel.setManualLiveStatus(state)
                    },
                    onEditHourState = { date, hour, state ->
                        viewModel.setManualHourState(date, hour, state)
                    },
                    onClearManualDayEdits = { date ->
                        viewModel.clearManualDayEdits(date)
                    },
                    isHourManuallyEdited = { date, hour ->
                        viewModel.getHourManualOverride(date, hour) != null
                    },
                    onToggleNotifications = {
                        val currentlyActive =
                            uiState.notificationsEnabled && uiState.hasNotificationPermission
                        if (currentlyActive) {
                            viewModel.setNotificationsEnabled(false)
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    getString(R.string.notify_disabled_banner)
                                )
                            }
                        } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !OutageNotificationHelper.hasNotificationPermission(this@MainActivity)
                            ) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.setNotificationsEnabled(true)
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        getString(R.string.notify_enabled_banner)
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}
