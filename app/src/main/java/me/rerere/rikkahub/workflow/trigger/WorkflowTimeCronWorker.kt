package me.rerere.rikkahub.workflow.trigger

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.java.KoinJavaComponent

/**
 * Phase 12 - WorkManager worker that fires a workflow on its time/cron schedule.
 *
 * Uses Koin component injection (same pattern as CronJobWorker for scheduled jobs). The
 * actual fire dispatch goes through [TriggerRegistry] so condition + cooldown evaluation
 * happens consistently with broadcast-driven fires.
 *
 * Worker lifecycle guarantees:
 *  - The worker structurally awaits the fire callback completion before returning success.
 *  - Cancellation exceptions (from WorkManager cancelling the job) propagate so the
 *    worker is correctly marked cancelled rather than success.
 *  - Infrastructure exceptions (Koin not ready, unexpected runtime error) return
 *    [Result.retry] with a log so WorkManager backs off and re-attempts.
 *  - Business failures (workflow not found, disabled, conditions not met, action errors)
 *    are persisted by [WorkflowEngine] itself and are NOT treated as Worker infra
 *    failures - the worker returns success so it doesn't retry a fire that already ran.
 */
class WorkflowTimeCronWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val registry: TriggerRegistry by inject()

    override suspend fun doWork(): Result {
        val workflowId = inputData.getString(TimeCronTriggerFamily.KEY_WORKFLOW_ID)
            ?: return Result.failure()

        return try {
            val kindKey = TimeCronTriggerFamily.KEY_PERIODIC_V1
            val requestIsPeriodic = if (inputData.hasKeyWithValueOfType(kindKey, Boolean::class.javaObjectType)) {
                inputData.getBoolean(kindKey, false)
            } else {
                val legacyWork = WorkManager.getInstance(applicationContext).getWorkInfoByIdFlow(id).first()
                    ?: return Result.success()
                legacyWork.periodicityInfo != null
            }
            // Structured wait: fireFromTimeCronWorker completes only after the engine
            // fire finishes (or is intentionally short-circuited). Pass this WorkRequest's
            // stable UUID (`id`) so if post-fire one-shot renewal fails and WorkManager
            // retries this WorkRequest, the already-executed slot is NOT re-fired.
            registry.fireFromTimeCronWorker(workflowId, id.toString(), requestIsPeriodic)
            Result.success()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Propagate cancellation so WorkManager marks the worker as cancelled,
            // not as a spurious success.
            throw ce
        } catch (e: Exception) {
            // Infrastructure failure (Koin not ready, unexpected runtime error).
            // Retry so WorkManager re-attempts after backoff rather than silently
            // dropping the scheduled fire.
            Log.e(TAG, "time_cron worker infra failure for $workflowId, will retry", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "WorkflowTrigger"
    }
}

/**
 * Worker-side repository and cold-start engine lookups, outside the family so it can
 * stay free of Koin lookups.
 *
 * Also provides the engine fire callback fallback for cold-start fires, mirroring
 * [BootTriggerHelper.repositoryLookup].
 */
internal object TimeCronWorkerHelper {
    suspend fun repositoryLookup(workflowId: String): WorkflowRepository.Loaded? {
        val repo = KoinJavaComponent.getKoin().get<WorkflowRepository>()
        return repo.getById(workflowId)
    }

    /**
     * Resolve the engine's trigger callback via Koin, for the cold-start case where
     * [TriggerRegistry] hasn't wired the callback yet. Lookup errors propagate to the Worker
     * so it retries once DI is ready.
     */
    fun resolveEngineCallback(): TriggerFireCallback {
        return KoinJavaComponent.getKoin()
            .get<WorkflowEngine>()
            .triggerCallback
    }
}
