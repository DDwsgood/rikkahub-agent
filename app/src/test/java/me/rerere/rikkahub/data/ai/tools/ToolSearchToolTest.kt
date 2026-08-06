package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolSearchToolTest {

    @Before
    fun setup() {
        ToolRegistry.clear()
    }

    @After
    fun tearDown() {
        ToolRegistry.clear()
    }

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

    @Test
    fun `category browse without query lists all tools in category`() = runBlocking {
        val file1 = "read_file"
        val file2 = "write_file"
        val device1 = "battery_status"
        ToolRegistry.register(entry(file1, category = "file"))
        ToolRegistry.register(entry(file2, category = "file"))
        ToolRegistry.register(entry(device1, category = "device"))

        val tool = toolSearchTool(availableToolNames = null)
        val output = tool.execute(Json.parseToJsonElement("""{"category":"file"}"""))
        val payload = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
        val names = payload.getValue("tools").jsonArray.map {
            it.jsonObject.getValue("name").jsonPrimitive.content
        }

        assertEquals(2, names.size)
        assertTrue(file1 in names)
        assertTrue(file2 in names)
        assertFalse(device1 in names)
    }

    @Test
    fun `both query and category empty returns error`() = runBlocking {
        val tool = toolSearchTool(availableToolNames = null)
        val output = tool.execute(Json.parseToJsonElement("""{}"""))
        val text = (output.single() as UIMessagePart.Text).text
        val payload = Json.parseToJsonElement(text).jsonObject

        assertNotNull(payload["error"])
    }

    @Test
    fun `multi-keyword search uses AND semantics`() = runBlocking {
        ToolRegistry.register(entry("ssh_upload", "Upload file via SSH"))
        ToolRegistry.register(entry("ssh_exec", "Execute command via SSH"))

        val tool = toolSearchTool(availableToolNames = null)
        val output = tool.execute(Json.parseToJsonElement("""{"query":"ssh upload"}"""))
        val payload = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
        val names = payload.getValue("tools").jsonArray.map {
            it.jsonObject.getValue("name").jsonPrimitive.content
        }

        assertEquals(1, names.size)
        assertEquals("ssh_upload", names[0])
    }

    private fun entry(name: String, desc: String = "test searchable tool", category: String = "test") = ToolRegistry.ToolEntry(
        name = name,
        description = desc,
        category = category,
        schema = null,
        needsApproval = false,
        source = ToolRegistry.ToolSource.LOCAL,
    )
}
