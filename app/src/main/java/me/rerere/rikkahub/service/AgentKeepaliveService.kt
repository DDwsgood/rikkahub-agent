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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.preferences.ScheduleDeliveryPreferences

/**
 * Optional user-enabled keep-alive foreground service (Termux-style): a low-importance
 * ongoing notification + optional opt-in bounded partial WakeLock that keep the process
 * foreground-priority so scheduled jobs and their exact alarms are more likely to survive
 * OEM-aggressive background killers (ColorOS / MIUI / EMUI).
 *
 * WakeLock management is driven by a single serialized policy collector + renewal loop
 * under [wakeLockMutex]:
 * - Default (`keepaliveWakeLockEnabled == false`): FGS-only, zero screen-off CPU lock.
 * - Opt-in (`keepaliveWakeLockEnabled == true`): acquires [WAKELOCK_CHUNK_MS] (30 min) and
 *   unconditionally refreshes the timeout every [WAKELOCK_RENEW_INTERVAL_MS] (20 min) while
 *   still opted in and TelegramBotService is not already holding coverage, preventing renewal gaps.
 * - Turning off or service destruction immediately releases the lock under the same mutex.
 */
class AgentKeepaliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wakeLockMutex = Mutex()
    private val wakeLockMonitor = Any()
    private var destroyed: Boolean = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var policyCollectorJob: Job? = null
    private var renewalLoopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        synchronized(wakeLockMonitor) {
            destroyed = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_WAKELOCK) {
            if (!isRunning) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ensurePolicyCollectorStarted()
            return START_STICKY
        }
        try {
            startInForeground()
            isRunning = true
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground failed; service will not run", e)
            isRunning = false
            stopSelf(startId)
            return START_NOT_STICKY
        }
        ensurePolicyCollectorStarted()
        return START_STICKY
    }

    /**
     * Single serialized collector reacting immediately to `keepaliveWakeLockEnabled` changes
     * without parallel refresh races.
     */
    private fun ensurePolicyCollectorStarted() {
        if (policyCollectorJob?.isActive == true) return
        policyCollectorJob = scope.launch {
            val prefs = runCatching {
                org.koin.core.context.GlobalContext.get().get<ScheduleDeliveryPreferences>()
            }.getOrNull()
            if (prefs == null) {
                applyWakeLockPolicy(enabled = false)
                return@launch
            }
            try {
                prefs.flow
                    .map { it.keepaliveWakeLockEnabled }
                    .distinctUntilChanged()
                    .collect { enabled ->
                        applyWakeLockPolicy(enabled)
                    }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "WakeLock policy collector failed; falling back to FGS-only", t)
                applyWakeLockPolicy(enabled = false)
            }
        }
    }

    private suspend fun applyWakeLockPolicy(enabled: Boolean) {
        wakeLockMutex.withLock {
            renewalLoopJob?.cancel()
            renewalLoopJob = null
            if (!enabled) {
                releaseWakeLockLocked()
                Log.i(TAG, "Keepalive running in FGS-only mode (opt-in WakeLock disabled)")
                return
            }
            refreshChunkLocked()
            renewalLoopJob = scope.launch {
                while (isActive) {
                    delay(WAKELOCK_RENEW_INTERVAL_MS)
                    wakeLockMutex.withLock {
                        // Re-verify live opt-in policy before every renewal tick.
                        val stillEnabled = runCatching {
                            org.koin.core.context.GlobalContext.get()
                                .get<ScheduleDeliveryPreferences>()
                                .current()
                                .keepaliveWakeLockEnabled
                        }.getOrDefault(false)
                        if (!stillEnabled) {
                            releaseWakeLockLocked()
                            return@launch
                        }
                        refreshChunkLocked()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        policyCollectorJob?.cancel()
        renewalLoopJob?.cancel()
        // Set `destroyed = true` and release under the EXACT SAME monitor that guards every
        // acquire/refresh call, eliminating any race where an already-entered coroutine acquires
        // the WakeLock after onDestroy() has released it.
        synchronized(wakeLockMonitor) {
            destroyed = true
            runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    /**
     * Caller MUST hold [wakeLockMutex]. Acquires or refreshes the non-reference-counted partial
     * WakeLock to a fresh [WAKELOCK_CHUNK_MS] (30 min) window before the prior chunk expires,
     * unless [TelegramBotService] is already running.
     */
    private fun refreshChunkLocked() {
        if (TelegramBotService.isRunning) {
            releaseWakeLockLocked()
            return
        }
        synchronized(wakeLockMonitor) {
            if (destroyed) {
                runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
                return
            }
            if (wakeLock == null) {
                val pm = getSystemService(PowerManager::class.java) ?: return
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                    setReferenceCounted(false)
                }
            }
            val lock = wakeLock ?: return
            // Calling acquire(timeout) on a non-reference-counted WakeLock resets the expiration
            // timer seamlessly BEFORE the current 30-minute chunk expires (zero coverage gap).
            runCatching { lock.acquire(WAKELOCK_CHUNK_MS) }
                .onFailure { Log.w(TAG, "wakelock acquire/refresh failed", it) }
        }
    }

    private fun releaseWakeLockLocked() {
        synchronized(wakeLockMonitor) {
            runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        }
    }

    companion object {
        private const val TAG = "AgentKeepaliveService"
        private const val CHANNEL_ID = "rikkahub_keepalive"
        private const val NOTIF_ID = 0x4B31
        private const val WAKELOCK_TAG = "rikkahub:keepalive"

        /** Bounded per-acquire cap when opt-in WakeLock is enabled (30 min). */
        private const val WAKELOCK_CHUNK_MS = 30L * 60_000L

        /** Renew 10 minutes before chunk expiry so continuous coverage has zero gap. */
        private const val WAKELOCK_RENEW_INTERVAL_MS = 20L * 60_000L

        const val ACTION_REFRESH_WAKELOCK = "me.rerere.rikkahub.action.KEEPALIVE_REFRESH_WAKELOCK"

        /** Process-local hint only (policy collector already reacts live via DataStore Flow). */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, AgentKeepaliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun refreshWakeLockIfRunning(context: Context) {
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, AgentKeepaliveService::class.java).setAction(ACTION_REFRESH_WAKELOCK)
                )
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentKeepaliveService::class.java))
        }
    }
}
