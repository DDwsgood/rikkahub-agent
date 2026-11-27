package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolTitleFormattingTest {

    private fun parse(raw: String) = Json.parseToJsonElement(raw)

    @Test
    fun `search_tools formats query category and combined arguments`() {
        assertEquals(
            "搜索工具: clipboard",
            formatToolTitle("搜索工具", "search_tools", parse("""{"query":"clipboard"}""")),
        )
        assertEquals(
            "搜索工具: category=file",
            formatToolTitle("搜索工具", "search_tools", parse("""{"category":"file"}""")),
        )
        assertEquals(
            "搜索工具: read (file)",
            formatToolTitle("搜索工具", "search_tools", parse("""{"query":"read","category":"file"}""")),
        )
    }

    @Test
    fun `common default tools surface command url and path previews`() {
        assertEquals(
            "运行 Termux 命令: ls -la /sdcard",
            formatToolTitle("运行 Termux 命令", "termux_run_command", parse("""{"command":"ls -la /sdcard"}""")),
        )
        assertEquals(
            "打开浏览器: https://example.com",
            formatToolTitle("打开浏览器", "browser_open", parse("""{"url":"https://example.com"}""")),
        )
        assertEquals(
            "读取 SD 卡文件: /sdcard/notes.txt",
            formatToolTitle("读取 SD 卡文件", "sdcard_read_file", parse("""{"path":"/sdcard/notes.txt"}""")),
        )
    }

    @Test
    fun `multi-field transfer and ssh tools format pairs cleanly`() {
        assertEquals(
            "移动 SD 卡文件: /sdcard/a.txt → /sdcard/b.txt",
            formatToolTitle(
                "移动 SD 卡文件",
                "sdcard_move_file",
                parse("""{"source":"/sdcard/a.txt","destination":"/sdcard/b.txt"}"""),
            ),
        )
        assertEquals(
            "在已保存主机运行 SSH 命令: prod: uptime",
            formatToolTitle(
                "在已保存主机运行 SSH 命令",
                "ssh_exec_saved",
                parse("""{"alias":"prod","command":"uptime"}"""),
            ),
        )
    }

    @Test
    fun `empty arguments return display name only`() {
        assertEquals("搜索工具", formatToolTitle("搜索工具", "search_tools", JsonObject(emptyMap())))
        assertNull(formatToolArgumentSummary("search_tools", parse("""{"query":"   "}""")))
    }

    @Test
    fun `sensitive keys are skipped and long summaries are truncated`() {
        assertEquals(
            "prod",
            formatToolArgumentSummary(
                "ssh_exec",
                parse("""{"password":"super-secret","token":"tok","host":"prod"}"""),
            ),
        )
        assertNull(
            formatToolArgumentSummary(
                "custom_tool",
                parse("""{"api_key":"sk-123","authorization":"Bearer x","headers":{"a":"b"}}"""),
            ),
        )
        val longCmd = "a".repeat(80)
        val summary = formatToolArgumentSummary("termux_run_command", parse("""{"command":"$longCmd"}"""))
        assertEquals("a".repeat(60) + "…", summary)
    }
}
