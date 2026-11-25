package com.example.data

import com.example.model.DaySchedule
import com.example.model.KyivDate
import com.example.model.ManualStatusOverride
import com.example.model.OutageTransition
import com.example.model.PowerStatusSnapshot
import com.example.model.ScheduleDataset
import com.example.model.SlotState
import com.example.model.TimeRange
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

object PowerScheduleParser {

    private val ISO_DATE_REGEX = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
    private val ISO_TIMESTAMP_REGEX = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})?$""")
    private val UA_TIMESTAMP_REGEX = Regex("""^(\d{2})\.(\d{2})\.(\d{4})\s+(\d{2}):(\d{2})(?::(\d{2}))?$""")
    private val SHORT_UA_TIMESTAMP_REGEX = Regex("""^(\d{2})\.(\d{2})\s+(\d{2}):(\d{2})$""")
    private val TIME_HHMM_REGEX = Regex("""^(\d{1,2}):(\d{2})$""")

    fun parseJson(
        rawJson: String,
        queueId: String = "3.1",
        fetchedAtMillis: Long = System.currentTimeMillis(),
        isFromCache: Boolean = false
    ): ScheduleDataset {
        val root = JSONObject(rawJson)
        if (root.has("days") && root.optJSONArray("days") != null) {
            return parseSvitlobotJson(
                rawJson = rawJson,
                queueId = queueId,
                fetchedAtMillis = fetchedAtMillis,
                isFromCache = isFromCache
            )
        }

        val fact = root.optJSONObject("fact")
        val dataObj = fact?.optJSONObject("data")
            ?: throw IllegalArgumentException("Відсутній розділ fact.data у графіку")

        val days = mutableMapOf<KyivDate, DaySchedule>()
        val discoveredQueues = linkedSetOf<String>()
        val dayKeys = dataObj.keys()
        while (dayKeys.hasNext()) {
            val tsKey = dayKeys.next()
            val date = parseDayKey(tsKey) ?: continue
            val dayQueues = dataObj.optJSONObject(tsKey) ?: continue

            val qKeys = dayQueues.keys()
            while (qKeys.hasNext()) {
                normalizeQueueId(qKeys.next())?.let { discoveredQueues.add(it) }
            }

            val queueObj = findQueueObject(dayQueues, queueId) ?: continue
            val isZeroIndexed = queueObj.has("0") && !queueObj.has("24")

            val slots = MutableList(48) { SlotState.ON }
            for (h in 0 until 24) {
                val key = if (isZeroIndexed) h.toString() else (h + 1).toString()
                val status = queueObj.optString(key, "yes")
                    .trim()
                    .lowercase()
                val firstSlotIdx = h * 2
                val secondSlotIdx = firstSlotIdx + 1
                val (s1, s2) = mapHourStatus(status)
                slots[firstSlotIdx] = s1
                slots[secondSlotIdx] = s2
            }
            days[date] = DaySchedule(date = date, slots = slots)
        }

        if (days.isEmpty()) {
            throw IllegalArgumentException("Не знайдено даних для черги $queueId")
        }

        val factUpdateRaw = fact.optString("update", "").trim()
        val rootUpdatedRaw = root.optString("lastUpdated", "").trim()
        val lastUpdatedRaw = factUpdateRaw.ifBlank { rootUpdatedRaw }
        val lastUpdatedLabel = formatLastUpdated(lastUpdatedRaw, fetchedAtMillis)
        val sourceUpdatedAtMillis = parseTimestampMillis(factUpdateRaw)
            ?: parseTimestampMillis(rootUpdatedRaw)

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
            isFromCache = isFromCache,
            sourceUpdatedAtMillis = sourceUpdatedAtMillis
        )
    }

    fun parseSvitlobotJson(
        rawJson: String,
        queueId: String = "3.1",
        fetchedAtMillis: Long = System.currentTimeMillis(),
        isFromCache: Boolean = false
    ): ScheduleDataset {
        val root = JSONObject(rawJson)
        val rawGroup = root.optString("group", "").trim()
        val normalizedGroup = normalizeQueueId(rawGroup)
        if (normalizedGroup != null && normalizedGroup != queueId.trim()) {
            throw IllegalArgumentException("Дані належать іншій черзі ($normalizedGroup)")
        }

        val daysArray = root.optJSONArray("days")
            ?: throw IllegalArgumentException("Відсутній розділ days у графіку")

        val days = mutableMapOf<KyivDate, DaySchedule>()
        for (i in 0 until daysArray.length()) {
            val dayObj = daysArray.optJSONObject(i) ?: continue
            val date = parseIsoDate(dayObj.optString("date", "")) ?: continue
            val slots = parseSvitlobotDaySlots(dayObj)
            days[date] = DaySchedule(date = date, slots = slots)
        }

        if (days.isEmpty()) {
            throw IllegalArgumentException("Не знайдено даних для черги $queueId")
        }

        val sourceUpdatedRaw = root.optString("source_updated_at", "").trim()
        val updatedRaw = root.optString("updated_at", "").trim()
        val bestTimestampRaw = sourceUpdatedRaw.ifBlank { updatedRaw }
        val lastUpdatedLabel = formatLastUpdated(bestTimestampRaw, fetchedAtMillis)
        val sourceUpdatedAtMillis = parseTimestampMillis(sourceUpdatedRaw)
            ?: parseTimestampMillis(updatedRaw)

        return ScheduleDataset(
            queueId = queueId,
            availableQueues = ScheduleDataset.DEFAULT_QUEUES,
            days = days.toSortedMap(),
            lastUpdatedLabel = lastUpdatedLabel,
            fetchedAtMillis = fetchedAtMillis,
            isFromCache = isFromCache,
            sourceUpdatedAtMillis = sourceUpdatedAtMillis
        )
    }

    private fun parseSvitlobotDaySlots(dayObj: JSONObject): List<SlotState> {
        val slots = MutableList(48) { SlotState.ON }
        val rawSlots = dayObj.optJSONArray("slots")
        val hasHourSlots = rawSlots != null && rawSlots.length() >= 24

        if (rawSlots != null) {
            if (rawSlots.length() == 48) {
                for (idx in 0 until 48) {
                    val v = rawSlots.opt(idx)
                    slots[idx] = when (v?.toString()?.trim()?.lowercase()) {
                        "1", "no", "off" -> SlotState.OFF
                        "2", "maybe" -> SlotState.MAYBE
                        else -> SlotState.ON
                    }
                }
            } else if (rawSlots.length() >= 24) {
                for (h in 0 until 24) {
                    val v = rawSlots.opt(h)?.toString()?.trim()?.lowercase() ?: "0"
                    val (s1, s2) = when (v) {
                        "0" -> SlotState.ON to SlotState.ON
                        "1" -> SlotState.OFF to SlotState.OFF
                        "2" -> SlotState.OFF to SlotState.ON
                        "3" -> SlotState.ON to SlotState.OFF
                        else -> mapHourStatus(v)
                    }
                    slots[h * 2] = s1
                    slots[h * 2 + 1] = s2
                }
            }
        }

        val outagesArr = dayObj.optJSONArray("outages")
        if (outagesArr != null && outagesArr.length() > 0) {
            val outageMask = parseOutageRangesMask(outagesArr)
            if (outageMask != null) {
                if (!hasHourSlots) {
                    for (idx in 0 until 48) {
                        if (outageMask[idx]) slots[idx] = SlotState.OFF
                    }
                } else {
                    for (h in 0 until 24) {
                        val m1 = outageMask[h * 2]
                        val m2 = outageMask[h * 2 + 1]
                        if (m1 != m2) {
                            slots[h * 2] = if (m1) SlotState.OFF else SlotState.ON
                            slots[h * 2 + 1] = if (m2) SlotState.OFF else SlotState.ON
                        }
                    }
                }
            }
        }

        return slots
    }

    private fun parseOutageRangesMask(outagesArr: JSONArray): BooleanArray? {
        val mask = BooleanArray(48)
        var parsedAny = false
        for (i in 0 until outagesArr.length()) {
            val item = outagesArr.opt(i) ?: continue
            var startMin: Int? = null
            var endMin: Int? = null
            if (item is JSONArray && item.length() >= 2) {
                startMin = parseHHmmToMinutes(item.optString(0, ""), isEnd = false)
                endMin = parseHHmmToMinutes(item.optString(1, ""), isEnd = true)
            } else if (item is String) {
                val parts = item.split('-', '–', '—')
                if (parts.size == 2) {
                    startMin = parseHHmmToMinutes(parts[0], isEnd = false)
                    endMin = parseHHmmToMinutes(parts[1], isEnd = true)
                }
            }
            if (startMin != null && endMin != null && endMin > startMin) {
                parsedAny = true
                for (slotIdx in 0 until 48) {
                    val slotStart = slotIdx * 30
                    val slotEnd = slotStart + 30
                    if (startMin < slotEnd && endMin > slotStart) {
                        mask[slotIdx] = true
                    }
                }
            }
        }
        return if (parsedAny) mask else null
    }

    private fun parseHHmmToMinutes(raw: String, isEnd: Boolean): Int? {
        val match = TIME_HHMM_REGEX.matchEntire(raw.trim()) ?: return null
        val h = match.groupValues[1].toIntOrNull() ?: return null
        val m = match.groupValues[2].toIntOrNull() ?: return null
        if (h == 24 && m == 0) return 1440
        if (h !in 0..23 || m !in 0..59) return null
        if (isEnd && h == 0 && m == 0) return 1440
        if (isEnd && h == 23 && m == 59) return 1440
        return h * 60 + m
    }

    fun mergeWithSvitlobot(
        primary: ScheduleDataset,
        secondary: ScheduleDataset
    ): ScheduleDataset {
        if (primary.queueId != secondary.queueId) return primary

        val pTs = primary.sourceUpdatedAtMillis
        val sTs = secondary.sourceUpdatedAtMillis
        val toleranceMs = 5L * 60L * 1000L

        if (pTs != null && sTs != null && sTs < pTs - toleranceMs) {
            return primary
        }
        val isSecondaryStrictlyNewer = pTs != null && sTs != null && sTs > pTs + toleranceMs

        val mergedDays = primary.days.toMutableMap()
        for ((date, sbDay) in secondary.days) {
            val oeDay = mergedDays[date]
            if (oeDay == null) {
                mergedDays[date] = sbDay
                continue
            }

            val mergedSlots = oeDay.slots.toMutableList()
            for (h in 0 until 24) {
                val i1 = h * 2
                val i2 = i1 + 1
                val oe1 = oeDay.slots[i1]
                val oe2 = oeDay.slots[i2]
                val sb1 = sbDay.slots[i1]
                val sb2 = sbDay.slots[i2]

                when {
                    oe1 == SlotState.OFF && oe2 == SlotState.OFF && sb1 == SlotState.OFF && sb2 == SlotState.ON -> {
                        mergedSlots[i1] = SlotState.OFF
                        mergedSlots[i2] = SlotState.MAYBE
                    }
                    oe1 == SlotState.OFF && oe2 == SlotState.OFF && sb1 == SlotState.ON && sb2 == SlotState.OFF -> {
                        mergedSlots[i1] = SlotState.MAYBE
                        mergedSlots[i2] = SlotState.OFF
                    }
                    oe1 == SlotState.ON && oe2 == SlotState.ON && (sb1 == SlotState.OFF || sb2 == SlotState.OFF) -> {
                        if (sb1 != sb2 || isSecondaryStrictlyNewer) {
                            mergedSlots[i1] = sb1
                            mergedSlots[i2] = sb2
                        }
                    }
                    oe1 == SlotState.OFF && oe2 == SlotState.OFF && sb1 == SlotState.ON && sb2 == SlotState.ON && isSecondaryStrictlyNewer -> {
                        mergedSlots[i1] = SlotState.ON
                        mergedSlots[i2] = SlotState.ON
                    }
                    (oe1 == SlotState.MAYBE || oe2 == SlotState.MAYBE) && (sb1 == SlotState.OFF || sb2 == SlotState.OFF) && isSecondaryStrictlyNewer -> {
                        mergedSlots[i1] = if (sb1 == SlotState.OFF) SlotState.OFF else oe1
                        mergedSlots[i2] = if (sb2 == SlotState.OFF) SlotState.OFF else oe2
                    }
                }
            }
            mergedDays[date] = oeDay.copy(slots = mergedSlots)
        }

        val bestUpdatedLabel = if (isSecondaryStrictlyNewer && !secondary.lastUpdatedLabel.isNullOrBlank()) {
            secondary.lastUpdatedLabel
        } else {
            primary.lastUpdatedLabel
        }
        val bestTimestamp = if (isSecondaryStrictlyNewer) sTs else pTs

        return primary.copy(
            days = mergedDays.toSortedMap(),
            lastUpdatedLabel = bestUpdatedLabel,
            fetchedAtMillis = maxOf(primary.fetchedAtMillis, secondary.fetchedAtMillis),
            isFromCache = primary.isFromCache && secondary.isFromCache,
            sourceUpdatedAtMillis = bestTimestamp
        )
    }

    private fun parseDayKey(rawKey: String): KyivDate? {
        val epochSeconds = rawKey.toLongOrNull()
        if (epochSeconds != null) {
            return KyivTime.dateFromEpochSeconds(epochSeconds)
        }
        return parseIsoDate(rawKey)
    }

    private fun parseIsoDate(raw: String): KyivDate? {
        val m = ISO_DATE_REGEX.matchEntire(raw.trim()) ?: return null
        val y = m.groupValues[1].toIntOrNull() ?: return null
        val mo = m.groupValues[2].toIntOrNull() ?: return null
        val d = m.groupValues[3].toIntOrNull() ?: return null
        if (mo !in 1..12 || d !in 1..31) return null
        return KyivDate(y, mo, d)
    }

    internal fun parseTimestampMillis(raw: String?): Long? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        ISO_TIMESTAMP_REGEX.matchEntire(trimmed)?.let { m ->
            val year = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val day = m.groupValues[3].toInt()
            val hour = m.groupValues[4].toInt()
            val minute = m.groupValues[5].toInt()
            val second = m.groupValues[6].toIntOrNull() ?: 0
            val tzPart = m.groupValues[7]
            val tz = when {
                tzPart == "Z" -> TimeZone.getTimeZone("UTC")
                tzPart.startsWith("+") || tzPart.startsWith("-") -> {
                    val normalizedOffset = if (tzPart.length == 5) {
                        "${tzPart.substring(0, 3)}:${tzPart.substring(3)}"
                    } else {
                        tzPart
                    }
                    TimeZone.getTimeZone("GMT$normalizedOffset")
                }
                else -> KyivTime.TIME_ZONE
            }
            return Calendar.getInstance(tz).apply {
                set(year, month - 1, day, hour, minute, second)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        UA_TIMESTAMP_REGEX.matchEntire(trimmed)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val year = m.groupValues[3].toInt()
            val hour = m.groupValues[4].toInt()
            val minute = m.groupValues[5].toInt()
            val second = m.groupValues[6].toIntOrNull() ?: 0
            return Calendar.getInstance(KyivTime.TIME_ZONE).apply {
                set(year, month - 1, day, hour, minute, second)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        SHORT_UA_TIMESTAMP_REGEX.matchEntire(trimmed)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val hour = m.groupValues[3].toInt()
            val minute = m.groupValues[4].toInt()
            val year = KyivTime.todayDate().year
            return Calendar.getInstance(KyivTime.TIME_ZONE).apply {
                set(year, month - 1, day, hour, minute, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        return null
    }

    private fun normalizeQueueId(rawKey: String): String? {
        val cleaned = rawKey.trim()
            .removePrefix("GPV")
            .removePrefix("gpv")
            .removePrefix("_")
            .removePrefix("-")
            .replace("_", ".")
            .replace("-", ".")
            .replace(" ", "")
        return if (Regex("""^\d+\.\d+$""").matches(cleaned)) cleaned else null
    }

    private fun findQueueObject(dayQueues: JSONObject, queueId: String): JSONObject? {
        val normalized = queueId.trim()
        val candidates = listOf(
            "GPV$normalized",
            "gpv$normalized",
            normalized,
            "GPV_${normalized.replace(".", "_")}",
            "GPV-${normalized.replace(".", "-")}"
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

    internal fun mapHourStatus(status: String): Pair<SlotState, SlotState> {
        val normalized = status.trim().lowercase().replace(Regex("""[+/\-\s]+"""), "_")
        return when (normalized) {
            "yes", "on" -> SlotState.ON to SlotState.ON
            "no", "off" -> SlotState.OFF to SlotState.OFF
            "first", "first_yes", "first_on", "off_on", "no_yes" -> SlotState.OFF to SlotState.ON
            "mfirst", "mfirst_yes", "maybe_yes", "maybe_on" -> SlotState.MAYBE to SlotState.ON
            "second", "yes_second", "on_second", "on_off", "yes_no" -> SlotState.ON to SlotState.OFF
            "msecond", "yes_msecond", "yes_maybe", "on_maybe" -> SlotState.ON to SlotState.MAYBE
            "maybe" -> SlotState.MAYBE to SlotState.MAYBE
            "first_msecond", "first_maybe", "no_msecond", "no_maybe", "off_maybe", "off_msecond" -> SlotState.OFF to SlotState.MAYBE
            "mfirst_second", "maybe_second", "mfirst_no", "maybe_no", "maybe_off", "mfirst_off" -> SlotState.MAYBE to SlotState.OFF
            else -> SlotState.ON to SlotState.ON
        }
    }

    private fun formatLastUpdated(raw: String, fallbackMillis: Long): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return KyivTime.formatUpdatedLabel(fallbackMillis)
        parseTimestampMillis(trimmed)?.let { ts ->
            return KyivTime.formatUpdatedLabel(ts)
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
            val prevState = timeline[idx - 1]
            val currState = timeline[idx]
            val shouldAlert = (prevState == SlotState.ON && currState != SlotState.ON) ||
                (prevState == SlotState.MAYBE && currState == SlotState.OFF && idx - 1 <= currentSlotIdx)
            if (shouldAlert) {
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
