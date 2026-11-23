package com.example

import com.example.data.KyivTime
import com.example.data.PowerScheduleParser
import com.example.model.KyivDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PowerScheduleParserTest {

    @Test
    fun `parses GPV3_1 half-hour slots and computes intervals accurately`() {
        val date = KyivDate(2026, 11, 23)
        val midnightEpochSec = KyivTime.epochMillisFor(date, 0) / 1000L

        val json = """
            {
              "regionId": "Ternopil",
              "lastUpdated": "2026-11-23T09:15:00.000Z",
              "fact": {
                "data": {
                  "$midnightEpochSec": {
                    "GPV3.1": {
                      "1": "yes",
                      "2": "yes",
                      "3": "second",
                      "4": "no",
                      "5": "first",
                      "6": "yes",
                      "7": "yes",
                      "8": "yes",
                      "9": "yes",
                      "10": "yes",
                      "11": "yes",
                      "12": "yes",
                      "13": "yes",
                      "14": "yes",
                      "15": "yes",
                      "16": "yes",
                      "17": "yes",
                      "18": "yes",
                      "19": "yes",
                      "20": "yes",
                      "21": "yes",
                      "22": "yes",
                      "23": "yes",
                      "24": "yes"
                    }
                  }
                },
                "update": "23.11.2026 09:15"
              }
            }
        """.trimIndent()

        val dataset = PowerScheduleParser.parseJson(json)
        val day = dataset.days[date]!!
        // Hour 3 ("second") -> 02:30-03:00 OFF; Hour 4 ("no") -> 03:00-04:00 OFF; Hour 5 ("first") -> 04:00-04:30 OFF
        val onRanges = PowerScheduleParser.computeTimeRanges(day.slots, wantOn = true)
        val offRanges = PowerScheduleParser.computeTimeRanges(day.slots, wantOn = false)

        assertEquals(listOf("00:00 – 02:30", "04:30 – 24:00"), onRanges.map { it.format() })
        assertEquals(listOf("02:30 – 04:30"), offRanges.map { it.format() })
        assertEquals(120, day.totalOffMinutes)
        assertEquals(1320, day.totalOnMinutes)

        // Check snapshot at 02:00 (30 min before outage)
        val at0200 = KyivTime.epochMillisFor(date, 2 * 60)
        val snapOn = PowerScheduleParser.computeSnapshot(dataset, at0200)
        assertTrue(snapOn.isPowerOn)
        assertEquals(1800L, snapOn.remainingSeconds)
        assertEquals("02:30", snapOn.nextChangeTimeLabel)

        // Check snapshot at 03:00 (during outage until 04:30 -> 90 min = 5400s)
        val at0300 = KyivTime.epochMillisFor(date, 3 * 60)
        val snapOff = PowerScheduleParser.computeSnapshot(dataset, at0300)
        assertFalse(snapOff.isPowerOn)
        assertEquals(5400L, snapOff.remainingSeconds)
        assertEquals("04:30", snapOff.nextChangeTimeLabel)

        // Check upcoming outage transitions from 01:00
        val at0100 = KyivTime.epochMillisFor(date, 60)
        val transitions = PowerScheduleParser.computeUpcomingOutageTransitions(dataset, at0100)
        assertEquals(1, transitions.size)
        assertEquals("02:30", transitions[0].offTimeFormatted)
        assertEquals("04:30", transitions[0].backOnTimeFormatted)
    }

    @Test
    fun `switches queue dynamically and discovers available queues`() {
        val date = KyivDate(2026, 11, 23)
        val midnightEpochSec = KyivTime.epochMillisFor(date, 0) / 1000L

        val json = """
            {
              "fact": {
                "data": {
                  "$midnightEpochSec": {
                    "GPV1.2": { "1": "no", "2": "yes" },
                    "GPV3.1": { "1": "yes", "2": "yes" }
                  }
                }
              }
            }
        """.trimIndent()

        val dataset12 = PowerScheduleParser.parseJson(json, queueId = "1.2")
        assertEquals("1.2", dataset12.queueId)
        assertEquals(listOf("1.2", "3.1"), dataset12.availableQueues)
        assertEquals(60, dataset12.days[date]!!.totalOffMinutes)
    }
}
