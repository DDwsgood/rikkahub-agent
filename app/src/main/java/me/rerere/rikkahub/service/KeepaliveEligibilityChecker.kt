package me.rerere.rikkahub.service

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
import me.rerere.rikkahub.widget.AgentWidgetProvider

/**
 * WakeUp-style gate for the "high-reliability background" features: the keep-alive FGS
 * and the 1×1 home-screen widget only help when three preconditions hold, so the settings
 * toggle checks them up front and walks the user through the missing pieces instead of
 * silently turning on a feature that can't work.
 *
 *  1. Notifications — the FGS must be able to post its ongoing notification
 *     (`areNotificationsEnabled` covers both POST_NOTIFICATIONS on 13+ and the older
 *     per-app switch).
 *  2. Exact alarms — `canScheduleExactAlarms`; without it jobs silently degrade to
 *     flexible WorkManager timing.
 *  3. Widget placed — the 1×1 widget is the periodic revive point (system-driven
 *     APPWIDGET_UPDATE every 30 min); without it the whole WakeUp-style strategy has
 *     no anchor.
 */
object KeepaliveEligibilityChecker {

    data class Result(
        val notificationsGranted: Boolean,
        val exactAlarmsGranted: Boolean,
        val widgetPlaced: Boolean,
    ) {
        val allSatisfied: Boolean
            get() = notificationsGranted && exactAlarmsGranted && widgetPlaced
    }

    fun check(context: Context): Result = Result(
        notificationsGranted = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        exactAlarmsGranted = PermissionHelper.canScheduleExactAlarms(context),
        widgetPlaced = agentWidgetIds(context).isNotEmpty(),
    )

    /** App-widget ids of our 1×1 provider currently hosted by a launcher. */
    fun agentWidgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, AgentWidgetProvider::class.java))

    /**
     * Ask the system to pin the 1×1 widget (API 26+ shows the system pin sheet). Returns
     * false when the launcher doesn't support pinning — the user then has to add it the
     * long way via the widget picker.
     */
    fun requestPinWidget(context: Context): Boolean {
        val awm = AppWidgetManager.getInstance(context) ?: return false
        if (!awm.isRequestPinAppWidgetSupported) return false
        return runCatching {
            awm.requestPinAppWidget(
                ComponentName(context, AgentWidgetProvider::class.java),
                null,
                null,
            )
        }.getOrDefault(false)
    }
}
