package com.example.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.ScheduleRepository
import java.util.concurrent.TimeUnit

class ScheduleSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = ScheduleRepository(applicationContext)
        val result = repository.fetchSchedule(forceNetwork = true)
        return result.fold(
            onSuccess = { dataset ->
                OutageAlarmScheduler.rescheduleAlerts(applicationContext, dataset)
                com.example.widget.PowerStatusWidgetProvider.updateAllWidgets(applicationContext, dataset)
                Result.success()
            },
            onFailure = {
                Result.retry()
            }
        )
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "svitlo_31_schedule_sync"

        fun enqueuePeriodic(context: Context) {
            runCatching {
                val appContext = context.applicationContext
                if (!WorkManager.isInitialized()) {
                    WorkManager.initialize(
                        appContext,
                        androidx.work.Configuration.Builder().build()
                    )
                }
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val request = PeriodicWorkRequestBuilder<ScheduleSyncWorker>(
                    15, TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
            }
        }
    }
}
