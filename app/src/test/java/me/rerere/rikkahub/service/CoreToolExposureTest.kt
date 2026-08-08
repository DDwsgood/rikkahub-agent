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
            ),
            DIRECT_LOCAL_TOOL_OPTIONS,
        )
        assertFalse(LocalToolOption.Battery in DIRECT_LOCAL_TOOL_OPTIONS)
        assertFalse(LocalToolOption.SmsSend in DIRECT_LOCAL_TOOL_OPTIONS)
        assertFalse(LocalToolOption.WebFetch in DIRECT_LOCAL_TOOL_OPTIONS)
    }

    @Test
    fun `only direct options are exposed via selectDirectLocalToolNames`() {
        assertFalse(LocalToolOption.Termux in DIRECT_LOCAL_TOOL_OPTIONS)

        assertEquals(
            setOf("ask_user", "read_file"),
            selectDirectLocalToolNames(
                enabledOptions = setOf(
                    LocalToolOption.AskUser,
                    LocalToolOption.Files,
                    LocalToolOption.WebFetch,
                    LocalToolOption.Termux,
                    LocalToolOption.Battery,
                    LocalToolOption.SmsSend,
                ),
                directOptionToolNames = setOf("ask_user", "read_file"),
                availableToolNames = setOf(
                    "ask_user",
                    "read_file",
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