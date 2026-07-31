package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolSearchRehydrateTest {
    @Test
    fun `restores discoverable tools from current turn only`() {
        val messages = listOf(
            user("previous"),
            assistantTool("old_tool"),
            user("current"),
            assistantTool("send_sms"),
            assistantTool("disabled_tool"),
        )

        val restored = rehydrateDiscoveredToolNames(
            messages = messages,
            discoverableToolNames = setOf("old_tool", "send_sms"),
        )

        assertEquals(setOf("send_sms"), restored)
    }

    @Test
    fun `fresh user turn restores no tools`() {
        assertEquals(
            emptySet<String>(),
            rehydrateDiscoveredToolNames(
                messages = listOf(user("hello")),
                discoverableToolNames = setOf("send_sms"),
            ),
        )
    }

    private fun user(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun assistantTool(name: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "call-$name",
                toolName = name,
                input = "{}",
                approvalState = ToolApprovalState.Approved,
            )
        ),
    )
}
