package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.extractDiscoveredToolNamesFromSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate
import kotlin.uuid.Uuid

class ConversationPrefixCacheTest {

    private fun baseKey(
        modelId: String = "deepseek-chat",
        reasoning: ReasoningLevel = ReasoningLevel.OFF,
        tools: List<String> = listOf("ask_user", "workspace_shell"),
    ) = ConversationPrefixCache.PrefixConfigKey(
        modelId = modelId,
        reasoningLevel = reasoning,
        assistantId = Uuid.parse("00000000-0000-0000-0000-000000000001"),
        effectiveSystemPrompt = "You are helpful.",
        enableWebSearch = true,
        enableMemory = true,
        enableRecentChatsReference = true,
        workspaceId = "ws-1",
        workspaceSdcardMode = "READ_WRITE",
        workspaceShellStatus = "READY",
        sharedDirMounted = false,
        workspaceCwd = null,
        initialToolNames = tools,
        enabledSkillsSorted = listOf("a-skill", "b-skill"),
        localeTag = "zh-CN",
    )

    @Test
    fun `consecutive turns with unchanged config reuse exact pinned snapshot`() {
        val cache = ConversationPrefixCache()
        val convId = Uuid.parse("00000000-0000-0000-0000-000000000099")
        var day = LocalDate.of(2026, 11, 26)
        var battery = 85

        val turn1 = cache.getOrCreate(
            conversationId = convId,
            configKey = baseKey(),
            isFreshTurn = true,
            dateProvider = { day },
            batteryProvider = { battery },
        )
        turn1.pinnedMemoryPrompt = "**Memories**\n[]"
        turn1.pinnedRecentChatsPrompt = "**Recent Chats**\n[]"

        // Simulate midnight rollover, battery drain, and memory/recent-chat edits before Turn 2
        day = LocalDate.of(2026, 11, 27)
        battery = 42

        val turn2 = cache.getOrCreate(
            conversationId = convId,
            configKey = baseKey(),
            isFreshTurn = false,
            dateProvider = { day },
            batteryProvider = { battery },
        )

        assertSame(turn1, turn2)
        assertEquals(LocalDate.of(2026, 11, 26), turn2.pinnedDate)
        assertEquals(85, turn2.pinnedBatteryLevel)
        assertEquals("**Memories**\n[]", turn2.pinnedMemoryPrompt)
        assertEquals("**Recent Chats**\n[]", turn2.pinnedRecentChatsPrompt)
    }

    @Test
    fun `changing model, reasoning level, or tools invalidates snapshot`() {
        val cache = ConversationPrefixCache()
        val convId = Uuid.parse("00000000-0000-0000-0000-000000000098")
        val snap1 = cache.getOrCreate(
            conversationId = convId,
            configKey = baseKey(),
            isFreshTurn = true,
            dateProvider = { LocalDate.of(2026, 11, 26) },
            batteryProvider = { 80 },
        )

        val snapModelChanged = cache.getOrCreate(
            conversationId = convId,
            configKey = baseKey(modelId = "gpt-5"),
            isFreshTurn = false,
            dateProvider = { LocalDate.of(2026, 11, 27) },
            batteryProvider = { 70 },
        )
        assertNotSame(snap1, snapModelChanged)
        assertEquals(LocalDate.of(2026, 11, 27), snapModelChanged.pinnedDate)

        val snapReasoningChanged = cache.getOrCreate(
            conversationId = convId,
            configKey = baseKey(modelId = "gpt-5", reasoning = ReasoningLevel.HIGH),
            isFreshTurn = false,
        )
        assertNotSame(snapModelChanged, snapReasoningChanged)

        val snapToolsChanged = cache.getOrCreate(
            conversationId = convId,
            configKey = baseKey(modelId = "gpt-5", reasoning = ReasoningLevel.HIGH, tools = listOf("ask_user")),
            isFreshTurn = false,
        )
        assertNotSame(snapReasoningChanged, snapToolsChanged)
    }

    @Test
    fun `extractDiscoveredToolNamesFromSearchResult preserves returned order and ignores malformed payloads`() {
        val part = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "search_tools",
            input = """{"query":"sdcard"}""",
            output = listOf(
                UIMessagePart.Text("not-json"),
                UIMessagePart.Text(
                    """{"count":2,"tools":[{"name":"sdcard_read_file","category":"file"},{"name":"sdcard_list_files","category":"file"}]}"""
                ),
            ),
        )
        assertEquals(
            listOf("sdcard_read_file", "sdcard_list_files"),
            extractDiscoveredToolNamesFromSearchResult(part),
        )
    }
}
