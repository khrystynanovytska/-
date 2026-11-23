package com.example.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.SchedulePreferences

class OutageAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = SchedulePreferences(context)
        if (!prefs.notificationsEnabled) return

        val offTime = intent.getStringExtra(EXTRA_OFF_TIME) ?: return
        val backOnTime = intent.getStringExtra(EXTRA_BACK_ON_TIME)
        val outageStartMillis = intent.getLongExtra(EXTRA_OUTAGE_START_MILLIS, 0L)
        val queueId = intent.getStringExtra(EXTRA_QUEUE_ID) ?: prefs.selectedQueue
        val addressLabel = intent.getStringExtra(EXTRA_ADDRESS_LABEL) ?: prefs.addressLabel

        if (outageStartMillis > 0L && prefs.lastNotifiedOutageStartMillis == outageStartMillis) {
            return
        }
        if (outageStartMillis > 0L) {
            prefs.lastNotifiedOutageStartMillis = outageStartMillis
        }

        OutageNotificationHelper.showPreOutageNotification(
            context = context,
            offTime = offTime,
            backOnTime = backOnTime,
            outageStartMillis = outageStartMillis,
            queueId = queueId,
            addressLabel = addressLabel
        )
    }

    companion object {
        const val EXTRA_OFF_TIME = "extra_off_time"
        const val EXTRA_BACK_ON_TIME = "extra_back_on_time"
        const val EXTRA_OUTAGE_START_MILLIS = "extra_outage_start_millis"
        const val EXTRA_QUEUE_ID = "extra_queue_id"
        const val EXTRA_ADDRESS_LABEL = "extra_address_label"
    }
}
