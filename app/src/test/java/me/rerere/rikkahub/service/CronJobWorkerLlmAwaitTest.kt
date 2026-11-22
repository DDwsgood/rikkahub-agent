package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CronJobWorkerLlmAwaitTest {

    @Test
    fun `external automation still distinguishes an idle flow from a timeout`() = runBlocking {
        val state = MutableStateFlow<Job?>(null)
        assertTrue(awaitGenerationTerminal(state, timeoutMs = 1_000L))
        state.value = Job()
        assertFalse(awaitGenerationTerminal(state, timeoutMs = 50L))
    }

    @Test
    fun `waits for the turn result and joins generation on completion`() = runBlocking {
        val result = CompletableDeferred<SendMessageResult>()
        val generation = Job()
        val worker = async(start = CoroutineStart.UNDISPATCHED) {
            awaitLlmTurn(SendMessageTurn(result, generation))
        }

        assertFalse(worker.isCompleted)
        val success = SendMessageResult(success = true)
        result.complete(success)
        try {
            yield()
            assertFalse(worker.isCompleted)
        } finally {
            generation.complete()
        }
        assertEquals(success, worker.await())
        assertFalse(generation.isCancelled)
    }

    @Test
    fun `cancelling the worker while awaiting cancels its generation`() = runBlocking {
        val result = CompletableDeferred<SendMessageResult>()
        val generation = Job()
        val worker = async(start = CoroutineStart.UNDISPATCHED) {
            awaitLlmTurn(SendMessageTurn(result, generation))
        }

        worker.cancelAndJoin()

        assertTrue(worker.isCancelled)
        assertTrue(generation.isCancelled)
        assertTrue(generation.isCompleted)
    }
}
