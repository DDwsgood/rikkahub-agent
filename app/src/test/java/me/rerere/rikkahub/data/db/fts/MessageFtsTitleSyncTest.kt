package me.rerere.rikkahub.data.db.fts

import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/**
 * Targeted regression tests for:
 * 1. P1-Reg-3: FTS search query joins live `ConversationEntity.title` and qualifies sort columns
 *    to avoid ambiguous `update_at` SQLite errors.
 * 2. P1-Gap-P1-1: [WorkspaceTerminalRegistry] rejects stale mount-generation registrations.
 * 3. P1-Gap-P1-8: [ConversationSession.sendExecutionMutex] + [ConversationSession.replaceJob]
 *    keeps 3+ rapid sends strictly serialized even when an unstarted middle LAZY job cancels instantly.
 */
class MessageFtsTitleSyncTest {

    @Test
    fun ftsSearchQueryJoinsLiveConversationTitleAndQualifiesOrderByColumns() {
        for (sort in MessageSearchSort.entries) {
            val sql = MessageFtsManager.buildSearchQuerySql(sort)
            assertTrue("Query must LEFT JOIN ConversationEntity for live titles: $sql", sql.contains("LEFT JOIN ConversationEntity"))
            assertTrue("Query must prefer live ConversationEntity.title: $sql", sql.contains("COALESCE(c.title, message_fts.title)"))
            assertTrue("ORDER BY must qualify message_fts.update_at to avoid ambiguity: $sql", sql.contains("message_fts.update_at"))
            assertTrue("ORDER BY must qualify message_fts.rank: $sql", sql.contains("message_fts.rank"))
        }
    }

    @Test
    fun terminalRegistryRejectsStaleMountGenerationSnapshot() {
        val wsId = "ws-gen-test-${Uuid.random()}"
        WorkspaceTerminalRegistry.resetForTest(wsId)
        try {
            val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = unsafeField.get(null) as Unsafe
            val dummySession1 = unsafe.allocateInstance(TerminalSession::class.java) as TerminalSession
            val dummySession2 = unsafe.allocateInstance(TerminalSession::class.java) as TerminalSession

            val snapshotGen = WorkspaceTerminalRegistry.currentGeneration(wsId)
            assertEquals(0L, snapshotGen)

            // Mount policy changes BEFORE session1 finishes constructing & registering
            val bumped = WorkspaceTerminalRegistry.bumpGenerationAndFinishAll(wsId)
            assertEquals(1L, bumped)
            assertEquals(1L, WorkspaceTerminalRegistry.generationFlow(wsId).value)

            // Registering with stale snapshotGen must be rejected!
            assertFalse(WorkspaceTerminalRegistry.registerAndStart(wsId, dummySession1, snapshotGen) {
                error("A stale snapshot must never launch a PTY")
            })
            var started = false
            assertTrue(WorkspaceTerminalRegistry.registerAndStart(wsId, dummySession2, bumped) { started = true })
            assertTrue(started)
        } finally {
            WorkspaceTerminalRegistry.resetForTest(wsId)
        }
    }

    @Test
    fun threeRapidSendsNeverOverlapEvenWhenMiddleLazyJobCancelsBeforeStart() = runBlocking {
        val convId = Uuid.random()
        val session = ConversationSession(
            id = convId,
            initial = Conversation.ofId(convId, Uuid.random()),
            scope = this,
            onIdle = {},
        )
        val firstEntered = CompletableDeferred<Unit>()
        val allowFirstUnwind = CompletableDeferred<Unit>()
        val activeConcurrency = AtomicInteger(0)
        val maxConcurrency = AtomicInteger(0)

        fun recordConcurrency(delta: Int) {
            val now = activeConcurrency.addAndGet(delta)
            maxConcurrency.accumulateAndGet(now, ::maxOf)
        }

        // Job 1 enters sendExecutionMutex and stays unwinding briefly even after cancellation
        var prev1: Job? = null
        val job1 = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            runCatching { prev1?.join() }
            session.sendExecutionMutex.withLock {
                recordConcurrency(1)
                firstEntered.complete(Unit)
                try {
                    allowFirstUnwind.await()
                } finally {
                    // Simulate slow non-cancellable cleanup before exiting
                    Thread.sleep(40)
                    recordConcurrency(-1)
                }
            }
        }
        prev1 = session.replaceJob(job1)
        job1.start()
        firstEntered.await()

        // Job 2 is created LAZY and replaced by Job 3 BEFORE Job 2 ever starts!
        var prev2: Job? = null
        val job2 = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            runCatching { prev2?.join() }
            session.sendExecutionMutex.withLock {
                recordConcurrency(1)
                recordConcurrency(-1)
            }
        }
        prev2 = session.replaceJob(job2)
        // Notice: do NOT start job2 yet — Job 3 arrives immediately and cancels unstarted job2!

        var prev3: Job? = null
        val job3 = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            runCatching { prev3?.join() }
            session.sendExecutionMutex.withLock {
                recordConcurrency(1)
                Thread.sleep(20)
                recordConcurrency(-1)
            }
        }
        prev3 = session.replaceJob(job3)
        job3.start()
        // Release Job 1's unwind after a tiny delay so Job 3 is definitely racing it
        Thread.sleep(25)
        allowFirstUnwind.complete(Unit)

        job3.join()
        job1.cancelAndJoin()
        assertEquals("At most ONE send turn may execute inside sendExecutionMutex at a time", 1, maxConcurrency.get())
    }
}
