package me.rerere.rikkahub.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.util.concurrent.Executor

/**
 * Boot / time / permission broadcast entry point for cron scheduling.
 *
 * The receiver itself is deliberately tiny: it classifies the action, enqueues ONE durable
 * [CronReconcileWorker] and finishes once WorkManager has committed the enqueue. All Room
 * traversal, catchup planning and re-arming happen inside the worker — never in the
 * broadcast lifecycle, where goAsync()'s ~10s budget would be blown by a cold-process Koin
 * bootstrap + an unbounded reconcile sweep.
 *
 * Each event family uses its own unique work name with APPEND_OR_REPLACE, so a burst of
 * closely-timed but different broadcasts (e.g. TIME_SET then PERMISSION_CHANGED) never
 * REPLACEs away an unprocessed event; identical duplicates append and run redundantly,
 * which is safe because reconciliation is idempotent.
 *
 * If WorkManager is unavailable (enqueue throws synchronously or the Operation fails), a
 * bounded, stable-identity inexact AlarmManager retry re-fires this receiver after a short
 * delay ([CronBootRetry]) so the event is not permanently lost. `pendingResult.finish()`
 * runs on every path.
 */
class CronBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == ACTION_RETRY) {
            val kind = intent.getStringExtra(CronReconcileWorker.KEY_KIND)
                ?: CronReconcileWorker.KIND_BOOT
            val attempt = intent.getIntExtra(CronAlarmRetry.KEY_RETRY_ATTEMPT, 0)
            enqueueReconcile(context, kind, attempt)
            return
        }

        // LOCKED_BOOT_COMPLETED is intentionally NOT registered: Application.onCreate runs
        // before any receiver and touches credential-protected storage (Koin →
        // SharedPreferences/DataStore/Room), so direct-boot delivery would crash. All
        // reconcile work needs that storage anyway — BOOT_COMPLETED (post-unlock) is the
        // safe boundary and re-arms alarms within ~10s of unlock.
        val isBootLike = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        val isClockChange = action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        val isPermissionChange =
            action == "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        if (!isBootLike && !isClockChange && !isPermissionChange) return

        val kind = when {
            isBootLike -> CronReconcileWorker.KIND_BOOT
            isClockChange -> CronReconcileWorker.KIND_TIME
            else -> CronReconcileWorker.KIND_PERMISSION
        }
        enqueueReconcile(context, kind, attempt = 0)
    }

    private fun enqueueReconcile(context: Context, kind: String, attempt: Int) {
        // Boot is also a good moment to (re)assert the pipeline warmers. arm() directly —
        // a reboot wiped the alarm even when the PendingIntent registration still resolves.
        CronDailyKeepAliveReceiver.arm(context)
        CronReconcileWorker.schedulePeriodic(context)

        val workName = when (kind) {
            CronReconcileWorker.KIND_TIME -> RECONCILE_TIME_WORK_NAME
            CronReconcileWorker.KIND_PERMISSION -> RECONCILE_PERMISSION_WORK_NAME
            else -> RECONCILE_BOOT_WORK_NAME
        }

        val request = OneTimeWorkRequestBuilder<CronReconcileWorker>()
            .setInputData(Data.Builder()
                .putString(CronReconcileWorker.KEY_KIND, kind)
                .build())
            // Reconciliation over many jobs can exceed WorkManager's ~10-minute execution
            // limit, so expedite it (the worker has getForegroundInfo); quota exhaustion
            // gracefully degrades to a normal request. The work kind semantics are
            // unchanged.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        val pendingResult = goAsync()
        val operation = try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        } catch (t: Throwable) {
            // WorkManager isn't available — persist a durable retry so the event isn't
            // lost, then finish.
            Log.e(TAG, "Unable to enqueue cron reconciliation (kind=$kind)", t)
            CronBootRetry.arm(context, kind, attempt)
            pendingResult.finish()
            return
        }
        operation.result.addListener(
            {
                val ok = runCatching { operation.result.get() }.isSuccess
                if (!ok) {
                    Log.e(TAG, "Failed to persist cron reconciliation (kind=$kind)")
                    CronBootRetry.arm(context, kind, attempt)
                }
                pendingResult.finish()
            },
            DIRECT_EXECUTOR,
        )
    }

    companion object {
        /** Custom action used by [CronBootRetry] to re-trigger this receiver. */
        internal const val ACTION_RETRY = "me.rerere.rikkahub.action.CRON_RECONCILE_RETRY"

        private const val TAG = "CronBootReceiver"
        private const val RECONCILE_BOOT_WORK_NAME = "cron_reconcile_boot"
        private const val RECONCILE_TIME_WORK_NAME = "cron_reconcile_time"
        private const val RECONCILE_PERMISSION_WORK_NAME = "cron_reconcile_permission"
        private val DIRECT_EXECUTOR = Executor { command -> command.run() }
    }
}

/**
 * Bounded, stable-identity AlarmManager retry for a failed [CronBootReceiver] enqueue.
 * One PendingIntent identity per kind (`rikkahub://cron-boot-retry/<kind>`), so retries
 * never proliferate and different kinds never clobber each other. Inexact by design —
 * boot-time work does not need to be precise, only durable. Never throws.
 */
private object CronBootRetry {
    private const val TAG = "CronBootRetry"
    private const val MAX_ATTEMPTS = 5
    private const val RETRY_DELAY_MS = 60_000L

    fun arm(context: Context, kind: String, attempt: Int) {
        if (attempt >= MAX_ATTEMPTS) {
            Log.e(TAG, "giving up after $attempt retries for cron reconcile (kind=$kind)")
            return
        }
        try {
            val pi = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, CronBootReceiver::class.java)
                    .setAction(CronBootReceiver.ACTION_RETRY)
                    .setData(Uri.parse("rikkahub://cron-boot-retry/$kind"))
                    .putExtra(CronReconcileWorker.KEY_KIND, kind)
                    .putExtra(CronAlarmRetry.KEY_RETRY_ATTEMPT, attempt + 1)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val at = System.currentTimeMillis() + RETRY_DELAY_MS
            val am = context.getSystemService(AlarmManager::class.java)
            // Inexact — durable, no exact-alarm permission required.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (t: Throwable) {
            Log.e(TAG, "failed to arm cron reconcile retry (kind=$kind)", t)
        }
    }
}
