package com.example.data

import android.content.Context
import com.example.model.ScheduleDataset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ScheduleRepository(
    context: Context,
    private val prefs: SchedulePreferences = SchedulePreferences(context)
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun fetchSchedule(forceNetwork: Boolean = true): Result<ScheduleDataset> =
        withContext(Dispatchers.IO) {
            if (!forceNetwork) {
                getCachedDataset()?.let { return@withContext Result.success(it) }
            }

            var lastError: Throwable? = null
            for (url in FEED_URLS) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("Cache-Control", "no-cache")
                        .header("Accept", "application/json")
                        .header("User-Agent", "Svitlo31Android/1.0")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IllegalStateException("HTTP ${response.code}")
                        }
                        val body = response.body?.string()
                            ?: throw IllegalStateException("Порожня відповідь сервера")
                        val now = System.currentTimeMillis()
                        val parsed = PowerScheduleParser.parseJson(
                            rawJson = body,
                            queueId = prefs.selectedQueue,
                            fetchedAtMillis = now,
                            isFromCache = false
                        )
                        prefs.saveCachedJson(body, now)
                        return@withContext Result.success(parsed)
                    }
                } catch (t: Throwable) {
                    lastError = t
                }
            }

            // Fallback to cached schedule if network failed
            getCachedDataset()?.let { cached ->
                return@withContext Result.success(cached)
            }

            Result.failure(lastError ?: IllegalStateException("Не вдалося отримати дані"))
        }

    fun getCachedDataset(queueId: String = prefs.selectedQueue): ScheduleDataset? {
        val (json, fetchedAt) = prefs.getCachedJson() ?: return null
        return try {
            PowerScheduleParser.parseJson(
                rawJson = json,
                queueId = queueId,
                fetchedAtMillis = fetchedAt,
                isFromCache = true
            )
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        // Structured real-time mirrors scraped from https://poweron.toe.com.ua/
        private val FEED_URLS = listOf(
            "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Ternopiloblenerho.json",
            "https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/ternopiloblenergo.json"
        )
        const val OFFICIAL_PORTAL_URL = "https://poweron.toe.com.ua/"
    }
}
