package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.message.parseOutputJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultRegressionTest {
    @Test
    fun `missing tool reports callable names without becoming a generic tool failure`() {
        val result = Json.parseToJsonElement(toolNotFoundResult("mcp__missing", listOf("search_tools", "mcp_list_tools")).text)
            .jsonObject

        assertEquals("tool_not_found", result.getValue("error").jsonPrimitive.content)
        assertTrue(result.getValue("detail").jsonPrimitive.content.contains("mcp__missing"))
        assertEquals("search_tools, mcp_list_tools", result.getValue("tools_available_this_turn").jsonPrimitive.content)
    }

    @Test
    fun `oversized search results keep their JSON even when workspace shell is available`() {
        val result = """{"results":[{"id":1,"snippet":"${"x".repeat(40 * 1024)}"}]}"""
        val search = UIMessagePart.Tool("call_1", "search_web", "{}", listOf(UIMessagePart.Text(result)))

        assertFalse(shouldTruncateToolOutput(search.toolName, result.length, hasShellAccess = true))
        assertTrue(shouldTruncateToolOutput("other_tool", result.length, hasShellAccess = true))
        assertEquals(Json.parseToJsonElement(result), search.parseOutputJson())
        assertNull(search.copy(output = listOf(UIMessagePart.Text("[Tool output truncated]"))).parseOutputJson())
    }
}
