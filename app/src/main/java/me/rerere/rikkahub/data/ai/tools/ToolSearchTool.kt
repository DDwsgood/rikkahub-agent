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
 * 默认注入到每个 LLM 请求中。LLM 调用它来搜索
 * 未默认注入的工具（非核心工具、MCP 工具等）。
 * 返回匹配工具的完整 schema，LLM 可以立即调用。
 */
fun toolSearchTool(
    availableToolNames: Set<String>? = null,
    onToolsDiscovered: (List<String>) -> Unit = {},
) = Tool(
    name = "search_tools",
    description = """
        Search for available tools by keyword or category.
        Use this when you need a capability that is not in your current tool list.
        Returns matching tool names, descriptions, categories, and parameter schemas
        so you can call them immediately.

        Available categories: device, media, phone, camera, screen, app, shell, telegram,
        cron, file, notification, mcp, automation, config, subagent, skill,
        intent, workflow, browser, security, nfc, storage, archive, keyboard,
        workspace, download, location, sensor, telephony, wallpaper
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Search keyword (matches tool name and description)")
                })
                put("category", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional: filter by category (e.g. 'shell', 'telegram', 'file')")
                })
                put("limit", buildJsonObject {
                    put("type", "number")
                    put("description", "Max results to return (default 10, max 50)")
                })
            },
            required = listOf("query")
        )
    },
    needsApproval = { false },
    execute = { args ->
        val query = args.jsonObject["query"]?.jsonPrimitive?.contentOrNull ?: ""
        val category = args.jsonObject["category"]?.jsonPrimitive?.contentOrNull
        val limit = (args.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 50)

        if (query.isBlank()) {
            return@Tool listOf(UIMessagePart.Text(
                """{"error": "query parameter is required and cannot be empty"}"""
            ))
        }

        val matches = ToolRegistry.search(query, category)
            .filter { availableToolNames == null || it.name in availableToolNames }
            .take(limit)
        onToolsDiscovered(matches.map { it.name })

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
