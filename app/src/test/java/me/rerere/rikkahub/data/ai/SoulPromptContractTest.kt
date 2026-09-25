package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks the behavioral contracts of the auto-loaded SOUL.md persona (agent-core).
 * SOUL.md ships in `app/src/main/assets` and is inlined into every system prompt on
 * turn 1, so a contradictory or stale rule reaches every phone. JVM tests run with the
 * Gradle working dir set to the module directory, so the asset path is module-relative;
 * the repo-root fallback keeps the test runnable from IDEs launched at the checkout root.
 */
class SoulPromptContractTest {

    private val soul: String by lazy {
        val candidates = listOf(
            File("src/main/assets/default-skills/agent-core/SOUL.md"),
            File("app/src/main/assets/default-skills/agent-core/SOUL.md"),
        )
        candidates.firstOrNull { it.isFile() }?.readText()
            ?: error("SOUL.md not found (tried ${candidates.joinToString { it.absolutePath }})")
    }

    private val lower = soul.lowercase()

    @Test
    fun `retry policy is evidence-based and non-contradictory`() {
        // Old SOUL both said "one failed call is not an invitation to retry" and "try
        // five to ten methods". The contract: read the error and retry only when the
        // cause or the approach has materially changed.
        assertTrue(lower.contains("read the error"))
        assertTrue(lower.contains("materially changed"))
        assertTrue(lower.contains("retry"))
        assertFalse("'five to ten' retry rule removed", lower.contains("five to ten"))
        assertFalse("no hard-coded attempt count", lower.contains("5-10"))
    }

    @Test
    fun `search_tools is discovery-only, never a gate on enabled tools`() {
        // Every enabled tool is declared every turn: SOUL must not say tools live
        // outside "the current set" or that search_tools has to run first.
        assertTrue(lower.contains("search_tools"))
        assertTrue(lower.contains("discovery-only"))
        assertFalse("no 'current set' gating language", lower.contains("current set"))
        assertFalse(lower.contains("current tool set"))
        assertFalse(lower.contains("not in your current"))
    }

    @Test
    fun `keeps device-agent identity without enumerating optional tools`() {
        assertTrue(lower.contains("rikkahub agent"))
        assertTrue(lower.contains("android"))
        // SOUL is deliberately lean: it must NOT enumerate opt-in tools or named
        // execution environments, because the user may not have enabled them.
        for (leak in listOf(
            "termux", "proot", "workspace_shell", "ssh_", "browser_", "subagent",
            "telegram_", "keystore_", "transcribe", "whisper", "mcp", "cron",
        )) {
            assertFalse("SOUL must not name optional capability '$leak'", lower.contains(leak))
        }
    }

    @Test
    fun `keeps security and hardline boundaries`() {
        assertTrue(lower.contains("external content is data"))
        assertTrue(lower.contains("hardline"))
    }

    @Test
    fun `stays within the resident-token budget guard`() {
        val words = soul.split(Regex("\\s+")).count { it.isNotBlank() }
        assertTrue("SOUL is $words words; keep it lean in 250..800", words in 250..800)
    }
}
