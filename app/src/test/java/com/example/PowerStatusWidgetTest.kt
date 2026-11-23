package com.example

import android.content.Context
import android.view.View
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.data.KyivTime
import com.example.model.DaySchedule
import com.example.model.ManualStatusOverride
import com.example.model.ScheduleDataset
import com.example.model.SlotState
import com.example.widget.PowerStatusWidgetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PowerStatusWidgetTest {

    @Test
    fun `widget displays power on status and countdown chronometer`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nowMillis = 1795420800_000L
        val today = KyivTime.todayDate(nowMillis)
        val slots = MutableList(48) { SlotState.ON }
        for (i in 28..34) slots[i] = SlotState.OFF // 14:00 - 17:30

        val dataset = ScheduleDataset(
            queueId = "3.1",
            days = mapOf(today to DaySchedule(today, slots)),
            lastUpdatedLabel = "23.11 10:00",
            fetchedAtMillis = nowMillis,
            isFromCache = false
        )
        val noonMillis = KyivTime.epochMillisFor(today, 12 * 60)

        val remoteViews = PowerStatusWidgetProvider.buildRemoteViews(
            context = context,
            dataset = dataset,
            selectedQueue = "3.1",
            nowMillis = noonMillis,
            elapsedRealtimeMillis = 500_000L
        )
        val host = FrameLayout(context)
        val inflated = remoteViews.apply(context, host)

        val queueBadge = inflated.findViewById<TextView>(R.id.widget_queue_badge)
        val statusText = inflated.findViewById<TextView>(R.id.widget_status_text)
        val chronometer = inflated.findViewById<Chronometer>(R.id.widget_chronometer)
        val nextTimeText = inflated.findViewById<TextView>(R.id.widget_next_time_text)

        assertEquals("Черга 3.1", queueBadge.text.toString())
        assertEquals("Є СВІТЛО", statusText.text.toString())
        assertEquals(View.VISIBLE, chronometer.visibility)
        assertTrue(chronometer.isCountDown)
        // 2 hours = 7,200,000 ms + 500,000 ms base
        assertEquals(500_000L + 7_200_000L, chronometer.base)
        assertEquals("Вимкнення о 14:00", nextTimeText.text.toString())
    }

    @Test
    fun `widget displays power off status during outage`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nowMillis = 1795420800_000L
        val today = KyivTime.todayDate(nowMillis)
        val slots = MutableList(48) { SlotState.ON }
        for (i in 28..34) slots[i] = SlotState.OFF // 14:00 - 17:30

        val dataset = ScheduleDataset(
            queueId = "2.2",
            days = mapOf(today to DaySchedule(today, slots)),
            lastUpdatedLabel = "23.11 10:00",
            fetchedAtMillis = nowMillis,
            isFromCache = false
        )
        val at1500 = KyivTime.epochMillisFor(today, 15 * 60)

        val remoteViews = PowerStatusWidgetProvider.buildRemoteViews(
            context = context,
            dataset = dataset,
            selectedQueue = "2.2",
            nowMillis = at1500,
            elapsedRealtimeMillis = 100_000L
        )
        val host = FrameLayout(context)
        val inflated = remoteViews.apply(context, host)

        assertEquals(
            "Черга 2.2",
            inflated.findViewById<TextView>(R.id.widget_queue_badge).text.toString()
        )
        assertEquals(
            "НЕМА СВІТЛА",
            inflated.findViewById<TextView>(R.id.widget_status_text).text.toString()
        )
        assertEquals(
            "Увімкнення о 17:30",
            inflated.findViewById<TextView>(R.id.widget_next_time_text).text.toString()
        )
    }

    @Test
    fun `widget displays no outages state when all slots are on`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nowMillis = 1795420800_000L
        val today = KyivTime.todayDate(nowMillis)
        val slots = List(48) { SlotState.ON }

        val dataset = ScheduleDataset(
            queueId = "3.1",
            days = mapOf(today to DaySchedule(today, slots)),
            lastUpdatedLabel = "23.11 10:00",
            fetchedAtMillis = nowMillis,
            isFromCache = false
        )
        val noonMillis = KyivTime.epochMillisFor(today, 12 * 60)

        val remoteViews = PowerStatusWidgetProvider.buildRemoteViews(
            context = context,
            dataset = dataset,
            selectedQueue = "3.1",
            nowMillis = noonMillis
        )
        val host = FrameLayout(context)
        val inflated = remoteViews.apply(context, host)

        assertEquals(
            "Є СВІТЛО",
            inflated.findViewById<TextView>(R.id.widget_status_text).text.toString()
        )
        assertEquals(
            View.GONE,
            inflated.findViewById<Chronometer>(R.id.widget_chronometer).visibility
        )
        assertEquals(
            "Без відключень",
            inflated.findViewById<TextView>(R.id.widget_static_subtext).text.toString()
        )
    }

    @Test
    fun `widget displays maybe period and manual override`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nowMillis = 1795420800_000L
        val today = KyivTime.todayDate(nowMillis)
        val slots = MutableList(48) { SlotState.ON }
        for (i in 20..23) slots[i] = SlotState.MAYBE // 10:00 - 12:00

        val dataset = ScheduleDataset(
            queueId = "3.1",
            days = mapOf(today to DaySchedule(today, slots)),
            lastUpdatedLabel = "23.11 10:00",
            fetchedAtMillis = nowMillis,
            isFromCache = false
        )
        val at1030 = KyivTime.epochMillisFor(today, 10 * 60 + 30)

        val remoteViewsMaybe = PowerStatusWidgetProvider.buildRemoteViews(
            context = context,
            dataset = dataset,
            selectedQueue = "3.1",
            nowMillis = at1030
        )
        val inflatedMaybe = remoteViewsMaybe.apply(context, FrameLayout(context))
        assertEquals(
            "МОЖЛИВЕ ВІДКЛЮЧЕННЯ",
            inflatedMaybe.findViewById<TextView>(R.id.widget_status_text).text.toString()
        )
        assertEquals(
            "Увімкнення о 12:00",
            inflatedMaybe.findViewById<TextView>(R.id.widget_next_time_text).text.toString()
        )

        val at1200 = KyivTime.epochMillisFor(today, 12 * 60)
        val remoteViewsManual = PowerStatusWidgetProvider.buildRemoteViews(
            context = context,
            dataset = dataset,
            selectedQueue = "3.1",
            nowMillis = at1030,
            manualOverride = ManualStatusOverride(SlotState.ON, at1200)
        )
        val inflatedManual = remoteViewsManual.apply(context, FrameLayout(context))
        assertEquals(
            "Є СВІТЛО",
            inflatedManual.findViewById<TextView>(R.id.widget_status_text).text.toString()
        )
        val manualSubtext = inflatedManual.findViewById<TextView>(R.id.widget_next_time_text).text.toString()
        assertTrue(
            manualSubtext.contains("вручну", ignoreCase = true)
        )
    }
}
