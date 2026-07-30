package me.rerere.rikkahub.data.ai.tools

/**
 * 所有可用工具的可搜索注册表。
 *
 * 默认情况下，只有核心工具被注入到 LLM 请求的 tools 字段中。
 * 其余工具通过 ToolRegistry 注册，LLM 调用 search_tools 时发现它们。
 *
 * ToolRegistry 只存储元数据（名称、描述、类别、schema），
 * 不存储 execute lambda - execute 在工具实际被调用时才从原始来源解析。
 */
object ToolRegistry {

    data class ToolEntry(
        val name: String,
        val description: String,
        val category: String,
        val schema: me.rerere.ai.core.InputSchema?,
        val needsApproval: Boolean,
        val source: ToolSource,
    )

    enum class ToolSource {
        LOCAL,         // 本地设备工具（LocalTools.kt）
        MCP,           // MCP 服务器工具
        SEARCH,        // 搜索工具（SearchTools.kt）
        SKILL,         // 技能工具
        WORKSPACE,     // 工作区工具
        CONVERSATION,  // 对话工具
        MEMORY,        // 记忆工具
    }

    // 线程安全的注册表
    private val entries = java.util.concurrent.ConcurrentHashMap<String, ToolEntry>()

    fun register(entry: ToolEntry) {
        entries[entry.name] = entry
    }

    fun unregister(name: String) {
        entries.remove(name)
    }

    fun clear() {
        entries.clear()
    }

    fun clearBySource(source: ToolSource) {
        entries.values.removeIf { it.source == source }
    }

    fun clearByCategory(category: String) {
        entries.values.removeIf { it.category == category }
    }

    /**
     * 按关键词和类别搜索工具。
     * 匹配规则：工具名或描述中包含查询字符串（不区分大小写）。
     */
    fun search(query: String, category: String? = null): List<ToolEntry> {
        val q = query.lowercase()
        return entries.values.filter { entry ->
            (category == null || entry.category.equals(category, ignoreCase = true)) &&
                (entry.name.lowercase().contains(q) || entry.description.lowercase().contains(q))
        }.sortedBy { it.name }
    }

    fun getAll(): List<ToolEntry> = entries.values.toList()

    fun get(name: String): ToolEntry? = entries[name]

    fun size(): Int = entries.size

    fun categories(): Set<String> = entries.values.map { it.category }.toSet()
}
