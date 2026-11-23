package com.example.data

import android.content.Context
import android.content.SharedPreferences

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

    companion object {
        private const val PREFS_NAME = "svitlo_31_prefs"
        private const val KEY_CACHED_JSON = "cached_schedule_json"
        private const val KEY_FETCHED_AT = "cached_fetched_at"
        private const val KEY_SELECTED_QUEUE = "selected_queue"
        private const val KEY_ADDRESS_LABEL = "address_label"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        private const val KEY_LAST_NOTIFIED_TRANSITION = "last_notified_transition"
        const val DEFAULT_QUEUE = "3.1"
        const val DEFAULT_ADDRESS = "с. Городище, вул. Польова"
    }
}
