package me.rerere.rikkahub.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.util.concurrent.TimeUnit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "WebServerHealth"
private const val HEALTH_WORK_NAME = "web_server_health"
private const val HEALTH_INTERVAL_MIN = 30L

/**
 * Periodic health probe for [WebServerService]. If the web server is configured + enabled
 * but the service isn't currently running (OEM aggressive task-killing, OOM kills, etc.),
 * re-start it.
 *
 * Runs every [HEALTH_INTERVAL_MIN] minutes via WorkManager. Self-cancels implicitly — if
 * the web server is disabled in settings, the worker reads `webServerEnabled` fresh each
 * invocation and no-ops.
 *
 * Mirrors [TelegramBotHealthWorker] in structure.
 */
class WebServerHealthWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val settingsStore: SettingsStore by inject()

    override suspend fun doWork(): Result {
        val settings = runCatching { settingsStore.settingsFlowRaw.first() }.getOrNull()
        if (settings == null || !settings.webServerEnabled) {
            return Result.success()
        }
        // Don't restart if notification permission is missing — the service needs
        // foreground notification to survive, and starting without it would just
        // get killed again immediately.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "doWork: notification permission not granted, skipping")
            return Result.success()
        }
        if (isServiceRunning()) {
            return Result.success()
        }
        Log.w(TAG, "doWork: web server is enabled but service isn't running — restarting")
        runCatching {
            val intent = Intent(applicationContext, WebServerService::class.java).apply {
                action = WebServerService.ACTION_START
                putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
                putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
            }
            ContextCompat.startForegroundService(applicationContext, intent)
        }.onFailure {
            Log.e(TAG, "doWork: failed to restart service", it)
        }
        return Result.success()
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == WebServerService::class.java.name
        }
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<WebServerHealthWorker>(
                HEALTH_INTERVAL_MIN, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                HEALTH_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(HEALTH_WORK_NAME)
        }
    }
}