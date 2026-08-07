package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant

/**
 * search_tools meta-tool。
 *
 * 默认注入到每个 LLM 请求中。LLM 调用它来发现不知道名字的能力
 * （按关键字或类别搜索，返回匹配工具的完整 schema）。
 * 这只是能力发现：所有已启用工具在每个步骤都已声明、可直接调用，
 * 调用本工具与否不会影响任何工具的声明或执行。
 */
fun toolSearchTool(
    availableToolNames: Set<String>? = null,
) = Tool(
    name = "search_tools",
    description = """
        Search for available tools by keyword or category.
        Use this when you need a capability but don't know which tool provides it.
        Returns matching tool names, descriptions, categories, and parameter schemas
        so you can call them immediately.

        Multi-keyword search: separate keywords with spaces. ALL keywords must match
        (tool name or description). Example: "ssh upload" matches tools with both
        "ssh" and "upload" in their name or description. If no tool matches all
        keywords, the search falls back to OR semantics, then to fuzzy matching.

        Category browse: omit query and pass category to list all tools in that
        category. Example: category="file" lists all file management tools.

        Available categories: device, media, phone, camera, screen, app, shell, telegram,
        cron, file, notification, mcp, automation, config, subagent, skill,
        intent, workflow, browser, security, nfc, storage, archive, keyboard,
        workspace, download, location, sensor, telephony, wallpaper, misc
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Search keyword(s). Multiple keywords separated by spaces require ALL to match. Omit to browse by category.")
                })
                put("category", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional: filter by category (e.g. 'shell', 'file', 'browser'). Use alone to browse all tools in a category.")
                })
                put("limit", buildJsonObject {
                    put("type", "number")
                    put("description", "Max results to return (default 10, max 50)")
                })
            },
            required = emptyList()
        )
    },
    needsApproval = { false },
    execute = { args ->
        val query = args.jsonObject["query"]?.jsonPrimitive?.contentOrNull ?: ""
        val category = args.jsonObject["category"]?.jsonPrimitive?.contentOrNull
        val limit = (args.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 50)

        if (query.isBlank() && category.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(
                """{"error": "at least one of query or category is required"}"""
            ))
        }

        val matches = ToolRegistry.search(query, category)
            .filter { availableToolNames == null || it.name in availableToolNames }
            .take(limit)

        val result = buildJsonObject {
            put("count", matches.size)
            put("total_registered", ToolRegistry.size())
            if (matches.isEmpty()) {
                put("hint", "No tools found. Try a broader keyword or check category names.")
            }
            putJsonArray("tools") {
                matches.forEach { entry ->
                    add(buildJsonObject {
                        put("name", entry.name)
                        put("description", entry.description)
                        put("category", entry.category)
                        put("needs_approval", entry.needsApproval)
                        entry.schema?.let { schema ->
                            put("parameters", JsonInstant.encodeToJsonElement(schema))
                        }
                    })
                }
            }
        }
        listOf(UIMessagePart.Text(result.toString()))
    }
)
