package com.example.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.ScheduleRepository
import com.example.model.DaySchedule
import com.example.model.KyivDate
import com.example.model.PowerStatusSnapshot
import com.example.model.SavedQueueProfile
import com.example.model.SlotState
import com.example.model.TimeRange
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.AmberWarningSoft
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.PowerMaybeAmberEnd
import com.example.ui.theme.PowerMaybeAmberSoftDark
import com.example.ui.theme.PowerMaybeAmberStart
import com.example.ui.theme.PowerOffRedBright
import com.example.ui.theme.PowerOffRedEnd
import com.example.ui.theme.PowerOffRedSoftDark
import com.example.ui.theme.PowerOffRedSoftLight
import com.example.ui.theme.PowerOffRedStart
import com.example.ui.theme.PowerOnGreenBright
import com.example.ui.theme.PowerOnGreenEnd
import com.example.ui.theme.PowerOnGreenSoftDark
import com.example.ui.theme.PowerOnGreenSoftLight
import com.example.ui.theme.PowerOnGreenStart
import com.example.ui.theme.PowerSplitYellow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: ScheduleUiState,
    snackbarHostState: SnackbarHostState,
    todayDateLabel: String,
    tomorrowDateLabel: String,
    onRefresh: () -> Unit,
    onSelectDay: (Boolean) -> Unit,
    onToggleNotifications: () -> Unit,
    onSelectQueue: (String, String) -> Unit = { _, _ -> },
    onSwitchProfile: (String) -> Unit = {},
    onUpsertProfile: (String?, String, String) -> Unit = { _, _, _ -> },
    onDeleteProfile: (String) -> Unit = {},
    onSetManualLiveStatus: (SlotState?) -> Unit = {},
    onEditHourState: (KyivDate, Int, SlotState?) -> Unit = { _, _, _ -> },
    onEditHourSlots: (KyivDate, Int, SlotState, SlotState) -> Unit = { _, _, _, _ -> },
    onEditSlotState: (KyivDate, Int, SlotState?) -> Unit = { _, _, _ -> },
    onClearManualDayEdits: (KyivDate) -> Unit = {},
    isHourManuallyEdited: (KyivDate, Int) -> Boolean = { _, _ -> false }
) {
    var showQueueSheet by remember { mutableStateOf(false) }
    var queueSheetStartAddMode by remember { mutableStateOf(false) }
    var editingHourTarget by remember { mutableStateOf<Pair<KyivDate, Int>?>(null) }
    val displayedProfiles = remember(uiState.savedProfiles, uiState.activeProfileId, uiState.selectedQueue, uiState.addressLabel) {
        uiState.savedProfiles.ifEmpty {
            listOf(SavedQueueProfile(uiState.activeProfileId, uiState.selectedQueue, uiState.addressLabel))
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { _ ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HeaderBar(
                            selectedQueue = uiState.selectedQueue,
                            addressLabel = uiState.addressLabel,
                            notificationsActive = uiState.notificationsEnabled && uiState.hasNotificationPermission,
                            isRefreshing = uiState.isRefreshing || uiState.isLoading,
                            onOpenQueueSelector = {
                                queueSheetStartAddMode = false
                                showQueueSheet = true
                            },
                            onToggleNotifications = onToggleNotifications,
                            onRefresh = onRefresh
                        )

                        SavedQueuesStrip(
                            profiles = displayedProfiles,
                            activeProfileId = uiState.activeProfileId,
                            onSelectProfile = onSwitchProfile,
                            onAddNewPlace = {
                                queueSheetStartAddMode = true
                                showQueueSheet = true
                            }
                        )
                    }

                    when {
                        uiState.isLoading && uiState.snapshot == null -> {
                            LoadingStateCard()
                        }

                        uiState.errorMessage != null && uiState.snapshot == null -> {
                            ErrorStateCard(
                                message = uiState.errorMessage,
                                onRetry = onRefresh
                            )
                        }

                        uiState.snapshot != null && uiState.dataset != null -> {
                            val snapshot = uiState.snapshot
                            val isViewingTomorrow = uiState.selectedTomorrow && snapshot.tomorrowSchedule != null
                            val activeDaySchedule = if (isViewingTomorrow) {
                                snapshot.tomorrowSchedule
                            } else {
                                snapshot.todaySchedule
                            }
                            val activeOnRanges = if (isViewingTomorrow) {
                                snapshot.tomorrowOnRanges
                            } else {
                                snapshot.todayOnRanges
                            }
                            val activeMaybeRanges = if (isViewingTomorrow) {
                                snapshot.tomorrowMaybeRanges
                            } else {
                                snapshot.todayMaybeRanges
                            }
                            val activeOffRanges = if (isViewingTomorrow) {
                                snapshot.tomorrowOffRanges
                            } else {
                                snapshot.todayOffRanges
                            }

                            HeroStatusCard(
                                snapshot = snapshot,
                                onSetManualLiveStatus = onSetManualLiveStatus
                            )

                            DailyScheduleCard(
                                daySchedule = activeDaySchedule,
                                hasTomorrow = snapshot.tomorrowSchedule != null,
                                isViewingTomorrow = isViewingTomorrow,
                                todayDateLabel = todayDateLabel,
                                tomorrowDateLabel = tomorrowDateLabel,
                                currentMinuteOfDay = snapshot.currentMinuteOfDay,
                                onSelectDay = onSelectDay,
                                onHourClick = { hour ->
                                    activeDaySchedule?.date?.let { date ->
                                        editingHourTarget = date to hour
                                    }
                                },
                                onResetManualEdits = {
                                    activeDaySchedule?.date?.let(onClearManualDayEdits)
                                }
                            )

                            TextIntervalsCard(
                                isViewingTomorrow = isViewingTomorrow,
                                hasDayData = activeDaySchedule != null,
                                onRanges = activeOnRanges,
                                maybeRanges = activeMaybeRanges,
                                offRanges = activeOffRanges
                            )

                            SourceFooter(
                                updatedLabel = uiState.dataset.lastUpdatedLabel,
                                fetchedAtMillis = uiState.dataset.fetchedAtMillis,
                                isFromCache = uiState.dataset.isFromCache
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }

    if (showQueueSheet) {
        QueueSelectorBottomSheet(
            savedProfiles = displayedProfiles,
            activeProfileId = uiState.activeProfileId,
            startInAddMode = queueSheetStartAddMode,
            availableQueues = uiState.availableQueues,
            onDismiss = { showQueueSheet = false },
            onSwitchProfile = onSwitchProfile,
            onSaveProfile = { profileId, queueId, label ->
                showQueueSheet = false
                if (uiState.savedProfiles.isEmpty() && profileId != null) {
                    onSelectQueue(queueId, label)
                } else {
                    onUpsertProfile(profileId, queueId, label)
                }
            },
            onDeleteProfile = onDeleteProfile
        )
    }

    editingHourTarget?.let { (date, hour) ->
        val daySlots = uiState.dataset?.days?.get(date)?.slots
        val firstHalfState = daySlots?.getOrElse(hour * 2) { SlotState.ON } ?: SlotState.ON
        val secondHalfState = daySlots?.getOrElse(hour * 2 + 1) { SlotState.ON } ?: SlotState.ON
        EditHourDialog(
            hour = hour,
            firstHalfState = firstHalfState,
            secondHalfState = secondHalfState,
            hasManualOverride = isHourManuallyEdited(date, hour),
            onDismiss = { editingHourTarget = null },
            onSelectWholeHour = { chosenState ->
                editingHourTarget = null
                onEditHourState(date, hour, chosenState)
            },
            onSelectSplitHour = { first, second ->
                editingHourTarget = null
                onEditHourSlots(date, hour, first, second)
            },
            onSelectHalfSlot = { slotOffset, chosenState ->
                onEditSlotState(date, hour * 2 + slotOffset, chosenState)
            }
        )
    }
}

@Composable
private fun HeaderBar(
    selectedQueue: String,
    addressLabel: String,
    notificationsActive: Boolean,
    isRefreshing: Boolean,
    onOpenQueueSelector: () -> Unit,
    onToggleNotifications: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onOpenQueueSelector)
                .padding(vertical = 4.dp, horizontal = 2.dp)
                .testTag("queue_selector_button")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.queue_format, selectedQueue),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.testTag("queue_title_text")
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.select_queue_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
            if (addressLabel.isNotBlank()) {
                Text(
                    text = addressLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onToggleNotifications,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("notifications_toggle_button")
            ) {
                Icon(
                    imageVector = if (notificationsActive) {
                        Icons.Rounded.NotificationsActive
                    } else {
                        Icons.Outlined.NotificationsOff
                    },
                    contentDescription = if (notificationsActive) {
                        stringResource(R.string.notifications_on_cd)
                    } else {
                        stringResource(R.string.notifications_off_cd)
                    },
                    tint = if (notificationsActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            IconButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("refresh_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.refresh_cd),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroStatusCard(
    snapshot: PowerStatusSnapshot,
    onSetManualLiveStatus: (SlotState?) -> Unit
) {
    val targetStart = when (snapshot.effectiveState) {
        SlotState.ON -> PowerOnGreenStart
        SlotState.MAYBE -> PowerMaybeAmberStart
        SlotState.OFF -> PowerOffRedStart
    }
    val targetEnd = when (snapshot.effectiveState) {
        SlotState.ON -> PowerOnGreenEnd
        SlotState.MAYBE -> PowerMaybeAmberEnd
        SlotState.OFF -> PowerOffRedEnd
    }

    val startColor by animateColorAsState(
        targetValue = targetStart,
        animationSpec = tween(durationMillis = 350),
        label = "heroStart"
    )
    val endColor by animateColorAsState(
        targetValue = targetEnd,
        animationSpec = tween(durationMillis = 350),
        label = "heroEnd"
    )

    val hasTodaySchedule = snapshot.todaySchedule != null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hero_status_card"),
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 6.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(startColor, endColor)
                    )
                )
                .padding(22.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = Color.White.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                            Text(
                                text = if (snapshot.isManualOverride) {
                                    stringResource(R.string.manual_badge)
                                } else {
                                    "Зараз"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                        }
                    }

                    Icon(
                        imageVector = when (snapshot.effectiveState) {
                            SlotState.ON -> Icons.Rounded.Bolt
                            SlotState.MAYBE -> Icons.Rounded.WarningAmber
                            SlotState.OFF -> Icons.Rounded.FlashOff
                        },
                        contentDescription = when (snapshot.effectiveState) {
                            SlotState.ON -> stringResource(R.string.status_power_on)
                            SlotState.MAYBE -> stringResource(R.string.status_maybe_off)
                            SlotState.OFF -> stringResource(R.string.status_power_off)
                        },
                        tint = Color.White.copy(alpha = 0.92f),
                        modifier = Modifier.size(30.dp)
                    )
                }

                Text(
                    text = when (snapshot.effectiveState) {
                        SlotState.ON -> stringResource(R.string.status_power_on)
                        SlotState.MAYBE -> stringResource(R.string.status_maybe_off)
                        SlotState.OFF -> stringResource(R.string.status_power_off)
                    },
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = if (snapshot.effectiveState == SlotState.MAYBE) 26.sp else 34.sp,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    color = Color.White,
                    modifier = Modifier.testTag("power_status_headline")
                )

                if (!hasTodaySchedule) {
                    Text(
                        text = "Дані графіка на сьогодні ще оновлюються на сайті",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (snapshot.remainingSeconds >= 0L) {
                            Text(
                                text = when (snapshot.effectiveState) {
                                    SlotState.ON -> stringResource(R.string.timer_power_on_prefix)
                                    SlotState.MAYBE -> "Період можливих відключень ще:"
                                    SlotState.OFF -> stringResource(R.string.timer_power_off_prefix)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.88f)
                            )

                            Text(
                                text = formatCountdownHMS(snapshot.remainingSeconds),
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 40.sp,
                                    lineHeight = 44.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color.White,
                                modifier = Modifier.testTag("countdown_timer_text")
                            )

                            if (snapshot.nextChangeTimeLabel != null) {
                                Text(
                                    text = when (snapshot.nextChangeTargetState) {
                                        SlotState.ON -> stringResource(R.string.next_on_at, snapshot.nextChangeTimeLabel)
                                        SlotState.MAYBE -> if (snapshot.effectiveState == SlotState.ON) {
                                            stringResource(R.string.next_maybe_at, snapshot.nextChangeTimeLabel)
                                        } else {
                                            stringResource(R.string.next_maybe_from, snapshot.nextChangeTimeLabel)
                                        }
                                        SlotState.OFF, null -> stringResource(R.string.next_off_at, snapshot.nextChangeTimeLabel)
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White.copy(alpha = 0.92f)
                                )
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            LinearProgressIndicator(
                                progress = { snapshot.blockProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(50)),
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.25f)
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.no_outages_today),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White.copy(alpha = 0.95f),
                                modifier = Modifier.testTag("no_outages_text")
                            )
                        }
                    }

                    // Daily totals summary pill
                    Surface(
                        color = Color.Black.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.daily_with_power),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.78f)
                                )
                                Text(
                                    text = formatDurationUa(snapshot.todayOnMinutes),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color.White
                                )
                            }

                            if (snapshot.todayMaybeMinutes > 0) {
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(28.dp)
                                        .background(Color.White.copy(alpha = 0.22f))
                                )
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = stringResource(R.string.daily_maybe_off),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.78f)
                                    )
                                    Text(
                                        text = formatDurationUa(snapshot.todayMaybeMinutes),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = Color.White
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(28.dp)
                                    .background(Color.White.copy(alpha = 0.22f))
                            )

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = stringResource(R.string.daily_without_power),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.78f)
                                )
                                Text(
                                    text = formatDurationUa(snapshot.todayOffMinutes),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color.White
                                )
                            }
                        }
                    }

                    // Quick manual live input (whether light is actually on/off right now)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.manual_status_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ManualStatusChip(
                                label = stringResource(R.string.manual_btn_on),
                                selected = snapshot.isManualOverride && snapshot.effectiveState == SlotState.ON,
                                testTag = "manual_on_button",
                                onClick = { onSetManualLiveStatus(SlotState.ON) }
                            )
                            ManualStatusChip(
                                label = stringResource(R.string.manual_btn_off),
                                selected = snapshot.isManualOverride && snapshot.effectiveState == SlotState.OFF,
                                testTag = "manual_off_button",
                                onClick = { onSetManualLiveStatus(SlotState.OFF) }
                            )
                            if (snapshot.isManualOverride) {
                                ManualStatusChip(
                                    label = stringResource(R.string.manual_btn_auto),
                                    selected = false,
                                    testTag = "manual_reset_button",
                                    onClick = { onSetManualLiveStatus(null) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualStatusChip(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) Color.White else Color.White.copy(alpha = 0.18f),
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color(0xFF0F172A) else Color.White,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DailyScheduleCard(
    daySchedule: DaySchedule?,
    hasTomorrow: Boolean,
    isViewingTomorrow: Boolean,
    todayDateLabel: String,
    tomorrowDateLabel: String,
    currentMinuteOfDay: Int,
    onSelectDay: (Boolean) -> Unit,
    onHourClick: (Int) -> Unit,
    onResetManualEdits: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("daily_schedule_card"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.schedule_24h_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                if (hasTomorrow) {
                    DaySwitchPill(
                        isViewingTomorrow = isViewingTomorrow,
                        todayLabel = "${stringResource(R.string.tab_today)}, $todayDateLabel",
                        tomorrowLabel = "${stringResource(R.string.tab_tomorrow)}, $tomorrowDateLabel",
                        onSelectDay = onSelectDay
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = todayDateLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            if (daySchedule == null) {
                Text(
                    text = "Графік для цієї дати ще не опубліковано.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Timeline24hBar(
                    slots = daySchedule.slots,
                    showNowMarker = !isViewingTomorrow,
                    currentMinuteOfDay = currentMinuteOfDay
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.tap_hour_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (daySchedule.hasManualEdits) {
                        Text(
                            text = stringResource(R.string.reset_manual_edits),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onResetManualEdits)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("reset_manual_day_button")
                        )
                    }
                }

                HourlyMatrixGrid(
                    slots = daySchedule.slots,
                    currentHour = if (!isViewingTomorrow) (currentMinuteOfDay / 60).coerceIn(0, 23) else -1,
                    onHourClick = onHourClick
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LegendDot(
                        color = PowerOnGreenBright,
                        label = stringResource(R.string.legend_on)
                    )
                    LegendDot(
                        color = AmberWarning,
                        label = stringResource(R.string.legend_maybe)
                    )
                    LegendDot(
                        color = PowerOffRedBright,
                        label = stringResource(R.string.legend_off)
                    )
                    SplitLegendDot(
                        leftColor = PowerOffRedBright,
                        rightColor = PowerSplitYellow,
                        label = stringResource(R.string.legend_split_off_maybe)
                    )
                    if (!isViewingTomorrow) {
                        LegendDot(
                            color = MaterialTheme.colorScheme.onSurface,
                            label = stringResource(R.string.legend_now)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DaySwitchPill(
    isViewingTomorrow: Boolean,
    todayLabel: String,
    tomorrowLabel: String,
    onSelectDay: (Boolean) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50)
    ) {
        Row(modifier = Modifier.padding(3.dp)) {
            listOf(false to todayLabel, true to tomorrowLabel).forEach { (isTom, _) ->
                val selected = isViewingTomorrow == isTom
                Surface(
                    color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    shape = RoundedCornerShape(50),
                    shadowElevation = if (selected) 1.dp else 0.dp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onSelectDay(isTom) }
                        .testTag(if (isTom) "tab_tomorrow" else "tab_today")
                ) {
                    Text(
                        text = if (isTom) stringResource(R.string.tab_tomorrow) else stringResource(R.string.tab_today),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun Timeline24hBar(
    slots: List<SlotState>,
    showNowMarker: Boolean,
    currentMinuteOfDay: Int
) {
    val markerColor = MaterialTheme.colorScheme.onBackground
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
        ) {
            val totalWidth = size.width
            val barHeight = size.height - 6.dp.toPx()
            val topY = 3.dp.toPx()
            val hourWidth = totalWidth / 24f
            val hourGap = 1.5.dp.toPx()
            val radius = CornerRadius(10.dp.toPx(), 10.dp.toPx())

            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, topY),
                size = Size(totalWidth, barHeight),
                cornerRadius = radius
            )

            val barClip = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = topY,
                        right = totalWidth,
                        bottom = topY + barHeight,
                        cornerRadius = radius
                    )
                )
            }

            fun colorFor(state: SlotState, isOffMaybePair: Boolean) = when (state) {
                SlotState.ON -> PowerOnGreenBright
                SlotState.MAYBE -> if (isOffMaybePair) PowerSplitYellow else AmberWarning
                SlotState.OFF -> PowerOffRedBright
            }

            clipPath(barClip) {
                for (h in 0 until 24) {
                    val x = h * hourWidth
                    val blockWidth = (hourWidth - if (h < 23) hourGap else 0f).coerceAtLeast(2f)
                    val halfWidth = blockWidth / 2f
                    val firstHalf = slots.getOrElse(h * 2) { SlotState.ON }
                    val secondHalf = slots.getOrElse(h * 2 + 1) { SlotState.ON }
                    val isOffMaybePair = (firstHalf == SlotState.OFF && secondHalf == SlotState.MAYBE) ||
                        (firstHalf == SlotState.MAYBE && secondHalf == SlotState.OFF)

                    drawRect(
                        color = colorFor(firstHalf, isOffMaybePair),
                        topLeft = Offset(x, topY),
                        size = Size(halfWidth, barHeight)
                    )
                    drawRect(
                        color = colorFor(secondHalf, isOffMaybePair),
                        topLeft = Offset(x + halfWidth, topY),
                        size = Size(blockWidth - halfWidth, barHeight)
                    )
                    if (firstHalf != secondHalf) {
                        drawLine(
                            color = Color.Black.copy(alpha = 0.28f),
                            start = Offset(x + halfWidth, topY),
                            end = Offset(x + halfWidth, topY + barHeight),
                            strokeWidth = 0.8.dp.toPx()
                        )
                    }
                }
            }

            if (showNowMarker) {
                val fraction = (currentMinuteOfDay.coerceIn(0, 1440) / 1440f)
                val markerX = (fraction * totalWidth).coerceIn(2.dp.toPx(), totalWidth - 2.dp.toPx())
                drawLine(
                    color = markerColor,
                    start = Offset(markerX, 0f),
                    end = Offset(markerX, size.height),
                    strokeWidth = 2.5.dp.toPx()
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("00", "03", "06", "09", "12", "15", "18", "21", "24").forEach { hr ->
                Text(
                    text = hr,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = JetBrainsMonoFontFamily
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HourlyMatrixGrid(
    slots: List<SlotState>,
    currentHour: Int,
    onHourClick: (Int) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val onBg = if (isDark) PowerOnGreenSoftDark else PowerOnGreenSoftLight
    val maybeBg = if (isDark) PowerMaybeAmberSoftDark else AmberWarningSoft
    val offBg = if (isDark) PowerOffRedSoftDark else PowerOffRedSoftLight

    val onText = if (isDark) Color(0xFFDCFCE7) else Color(0xFF14532D)
    val maybeText = if (isDark) Color(0xFFFEF3C7) else Color(0xFF78350F)
    val offText = if (isDark) Color(0xFFFEE2E2) else Color(0xFF7F1D1D)
    val currentBorderColor = MaterialTheme.colorScheme.onBackground

    fun bgFor(state: SlotState) = when (state) {
        SlotState.ON -> onBg
        SlotState.MAYBE -> maybeBg
        SlotState.OFF -> offBg
    }

    fun splitBgFor(state: SlotState) = when (state) {
        SlotState.ON -> PowerOnGreenBright
        SlotState.MAYBE -> PowerSplitYellow
        SlotState.OFF -> PowerOffRedBright
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (rowIdx in 0 until 6) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (colIdx in 0 until 4) {
                    val hour = rowIdx * 4 + colIdx
                    val firstHalf = slots.getOrElse(hour * 2) { SlotState.ON }
                    val secondHalf = slots.getOrElse(hour * 2 + 1) { SlotState.ON }
                    val isCurrent = hour == currentHour

                    val bothSame = firstHalf == secondHalf
                    val labelColor = if (bothSame) {
                        when (firstHalf) {
                            SlotState.ON -> onText
                            SlotState.MAYBE -> maybeText
                            SlotState.OFF -> offText
                        }
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onHourClick(hour) }
                            .then(
                                if (isCurrent) {
                                    Modifier.border(
                                        width = 2.dp,
                                        color = currentBorderColor,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .testTag("hour_cell_$hour"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .background(if (bothSame) bgFor(firstHalf) else splitBgFor(firstHalf))
                            )
                            if (!bothSame) {
                                Box(
                                    modifier = Modifier
                                        .width(1.5.dp)
                                        .fillMaxHeight()
                                        .background(Color.Black.copy(alpha = 0.25f))
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .background(if (bothSame) bgFor(secondHalf) else splitBgFor(secondHalf))
                            )
                        }

                        if (bothSame) {
                            Text(
                                text = String.format("%02d:00", hour),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = labelColor
                            )
                        } else {
                            Surface(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                shape = RoundedCornerShape(6.dp),
                                shadowElevation = 1.dp
                            ) {
                                Text(
                                    text = String.format("%02d:00", hour),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = labelColor,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SplitLegendDot(leftColor: Color, rightColor: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SplitCircleDot(leftColor = leftColor, rightColor = rightColor, sizeDp = 11)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SplitCircleDot(leftColor: Color, rightColor: Color, sizeDp: Int) {
    Row(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(leftColor)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(rightColor)
        )
    }
}

@Composable
private fun TextIntervalsCard(
    isViewingTomorrow: Boolean,
    hasDayData: Boolean,
    onRanges: List<TimeRange>,
    maybeRanges: List<TimeRange>,
    offRanges: List<TimeRange>
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("text_intervals_card"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            IntervalSection(
                dotColor = PowerOnGreenBright,
                title = if (isViewingTomorrow) {
                    stringResource(R.string.text_power_on_tomorrow)
                } else {
                    stringResource(R.string.text_power_on_today)
                },
                rangesText = when {
                    !hasDayData -> "—"
                    onRanges.isEmpty() -> stringResource(R.string.text_none)
                    onRanges.size == 1 && onRanges[0].startMinute == 0 && onRanges[0].endMinute == 1440 ->
                        stringResource(R.string.text_all_day)
                    else -> onRanges.joinToString(", ") { it.format() }
                },
                testTag = "power_on_intervals_text"
            )

            if (hasDayData && maybeRanges.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                IntervalSection(
                    dotColor = AmberWarning,
                    title = if (isViewingTomorrow) {
                        stringResource(R.string.text_power_maybe_tomorrow)
                    } else {
                        stringResource(R.string.text_power_maybe_today)
                    },
                    rangesText = maybeRanges.joinToString(", ") { it.format() },
                    testTag = "power_maybe_intervals_text"
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            IntervalSection(
                dotColor = PowerOffRedBright,
                title = if (isViewingTomorrow) {
                    stringResource(R.string.text_power_off_tomorrow)
                } else {
                    stringResource(R.string.text_power_off_today)
                },
                rangesText = when {
                    !hasDayData -> "—"
                    offRanges.isEmpty() -> stringResource(R.string.text_none)
                    else -> offRanges.joinToString(", ") { it.format() }
                },
                testTag = "power_off_intervals_text"
            )
        }
    }
}

@Composable
private fun IntervalSection(
    dotColor: Color,
    title: String,
    rangesText: String,
    testTag: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Text(
            text = rangesText,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 18.dp)
                .testTag(testTag)
        )
    }
}

@Composable
private fun SourceFooter(
    updatedLabel: String?,
    fetchedAtMillis: Long,
    isFromCache: Boolean
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Rounded.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            val checkedAt = if (fetchedAtMillis > 0L) {
                com.example.data.KyivTime.formatHHmm(fetchedAtMillis)
            } else null
            val cacheSuffix = if (isFromCache) " (кеш)" else ""
            val footerInfo = buildString {
                if (!updatedLabel.isNullOrBlank()) {
                    append("Графік: $updatedLabel")
                }
                if (checkedAt != null) {
                    if (isNotEmpty()) append(" · ")
                    append("Перевірено о $checkedAt")
                }
                append(cacheSuffix)
            }
            Text(
                text = footerInfo,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(ScheduleRepository.OFFICIAL_PORTAL_URL)
                    )
                    context.startActivity(intent)
                }
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .testTag("source_portal_link"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.source_label),
                style = MaterialTheme.typography.labelSmall.copy(
                    textDecoration = TextDecoration.Underline
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = stringResource(R.string.source_label),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun EditHourDialog(
    hour: Int,
    firstHalfState: SlotState,
    secondHalfState: SlotState,
    hasManualOverride: Boolean,
    onDismiss: () -> Unit,
    onSelectWholeHour: (SlotState?) -> Unit,
    onSelectSplitHour: (SlotState, SlotState) -> Unit,
    onSelectHalfSlot: (Int, SlotState) -> Unit
) {
    val rangeTitle = String.format("%02d:00 – %02d:00", hour, hour + 1)
    val firstRangeLabel = String.format("%02d:00 – %02d:30", hour, hour)
    val secondRangeLabel = String.format("%02d:30 – %02d:00", hour, hour + 1)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.edit_hour_title, rangeTitle),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.edit_hour_whole_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        Triple(SlotState.ON, PowerOnGreenBright, stringResource(R.string.chip_on_short) to "edit_hour_on"),
                        Triple(SlotState.MAYBE, AmberWarning, stringResource(R.string.chip_maybe_short) to "edit_hour_maybe"),
                        Triple(SlotState.OFF, PowerOffRedBright, stringResource(R.string.chip_off_short) to "edit_hour_off")
                    ).forEach { (slotState, dotColor, labelAndTag) ->
                        val (label, tag) = labelAndTag
                        val isSelected = firstHalfState == slotState && secondHalfState == slotState
                        Surface(
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = RoundedCornerShape(12.dp),
                            border = if (isSelected) {
                                BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                null
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectWholeHour(slotState) }
                                .testTag(tag)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.edit_hour_split_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        Triple(
                            SlotState.OFF to SlotState.MAYBE,
                            PowerOffRedBright to PowerSplitYellow,
                            stringResource(R.string.split_off_maybe) to "edit_hour_off_maybe"
                        ),
                        Triple(
                            SlotState.MAYBE to SlotState.OFF,
                            PowerSplitYellow to PowerOffRedBright,
                            stringResource(R.string.split_maybe_off) to "edit_hour_maybe_off"
                        )
                    ).forEach { (pair, colors, labelAndTag) ->
                        val (firstTarget, secondTarget) = pair
                        val (leftColor, rightColor) = colors
                        val (label, tag) = labelAndTag
                        val isSelected = firstHalfState == firstTarget && secondHalfState == secondTarget
                        Surface(
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = RoundedCornerShape(12.dp),
                            border = if (isSelected) {
                                BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                null
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectSplitHour(firstTarget, secondTarget) }
                                .testTag(tag)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                SplitCircleDot(
                                    leftColor = leftColor,
                                    rightColor = rightColor,
                                    sizeDp = 12
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 2.dp)
                )

                Text(
                    text = stringResource(R.string.edit_hour_half_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HalfSlotSelectorRow(
                    timeRangeLabel = firstRangeLabel,
                    selectedState = firstHalfState,
                    prefixTag = "slot_first",
                    onSelect = { onSelectHalfSlot(0, it) }
                )

                HalfSlotSelectorRow(
                    timeRangeLabel = secondRangeLabel,
                    selectedState = secondHalfState,
                    prefixTag = "slot_second",
                    onSelect = { onSelectHalfSlot(1, it) }
                )

                if (hasManualOverride) {
                    OutlinedButton(
                        onClick = { onSelectWholeHour(null) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("edit_hour_reset")
                    ) {
                        Text(stringResource(R.string.option_reset_hour))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("edit_hour_done")
            ) {
                Text("Готово")
            }
        }
    )
}

@Composable
private fun HalfSlotSelectorRow(
    timeRangeLabel: String,
    selectedState: SlotState,
    prefixTag: String,
    onSelect: (SlotState) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = timeRangeLabel,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                Triple(SlotState.ON, PowerOnGreenBright, stringResource(R.string.chip_on_short) to "${prefixTag}_on"),
                Triple(SlotState.MAYBE, AmberWarning, stringResource(R.string.chip_maybe_short) to "${prefixTag}_maybe"),
                Triple(SlotState.OFF, PowerOffRedBright, stringResource(R.string.chip_off_short) to "${prefixTag}_off")
            ).forEach { (slotState, dotColor, labelAndTag) ->
                val (label, tag) = labelAndTag
                val isSelected = selectedState == slotState
                Surface(
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(12.dp),
                    border = if (isSelected) {
                        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(slotState) }
                        .testTag(tag)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingStateCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.loading_schedule),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorStateCard(
    message: String,
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.FlashOff,
                contentDescription = null,
                tint = PowerOffRedBright,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = stringResource(R.string.error_loading),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("retry_button")
            ) {
                Text(text = stringResource(R.string.retry))
            }
        }
    }
}

fun formatCountdownHMS(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0L)
    val hours = safe / 3600L
    val minutes = (safe % 3600L) / 60L
    val seconds = safe % 60L
    return String.format("%02d:%02d:%02d", hours, minutes, seconds)
}

fun formatDurationUa(totalMinutes: Int): String {
    val safe = totalMinutes.coerceAtLeast(0)
    val h = safe / 60
    val m = safe % 60
    return when {
        h > 0 && m > 0 -> "$h год $m хв"
        h > 0 -> "$h год"
        else -> "$m хв"
    }
}

@Composable
private fun SavedQueuesStrip(
    profiles: List<SavedQueueProfile>,
    activeProfileId: String,
    onSelectProfile: (String) -> Unit,
    onAddNewPlace: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag("saved_queues_strip"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        profiles.forEach { profile ->
            val isSelected = profile.id == activeProfileId
            Surface(
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shape = RoundedCornerShape(50),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                ),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(50))
                    .clickable { onSelectProfile(profile.id) }
                    .testTag("saved_profile_chip_${profile.id}")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (profile.label.isNotBlank()) {
                        Text(
                            text = profile.label,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 150.dp)
                        )
                        Text(
                            text = "· ${profile.queueId}",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.queue_format, profile.queueId),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onAddNewPlace)
                .testTag("add_queue_profile_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.add_place_chip),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun QueueSelectorBottomSheet(
    savedProfiles: List<SavedQueueProfile>,
    activeProfileId: String,
    startInAddMode: Boolean,
    availableQueues: List<String>,
    onDismiss: () -> Unit,
    onSwitchProfile: (String) -> Unit,
    onSaveProfile: (String?, String, String) -> Unit,
    onDeleteProfile: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    var editingProfileId by remember(startInAddMode, activeProfileId) {
        mutableStateOf(if (startInAddMode) null else activeProfileId)
    }
    val targetProfile = savedProfiles.firstOrNull { it.id == editingProfileId }
    var pickedQueue by remember(editingProfileId) {
        mutableStateOf(
            targetProfile?.queueId ?: savedProfiles.firstOrNull { it.id == activeProfileId }?.queueId ?: "3.1"
        )
    }
    var editedAddress by remember(editingProfileId) {
        mutableStateOf(targetProfile?.label ?: "")
    }

    val labelPresets = listOf(
        stringResource(R.string.preset_home),
        stringResource(R.string.preset_work),
        stringResource(R.string.preset_parents),
        stringResource(R.string.preset_dacha)
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.saved_queues_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                savedProfiles.forEach { profile ->
                    val isActive = profile.id == activeProfileId
                    val isBeingEdited = profile.id == editingProfileId
                    Surface(
                        color = if (isBeingEdited) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(14.dp),
                        border = if (isBeingEdited || isActive) {
                            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                editingProfileId = profile.id
                                pickedQueue = profile.queueId
                                editedAddress = profile.label
                                onSwitchProfile(profile.id)
                            }
                            .testTag("sheet_profile_row_${profile.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = profile.label.ifBlank {
                                        stringResource(R.string.queue_format, profile.queueId)
                                    },
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stringResource(R.string.queue_format, profile.queueId) +
                                        if (isActive) " · активна" else "",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (savedProfiles.size > 1) {
                                IconButton(
                                    onClick = {
                                        if (editingProfileId == profile.id) {
                                            editingProfileId = savedProfiles.firstOrNull { it.id != profile.id }?.id
                                        }
                                        onDeleteProfile(profile.id)
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("delete_profile_${profile.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.delete_place_cd),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                if (editingProfileId != null) {
                    OutlinedButton(
                        onClick = {
                            editingProfileId = null
                            editedAddress = ""
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("new_profile_mode_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.add_new_place_title))
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Text(
                text = if (editingProfileId == null) {
                    stringResource(R.string.add_new_place_title)
                } else {
                    stringResource(R.string.edit_current_place_title)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            val rows = availableQueues.chunked(4)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { rowQueues ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowQueues.forEach { queue ->
                            val isSelected = queue == pickedQueue
                            Surface(
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { pickedQueue = queue }
                                    .testTag("queue_option_$queue")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = queue,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }
                            }
                        }
                        repeat(4 - rowQueues.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                labelPresets.forEach { preset ->
                    val chosen = editedAddress.trim().equals(preset, ignoreCase = true)
                    Surface(
                        color = if (chosen) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(50),
                        border = if (chosen) {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            null
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { editedAddress = preset }
                            .testTag("preset_label_$preset")
                    ) {
                        Text(
                            text = preset,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            OutlinedTextField(
                value = editedAddress,
                onValueChange = { editedAddress = it },
                label = { Text(stringResource(R.string.place_label_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("address_label_input")
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(ScheduleRepository.OFFICIAL_PORTAL_URL)
                        )
                        context.startActivity(intent)
                    }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.check_street_on_site),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Button(
                onClick = { onSaveProfile(editingProfileId, pickedQueue, editedAddress) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("save_queue_button")
            ) {
                Text(
                    text = if (editingProfileId == null) {
                        stringResource(R.string.add_place_btn)
                    } else {
                        stringResource(R.string.save_queue)
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}
