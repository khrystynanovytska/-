package com.example.data

import com.example.model.KyivDate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object KyivTime {
    val TIME_ZONE: TimeZone = TimeZone.getTimeZone("Europe/Kyiv")

    fun calendarAt(epochMillis: Long): Calendar =
        Calendar.getInstance(TIME_ZONE).apply {
            timeInMillis = epochMillis
        }

    fun dateFromEpochSeconds(epochSeconds: Long): KyivDate {
        val cal = calendarAt(epochSeconds * 1000L)
        return KyivDate(
            year = cal.get(Calendar.YEAR),
            month = cal.get(Calendar.MONTH) + 1,
            day = cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun todayDate(nowMillis: Long = System.currentTimeMillis()): KyivDate {
        val cal = calendarAt(nowMillis)
        return KyivDate(
            year = cal.get(Calendar.YEAR),
            month = cal.get(Calendar.MONTH) + 1,
            day = cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun nextDay(date: KyivDate): KyivDate {
        val cal = Calendar.getInstance(TIME_ZONE).apply {
            set(Calendar.YEAR, date.year)
            set(Calendar.MONTH, date.month - 1)
            set(Calendar.DAY_OF_MONTH, date.day)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, 1)
        }
        return KyivDate(
            year = cal.get(Calendar.YEAR),
            month = cal.get(Calendar.MONTH) + 1,
            day = cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun previousDay(date: KyivDate): KyivDate {
        val cal = Calendar.getInstance(TIME_ZONE).apply {
            set(Calendar.YEAR, date.year)
            set(Calendar.MONTH, date.month - 1)
            set(Calendar.DAY_OF_MONTH, date.day)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, -1)
        }
        return KyivDate(
            year = cal.get(Calendar.YEAR),
            month = cal.get(Calendar.MONTH) + 1,
            day = cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun epochMillisFor(date: KyivDate, minuteOfDay: Int): Long {
        val cal = Calendar.getInstance(TIME_ZONE).apply {
            set(Calendar.YEAR, date.year)
            set(Calendar.MONTH, date.month - 1)
            set(Calendar.DAY_OF_MONTH, date.day)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, minuteOfDay)
        }
        return cal.timeInMillis
    }

    fun currentMinuteOfDay(nowMillis: Long = System.currentTimeMillis()): Int {
        val cal = calendarAt(nowMillis)
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    fun currentSecondInMinute(nowMillis: Long = System.currentTimeMillis()): Int {
        val cal = calendarAt(nowMillis)
        return cal.get(Calendar.SECOND)
    }

    fun formatHHmm(epochMillis: Long): String {
        val fmt = SimpleDateFormat("HH:mm", Locale.US).apply {
            timeZone = TIME_ZONE
        }
        return fmt.format(Date(epochMillis))
    }

    fun formatUpdatedLabel(epochMillis: Long): String {
        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.US).apply {
            timeZone = TIME_ZONE
        }
        return fmt.format(Date(epochMillis))
    }
}
