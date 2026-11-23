package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.model.DaySchedule
import com.example.model.KyivDate
import com.example.model.ManualStatusOverride
import com.example.model.ScheduleDataset
import com.example.model.SlotState

class SchedulePreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveCachedJson(json: String, fetchedAtMillis: Long) {
        prefs.edit()
            .putString(KEY_CACHED_JSON, json)
            .putLong(KEY_FETCHED_AT, fetchedAtMillis)
            .apply()
    }

    fun getCachedJson(): Pair<String, Long>? {
        val json = prefs.getString(KEY_CACHED_JSON, null) ?: return null
        val fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
        return json to fetchedAt
    }

    var selectedQueue: String
        get() = prefs.getString(KEY_SELECTED_QUEUE, DEFAULT_QUEUE) ?: DEFAULT_QUEUE
        set(value) {
            prefs.edit().putString(KEY_SELECTED_QUEUE, value).apply()
        }

    var addressLabel: String
        get() = prefs.getString(KEY_ADDRESS_LABEL, DEFAULT_ADDRESS) ?: DEFAULT_ADDRESS
        set(value) {
            prefs.edit().putString(KEY_ADDRESS_LABEL, value).apply()
        }

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, value).apply()
        }

    var lastNotifiedOutageStartMillis: Long
        get() = prefs.getLong(KEY_LAST_NOTIFIED_TRANSITION, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_NOTIFIED_TRANSITION, value).apply()
        }

    fun setManualStatusOverride(state: SlotState?, expiresAtMillis: Long) {
        if (state == null) {
            prefs.edit()
                .remove(KEY_MANUAL_STATUS)
                .remove(KEY_MANUAL_STATUS_EXPIRES)
                .apply()
        } else {
            prefs.edit()
                .putString(KEY_MANUAL_STATUS, state.name)
                .putLong(KEY_MANUAL_STATUS_EXPIRES, expiresAtMillis)
                .apply()
        }
    }

    fun getActiveManualOverride(nowMillis: Long = System.currentTimeMillis()): ManualStatusOverride? {
        val raw = prefs.getString(KEY_MANUAL_STATUS, null) ?: return null
        val expires = prefs.getLong(KEY_MANUAL_STATUS_EXPIRES, 0L)
        if (expires <= nowMillis) {
            setManualStatusOverride(null, 0L)
            return null
        }
        val state = runCatching { SlotState.valueOf(raw) }.getOrNull() ?: return null
        return ManualStatusOverride(state = state, expiresAtMillis = expires)
    }

    private fun slotOverridesKey(queueId: String, date: KyivDate): String =
        "slot_overrides_${queueId}_${date.year}_${date.month}_${date.day}"

    fun setHourOverride(queueId: String, date: KyivDate, hour: Int, state: SlotState?) {
        val key = slotOverridesKey(queueId, date)
        val current = prefs.getString(key, null)?.padEnd(48, '.')?.toCharArray()
            ?: CharArray(48) { '.' }
        val ch = when (state) {
            SlotState.ON -> '1'
            SlotState.MAYBE -> '2'
            SlotState.OFF -> '0'
            null -> '.'
        }
        val idx1 = (hour * 2).coerceIn(0, 47)
        val idx2 = (hour * 2 + 1).coerceIn(0, 47)
        current[idx1] = ch
        current[idx2] = ch
        val encoded = String(current)
        if (encoded.all { it == '.' }) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, encoded).apply()
        }
    }

    fun getHourOverride(queueId: String, date: KyivDate, hour: Int): SlotState? {
        val encoded = prefs.getString(slotOverridesKey(queueId, date), null) ?: return null
        val idx = (hour * 2).coerceIn(0, encoded.lastIndex)
        return charToSlotState(encoded[idx])
    }

    fun clearDaySlotOverrides(queueId: String, date: KyivDate) {
        prefs.edit().remove(slotOverridesKey(queueId, date)).apply()
    }

    fun applyManualSlotOverrides(dataset: ScheduleDataset): ScheduleDataset {
        val updatedDays = dataset.days.mapValues { (date, daySchedule) ->
            val encoded = prefs.getString(slotOverridesKey(dataset.queueId, date), null)
            if (encoded == null || encoded.length < 48) {
                daySchedule
            } else {
                var edited = false
                val mergedSlots = daySchedule.slots.mapIndexed { idx, original ->
                    val override = charToSlotState(encoded[idx])
                    if (override != null) {
                        edited = true
                        override
                    } else {
                        original
                    }
                }
                DaySchedule(
                    date = date,
                    slots = mergedSlots,
                    hasManualEdits = edited
                )
            }
        }
        return dataset.copy(days = updatedDays)
    }

    private fun charToSlotState(ch: Char): SlotState? = when (ch) {
        '1' -> SlotState.ON
        '2' -> SlotState.MAYBE
        '0' -> SlotState.OFF
        else -> null
    }

    companion object {
        private const val PREFS_NAME = "svitlo_31_prefs"
        private const val KEY_CACHED_JSON = "cached_schedule_json"
        private const val KEY_FETCHED_AT = "cached_fetched_at"
        private const val KEY_SELECTED_QUEUE = "selected_queue"
        private const val KEY_ADDRESS_LABEL = "address_label"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        private const val KEY_LAST_NOTIFIED_TRANSITION = "last_notified_transition"
        private const val KEY_MANUAL_STATUS = "manual_status_override"
        private const val KEY_MANUAL_STATUS_EXPIRES = "manual_status_expires_at"
        const val DEFAULT_QUEUE = "3.1"
        const val DEFAULT_ADDRESS = "с. Городище, вул. Польова"
    }
}
