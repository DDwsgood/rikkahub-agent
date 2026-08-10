package me.rerere.rikkahub.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-9 对抗性审查回归测试:
 *  - tools/list 翻页循环是防环的 (重复 cursor 立即失败), 达到页数上限明确抛错而不是
 *    静默截断成部分工具表。
 *  - mergeTools 对同一 server 的重复名工具去重, 保留第一个。
 */
class McpPaginationAndMergeTest {

    @Test
    fun `pagination collects every page until cursor is null`() = runBlocking {
        val pages = listOf(
            ToolListPage(tools = listOf(tool("a")), nextCursor = "c1"),
            ToolListPage(tools = listOf(tool("b")), nextCursor = "c2"),
            ToolListPage(tools = listOf(tool("c")), nextCursor = null),
        )
        val fetched = mutableListOf<String?>()
        val tools = paginateTools { cursor ->
            fetched += cursor
            pages[fetched.size - 1]
        }

        assertEquals(listOf("a", "b", "c"), tools.map { it.name })
        assertEquals(listOf(null, "c1", "c2"), fetched)
    }

    @Test
    fun `repeated cursor aborts pagination instead of looping`() = runBlocking {
        val thrown = runCatching {
            paginateTools { ToolListPage(tools = emptyList(), nextCursor = "loop") }
        }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got: $thrown", thrown is IllegalStateException)
    }

    @Test
    fun `page cap aborts instead of silently truncating`() = runBlocking {
        // 每页都返回 nextCursor, 第 10 页后仍有余页 → 抛错而非返回部分工具
        val thrown = runCatching {
            paginateTools { ToolListPage(tools = listOf(tool("x")), nextCursor = "keep-going") }
        }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got: $thrown", thrown is IllegalStateException)
    }

    @Test
    fun `mergeTools drops duplicate tool names keeping the first`() {
        val serverTools = listOf(tool("dup", desc = "first"), tool("dup", desc = "second"), tool("uniq"))
        val merged = mergeTools(storedTools = emptyList(), serverTools = serverTools)

        assertEquals(listOf("dup", "uniq"), merged.map { it.name })
        assertEquals("first", merged[0].description)
    }

    @Test
    fun `mergeTools preserves stored enable flags by name`() {
        val stored = listOf(McpTool(name = "search", enable = false))
        val merged = mergeTools(storedTools = stored, serverTools = listOf(tool("search")))

        assertEquals(1, merged.size)
        assertEquals(false, merged[0].enable)
    }

    private fun tool(name: String, desc: String? = null): Tool =
        Tool(name = name, description = desc, inputSchema = ToolSchema())
}
