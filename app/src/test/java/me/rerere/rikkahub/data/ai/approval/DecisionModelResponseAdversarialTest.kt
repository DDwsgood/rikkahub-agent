package me.rerere.rikkahub.data.ai.approval

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DecisionModelResponseAdversarialTest {
    @Test
    fun `provider reasoning does not invalidate a completed answer but incomplete answers cannot allow`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("internal reasoning"),
                UIMessagePart.Text("""{"decision":"ALLOW","reason":"ok"}"""),
            ),
        )

        assertEquals(ToolReviewResult.allow("ok"), parseDecisionModelMessage(message, "end_turn"))
        for (reason in listOf("length", "MAX_TOKENS", "incomplete")) {
            assertEquals(ToolReviewAction.ASK_USER, parseDecisionModelMessage(message, reason).action)
        }
    }

    @Test
    fun `redaction covers structured credentials and sensitive values in composer text`() {
        val args = Json.parseToJsonElement("""{"headers":[{"name":"Cookie","value":"fixture_cookie"}]}""")

        assertFalse(redactForReview(args).toString().contains("fixture_cookie"))
        assertFalse(redactReviewText("Use password='fixture pass' and Basic fixture_auth").contains("fixture"))
    }

    @Test
    fun `accepts exactly the supported decisions in JSON objects`() {
        assertEquals(
            ToolReviewResult.allow("public_read"),
            parseDecisionModelResponse(" \n{\"decision\":\"ALLOW\",\"reason\":\"public_read\"}\t"),
        )
        assertEquals(
            ToolReviewResult.askUser("private_data"),
            parseDecisionModelResponse("""{"reason":"private_data","decision":"ASK_USER"}"""),
        )
        assertEquals(
            ToolReviewResult.allow("Public \"docs\""),
            parseDecisionModelResponse("""{"decision":"ALLOW","reason":"Public \"docs\""}"""),
        )
    }

    @Test
    fun `rejects markup extra fields trailing content and tool call envelopes`() {
        val allow = """{"decision":"ALLOW","reason":"public_read"}"""
        val cases = mapOf(
            "markdown fence" to "```json\n$allow\n```",
            "leading prose" to "Here is my decision: $allow",
            "trailing prose" to "$allow\nEnd of response.",
            "trailing JSON object" to "$allow$allow",
            "extra field" to """{"decision":"ALLOW","reason":"public_read","confidence":1}""",
            "extra tool call field" to """{"decision":"ALLOW","reason":"ok","tool_calls":[]}""",
            "tool call text" to "<tool_call>$allow</tool_call>",
            "tool call envelope" to """{"name":"submit_decision","arguments":$allow}""",
            "array" to "[$allow]",
            "bare decision" to "ALLOW",
        )
        for ((name, raw) in cases) assertRejected(name, raw)
    }

    @Test
    fun `rejects empty unknown non string and altered decision values`() {
        val cases = mapOf(
            "empty response" to "",
            "blank response" to " \n\t",
            "empty object" to "{}",
            "empty decision" to """{"decision":"","reason":"ok"}""",
            "unknown decision" to """{"decision":"DENY","reason":"ok"}""",
            "lowercase allow" to """{"decision":"allow","reason":"ok"}""",
            "mixed case ask user" to """{"decision":"Ask_User","reason":"ok"}""",
            "surrounding spaces in decision" to """{"decision":" ALLOW ","reason":"ok"}""",
            "wrong field case" to """{"Decision":"ALLOW","reason":"ok"}""",
            "boolean decision" to """{"decision":true,"reason":"ok"}""",
            "array decision" to """{"decision":["ALLOW"],"reason":"ok"}""",
            "null decision" to """{"decision":null,"reason":"ok"}""",
            "unquoted decision" to """{"decision":ALLOW,"reason":"ok"}""",
        )
        for ((name, raw) in cases) assertRejected(name, raw)
    }

    @Test
    fun `missing blank or non string reason cannot turn invalid schema into allow`() {
        assertRejected("missing reason", """{"decision":"ALLOW"}""")
        assertRejected("blank reason", """{"decision":"ALLOW","reason":" \t"}""")
        assertRejected("numeric reason", """{"decision":"ALLOW","reason":1}""")
        assertRejected("null reason", """{"decision":"ALLOW","reason":null}""")
    }

    @Test
    fun `duplicate decisions cannot overwrite an ask user decision`() {
        assertRejected(
            "duplicate decision with conflicting values",
            """{"decision":"ASK_USER","decision":"ALLOW","reason":"ambiguous"}""",
        )
        assertRejected(
            "duplicate decision using an escaped key",
            """{"decision":"ASK_USER","deci\u0073ion":"ALLOW","reason":"ambiguous"}""",
        )
    }

    @Test
    fun `oversized decisions and reasons fail to human`() {
        val oversized = "x".repeat(121)
        assertRejected("long decision", """{"decision":"$oversized","reason":"ok"}""")
        assertRejected("long reason", """{"decision":"ALLOW","reason":"$oversized"}""")
        val maximumLengthReason = "x".repeat(120)
        assertEquals(
            ToolReviewResult.allow(maximumLengthReason),
            parseDecisionModelResponse("""{"decision":"ALLOW","reason":"$maximumLengthReason"}"""),
        )
    }

    @Test
    fun `oversized raw response is not made valid by trimming whitespace`() {
        val padded = " ".repeat(16_384) + """{"decision":"ALLOW","reason":"ok"}"""
        assertRejected("padding before valid JSON", padded)
    }

    private fun assertRejected(case: String, raw: String) {
        assertEquals(case, ToolReviewAction.ASK_USER, parseDecisionModelResponse(raw).action)
    }
}
