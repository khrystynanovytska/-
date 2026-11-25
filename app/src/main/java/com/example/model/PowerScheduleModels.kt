package com.example.model

enum class SlotState {
    ON,
    MAYBE,
    OFF;

    val isPowerAvailable: Boolean
        get() = this == ON
}

data class KyivDate(
    val year: Int,
    val month: Int, // 1..12
    val day: Int    // 1..31
) : Comparable<KyivDate> {

    override fun compareTo(other: KyivDate): Int {
        if (year != other.year) return year.compareTo(other.year)
        if (month != other.month) return month.compareTo(other.month)
        return day.compareTo(other.day)
    }

    fun formatUkrainianShort(): String {
        val monthName = when (month) {
            1 -> "січ."
            2 -> "лют."
            3 -> "бер."
            4 -> "квіт."
            5 -> "трав."
            6 -> "черв."
            7 -> "лип."
            8 -> "серп."
            9 -> "вер."
            10 -> "жовт."
            11 -> "лист."
            12 -> "груд."
            else -> ""
        }
        return "$day $monthName"
    }
}

data class TimeRange(
    val startMinute: Int, // 0..1440
    val endMinute: Int,   // 0..1440
    val state: SlotState
) {
    val isOn: Boolean
        get() = state == SlotState.ON

    val durationMinutes: Int
        get() = (endMinute - startMinute).coerceAtLeast(0)

    fun format(): String = "${formatMinutes(startMinute)} – ${formatMinutes(endMinute)}"

    companion object {
        fun formatMinutes(totalMinutes: Int): String {
            val clamped = totalMinutes.coerceIn(0, 1440)
            val h = clamped / 60
            val m = clamped % 60
            return String.format("%02d:%02d", h, m)
        }
    }
}

data class DaySchedule(
    val date: KyivDate,
    val slots: List<SlotState>, // size 48, each 30 min
    val hasManualEdits: Boolean = false
) {
    val hasAnyInterruption: Boolean
        get() = slots.any { it != SlotState.ON }

    val totalOnMinutes: Int
        get() = slots.count { it == SlotState.ON } * 30

    val totalMaybeMinutes: Int
        get() = slots.count { it == SlotState.MAYBE } * 30

    val totalOffMinutes: Int
        get() = slots.count { it == SlotState.OFF } * 30
}

data class ScheduleDataset(
    val queueId: String = "3.1",
    val availableQueues: List<String> = DEFAULT_QUEUES,
    val days: Map<KyivDate, DaySchedule>,
    val lastUpdatedLabel: String?,
    val fetchedAtMillis: Long,
    val isFromCache: Boolean
) {
    companion object {
        val DEFAULT_QUEUES = listOf(
            "1.1", "1.2",
            "2.1", "2.2",
            "3.1", "3.2",
            "4.1", "4.2",
            "5.1", "5.2",
            "6.1", "6.2"
        )
    }
}

data class OutageTransition(
    val outageStartMillis: Long,
    val outageEndMillis: Long?,
    val offTimeFormatted: String,
    val backOnTimeFormatted: String?,
    val isMaybe: Boolean = false
)

data class ManualStatusOverride(
    val state: SlotState, // ON or OFF
    val expiresAtMillis: Long
)

data class PowerStatusSnapshot(
    val todaySchedule: DaySchedule?,
    val tomorrowSchedule: DaySchedule?,
    val effectiveState: SlotState,
    val scheduleState: SlotState,
    val isManualOverride: Boolean,
    val remainingSeconds: Long, // -1L when no scheduled change in today/tomorrow window
    val blockProgress: Float,   // 0f..1f within current block
    val nextChangeTimeLabel: String?,
    val nextChangeTargetState: SlotState?,
    val todayOnMinutes: Int,
    val todayMaybeMinutes: Int,
    val todayOffMinutes: Int,
    val todayOnRanges: List<TimeRange>,
    val todayMaybeRanges: List<TimeRange>,
    val todayOffRanges: List<TimeRange>,
    val tomorrowOnRanges: List<TimeRange>,
    val tomorrowMaybeRanges: List<TimeRange>,
    val tomorrowOffRanges: List<TimeRange>,
    val currentMinuteOfDay: Int
) {
    val isPowerOn: Boolean
        get() = effectiveState == SlotState.ON
}

data class SavedQueueProfile(
    val id: String,
    val queueId: String,
    val label: String
)

