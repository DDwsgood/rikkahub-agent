package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSearchToolTest {

    @Test
    fun `search only returns and enables tools available to current assistant`() = runBlocking {
        val allowed = "test_search_allowed"
        val stale = "test_search_stale"
        ToolRegistry.register(entry(allowed))
        ToolRegistry.register(entry(stale))
        try {
            val discovered = mutableListOf<String>()
            val tool = toolSearchTool(
                availableToolNames = setOf(allowed),
                onToolsDiscovered = discovered::addAll,
            )

            val output = tool.execute(Json.parseToJsonElement("""{"query":"test_search"}"""))
            val payload = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
            val names = payload.getValue("tools").jsonArray.map {
                it.jsonObject.getValue("name").jsonPrimitive.content
            }

            assertEquals(listOf(allowed), names)
            assertEquals(listOf(allowed), discovered)
            assertTrue(allowed in discovered)
            assertFalse(stale in discovered)
        } finally {
            ToolRegistry.unregister(allowed)
            ToolRegistry.unregister(stale)
        }
    }

    private fun entry(name: String) = ToolRegistry.ToolEntry(
        name = name,
        description = "test searchable tool",
        category = "test",
        schema = null,
        needsApproval = false,
        source = ToolRegistry.ToolSource.LOCAL,
    )
}
