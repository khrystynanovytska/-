package com.example.data

import com.example.model.DaySchedule
import com.example.model.KyivDate
import com.example.model.ManualStatusOverride
import com.example.model.OutageTransition
import com.example.model.PowerStatusSnapshot
import com.example.model.ScheduleDataset
import com.example.model.SlotState
import com.example.model.TimeRange
import org.json.JSONObject

object PowerScheduleParser {

    fun parseJson(
        rawJson: String,
        queueId: String = "3.1",
        fetchedAtMillis: Long = System.currentTimeMillis(),
        isFromCache: Boolean = false
    ): ScheduleDataset {
        val root = JSONObject(rawJson)
        val fact = root.optJSONObject("fact")
        val dataObj = fact?.optJSONObject("data")
            ?: throw IllegalArgumentException("Відсутній розділ fact.data у графіку")

        val days = mutableMapOf<KyivDate, DaySchedule>()
        val discoveredQueues = linkedSetOf<String>()
        val dayKeys = dataObj.keys()
        while (dayKeys.hasNext()) {
            val tsKey = dayKeys.next()
            val epochSeconds = tsKey.toLongOrNull() ?: continue
            val dayQueues = dataObj.optJSONObject(tsKey) ?: continue

            val qKeys = dayQueues.keys()
            while (qKeys.hasNext()) {
                normalizeQueueId(qKeys.next())?.let { discoveredQueues.add(it) }
            }

            val queueObj = findQueueObject(dayQueues, queueId) ?: continue

            val slots = MutableList(48) { SlotState.ON }
            for (hour in 1..24) {
                val status = queueObj.optString(hour.toString(), "yes")
                    .trim()
                    .lowercase()
                val firstSlotIdx = (hour - 1) * 2
                val secondSlotIdx = firstSlotIdx + 1
                val (s1, s2) = mapHourStatus(status)
                slots[firstSlotIdx] = s1
                slots[secondSlotIdx] = s2
            }
            val date = KyivTime.dateFromEpochSeconds(epochSeconds)
            days[date] = DaySchedule(date = date, slots = slots)
        }

        if (days.isEmpty()) {
            throw IllegalArgumentException("Не знайдено даних для черги $queueId")
        }

        val lastUpdatedRaw = fact.optString("update", "").ifBlank {
            root.optString("lastUpdated", "")
        }
        val lastUpdatedLabel = formatLastUpdated(lastUpdatedRaw, fetchedAtMillis)

        val sortedQueues = if (discoveredQueues.isNotEmpty()) {
            discoveredQueues.sorted()
        } else {
            ScheduleDataset.DEFAULT_QUEUES
        }

        return ScheduleDataset(
            queueId = queueId,
            availableQueues = sortedQueues,
            days = days.toSortedMap(),
            lastUpdatedLabel = lastUpdatedLabel,
            fetchedAtMillis = fetchedAtMillis,
            isFromCache = isFromCache
        )
    }

    private fun normalizeQueueId(rawKey: String): String? {
        val cleaned = rawKey.trim()
            .removePrefix("GPV")
            .removePrefix("gpv")
            .removePrefix("_")
            .replace("_", ".")
        return if (Regex("""^\d+\.\d+$""").matches(cleaned)) cleaned else null
    }

