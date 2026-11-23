package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.KyivTime
import com.example.data.PowerScheduleParser
import com.example.data.SchedulePreferences
import com.example.data.ScheduleRepository
import com.example.model.PowerStatusSnapshot
import com.example.model.ScheduleDataset
import com.example.notifications.OutageAlarmScheduler
import com.example.notifications.OutageNotificationHelper
import com.example.widget.PowerStatusWidgetProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ScheduleUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val selectedQueue: String = SchedulePreferences.DEFAULT_QUEUE,
    val addressLabel: String = SchedulePreferences.DEFAULT_ADDRESS,
    val availableQueues: List<String> = ScheduleDataset.DEFAULT_QUEUES,
    val dataset: ScheduleDataset? = null,
    val snapshot: PowerStatusSnapshot? = null,
    val selectedTomorrow: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val hasNotificationPermission: Boolean = true,
    val nowMillis: Long = System.currentTimeMillis()
)

class ScheduleViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val prefs = SchedulePreferences(context)
    private val repository = ScheduleRepository(context, prefs)

    private val _uiState = MutableStateFlow(
        ScheduleUiState(
            selectedQueue = prefs.selectedQueue,
            addressLabel = prefs.addressLabel,
            notificationsEnabled = prefs.notificationsEnabled,
            hasNotificationPermission = OutageNotificationHelper.hasNotificationPermission(context)
        )
    )
    val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()

    init {
        loadInitialAndStartTicker()
    }

    private fun loadInitialAndStartTicker() {
        repository.getCachedDataset()?.let { cached ->
            val now = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    availableQueues = cached.availableQueues,
                    dataset = cached,
                    snapshot = PowerScheduleParser.computeSnapshot(cached, now),
                    nowMillis = now
                )
            }
            OutageAlarmScheduler.rescheduleAlerts(context, cached, now)
        }

        refresh(isManual = false)

        viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                val now = System.currentTimeMillis()
                val currentDataset = _uiState.value.dataset
                if (currentDataset != null) {
                    _uiState.update {
                        it.copy(
                            snapshot = PowerScheduleParser.computeSnapshot(currentDataset, now),
                            nowMillis = now
                        )
                    }
                }
            }
        }
    }

    fun refresh(isManual: Boolean = true) {
        viewModelScope.launch {
            _uiState.update {
                if (it.dataset == null) {
                    it.copy(isLoading = true, errorMessage = null)
                } else {
                    it.copy(isRefreshing = isManual, errorMessage = null)
                }
            }

            val result = repository.fetchSchedule(forceNetwork = true)
            val now = System.currentTimeMillis()

            result.fold(
                onSuccess = { dataset ->
                    val snapshot = PowerScheduleParser.computeSnapshot(dataset, now)
                    val keepTomorrow = _uiState.value.selectedTomorrow && snapshot.tomorrowSchedule != null
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = null,
                            availableQueues = dataset.availableQueues,
                            dataset = dataset,
                            snapshot = snapshot,
                            selectedTomorrow = keepTomorrow,
                            nowMillis = now
                        )
                    }
                    OutageAlarmScheduler.rescheduleAlerts(context, dataset, now)
                    PowerStatusWidgetProvider.updateAllWidgets(context, dataset, now)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = if (it.dataset == null) {
                                error.localizedMessage ?: "Помилка мережі"
                            } else {
                                null
                            }
                        )
                    }
                }
            )
        }
    }

    fun selectQueue(queueId: String, customAddress: String) {
        val cleanedAddress = customAddress.trim()
        prefs.selectedQueue = queueId
        prefs.addressLabel = cleanedAddress
        prefs.lastNotifiedOutageStartMillis = 0L

        val now = System.currentTimeMillis()
        val reparsed = repository.getCachedDataset(queueId = queueId)
        if (reparsed != null) {
            val snapshot = PowerScheduleParser.computeSnapshot(reparsed, now)
            _uiState.update {
                it.copy(
                    selectedQueue = queueId,
                    addressLabel = cleanedAddress,
                    availableQueues = reparsed.availableQueues,
                    dataset = reparsed,
                    snapshot = snapshot,
                    selectedTomorrow = it.selectedTomorrow && snapshot.tomorrowSchedule != null,
                    nowMillis = now
                )
            }
            OutageAlarmScheduler.rescheduleAlerts(context, reparsed, now)
            PowerStatusWidgetProvider.updateAllWidgets(context, reparsed, now)
        } else {
            _uiState.update {
                it.copy(
                    selectedQueue = queueId,
                    addressLabel = cleanedAddress
                )
            }
            refresh(isManual = true)
        }
    }

    fun selectDay(tomorrow: Boolean) {
        _uiState.update { it.copy(selectedTomorrow = tomorrow) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.notificationsEnabled = enabled
        val dataset = _uiState.value.dataset
        if (enabled && dataset != null) {
            OutageAlarmScheduler.rescheduleAlerts(context, dataset)
        } else {
            OutageAlarmScheduler.cancelAllAlarms(context)
        }
        _uiState.update {
            it.copy(
                notificationsEnabled = enabled,
                hasNotificationPermission = OutageNotificationHelper.hasNotificationPermission(context)
            )
        }
    }

    fun refreshPermissionState() {
        val granted = OutageNotificationHelper.hasNotificationPermission(context)
        _uiState.update { it.copy(hasNotificationPermission = granted) }
        val dataset = _uiState.value.dataset
        if (granted && _uiState.value.notificationsEnabled && dataset != null) {
            OutageAlarmScheduler.rescheduleAlerts(context, dataset)
        }
    }

    fun todayDateLabel(): String = KyivTime.todayDate(_uiState.value.nowMillis).formatUkrainianShort()
    fun tomorrowDateLabel(): String = KyivTime.nextDay(KyivTime.todayDate(_uiState.value.nowMillis)).formatUkrainianShort()
}
