package com.example.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.PowerScheduleParser
import com.example.data.SchedulePreferences
import com.example.data.ScheduleRepository
import com.example.model.ManualStatusOverride
import com.example.model.ScheduleDataset
import com.example.model.SlotState
import com.example.notifications.OutageAlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PowerStatusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val prefs = SchedulePreferences(context)
        val repository = ScheduleRepository(context, prefs)
        val cached = repository.getCachedDataset()
        val now = System.currentTimeMillis()
        val manualOverride = prefs.getActiveManualOverride(now)

        renderWidgets(context, appWidgetManager, appWidgetIds, cached, prefs.selectedQueue, now, manualOverride)
        scheduleNextTransitionUpdate(context, cached, now, manualOverride)

        val cacheAgeMs = cached?.let { now - it.fetchedAtMillis } ?: Long.MAX_VALUE
        if (cached == null || cacheAgeMs > CACHE_STALE_MS) {
            fetchAndUpdateAsync(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH_WIDGET -> fetchAndUpdateAsync(context)
            ACTION_TRANSITION_TICK -> updateAllWidgets(context)
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelTransitionUpdate(context)
    }

    private fun fetchAndUpdateAsync(context: Context) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val prefs = SchedulePreferences(appContext)
                val repository = ScheduleRepository(appContext, prefs)
                val result = repository.fetchSchedule(forceNetwork = true)
                result.onSuccess { dataset ->
                    val now = System.currentTimeMillis()
                    OutageAlarmScheduler.rescheduleAlerts(appContext, dataset, now)
                    updateAllWidgets(appContext, dataset, now)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH_WIDGET = "com.example.widget.ACTION_REFRESH_WIDGET"
        const val ACTION_TRANSITION_TICK = "com.example.widget.ACTION_TRANSITION_TICK"
        private const val TRANSITION_REQUEST_CODE = 4201
        private const val CACHE_STALE_MS = 15L * 60L * 1000L

        fun updateAllWidgets(
            context: Context,
            providedDataset: ScheduleDataset? = null,
            nowMillis: Long = System.currentTimeMillis()
        ) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val componentName = ComponentName(appContext, PowerStatusWidgetProvider::class.java)
            val appWidgetIds = manager.getAppWidgetIds(componentName)
            if (appWidgetIds.isEmpty()) return

            val prefs = SchedulePreferences(appContext)
            val dataset = providedDataset ?: ScheduleRepository(appContext, prefs).getCachedDataset()
            val manualOverride = prefs.getActiveManualOverride(nowMillis)

            renderWidgets(
                context = appContext,
                appWidgetManager = manager,
                appWidgetIds = appWidgetIds,
                dataset = dataset,
                selectedQueue = prefs.selectedQueue,
                nowMillis = nowMillis,
                manualOverride = manualOverride
            )
            scheduleNextTransitionUpdate(appContext, dataset, nowMillis, manualOverride)
        }

        internal fun buildRemoteViews(
            context: Context,
            dataset: ScheduleDataset?,
            selectedQueue: String,
            nowMillis: Long = System.currentTimeMillis(),
            elapsedRealtimeMillis: Long = SystemClock.elapsedRealtime(),
            manualOverride: ManualStatusOverride? = null
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_power_status)

            val queueText = context.getString(R.string.queue_format, dataset?.queueId ?: selectedQueue)
            views.setTextViewText(R.id.widget_queue_badge, queueText)

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent)

            val refreshIntent = Intent(context, PowerStatusWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_WIDGET
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context,
                1,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPendingIntent)

            if (dataset == null) {
                views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_bg_neutral)
                views.setTextViewText(R.id.widget_status_text, context.getString(R.string.app_name))
                views.setViewVisibility(R.id.widget_timer_prefix, View.GONE)
                views.setViewVisibility(R.id.widget_chronometer, View.GONE)
                views.setViewVisibility(R.id.widget_static_subtext, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_static_subtext,
                    context.getString(R.string.widget_loading)
                )
                views.setViewVisibility(R.id.widget_next_time_text, View.GONE)
                return views
            }

            val snapshot = PowerScheduleParser.computeSnapshot(dataset, nowMillis, manualOverride)
            val bgRes = when (snapshot.effectiveState) {
                SlotState.ON -> R.drawable.widget_bg_on
                SlotState.MAYBE -> R.drawable.widget_bg_maybe
                SlotState.OFF -> R.drawable.widget_bg_off
            }
            views.setInt(R.id.widget_root, "setBackgroundResource", bgRes)

            val statusText = when (snapshot.effectiveState) {
                SlotState.ON -> context.getString(R.string.status_power_on)
                SlotState.MAYBE -> context.getString(R.string.status_maybe_off)
                SlotState.OFF -> context.getString(R.string.status_power_off)
            }
            views.setTextViewText(R.id.widget_status_text, statusText)

            if (snapshot.todaySchedule == null) {
                views.setViewVisibility(R.id.widget_timer_prefix, View.GONE)
                views.setViewVisibility(R.id.widget_chronometer, View.GONE)
                views.setViewVisibility(R.id.widget_static_subtext, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_static_subtext,
                    "Графік на сьогодні очікується"
                )
                views.setViewVisibility(R.id.widget_next_time_text, View.GONE)
            } else if (snapshot.remainingSeconds >= 0L) {
                views.setViewVisibility(R.id.widget_timer_prefix, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_timer_prefix,
                    context.getString(R.string.widget_timer_prefix)
                )
                views.setViewVisibility(R.id.widget_chronometer, View.VISIBLE)
                views.setViewVisibility(R.id.widget_static_subtext, View.GONE)

                val targetElapsed = elapsedRealtimeMillis + snapshot.remainingSeconds * 1000L
                views.setChronometerCountDown(R.id.widget_chronometer, true)
                views.setChronometer(R.id.widget_chronometer, targetElapsed, null, true)

                if (snapshot.nextChangeTimeLabel != null) {
                    val nextLabel = when (snapshot.nextChangeTargetState) {
                        SlotState.ON -> context.getString(R.string.next_on_at, snapshot.nextChangeTimeLabel)
                        SlotState.MAYBE -> "Можливе вимкн. о ${snapshot.nextChangeTimeLabel}"
                        SlotState.OFF, null -> context.getString(R.string.next_off_at, snapshot.nextChangeTimeLabel)
                    }
                    val suffix = if (snapshot.isManualOverride) " · вручну" else ""
                    views.setViewVisibility(R.id.widget_next_time_text, View.VISIBLE)
                    views.setTextViewText(R.id.widget_next_time_text, nextLabel + suffix)
                } else if (snapshot.isManualOverride) {
                    views.setViewVisibility(R.id.widget_next_time_text, View.VISIBLE)
                    views.setTextViewText(R.id.widget_next_time_text, context.getString(R.string.manual_badge))
                } else {
                    views.setViewVisibility(R.id.widget_next_time_text, View.GONE)
                }
            } else {
                views.setViewVisibility(R.id.widget_timer_prefix, View.GONE)
                views.setViewVisibility(R.id.widget_chronometer, View.GONE)
                views.setViewVisibility(R.id.widget_static_subtext, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_static_subtext,
                    context.getString(R.string.widget_no_outages)
                )
                views.setViewVisibility(R.id.widget_next_time_text, View.GONE)
            }

            return views
        }

        private fun renderWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
            dataset: ScheduleDataset?,
            selectedQueue: String,
            nowMillis: Long,
            manualOverride: ManualStatusOverride?
        ) {
            val views = buildRemoteViews(
                context = context,
                dataset = dataset,
                selectedQueue = selectedQueue,
                nowMillis = nowMillis,
                manualOverride = manualOverride
            )
            appWidgetIds.forEach { widgetId ->
                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }

        private fun scheduleNextTransitionUpdate(
            context: Context,
            dataset: ScheduleDataset?,
            nowMillis: Long,
            manualOverride: ManualStatusOverride?
        ) {
            cancelTransitionUpdate(context)
            if (dataset == null) return

            val snapshot = PowerScheduleParser.computeSnapshot(dataset, nowMillis, manualOverride)
            if (snapshot.remainingSeconds <= 0L) return

            val triggerAtMillis = nowMillis + snapshot.remainingSeconds * 1000L + 1500L
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, PowerStatusWidgetProvider::class.java).apply {
                action = ACTION_TRANSITION_TICK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                TRANSITION_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }

        private fun cancelTransitionUpdate(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, PowerStatusWidgetProvider::class.java).apply {
                action = ACTION_TRANSITION_TICK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                TRANSITION_REQUEST_CODE,
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
