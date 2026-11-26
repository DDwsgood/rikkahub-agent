package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.ai.tools.DEFAULT_INITIAL_EXPOSED_TOOLS
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.effectiveInitialExposedTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreToolExposureTest {
    @Test
    fun `default initial exposed tools include core, termux, and workspace core only`() {
        assertTrue("ask_user" in DEFAULT_INITIAL_EXPOSED_TOOLS)
        assertTrue("get_time_info" in DEFAULT_INITIAL_EXPOSED_TOOLS)
        assertTrue("eval_javascript" in DEFAULT_INITIAL_EXPOSED_TOOLS)
        assertTrue("termux_run_command" in DEFAULT_INITIAL_EXPOSED_TOOLS)
        assertTrue("workspace_shell" in DEFAULT_INITIAL_EXPOSED_TOOLS)
        assertTrue("workspace_read_file" in DEFAULT_INITIAL_EXPOSED_TOOLS)
        assertTrue("workspace_write_file" in DEFAULT_INITIAL_EXPOSED_TOOLS)
        assertTrue("workspace_edit_file" in DEFAULT_INITIAL_EXPOSED_TOOLS)

        // Lazy-discovered tools must NOT be exposed on turn 1 by default
        assertFalse("sdcard_read_file" in DEFAULT_INITIAL_EXPOSED_TOOLS)
        assertFalse("workspace_run_background" in DEFAULT_INITIAL_EXPOSED_TOOLS)
        assertFalse("workspace_background_status" in DEFAULT_INITIAL_EXPOSED_TOOLS)
        assertFalse("workspace_background_kill" in DEFAULT_INITIAL_EXPOSED_TOOLS)
        assertFalse("workspace_export_to_downloads" in DEFAULT_INITIAL_EXPOSED_TOOLS)
        assertFalse(LocalToolOption.Battery in DIRECT_LOCAL_TOOL_OPTIONS)
        assertFalse(LocalToolOption.SmsSend in DIRECT_LOCAL_TOOL_OPTIONS)
    }

    @Test
    fun `effectiveInitialExposedTools honors user override or falls back to defaults`() {
        assertEquals(DEFAULT_INITIAL_EXPOSED_TOOLS, effectiveInitialExposedTools(null))
        val custom = setOf("ask_user", "workspace_shell")
        assertEquals(custom, effectiveInitialExposedTools(custom))
    }

    @Test
    fun `only initial exposed tools are selected from available tools`() {
        assertEquals(
            setOf("ask_user", "termux_run_command", "workspace_shell"),
            selectDirectLocalToolNames(
                enabledOptions = setOf(
                    LocalToolOption.AskUser,
                    LocalToolOption.Files,
                    LocalToolOption.Termux,
                    LocalToolOption.Battery,
                    LocalToolOption.SmsSend,
                ),
                directOptionToolNames = setOf("ask_user", "termux_run_command"),
                availableToolNames = setOf(
                    "ask_user",
                    "sdcard_read_file",
                    "termux_run_command",
                    "workspace_shell",
                    "workspace_run_background",
                    "get_battery_status",
                    "send_sms",
                ),
            ),
        )
    }
}