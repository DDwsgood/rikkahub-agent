package me.rerere.rikkahub.data.ai.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolRegistrySearchTest {

    @Before
    fun setup() {
        ToolRegistry.clear()
    }

    @After
    fun tearDown() {
        ToolRegistry.clear()
    }

    private fun entry(name: String, desc: String = "", category: String = "test") = ToolRegistry.ToolEntry(
        name = name,
        description = desc,
        category = category,
        schema = null,
        needsApproval = false,
        source = ToolRegistry.ToolSource.LOCAL,
    )

    @Test
    fun `single keyword matches name and description`() {
        ToolRegistry.register(entry("battery_status", "Get battery level and charging state"))
        ToolRegistry.register(entry("wifi_info", "Get wifi connection details"))

        val byName = ToolRegistry.search("battery")
        assertEquals(1, byName.size)
        assertEquals("battery_status", byName[0].name)

        val byDesc = ToolRegistry.search("charging")
        assertEquals(1, byDesc.size)
        assertEquals("battery_status", byDesc[0].name)
    }

    @Test
    fun `multi-keyword AND requires all keywords`() {
        ToolRegistry.register(entry("ssh_upload", "Upload a file to a remote SSH host"))
        ToolRegistry.register(entry("ssh_exec", "Execute a command on a remote SSH host"))
        ToolRegistry.register(entry("download_file", "Download a file from URL"))

        val results = ToolRegistry.search("ssh upload")
        assertEquals(1, results.size)
        assertEquals("ssh_upload", results[0].name)
    }

    @Test
    fun `AND fallback to OR when no tool matches all keywords`() {
        ToolRegistry.register(entry("ssh_exec", "Execute a command on a remote SSH host"))
        ToolRegistry.register(entry("download_file", "Download a file from URL"))

        // "ssh download" — no tool has both, OR fallback returns both
        val results = ToolRegistry.search("ssh download")
        assertEquals(2, results.size)
        assertTrue(results.any { it.name == "ssh_exec" })
        assertTrue(results.any { it.name == "download_file" })
    }

    @Test
    fun `fuzzy fallback matches typos via Levenshtein`() {
        ToolRegistry.register(entry("battery", "Get battery status"))

        // Typo: "battry" (missing 'e') — not a substring of name or description,
        // but Levenshtein distance 1 from "battery"
        val results = ToolRegistry.search("battry")
        assertTrue("Fuzzy fallback should match close typos", results.isNotEmpty())
        assertEquals("battery", results[0].name)
    }

    @Test
    fun `category-only browse returns all tools in category`() {
        ToolRegistry.register(entry("read_file", "Read a file", category = "file"))
        ToolRegistry.register(entry("write_file", "Write a file", category = "file"))
        ToolRegistry.register(entry("battery_status", "Battery info", category = "device"))

        val results = ToolRegistry.search("", category = "file")
        assertEquals(2, results.size)
        assertTrue(results.all { it.category == "file" })
    }

    @Test
    fun `blank query with no category returns all tools`() {
        ToolRegistry.register(entry("tool_a", "desc a"))
        ToolRegistry.register(entry("tool_b", "desc b"))

        val results = ToolRegistry.search("")
        assertEquals(2, results.size)
    }

    @Test
    fun `scoring ranks exact name above prefix above substring above description`() {
        ToolRegistry.register(entry("ssh", "SSH tool"))                    // exact match for "ssh"
        ToolRegistry.register(entry("ssh_exec", "Run remote command"))     // prefix match
        ToolRegistry.register(entry("list_ssh_hosts", "SSH host manager"))  // substring match in name
        ToolRegistry.register(entry("network_tool", "SSH configuration"))   // description-only match

        val results = ToolRegistry.search("ssh")
        assertEquals(4, results.size)
        // Exact match should be first
        assertEquals("ssh", results[0].name)
        // Prefix match should be second
        assertEquals("ssh_exec", results[1].name)
        // Substring match should be third
        assertEquals("list_ssh_hosts", results[2].name)
        // Description-only match should be last
        assertEquals("network_tool", results[3].name)
    }

    @Test
    fun `category filter narrows multi-keyword search`() {
        ToolRegistry.register(entry("ssh_exec", "Execute SSH command", category = "shell"))
        ToolRegistry.register(entry("ssh_exec_remote", "Remote SSH exec", category = "device"))

        val results = ToolRegistry.search("ssh exec", category = "shell")
        assertEquals(1, results.size)
        assertEquals("ssh_exec", results[0].name)
    }

    @Test
    fun `case insensitive matching`() {
        ToolRegistry.register(entry("Battery_Status", "Get battery info"))

        val lower = ToolRegistry.search("battery")
        val upper = ToolRegistry.search("BATTERY")
        val mixed = ToolRegistry.search("BaTtErY")

        assertEquals(1, lower.size)
        assertEquals(1, upper.size)
        assertEquals(1, mixed.size)
        assertEquals("Battery_Status", lower[0].name)
    }

    @Test
    fun `no match returns empty list`() {
        ToolRegistry.register(entry("battery_status", "Battery info"))

        val results = ToolRegistry.search("nonexistent_xyz")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `multi-keyword scoring prefers tools matching more keywords in name`() {
        ToolRegistry.register(entry("ssh_exec_saved", "Run a saved SSH command"))
        ToolRegistry.register(entry("ssh_exec", "Run SSH command"))

        // Both match "ssh" and "exec" in their names, but ssh_exec_saved is longer
        val results = ToolRegistry.search("ssh exec")
        assertEquals(2, results.size)
        // ssh_exec has a higher prefix score for both keywords (shorter name = less penalty)
        assertEquals("ssh_exec", results[0].name)
    }
}