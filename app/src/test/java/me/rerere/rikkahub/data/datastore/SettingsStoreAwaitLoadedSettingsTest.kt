package me.rerere.rikkahub.data.datastore

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the Cron cold-start fix: calls the real [SettingsStore.awaitLoadedSettings]
 * and asserts it returns the persisted Settings (non-dummy, with the Notification tool the user
 * enabled), even while [SettingsStore.settingsFlow] still holds the dummy placeholder. This is
 * the exact condition under which a headless cron worker must not read `settingsFlow.first()`.
 *
 * The test would FAIL if awaitLoadedSettings() were reverted to `settingsFlow.first()`, because
 * the StateFlow is kept on a manual-pump dispatcher that has not yet propagated the real value.
 */
class SettingsStoreAwaitLoadedSettingsTest {

    /**
     * A single-thread executor whose queue is pumped manually. Used as the SettingsStore scope
     * so the toMutableStateFlow collector that would update settingsFlow from the dummy does NOT
     * run until the test flushes it — reproducing the cold-start window before DataStore loads.
     */
    private class ManualExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()
        private var pumping = false
        override fun execute(command: Runnable) {
            if (pumping) command.run() else queue.addLast(command)
        }
        fun flush() {
            pumping = true
            try {
                while (queue.isNotEmpty()) queue.removeFirst().run()
            } finally {
                pumping = false
            }
        }
    }

    @Test
    fun `awaitLoadedSettings returns the persisted real settings while settingsFlow is still the dummy`() = runBlocking {
        val assistantId = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val realSettings = Settings(
            assistants = listOf(Assistant(id = assistantId, localTools = listOf(LocalToolOption.Notification))),
        )
        // Cold upstream mirroring dataStore.data: emits the real Settings on each collection.
        val upstream: Flow<Settings> = flow { emit(realSettings) }

        val executor = ManualExecutor()
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        val store = SettingsStore(
            dataStore = null,
            scope = scope,
            settingsFlowRawOverride = upstream,
        )

        // The toMutableStateFlow collector has not run (executor unflushed), so settingsFlow
        // still holds the dummy — this is the cold-start race.
        assertTrue("settingsFlow should still be the dummy before load", store.settingsFlow.value.init)

        // awaitLoadedSettings() collects the raw upstream in the caller's context, NOT the
        // unflushed scope, so it returns the REAL persisted Settings — never the dummy.
        val loaded = store.awaitLoadedSettings()
        assertFalse("awaitLoadedSettings must not return the dummy", loaded.init)
        val resolved = loaded.findAssistantById(assistantId)
        assertNotNull(resolved)
        assertTrue(
            "the persisted assistant's Notification option must be present",
            LocalToolOption.Notification in resolved!!.localTools,
        )

        // settingsFlow is STILL the dummy because the collector hasn't been flushed — proving
        // settingsFlow.first() would have returned the dummy here (the bug), while
        // awaitLoadedSettings() returned the real value (the fix).
        assertTrue("settingsFlow is still dummy until the collector is flushed", store.settingsFlow.value.init)

        // After flushing, the collector propagates the real value (UI sync read works post-load).
        executor.flush()
        assertFalse("settingsFlow reflects the real settings after the collector runs", store.settingsFlow.value.init)

        scope.cancel()
    }

    @Test
    fun `awaitLoadedSettings uses the cached StateFlow value after load without re-collecting raw`() = runBlocking {
        val assistantId = Uuid.parse("33333333-3333-3333-3333-333333333333")
        val realSettings = Settings(
            assistants = listOf(Assistant(id = assistantId, localTools = listOf(LocalToolOption.Notification))),
        )
        // Counting upstream: each collection increments the counter, so a redundant raw collect
        // (which the warm fast-path must avoid) is observable.
        val collectCount = AtomicInteger(0)
        val upstream: Flow<Settings> = flow { collectCount.incrementAndGet(); emit(realSettings) }

        val executor = ManualExecutor()
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        val store = SettingsStore(
            dataStore = null,
            scope = scope,
            settingsFlowRawOverride = upstream,
        )

        // Flush so the toMutableStateFlow collector runs once and loads settingsFlow with the
        // real (non-dummy) value.
        executor.flush()
        assertFalse("settingsFlow should hold the real settings after flush", store.settingsFlow.value.init)
        val collectsAfterFlush = collectCount.get()

        // Warm path (settingsFlow already loaded): awaitLoadedSettings() must return the cached
        // value WITHOUT re-collecting the raw upstream — avoiding decode/onEach/Pebble invalidate.
        val loaded = store.awaitLoadedSettings()
        assertFalse(loaded.init)
        assertEquals(
            "awaitLoadedSettings must not re-collect raw on the warm path",
            collectsAfterFlush,
            collectCount.get(),
        )

        scope.cancel()
    }
}