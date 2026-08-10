package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import me.rerere.rikkahub.data.preferences.TermuxDefaults
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behavioral coverage for the per-tool execution timeout that replaced the per-turn
 * wall-clock budget (T1 removed the whole-turn 600s budget; a hanging tool then had no outer
 * bound and could stall a turn forever).
 *
 * The timeout logic lives in [executeToolWithTimeout] + [buildToolCancelledTimeoutEnvelope],
 * extracted from [GenerationHandler.generateText] so it can be tested without an Android
 * Context (the timeout path deliberately performs no Android calls). Covers:
 *  1. A tool that never completes times out instead of hanging the turn, and the caller-side
 *     envelope is a structured "tool_cancelled_timeout".
 *  2. A tool that completes within the timeout returns its real output untouched.
 *  3. A CancellationException thrown by a tool propagates — user Stop must cancel the turn,
 *     not degrade into a timeout/failure envelope.
 *  4. maxSteps still defaults to the 32-step model-loop cap (the loop is
 *     `for (stepIndex in 0 until maxSteps)`, bounded by construction).
 *  5. The per-tool timeout defaults to 5 minutes and is runtime-adjustable via the @Volatile
 *     holder (test 1 shrank it the same way).
 */
class GenerationHandlerTurnTimeoutTest {

    private val json = Json { ignoreUnknownKeys = true }

    private var savedTimeoutMs: Long = 0L

    @Before
    fun saveHolder() {
        savedTimeoutMs = ToolRuntimeLimits.perToolExecutionTimeoutMs
    }

    @After
    fun restoreHolder() {
        ToolRuntimeLimits.perToolExecutionTimeoutMs = savedTimeoutMs
    }

    @Test
    fun hangingTool_timesOutAndEnvelopeIsToolCancelledTimeout() = runBlocking {
        // Shrink the live holder (production reads it per call) so the test doesn't wait 5 min.
        ToolRuntimeLimits.perToolExecutionTimeoutMs = 100

        val tool = Tool(
            name = "hang_forever",
            description = "a tool that never completes on its own (simulates a fused-location await)",
            execute = { awaitCancellation() },
        )
        val outcome = executeToolWithTimeout(tool, Json.parseToJsonElement("{}"))
        assertTrue(
            "hanging tool must time out instead of hanging the turn forever",
            outcome is ToolExecutionOutcome.TimedOut
        )
        val timedOut = outcome as ToolExecutionOutcome.TimedOut
        val envelope = buildToolCancelledTimeoutEnvelope(json, timedOut.timeoutMs)
        val obj = json.parseToJsonElement((envelope.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("tool_cancelled_timeout", obj["error"]?.jsonPrimitive?.content)
        val detail = obj["detail"]?.jsonPrimitive?.content
        assertNotNull(detail)
        assertTrue("detail must explain the single-tool timeout", detail!!.contains("single-tool timeout"))
    }

    @Test
    fun toolCompletingWithinTimeout_returnsRealOutput() = runBlocking {
        val tool = Tool(
            name = "fast_tool",
            description = "completes immediately",
            execute = { listOf(UIMessagePart.Text("done")) },
        )
        val outcome = executeToolWithTimeout(
            toolDef = tool,
            args = Json.parseToJsonElement("{}"),
            timeoutMs = 5_000,
        )
        assertTrue(outcome is ToolExecutionOutcome.Completed)
        assertEquals(
            "done",
            ((outcome as ToolExecutionOutcome.Completed).output.single() as UIMessagePart.Text).text
        )
    }

    @Test
    fun cancellationExceptionFromTool_propagatesNotEnvelope() {
        val tool = Tool(
            name = "cancel_tool",
            description = "throws cancellation immediately (user Stop)",
            execute = { throw CancellationException("user stopped the turn") },
        )
        var propagated = false
        try {
            runBlocking {
                executeToolWithTimeout(
                    toolDef = tool,
                    args = Json.parseToJsonElement("{}"),
                    timeoutMs = 5_000,
                )
            }
        } catch (t: Throwable) {
            propagated = t is CancellationException
        }
        assertTrue(
            "a tool-thrown CancellationException must propagate (not become a timeout/failure envelope)",
            propagated
        )
    }

    @Test
    fun maxSteps_defaultIs32() {
        assertEquals(
            "maxToolSteps must still default to the 32-step model-loop cap",
            32,
            ToolRuntimeLimits.maxToolSteps
        )
        assertEquals(TermuxDefaults.DEFAULT_MAX_TOOL_STEPS, ToolRuntimeLimits.maxToolSteps)
    }

    @Test
    fun perToolTimeout_defaultIs5Minutes_andHolderIsRuntimeMutable() {
        assertEquals(300_000L, ToolRuntimeLimits.DEFAULT_PER_TOOL_EXECUTION_TIMEOUT_MS)
        assertEquals(300_000L, ToolRuntimeLimits.perToolExecutionTimeoutMs)
        ToolRuntimeLimits.perToolExecutionTimeoutMs = 500L
        assertEquals(500L, ToolRuntimeLimits.perToolExecutionTimeoutMs)
    }
}
