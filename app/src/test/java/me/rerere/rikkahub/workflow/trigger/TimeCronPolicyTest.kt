package me.rerere.rikkahub.workflow.trigger

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.work.ExistingPeriodicWorkPolicy
import kotlinx.coroutines.cancel
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure-JVM tests for [TimeCronPolicy] decision helpers. These cover the scheduling
 * policy extracted from the time/cron family without requiring Android/Room/Koin.
 *
 * The policy functions determine:
 *  1. Whether a workflow needs to be (re)scheduled given its previous state.
 *  2. Whether the schedule is "new" (KEEP) vs a "real config change" (UPDATE/REPLACE).
 *  3. Whether a one-shot workflow should be re-enqueued after a fire.
 */
class TimeCronPolicyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun wf(
        id: String = "wf-1",
        enabled: Boolean = true,
        trigger: TriggerSpec.TimeCron = TriggerSpec.TimeCron(timeOfDay = "09:00"),
        updatedAtMs: Long = 1000L,
    ) = WorkflowDefinition(
        id = id,
        name = "test",
        trigger = trigger,
        actions = emptyList(),
        enabled = enabled,
        updatedAtMs = updatedAtMs,
    )

    // --- needsScheduling ---

    @Test fun `null previous always needs scheduling`() {
        assertTrue(TimeCronPolicy.needsScheduling(prev = null, current = wf()))
    }

    @Test fun `identical enabled workflow does not need rescheduling`() {
        val w = wf()
        assertFalse(TimeCronPolicy.needsScheduling(prev = w, current = w))
    }

    @Test fun `trigger change needs rescheduling`() {
        val prev = wf(trigger = TriggerSpec.TimeCron(timeOfDay = "09:00"))
        val curr = wf(trigger = TriggerSpec.TimeCron(timeOfDay = "10:00"))
        assertTrue(TimeCronPolicy.needsScheduling(prev, curr))
    }

    @Test fun `updatedAtMs change needs rescheduling`() {
        val prev = wf(updatedAtMs = 1000L)
        val curr = wf(updatedAtMs = 2000L)
        assertTrue(TimeCronPolicy.needsScheduling(prev, curr))
    }

    @Test fun `previously disabled needs rescheduling even if config unchanged`() {
        val prev = wf(enabled = false)
        val curr = wf(enabled = true)
        // Same trigger + same updatedAtMs, but prev was disabled
        assertTrue(TimeCronPolicy.needsScheduling(prev, curr))
    }

    // --- isNewSchedule ---

    @Test fun `null previous is new schedule`() {
        assertTrue(TimeCronPolicy.isNewSchedule(prev = null))
    }

    @Test fun `previously disabled is new schedule`() {
        assertTrue(TimeCronPolicy.isNewSchedule(prev = wf(enabled = false)))
    }

    @Test fun `previously enabled is not new schedule`() {
        assertFalse(TimeCronPolicy.isNewSchedule(prev = wf(enabled = true)))
    }

    // --- shouldReEnqueueOneShot ---

    @Test fun `null period re-enqueues one-shot`() {
        // Arbitrary 5-field cron -> null period -> one-shot path
        assertTrue(TimeCronPolicy.shouldReEnqueueOneShot(periodMs = null))
    }

    @Test fun `period below 15 minutes re-enqueues one-shot`() {
        // @every 60s = 60000ms, below 15min minimum -> one-shot reschedule
        assertTrue(TimeCronPolicy.shouldReEnqueueOneShot(periodMs = 60_000L))
    }

    @Test fun `period exactly 15 minutes does not re-enqueue one-shot`() {
        // 15min = 900000ms, at the periodic boundary -> periodic path
        assertFalse(TimeCronPolicy.shouldReEnqueueOneShot(periodMs = 900_000L))
    }

    @Test fun `period above 15 minutes does not re-enqueue one-shot`() {
        // @hourly = 3600000ms -> periodic path
        assertFalse(TimeCronPolicy.shouldReEnqueueOneShot(periodMs = 3_600_000L))
    }

    @Test fun `24h daily period does not re-enqueue one-shot`() {
        assertFalse(TimeCronPolicy.shouldReEnqueueOneShot(periodMs = 24L * 60 * 60 * 1000))
    }

    // --- decideLegacyOneShotMigration (upgrade compatibility) ---

    @Test fun `upgrade cold start preserves active queued legacy one-shot work`() {
        val action = TimeCronPolicy.decideLegacyOneShotMigration(
            isNew = true,
            appendAfterCurrent = false,
            legacyEntries = listOf(TimeCronPolicy.LegacyWorkEntry(isActive = true, isPeriodic = false)),
        )
        org.junit.Assert.assertEquals(
            TimeCronPolicy.LegacyOneShotMigrationAction.KEEP_LEGACY_ONE_SHOT,
            action,
        )
    }

    @Test fun `upgrade cold start cancels legacy periodic work and migrates to new one-shot name`() {
        val action = TimeCronPolicy.decideLegacyOneShotMigration(
            isNew = true,
            appendAfterCurrent = false,
            legacyEntries = listOf(TimeCronPolicy.LegacyWorkEntry(isActive = true, isPeriodic = true)),
        )
        org.junit.Assert.assertEquals(
            TimeCronPolicy.LegacyOneShotMigrationAction.CANCEL_LEGACY_AND_ENQUEUE_NEW,
            action,
        )
    }

    @Test fun `explicit config edit cancels legacy one-shot and enqueues replacement on new name`() {
        val action = TimeCronPolicy.decideLegacyOneShotMigration(
            isNew = false,
            appendAfterCurrent = false,
            legacyEntries = listOf(TimeCronPolicy.LegacyWorkEntry(isActive = true, isPeriodic = false)),
        )
        org.junit.Assert.assertEquals(
            TimeCronPolicy.LegacyOneShotMigrationAction.CANCEL_LEGACY_AND_ENQUEUE_NEW,
            action,
        )
    }

    @Test fun `post-fire continuation always enqueues under new one-shot name without cancelling legacy`() {
        val action = TimeCronPolicy.decideLegacyOneShotMigration(
            isNew = false,
            appendAfterCurrent = true,
            legacyEntries = listOf(TimeCronPolicy.LegacyWorkEntry(isActive = true, isPeriodic = false)),
        )
        org.junit.Assert.assertEquals(
            TimeCronPolicy.LegacyOneShotMigrationAction.ENQUEUE_NEW_ONLY,
            action,
        )
    }

    @Test fun `no active legacy work enqueues new one-shot directly`() {
        val action = TimeCronPolicy.decideLegacyOneShotMigration(
            isNew = true,
            appendAfterCurrent = false,
            legacyEntries = listOf(TimeCronPolicy.LegacyWorkEntry(isActive = false, isPeriodic = false)),
        )
        org.junit.Assert.assertEquals(
            TimeCronPolicy.LegacyOneShotMigrationAction.ENQUEUE_NEW_ONLY,
            action,
        )
    }

    @Test fun `blocked non-terminal legacy one-shot work is preserved on upgrade sync`() {
        val blockedState = androidx.work.WorkInfo.State.BLOCKED
        assertFalse("BLOCKED must be treated as non-terminal", blockedState.isFinished)
        val action = TimeCronPolicy.decideLegacyOneShotMigration(
            isNew = true,
            appendAfterCurrent = false,
            legacyEntries = listOf(
                TimeCronPolicy.LegacyWorkEntry(
                    isActive = !blockedState.isFinished,
                    isPeriodic = false,
                )
            ),
        )
        org.junit.Assert.assertEquals(
            TimeCronPolicy.LegacyOneShotMigrationAction.KEEP_LEGACY_ONE_SHOT,
            action,
        )
    }

    @Test fun `legacy lookup failure on upgrade cold start defers instead of duplicating chain`() {
        val action = TimeCronPolicy.decideLegacyOneShotMigration(
            isNew = true,
            appendAfterCurrent = false,
            legacyEntries = emptyList(),
            legacyLookupFailed = true,
        )
        org.junit.Assert.assertEquals(
            TimeCronPolicy.LegacyOneShotMigrationAction.KEEP_LEGACY_ONE_SHOT,
            action,
        )
    }

    @Test fun `legacy lookup failure on explicit config edit cancels legacy and replaces`() {
        val action = TimeCronPolicy.decideLegacyOneShotMigration(
            isNew = false,
            appendAfterCurrent = false,
            legacyEntries = emptyList(),
            legacyLookupFailed = true,
        )
        org.junit.Assert.assertEquals(
            TimeCronPolicy.LegacyOneShotMigrationAction.CANCEL_LEGACY_AND_ENQUEUE_NEW,
            action,
        )
    }

    // --- Real DataStore-backed TimeCronTriggerFamily P1-1 / P1-2 / P1-4 / P1-5 tests ---

    private fun newDataStoreSlotStore(scope: kotlinx.coroutines.CoroutineScope, name: String): DataStoreTimeCronSlotStore {
        val file = File(tempFolder.newFolder("timecron_ds_${name}_${System.nanoTime()}"), "slots.preferences_pb")
        return DataStoreTimeCronSlotStore(
            PreferenceDataStoreFactory.create(scope = scope) { file }
        )
    }

    private open class StubWorkAdapter : TimeCronWorkAdapter {
        val activeRenewalTags = mutableSetOf<String>()
        val finishedWorkIds = mutableSetOf<String>()
        var oneShotEnqueueCount = 0

        override suspend fun queryLegacyEntries(workflowId: String) = emptyList<TimeCronPolicy.LegacyWorkEntry>()
        override suspend fun cancelLegacyWork(workflowId: String) = Unit
        override suspend fun cancelOneShotWork(workflowId: String) = Unit
        override suspend fun enqueuePeriodic(
            workflowId: String,
            periodMs: Long,
            delayMs: Long,
            policy: androidx.work.ExistingPeriodicWorkPolicy,
        ) = Unit
        override suspend fun enqueueOneShot(
            workflowId: String,
            delayMs: Long,
            policy: androidx.work.ExistingWorkPolicy,
            renewalTag: String?,
        ) {
            oneShotEnqueueCount++
            if (!renewalTag.isNullOrBlank()) {
                activeRenewalTags += renewalTag
            }
        }
        override suspend fun hasActiveRenewal(workflowId: String, renewalTag: String): Boolean =
            renewalTag in activeRenewalTags
        override suspend fun isWorkRequestFinished(workId: String): Boolean =
            workId in finishedWorkIds
    }

    @Test fun `cold periodic sync replaces legacy one-shot but keeps existing periodic work`() = kotlinx.coroutines.runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val cronWf = wf()
        try {
            for (existingIsPeriodic in listOf(false, true)) {
                val operations = mutableListOf<String>()
                val adapter = object : StubWorkAdapter() {
                    override suspend fun queryLegacyEntries(workflowId: String) =
                        listOf(TimeCronPolicy.LegacyWorkEntry(isActive = true, isPeriodic = existingIsPeriodic))

                    override suspend fun cancelLegacyWork(workflowId: String) {
                        operations += "cancel legacy"
                    }

                    override suspend fun cancelOneShotWork(workflowId: String) {
                        operations += "cancel one-shot"
                    }

                    override suspend fun enqueuePeriodic(
                        workflowId: String,
                        periodMs: Long,
                        delayMs: Long,
                        policy: ExistingPeriodicWorkPolicy,
                    ) {
                        operations += "enqueue $policy"
                    }
                }
                val family = TimeCronTriggerFamily(
                    scope = scope,
                    workAdapter = adapter,
                    firedSlotStore = newDataStoreSlotStore(scope, "periodic_migration"),
                    syncRetryDelaysMs = longArrayOf(),
                    repoFallbackLookup = { cronWf },
                )
                var fireCount = 0
                val callback = TriggerFireCallback { _, _ -> fireCount++ }
                family.sync(listOf(cronWf), callback)
                family.sync(listOf(cronWf), callback)
                val expected = if (existingIsPeriodic) {
                    listOf("cancel one-shot", "enqueue KEEP")
                } else {
                    listOf("cancel legacy", "cancel one-shot", "enqueue KEEP")
                }
                assertEquals(expected, operations)
                family.onWorkerFired(cronWf.id, "stale-one-shot", requestIsPeriodic = false)
                assertEquals(0, fireCount)
                family.onWorkerFired(cronWf.id, "current-periodic", requestIsPeriodic = true)
                assertEquals(1, fireCount)
                assertEquals(0, adapter.oneShotEnqueueCount)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun `transient preflight failure does not claim the slot and the same worker can retry`() = kotlinx.coroutines.runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val store = newDataStoreSlotStore(scope, "preflight")
        val adapter = StubWorkAdapter()
        val cronWf = wf(trigger = TriggerSpec.TimeCron(cron = "*/5 * * * *"))
        val workId = "work-uuid-preflight"
        var preflightAttempts = 0
        var fireCount = 0
        val callback = object : TriggerFireCallback {
            override suspend fun onFire(workflowId: String, matchSpec: TriggerSpec) =
                error("Expected the scheduled action boundary")

            override suspend fun onScheduledFire(
                workflowId: String,
                matchSpec: TriggerSpec.TimeCron,
                beforeActions: suspend () -> Unit,
            ) {
                if (++preflightAttempts == 1) throw java.io.IOException("transient Room failure")
                beforeActions()
                assertEquals(TimeCronSlotPhase.CLAIMED, store.get(workId)?.phase)
                fireCount++
            }
        }
        val family = TimeCronTriggerFamily(
            scope = scope,
            workAdapter = adapter,
            firedSlotStore = store,
            syncRetryDelaysMs = longArrayOf(),
            repoFallbackLookup = { cronWf },
            engineCallbackFallback = { callback },
        )
        try {
            org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                kotlinx.coroutines.runBlocking { family.onWorkerFired(cronWf.id, workId) }
            }
            assertNull(store.get(workId))
            assertEquals(0, fireCount)
            assertEquals(0, adapter.oneShotEnqueueCount)
            family.onWorkerFired(cronWf.id, workId)
            assertEquals(1, fireCount)
            assertEquals(1, adapter.oneShotEnqueueCount)
        } finally {
            scope.cancel()
        }
    }

    @Test fun `one-shot renewal retry and replay before WorkManager confirmation neither re-fires nor duplicates next slot`() = kotlinx.coroutines.runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val slotStore = newDataStoreSlotStore(scope, "renew")
        var failFirstRenewal = true
        val adapter = object : StubWorkAdapter() {
            override suspend fun enqueueOneShot(
                workflowId: String,
                delayMs: Long,
                policy: androidx.work.ExistingWorkPolicy,
                renewalTag: String?,
            ) {
                if (renewalTag != null && failFirstRenewal) {
                    failFirstRenewal = false
                    throw java.io.IOException("simulated transient WorkManager failure on renewal")
                }
                super.enqueueOneShot(workflowId, delayMs, policy, renewalTag)
            }
        }
        val cronWf = wf(
            id = "wf-renew",
            trigger = TriggerSpec.TimeCron(cron = "0 9 * * 1"),
        )
        val family = TimeCronTriggerFamily(
            scope = scope,
            workAdapter = adapter,
            firedSlotStore = slotStore,
            syncRetryDelaysMs = longArrayOf(),
            repoFallbackLookup = { if (it == cronWf.id) cronWf else null },
        )
        var fireCount = 0
        val callback = TriggerFireCallback { _, _ -> fireCount++ }

        try {
            family.sync(listOf(cronWf), callback)
            adapter.oneShotEnqueueCount = 0

            // Attempt 1: cb.onFire runs, FIRED_PENDING_RENEWAL persisted on disk, renewal fails -> retry
            val err = org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                kotlinx.coroutines.runBlocking {
                    family.onWorkerFired(cronWf.id, workerExecutionId = "work-uuid-1")
                }
            }
            assertTrue(err.message!!.contains("transient"))
            org.junit.Assert.assertEquals(1, fireCount)
            org.junit.Assert.assertEquals(
                TimeCronSlotPhase.FIRED_PENDING_RENEWAL,
                slotStore.get("work-uuid-1")?.phase,
            )

            // Attempt 2 (WorkManager retry with SAME work-uuid-1): skips onFire, enqueues next slot ONCE,
            // promotes checkpoint to RENEWED and KEEPS it persisted until WorkManager confirms finished!
            family.onWorkerFired(cronWf.id, workerExecutionId = "work-uuid-1")
            org.junit.Assert.assertEquals(1, fireCount)
            org.junit.Assert.assertEquals(1, adapter.oneShotEnqueueCount)
            org.junit.Assert.assertEquals(
                "Checkpoint MUST stay persisted as RENEWED until WorkManager confirms finished",
                TimeCronSlotPhase.RENEWED,
                slotStore.get("work-uuid-1")?.phase,
            )

            // Attempt 3 (P1-1: Process died AFTER next slot persisted, BEFORE WorkManager marked work-uuid-1 SUCCEEDED):
            // Replay of SAME work-uuid-1 must NEITHER re-fire cb.onFire NOR enqueue a duplicate next slot!
            family.onWorkerFired(cronWf.id, workerExecutionId = "work-uuid-1")
            org.junit.Assert.assertEquals("Replay before WM finish must NOT re-fire action", 1, fireCount)
            org.junit.Assert.assertEquals("Replay before WM finish must NOT append duplicate next slot", 1, adapter.oneShotEnqueueCount)

            // Once WorkManager confirms work-uuid-1 is finished, pruning removes it cleanly.
            adapter.finishedWorkIds += "work-uuid-1"
            family.pruneConfirmedFinishedCheckpoints()
            org.junit.Assert.assertNull(slotStore.get("work-uuid-1"))
        } finally {
            family.shutdown()
            scope.cancel()
        }
    }

    @Test fun `crash between enqueueOneShot and RENEWED write uses stable renewalTag to prevent duplicate next slot`() = kotlinx.coroutines.runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val slotStore = newDataStoreSlotStore(scope, "midcrash")
        val adapter = StubWorkAdapter()
        val cronWf = wf(id = "wf-midcrash", trigger = TriggerSpec.TimeCron(cron = "0 9 * * 1"))
        val family = TimeCronTriggerFamily(
            scope = scope,
            workAdapter = adapter,
            firedSlotStore = slotStore,
            syncRetryDelaysMs = longArrayOf(),
            repoFallbackLookup = { if (it == cronWf.id) cronWf else null },
        )
        var fireCount = 0

        try {
            family.sync(listOf(cronWf), TriggerFireCallback { _, _ -> fireCount++ })
            adapter.oneShotEnqueueCount = 0

            // Simulate crash right AFTER enqueueOneShot(renewalTag) committed to WorkManager,
            // BEFORE slotStore could promote FIRED_PENDING_RENEWAL -> RENEWED.
            val tag = TimeCronTriggerFamily.renewalTagFor("work-uuid-mid")
            slotStore.upsert(
                TimeCronSlotCheckpoint(
                    slotKey = "work-uuid-mid",
                    workflowId = cronWf.id,
                    phase = TimeCronSlotPhase.FIRED_PENDING_RENEWAL,
                    renewalTag = tag,
                    updatedAtMs = 1000L,
                )
            )
            adapter.activeRenewalTags += tag

            // WorkManager replays work-uuid-mid -> detects active renewal by stable tag -> 0 fires, 0 extra enqueues!
            family.onWorkerFired(cronWf.id, workerExecutionId = "work-uuid-mid")
            org.junit.Assert.assertEquals(0, fireCount)
            org.junit.Assert.assertEquals(0, adapter.oneShotEnqueueCount)
            org.junit.Assert.assertEquals(TimeCronSlotPhase.RENEWED, slotStore.get("work-uuid-mid")?.phase)
        } finally {
            family.shutdown()
            scope.cancel()
        }
    }

    @Test fun `claim persistence failure leaves the action untouched until a retry can claim it`() = kotlinx.coroutines.runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val slotStore = newDataStoreSlotStore(scope, "claim_failure")
        var failClaim = true
        val failingStore = object : TimeCronFiredSlotStore by slotStore {
            override suspend fun upsert(checkpoint: TimeCronSlotCheckpoint) {
                if (failClaim) throw java.io.IOException("disk full: cannot persist checkpoint")
                slotStore.upsert(checkpoint)
            }
        }
        val adapter = StubWorkAdapter()
        val cronWf = wf(id = "wf-diskfull", trigger = TriggerSpec.TimeCron(cron = "*/5 * * * *"))
        var fireCount = 0
        val family = TimeCronTriggerFamily(
            scope = scope,
            workAdapter = adapter,
            firedSlotStore = failingStore,
            syncRetryDelaysMs = longArrayOf(),
            repoFallbackLookup = { if (it == cronWf.id) cronWf else null },
            engineCallbackFallback = { TriggerFireCallback { _, _ -> fireCount++ } },
        )

        try {
            org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                kotlinx.coroutines.runBlocking {
                    family.onWorkerFired(cronWf.id, workerExecutionId = "work-uuid-diskfull")
                }
            }
            org.junit.Assert.assertEquals(0, fireCount)
            org.junit.Assert.assertEquals(0, adapter.oneShotEnqueueCount)
            failClaim = false
            family.onWorkerFired(cronWf.id, workerExecutionId = "work-uuid-diskfull")
            org.junit.Assert.assertEquals(1, fireCount)
            org.junit.Assert.assertEquals(1, adapter.oneShotEnqueueCount)
        } finally {
            family.shutdown()
            scope.cancel()
        }
    }

    @Test fun `replay after the action but before the fired checkpoint only renews the slot`() = kotlinx.coroutines.runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val slotStore = newDataStoreSlotStore(scope, "claimed_replay")
        val adapter = StubWorkAdapter()
        val cronWf = wf(id = "wf-claimed-replay", trigger = TriggerSpec.TimeCron(cron = "*/5 * * * *"))
        val interruptedStore = object : TimeCronFiredSlotStore by slotStore {
            override suspend fun upsert(checkpoint: TimeCronSlotCheckpoint) {
                if (checkpoint.phase == TimeCronSlotPhase.FIRED_PENDING_RENEWAL) {
                    throw kotlinx.coroutines.CancellationException("interrupted after action")
                }
                slotStore.upsert(checkpoint)
            }
        }
        var fireCount = 0
        fun family(store: TimeCronFiredSlotStore) = TimeCronTriggerFamily(
            scope = scope,
            workAdapter = adapter,
            firedSlotStore = store,
            syncRetryDelaysMs = longArrayOf(),
            repoFallbackLookup = { cronWf },
            engineCallbackFallback = { TriggerFireCallback { _, _ -> fireCount++ } },
        )

        try {
            org.junit.Assert.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                kotlinx.coroutines.runBlocking {
                    family(interruptedStore).onWorkerFired(cronWf.id, workerExecutionId = "work-uuid-claimed")
                }
            }
            org.junit.Assert.assertEquals(1, fireCount)
            org.junit.Assert.assertEquals(TimeCronSlotPhase.CLAIMED, slotStore.get("work-uuid-claimed")?.phase)

            family(slotStore).onWorkerFired(cronWf.id, workerExecutionId = "work-uuid-claimed")
            org.junit.Assert.assertEquals(1, fireCount)
            org.junit.Assert.assertEquals(1, adapter.oneShotEnqueueCount)
            org.junit.Assert.assertEquals(TimeCronSlotPhase.RENEWED, slotStore.get("work-uuid-claimed")?.phase)
        } finally {
            scope.cancel()
        }
    }

    @Test fun `active checkpoint backlog over 100 slots is never evicted until WorkManager confirms finished`() = kotlinx.coroutines.runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val slotStore = newDataStoreSlotStore(scope, "backlog")
        val adapter = StubWorkAdapter()
        val family = TimeCronTriggerFamily(
            scope = scope,
            workAdapter = adapter,
            firedSlotStore = slotStore,
            syncRetryDelaysMs = longArrayOf(),
        )
        try {
            // Persist 100 active checkpoints (exceeding the old 64-item cap)
            repeat(100) { i ->
                val key = "slot-backlog-$i"
                slotStore.upsert(
                    TimeCronSlotCheckpoint(
                        slotKey = key,
                        workflowId = "wf-$i",
                        phase = if (i % 2 == 0) TimeCronSlotPhase.FIRED_PENDING_RENEWAL else TimeCronSlotPhase.RENEWED,
                        renewalTag = TimeCronTriggerFamily.renewalTagFor(key),
                        updatedAtMs = 1000L + i,
                    )
                )
            }
            org.junit.Assert.assertEquals("All 100 active checkpoints must survive on disk", 100, slotStore.allCheckpoints().size)

            // Confirm ONLY 10 of them finished in WorkManager -> remaining 90 active/unconfirmed stay intact!
            repeat(10) { i -> adapter.finishedWorkIds += "slot-backlog-$i" }
            family.pruneConfirmedFinishedCheckpoints()
            org.junit.Assert.assertEquals(90, slotStore.allCheckpoints().size)
            org.junit.Assert.assertNull(slotStore.get("slot-backlog-0"))
            org.junit.Assert.assertNotNull(slotStore.get("slot-backlog-10"))
            org.junit.Assert.assertNotNull(slotStore.get("slot-backlog-99"))
        } finally {
            family.shutdown()
            scope.cancel()
        }
    }

    @Test fun `transient initial sync lookup failure automatically recovers via bounded independent retry`() = kotlinx.coroutines.runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        var queryCount = 0
        val enqueued = kotlinx.coroutines.CompletableDeferred<String>()
        val adapter = object : StubWorkAdapter() {
            override suspend fun queryLegacyEntries(workflowId: String): List<TimeCronPolicy.LegacyWorkEntry> {
                queryCount++
                if (queryCount <= 2) throw java.io.IOException("transient lookup error #$queryCount")
                return emptyList()
            }
            override suspend fun enqueueOneShot(
                workflowId: String,
                delayMs: Long,
                policy: androidx.work.ExistingWorkPolicy,
                renewalTag: String?,
            ) {
                super.enqueueOneShot(workflowId, delayMs, policy, renewalTag)
                enqueued.complete(workflowId)
            }
        }
        val cronWf = wf(
            id = "wf-retry-sync",
            trigger = TriggerSpec.TimeCron(cron = "*/5 * * * *"),
        )
        val family = TimeCronTriggerFamily(
            scope = scope,
            workAdapter = adapter,
            firedSlotStore = newDataStoreSlotStore(scope, "retrysync"),
            syncRetryDelaysMs = longArrayOf(15L, 15L, 15L),
        )
        try {
            family.sync(listOf(cronWf), TriggerFireCallback { _, _ -> })
            val armedId = kotlinx.coroutines.withTimeout(2_000L) { enqueued.await() }
            org.junit.Assert.assertEquals(cronWf.id, armedId)
            org.junit.Assert.assertEquals(3, queryCount)
        } finally {
            family.shutdown()
            scope.cancel()
        }
    }

    @Test fun `persistent sync failure stops cleanly after bounded retry cap`() = kotlinx.coroutines.runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        var queryCount = 0
        val adapter = object : StubWorkAdapter() {
            override suspend fun queryLegacyEntries(workflowId: String): List<TimeCronPolicy.LegacyWorkEntry> {
                queryCount++
                throw java.io.IOException("permanent failure")
            }
        }
        val cronWf = wf(
            id = "wf-bounded-cap",
            trigger = TriggerSpec.TimeCron(cron = "*/5 * * * *"),
        )
        val family = TimeCronTriggerFamily(
            scope = scope,
            workAdapter = adapter,
            firedSlotStore = newDataStoreSlotStore(scope, "bounded"),
            syncRetryDelaysMs = longArrayOf(10L, 10L),
        )
        try {
            family.sync(listOf(cronWf), TriggerFireCallback { _, _ -> })
            kotlinx.coroutines.delay(150L)
            // Initial attempt (1) + 2 bounded retries = 3 total queries, then stops.
            org.junit.Assert.assertEquals(3, queryCount)
        } finally {
            family.shutdown()
            scope.cancel()
        }
    }
}
