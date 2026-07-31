package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CoreToolExposureTest {
    @Test
    fun `direct options contain only basic local capabilities`() {
        assertEquals(
            setOf(
                LocalToolOption.AskUser,
                LocalToolOption.Files,
                LocalToolOption.WebFetch,
            ),
            DIRECT_LOCAL_TOOL_OPTIONS,
        )
        assertFalse(LocalToolOption.Battery in DIRECT_LOCAL_TOOL_OPTIONS)
        assertFalse(LocalToolOption.SmsSend in DIRECT_LOCAL_TOOL_OPTIONS)
    }

    @Test
    fun `only command execution is directly exposed from Termux`() {
        assertEquals("termux_run_command", TERMUX_RUN_COMMAND_TOOL_NAME)
        assertFalse(LocalToolOption.Termux in DIRECT_LOCAL_TOOL_OPTIONS)

        assertEquals(
            setOf("ask_user", "read_file", "web_fetch", "termux_run_command"),
            selectDirectLocalToolNames(
                enabledOptions = setOf(
                    LocalToolOption.AskUser,
                    LocalToolOption.Files,
                    LocalToolOption.WebFetch,
                    LocalToolOption.Termux,
                    LocalToolOption.Battery,
                    LocalToolOption.SmsSend,
                ),
                directOptionToolNames = setOf("ask_user", "read_file", "web_fetch"),
                availableToolNames = setOf(
                    "ask_user",
                    "read_file",
                    "web_fetch",
                    "termux_run_command",
                    "transcribe_audio_file",
                    "whisper_status",
                    "get_battery_status",
                    "send_sms",
                ),
            ),
        )
    }
}
