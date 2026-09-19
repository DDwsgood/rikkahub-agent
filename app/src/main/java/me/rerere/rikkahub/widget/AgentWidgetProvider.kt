package me.rerere.rikkahub.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.service.CronJobScheduler
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 1×1 home-screen widget — the WakeUp-pattern revive anchor.
 *
 * Why this exists: `updatePeriodMillis` (30 min) APPWIDGET_UPDATE broadcasts are
 * dispatched by the system's AppWidgetService, so the broadcast reaches us even when the
 * process is dead — on OEM-aggressive ROMs that is the cheapest reliable "free" revive
 * point, and launchers rarely kill the providers behind user-pinned widgets. Each update
 * pass re-arms every enabled job's exact alarm, so a swallowed alarm broadcast self-heals
 * within half an hour.
 *
 * The widget shows the nearest upcoming fire ("Next HH:mm") and opens the app on tap.
 */
class AgentWidgetProvider : AppWidgetProvider(), KoinComponent {

    // Koin singletons — resolved lazily inside onUpdate's goAsync block so a cold
    // process start pays the injection cost inside the broadcast window, not on the
    // main thread during the provider callback.
    private val scheduler: CronJobScheduler by inject()
    private val repo: ScheduledJobRepository by inject()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pendingResult = goAsync()
        // goAsync gives us ~10s off-main; scheduleAllEnabled is a handful of awaited
        // WorkManager/AlarmManager calls — comfortably inside the budget for realistic
        // job counts. If the process is cold, Koin bootstrap eats most of the window, so
        // a failure is logged and left for the next 30-min pass rather than retried here.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Re-arm all enabled jobs first (the actual keepalive work)…
                runCatching { scheduler.scheduleAllEnabled() }
                    .onFailure { Log.w(TAG, "widget onUpdate: scheduleAllEnabled failed", it) }
                // …then read the freshest nextRunAtMs values for the label. Reading after
                // the re-arm means the label reflects the just-persisted control plane.
                val nextRunMs = runCatching {
                    repo.getEnabled()
                        .mapNotNull { it.nextRunAtMs }
                        .filter { it > System.currentTimeMillis() }
                        .minOrNull()
                }.getOrNull()
                updateWidgets(context, appWidgetManager, appWidgetIds, nextRunMs)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun updateWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        nextRunMs: Long?,
    ) {
        val label = if (nextRunMs != null) {
            context.getString(
                R.string.agent_widget_next_run,
                TIME_FORMAT.format(Date(nextRunMs)),
            )
        } else {
            context.getString(R.string.app_name)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, RouteActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_agent_1x1)
            views.setTextViewText(R.id.widget_agent_text, label)
            views.setOnClickPendingIntent(R.id.widget_agent_root, contentIntent)
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    companion object {
        private const val TAG = "AgentWidgetProvider"
        private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())
    }
}
