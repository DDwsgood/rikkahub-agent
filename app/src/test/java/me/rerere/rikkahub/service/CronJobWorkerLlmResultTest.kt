package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the cron↔ChatService success/failure contract.
 *
 * CronJobWorker now awaits the [SendMessageResult] handle returned by
 * [ChatService.sendMessage] instead of inferring success from the generation flow going
 * null (which cannot distinguish "finished" from "finished with a swallowed exception").
 *
 * The tests exercise the PRODUCTION helpers ChatService uses — [sendFailureResult] for the
 * catch block and [ensureSendMessageResultCompleted] for the identity-safe completion
 * guard — so the "internally caught exception is observable as failure" contract is locked
 * against the real mapping, not against a test-only construction.
 */
class CronJobWorkerLlmResultTest {

    @Test
    fun `completed success handle is observed as success`() = runBlocking {
        val handle = CompletableDeferred<SendMessageResult>()
        val deferred = async { awaitSendMessageResult(handle, timeoutMs = 5_000L) }
        handle.complete(SendMessageResult(success = true))
        val result = deferred.await()
        assertTrue("a naturally-completed send must be observed as success", result!!.success)
        assertNull("a success has no error message", result.errorMessage)
    }

    @Test
    fun `production failure helper includes the exception type`() {
        val result = sendFailureResult(RuntimeException("boom"))
        assertFalse(result.success)
        assertTrue("error message must include the exception type", result.errorMessage!!.contains("RuntimeException"))
        assertTrue("error message must include the exception message", result.errorMessage!!.contains("boom"))
    }

    @Test
    fun `internally-caught exception is observed as failure via the production helper`() = runBlocking {
        // Mirrors exactly what ChatService.sendMessage's catch block does: addError for the
        // UI AND complete the returned handle via the production sendFailureResult mapping.
        val handle = CompletableDeferred<SendMessageResult>()
        val deferred = async { awaitSendMessageResult(handle, timeoutMs = 5_000L) }
        handle.complete(sendFailureResult(RuntimeException("java.net.UnknownHostException: api.example.com")))
        val result = deferred.await()
        assertEquals("an internally-caught exception must surface as failure", false, result!!.success)
        assertTrue("the error message must reach the cron path", result.errorMessage!!.contains("UnknownHostException"))
        assertTrue("the exception type must be visible", result.errorMessage!!.contains("RuntimeException"))
    }

    @Test
    fun `cancelled send surfaces as failure - the cron path never hangs`() = runBlocking {
        // ChatService treats cancellation as a non-error for the UI but completes the
        // handle with failure so awaiters can observe it instead of hanging forever.
        val handle = CompletableDeferred<SendMessageResult>()
        val deferred = async { awaitSendMessageResult(handle, timeoutMs = 5_000L) }
        handle.complete(SendMessageResult(success = false, errorMessage = "cancelled: replaced by newer turn"))
        val result = deferred.await()
        assertEquals(false, result!!.success)
    }

    @Test
    fun `never-completing handle times out to null`() = runBlocking {
        val handle = CompletableDeferred<SendMessageResult>()
        val deferred = async { awaitSendMessageResult(handle, timeoutMs = 100L) }
        // Keep the handle referenced so the deferred doesn't get finalized early.
        delay(50L)
        assertNull(
            "a send that never terminates must report timed_out (null), not success",
            deferred.await(),
        )
    }

    // ---------- identity-safe completion guard (production helper) ----------

    @Test
    fun `guard completes a never-finished handle with failure`() {
        val handle = CompletableDeferred<SendMessageResult>()
        // cause == null models the job completing without its body ever running (e.g. the
        // app scope was cancelled right after launch) — the deferred must still complete.
        ensureSendMessageResultCompleted(handle, cause = null)
        assertTrue("the guard must complete an unfinished handle", handle.isCompleted)
        val result = handle.getCompleted()
        assertFalse(result.success)
        assertTrue(result.errorMessage!!.contains("without a result"))
    }

    @Test
    fun `guard completes with the cancellation cause and type`() {
        val handle = CompletableDeferred<SendMessageResult>()
        ensureSendMessageResultCompleted(handle, CancellationException("scope torn down"))
        val result = handle.getCompleted()
        assertFalse(result.success)
        assertTrue("the failure message must include the cause type", result.errorMessage!!.contains("CancellationException"))
    }

    @Test
    fun `guard is a no-op when the body already completed the handle`() {
        val handle = CompletableDeferred<SendMessageResult>()
        handle.complete(SendMessageResult(success = true))
        ensureSendMessageResultCompleted(handle, CancellationException("late"))
        assertTrue("an already-completed handle must not be overwritten", handle.getCompleted().success)
    }
}
