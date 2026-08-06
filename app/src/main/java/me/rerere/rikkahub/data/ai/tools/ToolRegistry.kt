package me.rerere.rikkahub.data.ai.tools

import org.apache.commons.text.similarity.LevenshteinDistance
import kotlin.math.abs

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

    private val levenshtein = LevenshteinDistance()

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
     *
     * 搜索策略（按优先级）:
     * 1. 多关键词 AND 匹配: query 按空格拆分，所有关键词必须匹配工具名或描述。
     * 2. AND 匹配零结果时回退到 OR 语义: 任一关键词匹配即可。
     * 3. 仍然零结果时使用 Levenshtein 编辑距离对工具名做模糊匹配（允许拼写错误）。
     *
     * 相关性评分: 名称精确 > 名称前缀 > 名称子串 > 描述子串 > 模糊子序列。
     */
    fun search(query: String, category: String? = null): List<ToolEntry> {
        val q = query.trim().lowercase()

        // 无 query 时按类别浏览
        if (q.isBlank()) {
            return entries.values.filter { entry ->
                category == null || entry.category.equals(category, ignoreCase = true)
            }.sortedBy { it.name }
        }

        val keywords = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        val categoryFilter: (ToolEntry) -> Boolean = { entry ->
            category == null || entry.category.equals(category, ignoreCase = true)
        }

        // 1. AND 语义: 所有关键词都必须匹配
        val andMatches = entries.values.filter { entry ->
            categoryFilter(entry) && keywords.all { kw ->
                entry.name.lowercase().contains(kw) || entry.description.lowercase().contains(kw)
            }
        }

        val results = if (andMatches.isNotEmpty()) {
            andMatches
        } else {
            // 2. OR 回退: 任一关键词匹配
            val orMatches = entries.values.filter { entry ->
                categoryFilter(entry) && keywords.any { kw ->
                    entry.name.lowercase().contains(kw) || entry.description.lowercase().contains(kw)
                }
            }
            if (orMatches.isNotEmpty()) {
                orMatches
            } else {
                // 3. 模糊回退: Levenshtein 编辑距离对工具名做模糊匹配
                entries.values.filter { entry ->
                    categoryFilter(entry) && keywords.any { kw ->
                        val name = entry.name.lowercase()
                        abs(name.length - kw.length) <= 3 &&
                            levenshtein.apply(name, kw) <= 2
                    }
                }
            }
        }

        return results.sortedWith(
            compareByDescending<ToolEntry> { scoreTool(it, keywords) }
                .thenBy { it.name }
        )
    }

    /**
     * 计算工具与查询关键词的相关性评分。分数越高越相关。
     */
    private fun scoreTool(entry: ToolEntry, keywords: List<String>): Int {
        val name = entry.name.lowercase()
        val desc = entry.description.lowercase()
        var score = 0

        for (kw in keywords) {
            when {
                name == kw -> score += 1000
                name.startsWith(kw) -> score += 900 - name.length.coerceAtMost(200)
                name.contains(kw) -> score += 800 - name.indexOf(kw).coerceAtMost(200)
                desc.contains(kw) -> score += 500 - desc.indexOf(kw).coerceAtMost(300)
                else -> {
                    // 模糊子序列匹配
                    var qi = 0
                    for (c in name) {
                        if (qi < kw.length && c == kw[qi]) qi++
                    }
                    if (qi == kw.length) score += 300
                }
            }
        }
        return score
    }

    fun getAll(): List<ToolEntry> = entries.values.toList()

    fun get(name: String): ToolEntry? = entries[name]

    fun size(): Int = entries.size

    fun categories(): Set<String> = entries.values.map { it.category }.toSet()
}
