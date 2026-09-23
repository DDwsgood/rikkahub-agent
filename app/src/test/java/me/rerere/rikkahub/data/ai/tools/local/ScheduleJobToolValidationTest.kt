package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleJobToolValidationTest {

    private val knownTools = listOf("post_notification", "telegram_send_message", "termux_run_command", "share_file")

    private fun base(): JsonObject = buildJsonObject {
        put("name", "test")
        put("mode", "llm")
        put("schedule_type", "cron")
        put("cron_expression", "@hourly")
        put("prompt", "do a thing")
    }

    @Test
    fun `valid llm cron job passes`() {
        val r = ScheduleJobValidator.validate(base(), knownTools)
        assertNull(r)
    }

    @Test
    fun `invalid cron returns invalid_cron`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "test")
            put("mode", "llm")
            put("schedule_type", "cron")
            put("cron_expression", "not a cron")
            put("prompt", "x")
        }, knownTools)
        assertNotNull(r)
        assertEquals("invalid_cron", r!!.code)
    }

    @Test
    fun `mode llm without prompt rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm")
            put("schedule_type", "once"); put("at_unix_ms", 100L)
        }, knownTools)
        assertEquals("mutual_exclusive", r!!.code)
    }

    @Test
    fun `mode direct empty actions rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "direct")
            put("actions", buildJsonArray { })
            put("schedule_type", "once"); put("at_unix_ms", 100L)
        }, knownTools)
        assertEquals("empty_actions", r!!.code)
    }

    @Test
    fun `mode direct unknown tool rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "direct")
            put("actions", buildJsonArray { add(buildJsonObject {
                put("tool", "no_such_tool"); put("args", buildJsonObject { })
            }) })
            put("schedule_type", "once"); put("at_unix_ms", 100L)
        }, knownTools)
        assertEquals("unknown_tool", r!!.code)
    }

    @Test
    fun `mode direct hardline-blocked rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "direct")
            put("actions", buildJsonArray { add(buildJsonObject {
                put("tool", "termux_run_command")
                put("args", buildJsonObject { put("command", "rm -rf /") })
            }) })
            put("schedule_type", "once"); put("at_unix_ms", 100L)
        }, knownTools)
        assertEquals("hardline_blocked", r!!.code)
    }

    @Test
    fun `bounds inverted rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("start_at_unix_ms", 200L); put("end_at_unix_ms", 100L)
        }, knownTools)
        assertEquals("bounds_inverted", r!!.code)
    }

    @Test
    fun `bad timezone rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("timezone", "Mars/Olympus")
        }, knownTools)
        assertEquals("bad_timezone", r!!.code)
    }

    @Test
    fun `too many actions rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "direct")
            put("schedule_type", "once"); put("at_unix_ms", 100L)
            put("actions", buildJsonArray {
                repeat(51) {
                    add(buildJsonObject {
                        put("tool", "post_notification")
                        put("args", buildJsonObject { put("title", "t"); put("body", "b") })
                    })
                }
            })
        }, knownTools)
        assertEquals("too_many_actions", r!!.code)
    }

    @Test
    fun `prompt too long rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("prompt", "a".repeat(4001))
        }, knownTools)
        assertEquals("prompt_too_long", r!!.code)
    }

    @Test
    fun `prompt at limit accepted`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("prompt", "a".repeat(4000))
        }, knownTools)
        assertNull(r)
    }

    @Test
    fun `bounds past rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("end_at_unix_ms", 1L)  // epoch ms — always in the past
        }, knownTools)
        assertEquals("bounds_past", r!!.code)
    }

    @Test
    fun `max_runs zero rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("max_runs", 0)
        }, knownTools)
        assertEquals("max_runs_invalid", r!!.code)
    }

    @Test
    fun `bad catchup value rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("catchup", "never")
        }, knownTools)
        assertEquals("bad_catchup", r!!.code)
    }

    @Test
    fun `legacy schedule_precision field is accepted and ignored`() {
        // Precision selection was removed: even a legacy 'exact' value must not be
        // rejected — and the caller persists flexible regardless of this field. A
        // high-frequency cron also passes now that the exact-frequency check is gone.
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "*/5 * * * *")
            put("schedule_precision", "exact")
        }, knownTools)
        assertNull(r)
    }

    @Test
    fun `unknown schedule_precision value is ignored, not validated`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("schedule_precision", "magic")
        }, knownTools)
        assertNull(r)
    }

    @Test
    fun `bad tag uppercase rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("tags", buildJsonArray { add("HasUppercase") })
        }, knownTools)
        assertEquals("bad_tag", r!!.code)
    }

    // ---- share_file direct-mode block (behavioral) ----

    @Test
    fun `direct mode share_file action returns interactive_only`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "share-test"); put("mode", "direct")
            put("actions", buildJsonArray { add(buildJsonObject {
                put("tool", "share_file")
                put("args", buildJsonObject { put("path", "/sdcard/test.txt") })
            }) })
            put("schedule_type", "once"); put("at_unix_ms", 200L)
        }, knownTools)
        assertNotNull(r)
        assertEquals("interactive_only", r!!.code)
    }

    @Test
    fun `direct mode share_file action error mentions the tool name`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "share-test"); put("mode", "direct")
            put("actions", buildJsonArray { add(buildJsonObject {
                put("tool", "share_file")
                put("args", buildJsonObject { put("path", "/sdcard/test.txt") })
            }) })
            put("schedule_type", "once"); put("at_unix_ms", 200L)
        }, knownTools)
        assertNotNull(r)
        assertTrue("detail should mention share_file: ${r!!.detail}", r.detail.contains("share_file"))
    }

    @Test
    fun `llm mode with share_file in prompt is not blocked by direct-mode guard`() {
        // LLM mode never enters the direct-mode action loop, so the interactive_only
        // guard must NOT fire. The job should pass validation (or fail for its own
        // reasons, but never with code "interactive_only").
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "llm-share"); put("mode", "llm")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("prompt", "Please use share_file to send ~/report.pdf to the user.")
        }, knownTools)
        // Should pass — LLM mode is valid
        assertNull(r)
    }

    @Test
    fun `direct mode with valid non-interactive tool passes`() {
        // Ensure the direct-mode block doesn't accidentally reject valid tools
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "ok"); put("mode", "direct")
            put("actions", buildJsonArray { add(buildJsonObject {
                put("tool", "post_notification")
                put("args", buildJsonObject { put("title", "t"); put("body", "b") })
            }) })
            put("schedule_type", "once"); put("at_unix_ms", 200L)
        }, knownTools)
        assertNull(r)
    }
}
