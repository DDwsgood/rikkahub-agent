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
 * keeping this app's AlarmManager pipeline warm when at least one scheduled job is enabled.
 *
 * Concurrency & direct-boot invariants:
 * - All state-flag reads/writes (`KEY_ARMED` in device-protected SharedPreferences) and
 *   AlarmManager arm/cancel calls are serialized under [stateLock] using synchronous `commit()`,
 *   so an in-flight daily broadcast can NEVER pass the flag check before a concurrent [cancel]
 *   and re-arm itself afterward.
 * - Direct-boot safe: no Room, DataStore, or WorkManager access in [onReceive].
 */
class CronDailyKeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_KEEPALIVE) return
        synchronized(stateLock) {
            if (!isArmedFlagLocked(context)) return
            armAlarmOnlyLocked(context)
        }
    }

    companion object {
        const val ACTION_KEEPALIVE = "me.rerere.rikkahub.action.CRON_KEEPALIVE"
        private const val TAG = "CronDailyKeepAlive"
        private const val INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val PREFS_NAME = "cron_daily_keepalive_state"
        private const val KEY_ARMED = "armed"
        private val stateLock = Any()

        private fun statePrefs(context: Context) = runCatching {
            val dp = context.createDeviceProtectedStorageContext()
            dp.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrElse {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        private fun isArmedFlagLocked(context: Context): Boolean =
            runCatching { statePrefs(context).getBoolean(KEY_ARMED, false) }.getOrDefault(false)

        private fun setArmedFlagLocked(context: Context, armed: Boolean) {
            runCatching { statePrefs(context).edit().putBoolean(KEY_ARMED, armed).commit() }
        }

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
         * Arms the daily keep-alive alarm only when none is pending. Serialized under [stateLock].
         */
        fun armIfAbsent(context: Context) {
            synchronized(stateLock) {
                setArmedFlagLocked(context, true)
                if (pendingIntent(context, PendingIntent.FLAG_NO_CREATE) != null) return
                armAlarmOnlyLocked(context)
            }
        }

        /**
         * Re-arms after reboot ONLY if the keep-alive flag was already armed before reboot
         * (prevents zero-job devices from arming alarms on every boot).
         */
        fun rearmIfEnabled(context: Context) {
            synchronized(stateLock) {
                if (!isArmedFlagLocked(context)) return
                armAlarmOnlyLocked(context)
            }
        }

        /** Cancels any pending daily keep-alive alarm and clears the armed flag atomically. */
        fun cancel(context: Context) {
            synchronized(stateLock) {
                setArmedFlagLocked(context, false)
                runCatching {
                    val pi = pendingIntent(context, PendingIntent.FLAG_NO_CREATE) ?: return
                    val am = context.getSystemService(AlarmManager::class.java)
                    am?.cancel(pi)
                    pi.cancel()
                }.onFailure {
                    Log.w(TAG, "failed to cancel daily keep-alive alarm", it)
                }
            }
        }

        /** Arms the keep-alive alarm [INTERVAL_MS] from now and marks it armed. Never throws. */
        fun arm(context: Context) {
            synchronized(stateLock) {
                setArmedFlagLocked(context, true)
                armAlarmOnlyLocked(context)
            }
        }

        private fun armAlarmOnlyLocked(context: Context) {
            try {
                val pi = pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
                val am = context.getSystemService(AlarmManager::class.java) ?: return
                val at = System.currentTimeMillis() + INTERVAL_MS
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                } catch (_: SecurityException) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "failed to arm daily keep-alive alarm", t)
            }
        }
    }
}
