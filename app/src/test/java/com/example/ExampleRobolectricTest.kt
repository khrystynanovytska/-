package com.example

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.KyivTime
import com.example.data.PowerScheduleParser
import com.example.model.DaySchedule
import com.example.model.KyivDate
import com.example.model.SavedQueueProfile
import com.example.model.ScheduleDataset
import com.example.model.SlotState
import com.example.ui.MainScreen
import com.example.ui.ScheduleUiState
import com.example.ui.theme.SvitloTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Світло 3.1", appName)
    }

    @Test
    fun `main screen displays power on status, timer and intervals`() {
        val nowMillis = 1795420800_000L // arbitrary epoch
        val today = KyivTime.todayDate(nowMillis)
        val slots = MutableList(48) { SlotState.ON }
        // Outage from 14:00 to 17:30 (slots 28..34)
        for (i in 28..34) slots[i] = SlotState.OFF

        val daySchedule = DaySchedule(date = today, slots = slots)
        val dataset = ScheduleDataset(
            days = mapOf(today to daySchedule),
            lastUpdatedLabel = "23.11 10:00",
            fetchedAtMillis = nowMillis,
            isFromCache = false
        )
        // Check at 12:00 Kyiv time (power is ON)
        val noonMillis = KyivTime.epochMillisFor(today, 12 * 60)
        val snapshot = PowerScheduleParser.computeSnapshot(dataset, noonMillis)

        composeTestRule.setContent {
            SvitloTheme {
                MainScreen(
                    uiState = ScheduleUiState(
                        isLoading = false,
                        dataset = dataset,
                        snapshot = snapshot,
                        nowMillis = noonMillis
                    ),
                    snackbarHostState = SnackbarHostState(),
                    todayDateLabel = today.formatUkrainianShort(),
                    tomorrowDateLabel = KyivTime.nextDay(today).formatUkrainianShort(),
                    onRefresh = {},
                    onSelectDay = {},
                    onToggleNotifications = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("power_status_headline")
            .assertIsDisplayed()
            .assertTextContains("Є СВІТЛО")
        composeTestRule.onNodeWithTag("countdown_timer_text")
            .assertIsDisplayed()
            .assertTextContains("02:00:00")
        composeTestRule.onNodeWithTag("power_on_intervals_text")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("00:00 – 14:00, 17:30 – 24:00")
        composeTestRule.onNodeWithTag("power_off_intervals_text")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("14:00 – 17:30")
    }

    @Test
    fun `main screen displays power off status during outage`() {
        val nowMillis = 1795420800_000L
        val today = KyivTime.todayDate(nowMillis)
        val slots = MutableList(48) { SlotState.ON }
        for (i in 28..34) slots[i] = SlotState.OFF // 14:00 - 17:30

        val daySchedule = DaySchedule(date = today, slots = slots)
        val dataset = ScheduleDataset(
            days = mapOf(today to daySchedule),
            lastUpdatedLabel = "23.11 10:00",
            fetchedAtMillis = nowMillis,
            isFromCache = false
        )
        val at1500 = KyivTime.epochMillisFor(today, 15 * 60)
        val snapshot = PowerScheduleParser.computeSnapshot(dataset, at1500)

        composeTestRule.setContent {
            SvitloTheme {
                MainScreen(
                    uiState = ScheduleUiState(
                        isLoading = false,
                        dataset = dataset,
                        snapshot = snapshot,
                        nowMillis = at1500
                    ),
                    snackbarHostState = SnackbarHostState(),
                    todayDateLabel = today.formatUkrainianShort(),
                    tomorrowDateLabel = KyivTime.nextDay(today).formatUkrainianShort(),
                    onRefresh = {},
                    onSelectDay = {},
                    onToggleNotifications = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("power_status_headline")
            .assertIsDisplayed()
            .assertTextContains("НЕМА СВІТЛА")
        composeTestRule.onNodeWithTag("countdown_timer_text")
            .assertIsDisplayed()
            .assertTextContains("02:30:00")
    }

    @Test
    fun `main screen displays maybe period, supports manual status and hour editing`() {
        val nowMillis = 1795420800_000L
        val today = KyivTime.todayDate(nowMillis)
        val slots = MutableList(48) { SlotState.ON }
        for (i in 20..23) slots[i] = SlotState.MAYBE // 10:00 - 12:00

        val daySchedule = DaySchedule(date = today, slots = slots)
        val dataset = ScheduleDataset(
            days = mapOf(today to daySchedule),
            lastUpdatedLabel = "23.11 10:00",
            fetchedAtMillis = nowMillis,
            isFromCache = false
        )
        val at1030 = KyivTime.epochMillisFor(today, 10 * 60 + 30)
        val snapshot = PowerScheduleParser.computeSnapshot(dataset, at1030)

        var manualStatusSelected: SlotState? = null
        var editedTarget: Triple<KyivDate, Int, SlotState?>? = null

        composeTestRule.setContent {
            SvitloTheme {
                MainScreen(
                    uiState = ScheduleUiState(
                        isLoading = false,
                        dataset = dataset,
                        snapshot = snapshot,
                        nowMillis = at1030
                    ),
                    snackbarHostState = SnackbarHostState(),
                    todayDateLabel = today.formatUkrainianShort(),
                    tomorrowDateLabel = KyivTime.nextDay(today).formatUkrainianShort(),
                    onRefresh = {},
                    onSelectDay = {},
                    onToggleNotifications = {},
                    onSetManualLiveStatus = { manualStatusSelected = it },
                    onEditHourState = { d, h, s -> editedTarget = Triple(d, h, s) }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("power_status_headline")
            .assertIsDisplayed()
            .assertTextContains("МОЖЛИВЕ ВІДКЛЮЧЕННЯ")

        composeTestRule.onNodeWithTag("manual_on_button")
            .performScrollTo()
            .performClick()
        assertEquals(SlotState.ON, manualStatusSelected)

        composeTestRule.onNodeWithTag("hour_cell_14")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("edit_hour_maybe").performClick()
        assertEquals(Triple(today, 14, SlotState.MAYBE), editedTarget)

        composeTestRule.onNodeWithTag("power_maybe_intervals_text")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("10:00 – 12:00")
    }

    @Test
    fun `main screen supports 30m OFF plus 30m MAYBE display and independent half-hour editing`() {
        val nowMillis = 1795420800_000L
        val today = KyivTime.todayDate(nowMillis)
        val slots = MutableList(48) { SlotState.ON }
        // 15:00-15:30 OFF (slot 30), 15:30-16:00 MAYBE (slot 31)
        slots[30] = SlotState.OFF
        slots[31] = SlotState.MAYBE

        val daySchedule = DaySchedule(date = today, slots = slots)
        val dataset = ScheduleDataset(
            days = mapOf(today to daySchedule),
            lastUpdatedLabel = "25.11 07:02",
            fetchedAtMillis = nowMillis,
            isFromCache = false
        )
        val at1510 = KyivTime.epochMillisFor(today, 15 * 60 + 10)
        val snapshot = PowerScheduleParser.computeSnapshot(dataset, at1510)

        val editedSlots = mutableListOf<Pair<Int, SlotState?>>()

        composeTestRule.setContent {
            SvitloTheme {
                MainScreen(
                    uiState = ScheduleUiState(
                        isLoading = false,
                        dataset = dataset,
                        snapshot = snapshot,
                        nowMillis = at1510
                    ),
                    snackbarHostState = SnackbarHostState(),
                    todayDateLabel = today.formatUkrainianShort(),
                    tomorrowDateLabel = KyivTime.nextDay(today).formatUkrainianShort(),
                    onRefresh = {},
                    onSelectDay = {},
                    onToggleNotifications = {},
                    onEditSlotState = { _, slotIdx, state -> editedSlots.add(slotIdx to state) }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("power_status_headline")
            .assertIsDisplayed()
            .assertTextContains("НЕМА СВІТЛА")
        composeTestRule.onNodeWithTag("countdown_timer_text")
            .assertIsDisplayed()
            .assertTextContains("00:20:00")
        composeTestRule.onNodeWithTag("power_maybe_intervals_text")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("15:30 – 16:00")
        composeTestRule.onNodeWithTag("power_off_intervals_text")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("15:00 – 15:30")

        composeTestRule.onNodeWithTag("hour_cell_15")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("slot_first_off")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("slot_second_maybe")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(30 to SlotState.OFF, 31 to SlotState.MAYBE), editedSlots)

        composeTestRule.onNodeWithTag("edit_hour_done").performClick()
    }

    @Test
    fun `main screen switches between saved labeled queues and adds a new labeled place`() {
        val nowMillis = 1795420800_000L
        val today = KyivTime.todayDate(nowMillis)
        val slots = List(48) { SlotState.ON }
        val daySchedule = DaySchedule(date = today, slots = slots)
        val dataset = ScheduleDataset(
            queueId = "3.1",
            days = mapOf(today to daySchedule),
            lastUpdatedLabel = "25.11 07:02",
            fetchedAtMillis = nowMillis,
            isFromCache = false
        )
        val snapshot = PowerScheduleParser.computeSnapshot(dataset, nowMillis)
        val profiles = listOf(
            SavedQueueProfile("p1", "3.1", "Дім"),
            SavedQueueProfile("p2", "1.2", "Робота")
        )

        var switchedProfileId: String? = null
        var upserted: Triple<String?, String, String>? = null

        composeTestRule.setContent {
            SvitloTheme {
                MainScreen(
                    uiState = ScheduleUiState(
                        isLoading = false,
                        selectedQueue = "3.1",
                        addressLabel = "Дім",
                        savedProfiles = profiles,
                        activeProfileId = "p1",
                        dataset = dataset,
                        snapshot = snapshot,
                        nowMillis = nowMillis
                    ),
                    snackbarHostState = SnackbarHostState(),
                    todayDateLabel = today.formatUkrainianShort(),
                    tomorrowDateLabel = KyivTime.nextDay(today).formatUkrainianShort(),
                    onRefresh = {},
                    onSelectDay = {},
                    onToggleNotifications = {},
                    onSwitchProfile = { switchedProfileId = it },
                    onUpsertProfile = { id, q, label -> upserted = Triple(id, q, label) }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("saved_profile_chip_p2")
            .assertIsDisplayed()
            .performClick()
        assertEquals("p2", switchedProfileId)

        composeTestRule.onNodeWithTag("add_queue_profile_button")
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("queue_option_5.2")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag("preset_label_Батьки")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag("save_queue_button")
            .performScrollTo()
            .performClick()

        assertEquals(Triple(null, "5.2", "Батьки"), upserted)
    }

    @Test
    fun `main screen supports 1-tap red-yellow and yellow-red 30-30 split presets`() {
        val nowMillis = 1795420800_000L
        val today = KyivTime.todayDate(nowMillis)
        val slots = List(48) { SlotState.ON }
        val daySchedule = DaySchedule(date = today, slots = slots)
        val dataset = ScheduleDataset(
            queueId = "1.2",
            days = mapOf(today to daySchedule),
            lastUpdatedLabel = "25.11 07:02",
            fetchedAtMillis = nowMillis,
            isFromCache = false
        )
        val snapshot = PowerScheduleParser.computeSnapshot(dataset, nowMillis)
        val splitEdits = mutableListOf<Triple<Int, SlotState, SlotState>>()

        composeTestRule.setContent {
            SvitloTheme {
                MainScreen(
                    uiState = ScheduleUiState(
                        isLoading = false,
                        selectedQueue = "1.2",
                        dataset = dataset,
                        snapshot = snapshot,
                        nowMillis = nowMillis
                    ),
                    snackbarHostState = SnackbarHostState(),
                    todayDateLabel = today.formatUkrainianShort(),
                    tomorrowDateLabel = KyivTime.nextDay(today).formatUkrainianShort(),
                    onRefresh = {},
                    onSelectDay = {},
                    onToggleNotifications = {},
                    onEditHourSlots = { _, hour, first, second ->
                        splitEdits.add(Triple(hour, first, second))
                    }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("hour_cell_15")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("edit_hour_off_maybe")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("hour_cell_16")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("edit_hour_maybe_off")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            listOf(
                Triple(15, SlotState.OFF, SlotState.MAYBE),
                Triple(16, SlotState.MAYBE, SlotState.OFF)
            ),
            splitEdits
        )
    }
}
