package com.example.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.data.PowerScheduleParser
import com.example.data.SchedulePreferences
import com.example.model.ScheduleDataset

object OutageAlarmScheduler {
    private const val MAX_SCHEDULED_ALARMS = 6
    private const val BASE_REQUEST_CODE = 3100
    private const val FIFTEEN_MINUTES_MS = 15L * 60L * 1000L
    private const val ONE_MINUTE_MS = 60L * 1000L

    fun rescheduleAlerts(
        context: Context,
        dataset: ScheduleDataset,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val prefs = SchedulePreferences(context)
        cancelAllAlarms(context)

        if (!prefs.notificationsEnabled) return
        if (!OutageNotificationHelper.hasNotificationPermission(context)) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val transitions = PowerScheduleParser.computeUpcomingOutageTransitions(dataset, nowMillis)

        var scheduledCount = 0
        for (transition in transitions) {
            if (scheduledCount >= MAX_SCHEDULED_ALARMS) break
            val triggerAtMillis = transition.outageStartMillis - FIFTEEN_MINUTES_MS

            if (triggerAtMillis > nowMillis) {
                val intent = Intent(context, OutageAlarmReceiver::class.java).apply {
                    putExtra(OutageAlarmReceiver.EXTRA_OFF_TIME, transition.offTimeFormatted)
                    putExtra(OutageAlarmReceiver.EXTRA_BACK_ON_TIME, transition.backOnTimeFormatted)
                    putExtra(OutageAlarmReceiver.EXTRA_OUTAGE_START_MILLIS, transition.outageStartMillis)
                    putExtra(OutageAlarmReceiver.EXTRA_QUEUE_ID, dataset.queueId)
                    putExtra(OutageAlarmReceiver.EXTRA_ADDRESS_LABEL, prefs.addressLabel)
                    putExtra(OutageAlarmReceiver.EXTRA_IS_MAYBE, transition.isMaybe)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    BASE_REQUEST_CODE + scheduledCount,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                scheduledCount++
            } else if (transition.outageStartMillis - nowMillis in ONE_MINUTE_MS..FIFTEEN_MINUTES_MS) {
                // Outage is already < 15 minutes away; fire once if not yet notified for this transition
                if (prefs.lastNotifiedOutageStartMillis != transition.outageStartMillis) {
                    prefs.lastNotifiedOutageStartMillis = transition.outageStartMillis
                    OutageNotificationHelper.showPreOutageNotification(
                        context = context,
                        offTime = transition.offTimeFormatted,
                        backOnTime = transition.backOnTimeFormatted,
                        outageStartMillis = transition.outageStartMillis,
                        queueId = dataset.queueId,
                        addressLabel = prefs.addressLabel,
                        isMaybe = transition.isMaybe
                    )
                }
            }
        }
    }

    fun cancelAllAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until MAX_SCHEDULED_ALARMS) {
            val intent = Intent(context, OutageAlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                BASE_REQUEST_CODE + i,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        }
    }
}
