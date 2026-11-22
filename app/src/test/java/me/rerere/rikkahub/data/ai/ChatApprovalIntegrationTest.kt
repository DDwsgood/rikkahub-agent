package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.approval.ToolReviewAction
import me.rerere.rikkahub.data.ai.approval.ToolReviewResult
import me.rerere.rikkahub.data.preferences.ChatPermissionMode
import me.rerere.rikkahub.service.ChatComposerSubmission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatApprovalIntegrationTest {
    @Test
    fun manualModeRevokesInFlightAutomaticApproval() = runBlocking {
        var mode = ChatPermissionMode.AUTO_REVIEW
        val reviewer = CompletableDeferred<ToolReviewResult>()
        val decision = async(start = CoroutineStart.UNDISPATCHED) {
            reviewForPermissionMode(mode, { mode }) { reviewer.await() }
        }

        mode = ChatPermissionMode.MANUAL
        reviewer.complete(ToolReviewResult.allow("test"))

        assertEquals(ToolReviewAction.ASK_USER, decision.await()?.action)
    }

    @Test
    fun emittingStartDoesNotReleaseExecutionUntilCollectorAcknowledgesPersistence() = runBlocking {
        val chunk = CompletableDeferred<GenerationChunk.Messages>()
        val start = async(start = CoroutineStart.UNDISPATCHED) {
            persistToolExecutionState(emptyList()) { chunk.complete(it) }
        }

        val acknowledgement = requireNotNull(chunk.await().toolExecutionPersistenceAck)
        assertFalse(start.isCompleted)
        acknowledgement.complete(Unit)
        start.await()
    }

    @Test
    fun modeChangeAfterPersistedStartCanResumeAfterManualApproval() {
        val markedTool = UIMessagePart.Tool(
            toolCallId = "call",
            toolName = "test_tool",
            input = "{}",
            executionStartedAt = 123L,
        )
        assertTrue(markedTool.isInterruptedAttempt)

        val pendingTool = markedTool.awaitApprovalBeforeExecution()
        assertTrue(pendingTool.isPending)
        assertNull(pendingTool.executionStartedAt)

        val approvedTool = pendingTool.copy(approvalState = ToolApprovalState.Approved)
        assertFalse(approvedTool.isInterruptedAttempt)
        assertEquals(listOf(approvedTool), resumableToolsIncludingUnexecutedAuto(listOf(approvedTool)))
    }

    @Test
    fun onlySynchronousComposerSubmissionProvidesOneShotUserIntent() {
        val input = listOf(UIMessagePart.Text("request"))
        assertEquals("", ChatComposerSubmission.takeUserIntent(input))
        ChatComposerSubmission.dispatch {
            assertEquals("request", ChatComposerSubmission.takeUserIntent(input))
            assertEquals("", ChatComposerSubmission.takeUserIntent(input))
        }
        assertEquals("", ChatComposerSubmission.takeUserIntent(input))
    }

    @Test
    fun onlyMissingModeUsesLegacyYolo() {
        assertEquals(ChatPermissionMode.FULL_ACCESS, ChatPermissionMode.fromStored(null, legacyYolo = true))
        assertEquals(ChatPermissionMode.MANUAL, ChatPermissionMode.fromStored("unknown", legacyYolo = true))
    }
}
