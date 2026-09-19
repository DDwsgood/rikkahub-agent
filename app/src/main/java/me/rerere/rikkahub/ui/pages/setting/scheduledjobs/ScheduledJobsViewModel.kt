package me.rerere.rikkahub.ui.pages.setting.scheduledjobs

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.service.AgentKeepaliveService
import me.rerere.rikkahub.service.CronJobScheduler

/**
 * Backs the Settings → Scheduled Jobs list + detail screens. Mirrors the responsibilities
 * the cron-job *tools* expose to the LLM (`list_jobs`, `pause_job`, `resume_job`,
 * `trigger_job_now`, `delete_job`, `get_job_history`) so the UI can drive every operation
 * the assistant can — without going through the LLM.
 *
 * State changes go through [ScheduledJobRepository] AND [CronJobScheduler] in the same
 * order the tools use, so the WorkManager schedule and the DB row stay in lock-step.
 */
class ScheduledJobsViewModel(
    private val context: Application,
    private val repository: ScheduledJobRepository,
    private val runRepository: ScheduledJobRunRepository,
    private val scheduler: CronJobScheduler,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val jobs: StateFlow<List<ScheduledJobEntity>> =
        repository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val keepaliveEnabled: StateFlow<Boolean> =
        settingsStore.settingsFlow
            .map { it.keepaliveEnabled }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Flip the keep-alive FGS on/off. Callers (the settings switch) must only reach this
     * after [me.rerere.rikkahub.service.KeepaliveEligibilityChecker] passes — the screen
     * walks the user through missing notification/alarm/widget prerequisites first.
     */
    fun setKeepaliveEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                settingsStore.update { it.copy(keepaliveEnabled = enabled) }
                if (enabled) AgentKeepaliveService.start(context)
                else AgentKeepaliveService.stop(context)
            }.onFailure {
                android.util.Log.w("ScheduledJobsViewModel", "setKeepaliveEnabled($enabled) failed", it)
            }
        }
    }

    /** Opt-in lock-screen wake for scheduled-job fires (default off). */
    val wakeOnLockScreen: StateFlow<Boolean> =
        settingsStore.settingsFlow
            .map { it.scheduledJobWakeOnLockScreen }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setWakeOnLockScreen(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                settingsStore.update { it.copy(scheduledJobWakeOnLockScreen = enabled) }
            }.onFailure {
                android.util.Log.w("ScheduledJobsViewModel", "setWakeOnLockScreen($enabled) failed", it)
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            // The enabled flip + schedule/cancel transition must run as ONE linearized
            // scheduler call — writing Room here first and scheduling/cancelling async
            // afterwards would let a concurrent transition observe the half-updated state
            // (e.g. a stale snapshot re-enabling a just-paused job).
            try {
                scheduler.setEnabled(id, enabled)
            } catch (c: CancellationException) {
                // ViewModel scope cancellation must propagate — never swallow it as a
                // scheduling failure.
                throw c
            } catch (t: Throwable) {
                // The enabled flip is persisted by setEnabled BEFORE the cancel/schedule, so
                // a failed WorkManager operation leaves the database in the requested state.
                // Log (observable) instead of crashing the UI scope; the boot reconcile
                // worker retries the schedule/cancel.
                android.util.Log.w(
                    "ScheduledJobsViewModel",
                    "setEnabled($id, $enabled): scheduler transition failed", t,
                )
            }
        }
    }

    fun reconcileSchedules() {
        viewModelScope.launch(Dispatchers.IO) {
            scheduler.reconcileAllEnabled()
        }
    }

    fun delete(id: String, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            scheduler.cancel(id)
            runRepository.deleteAllForJob(id)
            repository.deleteById(id)
            // Callers pass UI work (nav.popBackStack) — must not run on the IO dispatcher.
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    /** Manually fire — same path as the LLM's trigger_job_now tool. */
    suspend fun runNow(id: String): RunNowOutcome {
        val job = repository.getById(id) ?: return RunNowOutcome.NotFound
        if (!job.enabled) return RunNowOutcome.Disabled
        scheduler.triggerNow(id)
        return RunNowOutcome.Fired
    }

    suspend fun history(id: String, limit: Int = 20): List<ScheduledJobRunEntity> =
        runRepository.getRecent(id, limit)

    suspend fun get(id: String): ScheduledJobEntity? = repository.getById(id)

    fun canScheduleExactAlarms(): Boolean = scheduler.canScheduleExactAlarms()

    enum class RunNowOutcome { Fired, NotFound, Disabled }
}
