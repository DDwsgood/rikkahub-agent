package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.ReasoningLevel
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Per-conversation in-memory prefix snapshot cache (survives the 5-second idle
 * [me.rerere.rikkahub.service.ConversationSession] eviction) so consecutive turns in the
 * same conversation keep a strictly invariant prompt prefix for provider KV-cache hits.
 *
 * Invalidated automatically when the conversation resets (`isFreshTurn`) or when any
 * configuration input in [PrefixConfigKey] changes (model, reasoning level, assistant,
 * system prompt, workspace state, initial tools, skills, locale, etc.).
 */
class ConversationPrefixCache(
    private val maxEntries: Int = 64,
) {
    data class PrefixConfigKey(
        val modelId: String,
        val reasoningLevel: ReasoningLevel,
        val assistantId: Uuid,
        val effectiveSystemPrompt: String,
        val enableWebSearch: Boolean,
        val enableMemory: Boolean,
        val enableRecentChatsReference: Boolean,
        val workspaceId: String?,
        val workspaceSdcardMode: String?,
        val workspaceShellStatus: String?,
        val sharedDirMounted: Boolean,
        val workspaceCwd: String?,
        val initialToolNames: List<String>,
        val enabledSkillsSorted: List<String>,
        val localeTag: String,
    )

    class Snapshot(
        val configKey: PrefixConfigKey,
        val pinnedDate: LocalDate,
        val pinnedBatteryLevel: Int,
    ) {
        @Volatile
        var pinnedMemoryPrompt: String? = null

        @Volatile
        var pinnedRecentChatsPrompt: String? = null
    }

    private val entries = object : LinkedHashMap<Uuid, Snapshot>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uuid, Snapshot>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun getOrCreate(
        conversationId: Uuid,
        configKey: PrefixConfigKey,
        isFreshTurn: Boolean,
        dateProvider: () -> LocalDate = { LocalDate.now() },
        batteryProvider: () -> Int = { -1 },
    ): Snapshot {
        val existing = entries[conversationId]
        if (!isFreshTurn && existing != null && existing.configKey == configKey) {
            return existing
        }
        val fresh = Snapshot(
            configKey = configKey,
            pinnedDate = dateProvider(),
            pinnedBatteryLevel = batteryProvider(),
        )
        entries[conversationId] = fresh
        return fresh
    }

    @Synchronized
    fun invalidate(conversationId: Uuid) {
        entries.remove(conversationId)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
