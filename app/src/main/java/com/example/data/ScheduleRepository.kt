package com.example.data

import android.content.Context
import android.util.Base64
import com.example.model.ScheduleDataset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
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
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun fetchSchedule(
        queueId: String = prefs.selectedQueue,
        forceNetwork: Boolean = true
    ): Result<ScheduleDataset> =
        withContext(Dispatchers.IO) {
            val targetQueue = queueId.trim()
            if (!forceNetwork) {
                getCachedDataset(targetQueue)?.let { return@withContext Result.success(it) }
            }

            val cacheBust = System.currentTimeMillis() / 60_000L

            coroutineScope {
                val toeDeferred = async { fetchToeOfficialFeed(targetQueue) }
                val oeDeferred = async { fetchOeFeed(targetQueue, cacheBust) }
                val sbDeferred = async { fetchSvitlobotFeed(targetQueue, cacheBust) }

                val toeOutcome = toeDeferred.await()
                val oeOutcome = oeDeferred.await()
                val sbOutcome = sbDeferred.await()

                val toeDataset = toeOutcome.getOrNull()
                val oeDataset = oeOutcome.getOrNull()
                val sbDataset = sbOutcome.getOrNull()

                val oeAndSb = when {
                    oeDataset != null && sbDataset != null ->
                        PowerScheduleParser.mergeWithSvitlobot(oeDataset, sbDataset)
                    oeDataset != null -> oeDataset
                    sbDataset != null -> sbDataset
                    else -> null
                }

                val combined = when {
                    toeDataset != null -> PowerScheduleParser.mergeWithToeOfficial(toeDataset, oeAndSb)
                    else -> oeAndSb
                }

                if (combined != null) {
                    return@coroutineScope Result.success(prefs.applyManualSlotOverrides(combined))
                }

                getCachedDataset(targetQueue)?.let { cached ->
                    return@coroutineScope Result.success(cached)
                }

                val err = toeOutcome.exceptionOrNull()
                    ?: oeOutcome.exceptionOrNull()
                    ?: sbOutcome.exceptionOrNull()
                Result.failure(err ?: IllegalStateException("Не вдалося отримати дані"))
            }
        }

    private fun fetchToeOfficialFeed(queueId: String): Result<ScheduleDataset> {
        val cityId = TOE_QUEUE_CITY_MAP[queueId.trim()]
            ?: return Result.failure(IllegalArgumentException("Невідома черга $queueId"))

        return try {
            val now = System.currentTimeMillis()
            val today = KyivTime.todayDate(now)
            val yesterday = KyivTime.previousDay(today)
            val afterTomorrow = KyivTime.nextDay(KyivTime.nextDay(today))

            val afterParam = String.format(
                Locale.US,
                "%04d-%02d-%02dT12:00:00+00:00",
                yesterday.year,
                yesterday.month,
                yesterday.day
            )
            val beforeParam = String.format(
                Locale.US,
                "%04d-%02d-%02dT00:00:00+00:00",
                afterTomorrow.year,
                afterTomorrow.month,
                afterTomorrow.day
            )

            val enc = { s: String -> URLEncoder.encode(s, StandardCharsets.UTF_8.name()) }
            val url = "$TOE_API_BASE_URL?before=${enc(beforeParam)}&after=${enc(afterParam)}&time=${enc(cityId)}"
            val debugKey = Base64.encodeToString(cityId.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache, no-store")
                .header("Accept", "application/json")
                .header("Origin", OFFICIAL_ORIGIN)
                .header("Referer", "$OFFICIAL_ORIGIN/")
                .header("X-debug-key", debugKey)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val body = enrichmentClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}")
                }
                response.body?.string() ?: throw IllegalStateException("Порожня відповідь сервера")
            }

            val parsedNow = System.currentTimeMillis()
            val parsed = PowerScheduleParser.parseToeOfficialJson(
                rawJson = body,
                queueId = queueId,
                fetchedAtMillis = parsedNow,
                isFromCache = false
            )
            prefs.saveCachedToeJson(queueId, body, parsedNow)
            Result.success(parsed)
        } catch (t: Throwable) {
            Result.failure(t)
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
        val toeFresh = prefs.getCachedToeJson(queueId)?.second?.let {
            it > 0L && (nowMillis - it) < maxAgeMillis
        } == true
        if (toeFresh) return true

        val (_, fetchedAt) = prefs.getCachedSvitlobotJson(queueId) ?: return false
        return fetchedAt > 0L && (nowMillis - fetchedAt) < maxAgeMillis
    }

    fun getCachedDataset(queueId: String = prefs.selectedQueue): ScheduleDataset? {
        val toeParsed = prefs.getCachedToeJson(queueId)?.let { (json, fetchedAt) ->
            runCatching {
                PowerScheduleParser.parseToeOfficialJson(
                    rawJson = json,
                    queueId = queueId,
                    fetchedAtMillis = fetchedAt,
                    isFromCache = true
                )
            }.getOrNull()
        }

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

        val oeAndSb = when {
            oeParsed != null && sbParsed != null ->
                PowerScheduleParser.mergeWithSvitlobot(oeParsed, sbParsed)
            oeParsed != null -> oeParsed
            sbParsed != null -> sbParsed
            else -> null
        }

        val combined = when {
            toeParsed != null -> PowerScheduleParser.mergeWithToeOfficial(toeParsed, oeAndSb)
            oeAndSb != null -> oeAndSb
            else -> return null
        }

        return prefs.applyManualSlotOverrides(combined)
    }

    companion object {
        private const val OFFICIAL_ORIGIN = "https://poweron.toe.com.ua"
        private const val TOE_API_BASE_URL = "https://api-poweron.toe.com.ua/api/a_gpv_g"
        private val TOE_QUEUE_CITY_MAP = mapOf(
            "1.1" to "192",
            "1.2" to "1",
            "2.1" to "604",
            "2.2" to "383",
            "3.1" to "266",
            "3.2" to "95",
            "4.1" to "22042",
            "4.2" to "1",
            "5.1" to "20982",
            "5.2" to "1021",
            "6.1" to "919",
            "6.2" to "329"
        )
        private val OE_FEED_URLS = listOf(
            "https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Ternopiloblenerho.json"
        )
        private const val SVITLOBOT_BASE_URL =
            "https://raw.githubusercontent.com/Svitlobot-official/timetables/main/ternopil"
        const val OFFICIAL_PORTAL_URL = "https://poweron.toe.com.ua/"
    }
}
