package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.model.DaySchedule
import com.example.model.KyivDate
import com.example.model.ManualStatusOverride
import com.example.model.SavedQueueProfile
import com.example.model.ScheduleDataset
import com.example.model.SlotState
import org.json.JSONArray
import org.json.JSONObject

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

    fun saveCachedSvitlobotJson(queueId: String, json: String, fetchedAtMillis: Long) {
        val q = queueId.trim()
        prefs.edit()
            .putString("cached_svitlobot_json_$q", json)
            .putLong("cached_svitlobot_at_$q", fetchedAtMillis)
            .apply()
    }

    fun getCachedSvitlobotJson(queueId: String): Pair<String, Long>? {
        val q = queueId.trim()
        val json = prefs.getString("cached_svitlobot_json_$q", null) ?: return null
        val fetchedAt = prefs.getLong("cached_svitlobot_at_$q", 0L)
        return json to fetchedAt
    }

    fun saveCachedToeJson(queueId: String, json: String, fetchedAtMillis: Long) {
        val q = queueId.trim()
        prefs.edit()
            .putString("cached_toe_json_$q", json)
            .putLong("cached_toe_at_$q", fetchedAtMillis)
            .apply()
    }

    fun getCachedToeJson(queueId: String): Pair<String, Long>? {
        val q = queueId.trim()
        val json = prefs.getString("cached_toe_json_$q", null) ?: return null
        val fetchedAt = prefs.getLong("cached_toe_at_$q", 0L)
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

    private fun slotStateToChar(state: SlotState?): Char = when (state) {
        SlotState.ON -> '1'
        SlotState.MAYBE -> '2'
        SlotState.OFF -> '0'
        null -> '.'
    }

    fun setSlotOverride(queueId: String, date: KyivDate, slotIdx: Int, state: SlotState?) {
        val key = slotOverridesKey(queueId, date)
        val current = prefs.getString(key, null)?.padEnd(48, '.')?.toCharArray()
            ?: CharArray(48) { '.' }
        val safeIdx = slotIdx.coerceIn(0, 47)
        current[safeIdx] = slotStateToChar(state)
        val encoded = String(current)
        if (encoded.all { it == '.' }) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, encoded).apply()
        }
    }

    fun getSlotOverride(queueId: String, date: KyivDate, slotIdx: Int): SlotState? {
        val encoded = prefs.getString(slotOverridesKey(queueId, date), null) ?: return null
        val safeIdx = slotIdx.coerceIn(0, encoded.lastIndex)
        return charToSlotState(encoded[safeIdx])
    }

    fun setHourOverride(queueId: String, date: KyivDate, hour: Int, state: SlotState?) {
        val key = slotOverridesKey(queueId, date)
        val current = prefs.getString(key, null)?.padEnd(48, '.')?.toCharArray()
            ?: CharArray(48) { '.' }
        val ch = slotStateToChar(state)
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

    fun setHourSlotsOverride(
        queueId: String,
        date: KyivDate,
        hour: Int,
        firstState: SlotState,
        secondState: SlotState
    ) {
        val key = slotOverridesKey(queueId, date)
        val current = prefs.getString(key, null)?.padEnd(48, '.')?.toCharArray()
            ?: CharArray(48) { '.' }
        val idx1 = (hour * 2).coerceIn(0, 47)
        val idx2 = (hour * 2 + 1).coerceIn(0, 47)
        current[idx1] = slotStateToChar(firstState)
        current[idx2] = slotStateToChar(secondState)
        prefs.edit().putString(key, String(current)).apply()
    }

    fun getHourOverride(queueId: String, date: KyivDate, hour: Int): SlotState? {
        val encoded = prefs.getString(slotOverridesKey(queueId, date), null) ?: return null
        val idx1 = (hour * 2).coerceIn(0, encoded.lastIndex)
        val idx2 = (hour * 2 + 1).coerceIn(0, encoded.lastIndex)
        return charToSlotState(encoded[idx1]) ?: charToSlotState(encoded[idx2])
    }

    fun hasHourManualOverride(queueId: String, date: KyivDate, hour: Int): Boolean {
        val encoded = prefs.getString(slotOverridesKey(queueId, date), null) ?: return false
        val idx1 = (hour * 2).coerceIn(0, encoded.lastIndex)
        val idx2 = (hour * 2 + 1).coerceIn(0, encoded.lastIndex)
        return charToSlotState(encoded[idx1]) != null || charToSlotState(encoded[idx2]) != null
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

    fun getSavedProfiles(): List<SavedQueueProfile> {
        val raw = prefs.getString(KEY_SAVED_PROFILES, null)
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val id = obj.optString("id").trim()
                        val queueId = obj.optString("queueId").trim()
                        val label = obj.optString("label", "").trim()
                        if (id.isNotEmpty() && queueId.isNotEmpty()) {
                            add(SavedQueueProfile(id = id, queueId = queueId, label = label))
                        }
                    }
                }
            }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return parsed
        }
        val initial = listOf(
            SavedQueueProfile(
                id = DEFAULT_PROFILE_ID,
                queueId = selectedQueue,
                label = addressLabel
            )
        )
        persistProfiles(initial)
        prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, DEFAULT_PROFILE_ID).apply()
        return initial
    }

    fun getActiveProfileId(): String {
        val profiles = getSavedProfiles()
        val stored = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
        return profiles.firstOrNull { it.id == stored }?.id ?: profiles.first().id
    }

    fun getActiveProfile(): SavedQueueProfile {
        val profiles = getSavedProfiles()
        val activeId = getActiveProfileId()
        return profiles.firstOrNull { it.id == activeId } ?: profiles.first()
    }

    fun selectProfile(profileId: String): SavedQueueProfile? {
        val profiles = getSavedProfiles()
        val target = profiles.firstOrNull { it.id == profileId } ?: return null
        prefs.edit()
            .putString(KEY_ACTIVE_PROFILE_ID, target.id)
            .putString(KEY_SELECTED_QUEUE, target.queueId)
            .putString(KEY_ADDRESS_LABEL, target.label)
            .apply()
        return target
    }

    fun saveProfile(profileId: String?, queueId: String, label: String): SavedQueueProfile {
        val profiles = getSavedProfiles().toMutableList()
        val cleanQueue = queueId.trim()
        val cleanLabel = label.trim()
        val existingIdx = if (profileId != null) profiles.indexOfFirst { it.id == profileId } else -1

        val saved = if (existingIdx >= 0) {
            val updated = profiles[existingIdx].copy(queueId = cleanQueue, label = cleanLabel)
            profiles[existingIdx] = updated
            updated
        } else {
            val newId = "p_${System.currentTimeMillis()}_${profiles.size + 1}"
            val created = SavedQueueProfile(id = newId, queueId = cleanQueue, label = cleanLabel)
            profiles.add(created)
            created
        }

        persistProfiles(profiles)
        prefs.edit()
            .putString(KEY_ACTIVE_PROFILE_ID, saved.id)
            .putString(KEY_SELECTED_QUEUE, saved.queueId)
            .putString(KEY_ADDRESS_LABEL, saved.label)
            .apply()
        return saved
    }

    fun deleteProfile(profileId: String): SavedQueueProfile? {
        val profiles = getSavedProfiles().toMutableList()
        if (profiles.size <= 1) return null
        val removed = profiles.removeAll { it.id == profileId }
        if (!removed || profiles.isEmpty()) return null

        persistProfiles(profiles)
        val currentActiveId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
        val nextActive = profiles.firstOrNull { it.id == currentActiveId } ?: profiles.first()
        prefs.edit()
            .putString(KEY_ACTIVE_PROFILE_ID, nextActive.id)
            .putString(KEY_SELECTED_QUEUE, nextActive.queueId)
            .putString(KEY_ADDRESS_LABEL, nextActive.label)
            .apply()
        return nextActive
    }

    private fun persistProfiles(profiles: List<SavedQueueProfile>) {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("queueId", p.queueId)
                    put("label", p.label)
                }
            )
        }
        prefs.edit().putString(KEY_SAVED_PROFILES, arr.toString()).apply()
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
        private const val KEY_SAVED_PROFILES = "saved_queue_profiles_json"
        private const val KEY_ACTIVE_PROFILE_ID = "active_queue_profile_id"
        const val DEFAULT_PROFILE_ID = "profile_default"
        const val DEFAULT_QUEUE = "3.1"
        const val DEFAULT_ADDRESS = "с. Городище, вул. Польова"
    }
}
