package com.example.data

import android.content.Context
import com.example.model.ScheduleDataset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    private val enrichmentClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun fetchSchedule(forceNetwork: Boolean = true): Result<ScheduleDataset> =
        withContext(Dispatchers.IO) {
            val queueId = prefs.selectedQueue.trim()
            if (!forceNetwork) {
                getCachedDataset(queueId)?.let { return@withContext Result.success(it) }
            }

            val cacheBust = System.currentTimeMillis() / 60_000L

            coroutineScope {
                val oeDeferred = async { fetchOeFeed(queueId, cacheBust) }
                val sbDeferred = async { fetchSvitlobotFeed(queueId, cacheBust) }

                val oeOutcome = oeDeferred.await()
                val sbOutcome = sbDeferred.await()

                val oeDataset = oeOutcome.getOrNull()
                val sbDataset = sbOutcome.getOrNull()

                val combined = when {
                    oeDataset != null && sbDataset != null ->
                        PowerScheduleParser.mergeWithSvitlobot(oeDataset, sbDataset)
                    oeDataset != null -> oeDataset
                    sbDataset != null -> sbDataset
                    else -> null
                }

                if (combined != null) {
                    return@coroutineScope Result.success(prefs.applyManualSlotOverrides(combined))
                }

                getCachedDataset(queueId)?.let { cached ->
                    return@coroutineScope Result.success(cached)
                }

                val err = oeOutcome.exceptionOrNull() ?: sbOutcome.exceptionOrNull()
                Result.failure(err ?: IllegalStateException("Не вдалося отримати дані"))
            }
        }

    private fun fetchOeFeed(queueId: String, cacheBust: Long): Result<ScheduleDataset> {
        var lastError: Throwable? = null
        for (url in OE_FEED_URLS) {
            try {
                val body = executeGet(client, "$url?t=$cacheBust")
                val now = System.currentTimeMillis()
                val parsed = PowerScheduleParser.parseJson(
                    rawJson = body,
                    queueId = queueId,
                    fetchedAtMillis = now,
                    isFromCache = false
                )
                prefs.saveCachedJson(body, now)
                return Result.success(parsed)
            } catch (t: Throwable) {
                lastError = t
            }
        }
        return Result.failure(lastError ?: IllegalStateException("Не вдалося завантажити основний графік"))
    }

    private fun fetchSvitlobotFeed(queueId: String, cacheBust: Long): Result<ScheduleDataset> {
        var lastError: Throwable? = null
        val candidates = listOf(
            "$SVITLOBOT_BASE_URL/$queueId.json",
            "$SVITLOBOT_BASE_URL/${queueId.replace(".", "-")}.json"
        ).distinct()

        for (url in candidates) {
            try {
                val body = executeGet(enrichmentClient, "$url?t=$cacheBust")
                val now = System.currentTimeMillis()
                val parsed = PowerScheduleParser.parseSvitlobotJson(
                    rawJson = body,
                    queueId = queueId,
                    fetchedAtMillis = now,
                    isFromCache = false
                )
                prefs.saveCachedSvitlobotJson(queueId, body, now)
                return Result.success(parsed)
            } catch (t: Throwable) {
                lastError = t
            }
        }
        return Result.failure(lastError ?: IllegalStateException("Не знайдено додаткових даних"))
    }

    private fun executeGet(httpClient: OkHttpClient, url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache, no-store")
            .header("Accept", "application/json")
            .header("User-Agent", "Svitlo31Android/1.0")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            return response.body?.string()
                ?: throw IllegalStateException("Порожня відповідь сервера")
        }
    }

    fun hasFreshSvitlobotCache(
        queueId: String = prefs.selectedQueue,
        nowMillis: Long = System.currentTimeMillis(),
        maxAgeMillis: Long = 15L * 60L * 1000L
    ): Boolean {
        val (_, fetchedAt) = prefs.getCachedSvitlobotJson(queueId) ?: return false
        return fetchedAt > 0L && (nowMillis - fetchedAt) < maxAgeMillis
    }

    fun getCachedDataset(queueId: String = prefs.selectedQueue): ScheduleDataset? {
        val oeParsed = prefs.getCachedJson()?.let { (json, fetchedAt) ->
            runCatching {
                PowerScheduleParser.parseJson(
                    rawJson = json,
                    queueId = queueId,
                    fetchedAtMillis = fetchedAt,
                    isFromCache = true
                )
            }.getOrNull()
        }

        val sbParsed = prefs.getCachedSvitlobotJson(queueId)?.let { (json, fetchedAt) ->
            runCatching {
                PowerScheduleParser.parseSvitlobotJson(
                    rawJson = json,
                    queueId = queueId,
                    fetchedAtMillis = fetchedAt,
                    isFromCache = true
                )
            }.getOrNull()
        }

        val combined = when {
            oeParsed != null && sbParsed != null ->
                PowerScheduleParser.mergeWithSvitlobot(oeParsed, sbParsed)
            oeParsed != null -> oeParsed
            sbParsed != null -> sbParsed
            else -> return null
        }

        return prefs.applyManualSlotOverrides(combined)
    }

    companion object {
        private val OE_FEED_URLS = listOf(
            "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Ternopiloblenerho.json"
        )
        private const val SVITLOBOT_BASE_URL =
            "https://raw.githubusercontent.com/Svitlobot-official/timetables/main/ternopil"
        const val OFFICIAL_PORTAL_URL = "https://poweron.toe.com.ua/"
    }
}
