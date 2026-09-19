package me.rerere.rikkahub.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Fossify-style "dummy" daily alarm: a self-perpetuating exact alarm whose only purpose is
 * keeping this app's AlarmManager pipeline warm. Some OEMs cold-treat apps that haven't
 * armed an alarm recently; one fire per day keeps the app on AlarmManager's active list
 * so real cron alarms deliver promptly.
 *
 * Hard cap: AT MOST one fire per day. MagicOS PowerGenie flags >3 setAlarmClock calls per
 * day as high-frequency wake-ups, so the interval stays well under that threshold and
 * [armIfAbsent] refuses to arm while a keep-alive alarm is already pending — call sites
 * (schedule/reconcile/boot) can never stack extra alarms.
 *
 * Direct-boot safe: [onReceive] only re-arms an alarm — no Room, DataStore or WorkManager
 * access — so the receiver is declared `directBootAware` and may fire before the device
 * is unlocked.
 */
class CronDailyKeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_KEEPALIVE) return
        // Self-perpetuate: arm the next daily fire. Touches no storage, which keeps a fire
        // that arrives while the device is still locked (direct boot) safe.
        arm(context)
    }

    companion object {
        const val ACTION_KEEPALIVE = "me.rerere.rikkahub.action.CRON_KEEPALIVE"
        private const val TAG = "CronDailyKeepAlive"
        private const val INTERVAL_MS = 24L * 60 * 60 * 1000

        private fun pendingIntent(context: Context, flags: Int): PendingIntent? =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, CronDailyKeepAliveReceiver::class.java)
                    .setAction(ACTION_KEEPALIVE)
                    .setData(Uri.parse("rikkahub://cron-keepalive"))
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                flags or PendingIntent.FLAG_IMMUTABLE,
            )

        /**
         * Arms the daily keep-alive alarm only when none is pending. Safe to call from any
         * scheduling path — the FLAG_NO_CREATE probe keeps the ≤1/day cap intact.
         */
        fun armIfAbsent(context: Context) {
            if (pendingIntent(context, PendingIntent.FLAG_NO_CREATE) != null) return
            arm(context)
        }

        /** Arms (or re-arms, on fire) the keep-alive alarm [INTERVAL_MS] from now. Never throws. */
        fun arm(context: Context) {
            try {
                val pi = pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
                val am = context.getSystemService(AlarmManager::class.java) ?: return
                val at = System.currentTimeMillis() + INTERVAL_MS
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                } catch (_: SecurityException) {
                    // Exact-alarm permission revoked — an inexact alarm still keeps the
                    // pipeline warm.
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "failed to arm daily keep-alive alarm", t)
            }
        }
    }
}
