package me.rerere.rikkahub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity

/**
 * Optional user-enabled keep-alive foreground service (Termux-style): a low-importance
 * ongoing notification + a partial WakeLock that keep the process foreground-priority so
 * scheduled jobs and their exact alarms are far more likely to survive OEM-aggressive
 * background killers (ColorOS / MIUI / EMUI).
 *
 * This is a mitigation, not a guarantee — force-stop and OEM task-kill still win; the
 * alarm/boot/widget revive paths remain the real recovery chain.
 *
 * Coordination with [TelegramBotService]: when the bot is already running, its own FGS
 * plus per-poll wakelocks already hold the process foreground, so we skip acquiring our
 * own always-on WakeLock (it would be pure battery cost). The keepalive FGS still runs
 * alongside — FGS requires a posted notification, so the two services each show their
 * own low-importance ongoing notification; when the bot later stops, this service keeps
 * the coverage gap-free.
 */
class AgentKeepaliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockWatchdog: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startInForeground()
        } catch (e: Throwable) {
            // Android 12+: a sticky revive has no foreground "ticket", so startForeground()
            // throws ForegroundServiceStartNotAllowedException (or SecurityException on
            // Android 14+ if the FGS-type permission were missing). Same pattern as
            // TelegramBotService — log loudly and stop instead of crashing.
            Log.e(TAG, "startForeground failed; service will not run", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        acquireWakeLockIfNeeded()
        // START_STICKY: we WANT the OS to bring us back after an LMK kill — that's the
        // whole point of the service. The try-catch above covers the A12+ sticky-revive
        // case where the restart is allowed but promotion is denied.
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLockWatchdog?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keepalive_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ))
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, RouteActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.keepalive_notification_title))
            .setContentText(getString(R.string.keepalive_notification_body))
            .setSmallIcon(R.drawable.small_icon)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .build()
        // specialUse (declared in the manifest with a subtype property): no daily budget
        // cap, the correct flavor for an indefinite user-requested keep-alive.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    /**
     * Acquire the partial WakeLock unless the Telegram bot's FGS is already holding the
     * process foreground (see class KDoc — a second always-on lock would be redundant).
     *
     * The lock is acquired in [WAKELOCK_CHUNK_MS] chunks rather than forever: if our
     * stop/release path ever fails (crash between acquire and onDestroy), the leak is
     * bounded at 12 h instead of draining the battery until the next reboot. A lightweight
     * watchdog coroutine re-arms the lock when a chunk expires while the service is still
     * deliberately running, so coverage is continuous in practice.
     */
    private fun acquireWakeLockIfNeeded() {
        if (TelegramBotService.isRunning) {
            Log.i(TAG, "Telegram bot FGS already running — skipping keepalive wakelock")
        } else {
            lockWakeLock()
        }
        // The watchdog starts even when the bot currently owns the coverage: if the bot
        // later stops while this service is still running, the watchdog picks the lock up
        // and the transition stays gap-free.
        if (wakeLockWatchdog?.isActive != true) {
            wakeLockWatchdog = scope.launch {
                while (true) {
                    delay(WAKELOCK_WATCHDOG_INTERVAL_MS)
                    // Re-acquire only when the previous chunk expired AND the Telegram bot
                    // still isn't covering the process. If the bot started meanwhile we
                    // release our lock so only one always-on holder remains.
                    if (TelegramBotService.isRunning) {
                        releaseWakeLock()
                    } else {
                        lockWakeLock()
                    }
                }
            }
        }
    }

    /** Creates the WakeLock on first use and acquires a fresh chunk when not held. */
    private fun lockWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                setReferenceCounted(false)
            }
        }
        val lock = wakeLock ?: return
        if (!lock.isHeld) {
            runCatching { lock.acquire(WAKELOCK_CHUNK_MS) }
                .onFailure { Log.w(TAG, "wakelock acquire failed", it) }
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
    }

    companion object {
        private const val TAG = "AgentKeepaliveService"
        private const val CHANNEL_ID = "rikkahub_keepalive"
        private const val NOTIF_ID = 0x4B31
        private const val WAKELOCK_TAG = "rikkahub:keepalive"

        /** Per-acquire cap — see [acquireWakeLockIfNeeded]. */
        private const val WAKELOCK_CHUNK_MS = 12L * 60 * 60_000L

        /** How often the watchdog checks whether the lock needs re-arming. */
        private const val WAKELOCK_WATCHDOG_INTERVAL_MS = 30L * 60_000L

        fun start(context: Context) {
            val intent = Intent(context, AgentKeepaliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentKeepaliveService::class.java))
        }
    }
}