    private fun findQueueObject(dayQueues: JSONObject, queueId: String): JSONObject? {
        val normalized = queueId.trim()
        val candidates = listOf(
            "GPV$normalized",
            "gpv$normalized",
            normalized,
            "GPV_${normalized.replace(".", "_")}"
        )
        for (key in candidates) {
            dayQueues.optJSONObject(key)?.let { return it }
        }
        val keys = dayQueues.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (normalizeQueueId(k) == normalized) {
                return dayQueues.optJSONObject(k)
            }
        }
        return null
    }

    internal fun mapHourStatus(status: String): Pair<SlotState, SlotState> = when (status) {
        "yes" -> SlotState.ON to SlotState.ON
        "no" -> SlotState.OFF to SlotState.OFF
        "first" -> SlotState.OFF to SlotState.ON
        "mfirst" -> SlotState.MAYBE to SlotState.ON
        "second" -> SlotState.ON to SlotState.OFF
        "msecond" -> SlotState.ON to SlotState.MAYBE
        "maybe" -> SlotState.MAYBE to SlotState.MAYBE
        "first_msecond", "first_maybe", "no_msecond", "off_maybe" -> SlotState.OFF to SlotState.MAYBE
        "mfirst_second", "maybe_second", "mfirst_no", "maybe_off" -> SlotState.MAYBE to SlotState.OFF
        else -> SlotState.ON to SlotState.ON
    }

    private fun formatLastUpdated(raw: String, fallbackMillis: Long): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return KyivTime.formatUpdatedLabel(fallbackMillis)
        if (trimmed.length >= 16 && trimmed[4] == '-' && trimmed[10] == 'T') {
            val datePart = trimmed.substring(8, 10) + "." + trimmed.substring(5, 7)
            val timePart = trimmed.substring(11, 16)
            return "$datePart $timePart"
        }
        return trimmed
    }

    fun computeTimeRanges(slots: List<SlotState>, targetState: SlotState): List<TimeRange> {
        if (slots.size != 48) return emptyList()
        val ranges = mutableListOf<TimeRange>()
        var activeStartMinute: Int? = null
        for (i in slots.indices) {
            val matches = slots[i] == targetState
            if (matches && activeStartMinute == null) {
                activeStartMinute = i * 30
            } else if (!matches && activeStartMinute != null) {
                ranges.add(TimeRange(activeStartMinute, i * 30, targetState))
                activeStartMinute = null
            }
        }
        if (activeStartMinute != null) {
            ranges.add(TimeRange(activeStartMinute, 1440, targetState))
        }
        return ranges
    }

    // Convenience overload kept compatible for simple ON/non-ON queries
    fun computeTimeRanges(slots: List<SlotState>, wantOn: Boolean): List<TimeRange> =
        computeTimeRanges(slots, if (wantOn) SlotState.ON else SlotState.OFF)

    fun computeCurrentBlockEndMillis(
        dataset: ScheduleDataset,
        nowMillis: Long = System.currentTimeMillis()
    ): Long {
        val todayDate = KyivTime.todayDate(nowMillis)
        val tomorrowDate = KyivTime.nextDay(todayDate)
        val todaySchedule = dataset.days[todayDate]
            ?: return KyivTime.epochMillisFor(todayDate, 1440)
        val tomorrowSchedule = dataset.days[tomorrowDate]

        val currentMinute = KyivTime.currentMinuteOfDay(nowMillis)
        val currentSlotIdx = (currentMinute / 30).coerceIn(0, 47)
        val currentState = todaySchedule.slots[currentSlotIdx]

        val timelineSlots = buildList {
            addAll(todaySchedule.slots)
            if (tomorrowSchedule != null) addAll(tomorrowSchedule.slots)
        }

        for (idx in (currentSlotIdx + 1) until timelineSlots.size) {
            if (timelineSlots[idx] != currentState) {
                return KyivTime.epochMillisFor(todayDate, idx * 30)
            }
        }
        return KyivTime.epochMillisFor(todayDate, timelineSlots.size * 30)
    }

    fun computeSnapshot(
        dataset: ScheduleDataset,
        nowMillis: Long = System.currentTimeMillis(),
        manualOverride: ManualStatusOverride? = null
    ): PowerStatusSnapshot {
        val todayDate = KyivTime.todayDate(nowMillis)
        val tomorrowDate = KyivTime.nextDay(todayDate)
        val yesterdayDate = KyivTime.previousDay(todayDate)

        val todaySchedule = dataset.days[todayDate]
        val tomorrowSchedule = dataset.days[tomorrowDate]
        val yesterdaySchedule = dataset.days[yesterdayDate]

        val activeOverride = manualOverride?.takeIf { it.expiresAtMillis > nowMillis }

        if (todaySchedule == null) {
            val currentMinute = KyivTime.currentMinuteOfDay(nowMillis)
            val tomorrowOnRanges = tomorrowSchedule?.let { computeTimeRanges(it.slots, SlotState.ON) } ?: emptyList()
            val tomorrowMaybeRanges = tomorrowSchedule?.let { computeTimeRanges(it.slots, SlotState.MAYBE) } ?: emptyList()
            val tomorrowOffRanges = tomorrowSchedule?.let { computeTimeRanges(it.slots, SlotState.OFF) } ?: emptyList()
            return PowerStatusSnapshot(
                todaySchedule = null,
                tomorrowSchedule = tomorrowSchedule,
                effectiveState = activeOverride?.state ?: SlotState.ON,
                scheduleState = SlotState.ON,
                isManualOverride = activeOverride != null,
                remainingSeconds = activeOverride?.let { ((it.expiresAtMillis - nowMillis) / 1000L).coerceAtLeast(0L) } ?: -1L,
                blockProgress = 0f,
                nextChangeTimeLabel = activeOverride?.let { KyivTime.formatHHmm(it.expiresAtMillis) },
                nextChangeTargetState = null,
                todayOnMinutes = 1440,
                todayMaybeMinutes = 0,
                todayOffMinutes = 0,
                todayOnRanges = emptyList(),
                todayMaybeRanges = emptyList(),
                todayOffRanges = emptyList(),
                tomorrowOnRanges = tomorrowOnRanges,
                tomorrowMaybeRanges = tomorrowMaybeRanges,
                tomorrowOffRanges = tomorrowOffRanges,
                currentMinuteOfDay = currentMinute
            )
        }

        val currentMinute = KyivTime.currentMinuteOfDay(nowMillis)
        val currentSlotIdx = (currentMinute / 30).coerceIn(0, 47)
        val scheduleState = todaySchedule.slots[currentSlotIdx]

        // Build effective timeline (applying active manual override up to its expiration slot)
        val rawTimeline = buildList {
            addAll(todaySchedule.slots)
            if (tomorrowSchedule != null) {
                addAll(tomorrowSchedule.slots)
            }
        }
        val effectiveTimeline = rawTimeline.toMutableList()
        if (activeOverride != null) {
            val todayMidnightMillis = KyivTime.epochMillisFor(todayDate, 0)
            val overrideEndSlotExclusive =
                (((activeOverride.expiresAtMillis - todayMidnightMillis) + 1799_999L) / 1800_000L)
                    .toInt()
                    .coerceIn(currentSlotIdx + 1, effectiveTimeline.size)
            for (idx in currentSlotIdx until overrideEndSlotExclusive) {
                effectiveTimeline[idx] = activeOverride.state
            }
        }

        val effectiveState = effectiveTimeline[currentSlotIdx]

        // Find next state change in effective timeline
        var nextChangeSlotIdx: Int? = null
        for (idx in (currentSlotIdx + 1) until effectiveTimeline.size) {
            if (effectiveTimeline[idx] != effectiveState) {
                nextChangeSlotIdx = idx
                break
            }
        }

        // Find start of current block
        var blockStartSlotOffset = currentSlotIdx
        var i = currentSlotIdx - 1
        while (i >= 0 && effectiveTimeline[i] == effectiveState) {
            blockStartSlotOffset = i
            i--
        }
        if (i < 0 && yesterdaySchedule != null && activeOverride == null) {
            var yIdx = 47
            while (yIdx >= 0 && yesterdaySchedule.slots[yIdx] == effectiveState) {
                blockStartSlotOffset = yIdx - 48
                yIdx--
            }
        }

        val remainingSeconds: Long
        val nextChangeTimeLabel: String?
        val nextChangeTargetState: SlotState?
        val blockProgress: Float

        if (nextChangeSlotIdx != null) {
            val changeTotalMinutesFromTodayMidnight = nextChangeSlotIdx * 30
            val changeMillis = KyivTime.epochMillisFor(todayDate, changeTotalMinutesFromTodayMidnight)
            remainingSeconds = ((changeMillis - nowMillis) / 1000L).coerceAtLeast(0L)
            nextChangeTargetState = effectiveTimeline[nextChangeSlotIdx]

            val minuteInTargetDay = changeTotalMinutesFromTodayMidnight % 1440
            val timeStr = TimeRange.formatMinutes(minuteInTargetDay)
            nextChangeTimeLabel = if (nextChangeSlotIdx >= 48) {
                "завтра о $timeStr"
            } else {
                timeStr
            }

            val blockStartMillis = KyivTime.epochMillisFor(todayDate, blockStartSlotOffset * 30)
            val totalBlockMillis = (changeMillis - blockStartMillis).coerceAtLeast(1L)
            val elapsedMillis = (nowMillis - blockStartMillis).coerceIn(0L, totalBlockMillis)
            blockProgress = (elapsedMillis.toFloat() / totalBlockMillis.toFloat()).coerceIn(0f, 1f)
        } else {
            remainingSeconds = if (todaySchedule.hasAnyInterruption || tomorrowSchedule != null) {
                val endMinutes = effectiveTimeline.size * 30
                val endMillis = KyivTime.epochMillisFor(todayDate, endMinutes)
                ((endMillis - nowMillis) / 1000L).coerceAtLeast(0L)
            } else {
                -1L
            }
            nextChangeTimeLabel = null
            nextChangeTargetState = null
            blockProgress = if (remainingSeconds > 0L) {
                val blockStartMillis = KyivTime.epochMillisFor(todayDate, blockStartSlotOffset * 30)
                val endMillis = KyivTime.epochMillisFor(todayDate, effectiveTimeline.size * 30)
                val total = (endMillis - blockStartMillis).coerceAtLeast(1L)
                ((nowMillis - blockStartMillis).toFloat() / total.toFloat()).coerceIn(0f, 1f)
            } else {
                1f
            }
        }

        val todayOnRanges = computeTimeRanges(todaySchedule.slots, SlotState.ON)
        val todayMaybeRanges = computeTimeRanges(todaySchedule.slots, SlotState.MAYBE)
        val todayOffRanges = computeTimeRanges(todaySchedule.slots, SlotState.OFF)
        val tomorrowOnRanges = tomorrowSchedule?.let { computeTimeRanges(it.slots, SlotState.ON) } ?: emptyList()
        val tomorrowMaybeRanges = tomorrowSchedule?.let { computeTimeRanges(it.slots, SlotState.MAYBE) } ?: emptyList()
        val tomorrowOffRanges = tomorrowSchedule?.let { computeTimeRanges(it.slots, SlotState.OFF) } ?: emptyList()

        return PowerStatusSnapshot(
            todaySchedule = todaySchedule,
            tomorrowSchedule = tomorrowSchedule,
            effectiveState = effectiveState,
            scheduleState = scheduleState,
            isManualOverride = activeOverride != null,
            remainingSeconds = remainingSeconds,
            blockProgress = blockProgress,
            nextChangeTimeLabel = nextChangeTimeLabel,
            nextChangeTargetState = nextChangeTargetState,
            todayOnMinutes = todaySchedule.totalOnMinutes,
            todayMaybeMinutes = todaySchedule.totalMaybeMinutes,
            todayOffMinutes = todaySchedule.totalOffMinutes,
            todayOnRanges = todayOnRanges,
            todayMaybeRanges = todayMaybeRanges,
            todayOffRanges = todayOffRanges,
            tomorrowOnRanges = tomorrowOnRanges,
            tomorrowMaybeRanges = tomorrowMaybeRanges,
            tomorrowOffRanges = tomorrowOffRanges,
            currentMinuteOfDay = currentMinute
        )
    }

    fun computeUpcomingOutageTransitions(
        dataset: ScheduleDataset,
        nowMillis: Long = System.currentTimeMillis()
    ): List<OutageTransition> {
        val todayDate = KyivTime.todayDate(nowMillis)
        val tomorrowDate = KyivTime.nextDay(todayDate)
        val todaySchedule = dataset.days[todayDate] ?: return emptyList()
        val tomorrowSchedule = dataset.days[tomorrowDate]

        val timeline = buildList {
            addAll(todaySchedule.slots)
            if (tomorrowSchedule != null) addAll(tomorrowSchedule.slots)
        }

        val currentSlotIdx = (KyivTime.currentMinuteOfDay(nowMillis) / 30).coerceIn(0, 47)
        val transitions = mutableListOf<OutageTransition>()

        var idx = currentSlotIdx.coerceAtLeast(1)
        while (idx < timeline.size) {
            val prevOn = timeline[idx - 1] == SlotState.ON
            val currState = timeline[idx]
            if (prevOn && currState != SlotState.ON) {
                val startMillis = KyivTime.epochMillisFor(todayDate, idx * 30)
                if (startMillis > nowMillis) {
                    var endIdx = idx + 1
                    while (endIdx < timeline.size && timeline[endIdx] != SlotState.ON) {
                        endIdx++
                    }
                    val endMillis = if (endIdx < timeline.size) {
                        KyivTime.epochMillisFor(todayDate, endIdx * 30)
                    } else null

                    transitions.add(
                        OutageTransition(
                            outageStartMillis = startMillis,
                            outageEndMillis = endMillis,
                            offTimeFormatted = TimeRange.formatMinutes((idx * 30) % 1440),
                            backOnTimeFormatted = endMillis?.let { KyivTime.formatHHmm(it) },
                            isMaybe = currState == SlotState.MAYBE
                        )
                    )
                }
            }
            idx++
        }
        return transitions
    }
}
