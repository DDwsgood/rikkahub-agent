package me.rerere.rikkahub.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceSdcardMode
import me.rerere.workspace.WorkspaceShellContext
import me.rerere.workspace.WorkspaceShellRunner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Concurrency & lifecycle regression tests for [WorkspaceRepository]:
 * 1. P0-2: Structured `writeRootfsText` serialized under `mountLockFor(id)` against concurrent
 *    `setSdcardMode` downgrade (`READ_WRITE -> READ_ONLY` / `READ_WRITE -> NONE`) and `delete`.
 * 2. P1-3: Foreground `executeCommand` cancelled DURING the `NonCancellable` launch critical
 *    section always cleans up its spawned process in `finally` (zero leaked processes).
 */
class WorkspaceRepositoryLifecycleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeWorkspaceDao(initial: List<WorkspaceEntity>) : WorkspaceDAO {
        private val state = MutableStateFlow(initial.associateBy { it.id })
        @Volatile var onUpsertHook: (suspend (WorkspaceEntity) -> Unit)? = null
        @Volatile var afterUpsertHook: (suspend () -> Unit)? = null

        override fun listFlow(): Flow<List<WorkspaceEntity>> = state.map { it.values.toList() }
        override suspend fun getById(id: String): WorkspaceEntity? = state.value[id]
        override suspend fun upsert(workspace: WorkspaceEntity) {
            onUpsertHook?.invoke(workspace)
            state.value = state.value + (workspace.id to workspace)
            afterUpsertHook?.invoke()
        }
        override suspend fun getAll(): List<WorkspaceEntity> = state.value.values.toList()
        override suspend fun updateName(id: String, name: String, updatedAt: Long): Int {
            val current = state.value[id] ?: return 0
            state.value = state.value + (id to current.copy(name = name, updatedAt = updatedAt))
            return 1
        }
        override suspend fun updateToolApprovals(id: String, approvals: String, updatedAt: Long): Int {
            val current = state.value[id] ?: return 0
            state.value = state.value + (id to current.copy(toolApprovals = approvals, updatedAt = updatedAt))
            return 1
        }
        override suspend fun updateSdcardMode(id: String, mode: String, updatedAt: Long) {
            val current = state.value[id] ?: return
            upsert(current.copy(sdcardMode = mode, updatedAt = updatedAt))
        }
        override suspend fun updateShellStatus(id: String, shellStatus: String, updatedAt: Long): Int {
            val cur = state.value[id] ?: return 0
            state.value = state.value + (id to cur.copy(shellStatus = shellStatus, updatedAt = updatedAt))
            return 1
        }
        override suspend fun deleteById(id: String): Int {
            if (id !in state.value) return 0
            state.value = state.value - id
            return 1
        }
    }

    private fun newSettingsStore(scope: CoroutineScope): SettingsStore {
        val dsFile = File(tempFolder.newFolder("ws_repo_ds_${System.nanoTime()}"), "settings.preferences_pb")
        return SettingsStore(
            dataStore = PreferenceDataStoreFactory.create(scope = scope) { dsFile },
            scope = scope,
            settingsFlowRawOverride = null,
        )
    }

    @Test
    fun writeRootfsTextInterleavedWithSdcardDowngradeNeverWritesUnderStalePolicy() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val baseDir = tempFolder.newFolder("ws_base_p02")
        val sdcardDir = tempFolder.newFolder("ws_sdcard_p02")
        val entity = WorkspaceEntity(
            id = "ws-1",
            name = "Test",
            root = "ws1",
            createdAt = 1L,
            updatedAt = 1L,
            sdcardMode = WorkspaceSdcardMode.READ_WRITE.name,
        )
        val dao = FakeWorkspaceDao(listOf(entity))
        val manager = WorkspaceManager(baseDir = baseDir, sdcardDir = sdcardDir)
        manager.ensureWorkspace("ws1")
        val repo = WorkspaceRepository(
            dao = dao,
            manager = manager,
            rootfsInstaller = RootfsInstaller(manager),
            settingsStore = newSettingsStore(scope),
        )

        try {
            // Pause setSdcardMode(READ_WRITE -> READ_ONLY) inside mountLockFor("ws-1") while a
            // concurrent writeRootfsText("/sdcard/blocked.txt") starts and blocks on mountLockFor.
            val downgradeEnteredLock = CompletableDeferred<Unit>()
            val releaseDowngrade = CompletableDeferred<Unit>()
            dao.onUpsertHook = {
                downgradeEnteredLock.complete(Unit)
                releaseDowngrade.await()
            }

            val downgradeJob = async(Dispatchers.IO) {
                repo.setSdcardMode("ws-1", WorkspaceSdcardMode.READ_ONLY)
            }
            withTimeout(2_000L) { downgradeEnteredLock.await() }

            val writeJob = async(Dispatchers.IO) {
                runCatching { repo.writeRootfsText("ws-1", "/sdcard/blocked.txt", "stale-write") }
            }
            // Give writeJob time to reach mountLockFor("ws-1") before releasing downgrade
            kotlinx.coroutines.delay(50)
            releaseDowngrade.complete(Unit)

            assertTrue(downgradeJob.await())
            val writeResult = writeJob.await()
            assertTrue("Write after READ_ONLY downgrade must fail", writeResult.isFailure)
            assertTrue(writeResult.exceptionOrNull()?.message.orEmpty().contains("read-only"))
            assertFalse(File(sdcardDir, "blocked.txt").exists())

            // Now downgrade further to NONE -> write to /sdcard must also fail (never silently write linux/sdcard)
            dao.onUpsertHook = null
            assertTrue(repo.setSdcardMode("ws-1", WorkspaceSdcardMode.NONE))
            val writeNone = runCatching { repo.writeRootfsText("ws-1", "/sdcard/none.txt", "nope") }
            assertTrue("Write when sdcardMode == NONE must fail", writeNone.isFailure)
            assertFalse(File(sdcardDir, "none.txt").exists())
            assertFalse(File(manager.linuxDir("ws1"), "sdcard/none.txt").exists())

            // Unmounted /shared write must also fail loudly
            val writeShared = runCatching { repo.writeRootfsText("ws-1", "/shared/unmounted.txt", "nope") }
            assertTrue("Write when /shared is not mounted must fail", writeShared.isFailure)
            assertFalse(File(manager.linuxDir("ws1"), "shared/unmounted.txt").exists())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun committedMountDowngradeStillTerminatesOldProcessWhenCallerCancels() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val id = "ws-revoke"
        val workspace = WorkspaceEntity(
            id = id,
            name = "Revocation",
            root = id,
            createdAt = 1L,
            updatedAt = 1L,
            sdcardMode = WorkspaceSdcardMode.READ_WRITE.name,
        )
        val dao = FakeWorkspaceDao(listOf(workspace))
        val manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("ws_revoke"),
            shellRunner = object : WorkspaceShellRunner {
                override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult = error("unused")
                override fun start(context: WorkspaceShellContext): Process = ProcessBuilder("sleep", "30").start()
            },
        )
        manager.ensureWorkspace(id)
        val repo = WorkspaceRepository(dao, manager, RootfsInstaller(manager), newSettingsStore(scope))
        val process = manager.startTrackedForegroundCommand(id, "sleep 30").process
        val committed = CompletableDeferred<Unit>()
        val resumeUpdate = CompletableDeferred<Unit>()
        dao.afterUpsertHook = {
            committed.complete(Unit)
            resumeUpdate.await()
        }
        try {
            val request = launch(Dispatchers.IO) { repo.setSdcardMode(id, WorkspaceSdcardMode.READ_ONLY) }
            withTimeout(2_000L) { committed.await() }
            request.cancel()
            resumeUpdate.complete(Unit)
            withTimeout(5_000L) { request.join() }
            assertTrue(process.waitFor(2, TimeUnit.SECONDS))
        } finally {
            resumeUpdate.complete(Unit)
            process.destroyForcibly()
            scope.cancel()
        }
    }

    @Test
    fun executeCommandCancelledDuringLaunchHandoffAlwaysTerminatesSpawnedProcess() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val baseDir = tempFolder.newFolder("ws_base_p13")
        val spawnedRef = AtomicReference<Process?>(null)
        val launchStarted = CompletableDeferred<Unit>()
        val allowLaunchReturn = CompletableDeferred<Unit>()

        val slowLaunchRunner = object : WorkspaceShellRunner {
            override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult =
                error("executeCommand must use startTrackedForegroundCommand")

            override fun start(context: WorkspaceShellContext): Process {
                val proc = ProcessBuilder("/bin/sh", "-c", "sleep 30").start()
                spawnedRef.set(proc)
                launchStarted.complete(Unit)
                // Keep launch inside NonCancellable until caller cancels the outer coroutine.
                runBlocking { allowLaunchReturn.await() }
                return proc
            }
        }

        val entity = WorkspaceEntity(id = "ws-fg", name = "FG", root = "wsfg", createdAt = 1L, updatedAt = 1L)
        val dao = FakeWorkspaceDao(listOf(entity))
        val manager = WorkspaceManager(baseDir = baseDir, shellRunner = slowLaunchRunner)
        manager.ensureWorkspace("wsfg")
        val repo = WorkspaceRepository(
            dao = dao,
            manager = manager,
            rootfsInstaller = RootfsInstaller(manager),
            settingsStore = newSettingsStore(scope),
        )

        try {
            var caughtCancellation = false
            val job = launch(Dispatchers.Default) {
                try {
                    repo.executeCommand("ws-fg", "sleep 30")
                } catch (_: CancellationException) {
                    caughtCancellation = true
                }
            }
            withTimeout(2_000L) { launchStarted.await() }
            // Cancel caller WHILE launch is still inside withContext(NonCancellable + Dispatchers.IO)
            job.cancel()
            kotlinx.coroutines.delay(50)
            allowLaunchReturn.complete(Unit)
            withTimeout(5_000L) { job.join() }

            val spawned = spawnedRef.get()
            assertNotNull("Process should have been launched before cancellation", spawned)
            assertTrue("Cancellation must propagate to outer caller", caughtCancellation)
            assertTrue(
                "Process launched during cancelled handoff must be terminated in finally",
                spawned!!.waitFor(5, TimeUnit.SECONDS),
            )
            assertFalse("Spawned process must not remain alive", spawned.isAlive)
        } finally {
            spawnedRef.get()?.let { if (it.isAlive) it.destroyForcibly() }
            scope.cancel()
        }
    }
}
