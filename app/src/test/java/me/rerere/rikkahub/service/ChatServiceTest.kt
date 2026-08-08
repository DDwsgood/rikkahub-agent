package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatServiceTest {
    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.OFF, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }

    @Test
    fun `all usable tools are available without any search_tools call`() {
        val localTools = listOf(tool("send_sms"), tool("read_file"))
        val mcpTools = listOf(tool("mcp__abc123_git__list_repos"))
        val discoverable = buildDiscoverableToolMap(localTools, mcpTools)

        // None of these were surfaced by search_tools: availability must not depend on
        // discovery — every enabled local tool and MCP tool is always declarable/resolvable.
        assertEquals(
            setOf("send_sms", "read_file", "mcp__abc123_git__list_repos"),
            discoverable.keys,
        )
        assertNotNull(discoverable["send_sms"])
        assertNotNull(discoverable["mcp__abc123_git__list_repos"])
    }

    @Test
    fun `availability is stable across a new user turn`() {
        val localTools = listOf(tool("send_sms"), tool("get_time_info"))
        // Turn 1: the model already used send_sms.
        val turn1 = buildDiscoverableToolMap(localTools, emptyList()).keys
        // Turn 2: a fresh user prompt arrives. The dynamic set derives only from the
        // assistant's config + MCP availability, never from message history, so tools
        // used in earlier turns must NOT disappear from declaration/resolution.
        val turn2 = buildDiscoverableToolMap(localTools, emptyList()).keys

        assertEquals(turn1, turn2)
        assertTrue("send_sms" in turn2)
        assertTrue("get_time_info" in turn2)
    }

    @Test
    fun `disabled and nonexistent tools are excluded`() {
        val discoverable = buildDiscoverableToolMap(listOf(tool("read_file")), emptyList())

        assertFalse("send_sms" in discoverable) // disabled for this assistant
        assertFalse("nonexistent_tool" in discoverable) // never existed
    }

    private fun tool(name: String) = Tool(
        name = name,
        description = "test tool $name",
        execute = { emptyList() },
    )
}
