package com.example.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.ScheduleRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            val repo = ScheduleRepository(context)
            repo.getCachedDataset()?.let { dataset ->
                OutageAlarmScheduler.rescheduleAlerts(context, dataset)
                com.example.widget.PowerStatusWidgetProvider.updateAllWidgets(context, dataset)
            }
            ScheduleSyncWorker.enqueuePeriodic(context)
        }
    }
}
