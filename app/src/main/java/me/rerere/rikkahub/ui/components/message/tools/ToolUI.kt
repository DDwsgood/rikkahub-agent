package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/**
 * 工具调用的渲染上下文, 预解析好工具入参与输出, 避免各渲染器重复解析
 */
data class ToolUIContext(
    val tool: UIMessagePart.Tool,
    /** 工具入参 ([UIMessagePart.Tool.input] 的 JSON 解析结果) */
    val arguments: JsonElement,
    /** 输出文本部件解析出的 JSON, 工具未执行时为 null */
    val content: JsonElement?,
    /** 该工具调用是否在生成中 */
    val loading: Boolean,
)

/**
 * 单个工具的 UI 渲染器
 *
 * 在 [ToolUIRegistry] 注册后, 聊天消息中对应的工具调用将使用该渲染器展示;
 * 未注册的工具 fallback 到接口的默认实现 (通用标题/图标 + JSON 详情)
 */
interface ToolUIRenderer {
    /** 渲染器对应的工具名 */
    val toolName: String

    /** 折叠步骤的图标 */
    fun icon(context: ToolUIContext): ImageVector = HugeIcons.Tools

    /** 折叠步骤的标题 */
    @Composable
    fun title(context: ToolUIContext): String = formatToolTitle(
        displayName = toolDisplayName(context.tool.toolName),
        toolName = context.tool.toolName,
        arguments = context.arguments,
    )

    /** 步骤展开时是否显示内联摘要 */
    fun hasSummary(context: ToolUIContext): Boolean = false

    /** 步骤展开时的内联摘要 */
    @Composable
    fun Summary(context: ToolUIContext) {
    }

    /** 点击步骤后的详情, 渲染在 BottomSheet 内 */
    @Composable
    fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        DefaultToolPreview(context = context)
    }
}

/** 未注册工具使用的默认渲染器, 全部行为来自 [ToolUIRenderer] 的默认实现 */
private object DefaultToolUIRenderer : ToolUIRenderer {
    override val toolName: String get() = ""
}

/**
 * 工具 UI 渲染器注册表, 为新工具定制渲染时在 [renderers] 中注册即可
 */
object ToolUIRegistry {
    private val renderers: Map<String, ToolUIRenderer> = listOf(
        MemoryToolUI,
        SearchWebToolUI,
        ScrapeWebToolUI,
        GetTimeInfoToolUI,
        ClipboardToolUI,
        TextToSpeechToolUI,
        GetScreenTimeToolUI,
        CalendarQueryToolUI,
        CalendarCreateToolUI,
        UseSkillToolUI,
        RecentChatsToolUI,
        ConversationSearchToolUI,
        EditFileToolUI,
        ReadFileToolUI,
        WriteFileToolUI,
        ShellToolUI,
        RunJsToolUI,
        CreateCalendarEventToolUI,
        CreateContactToolUI,
        SendSmsIntentToolUI,
        SendEmailIntentToolUI,
        OpenWifiSettingsToolUI,
        ShowLocationOnMapToolUI,
    ).associateBy { it.toolName }

    /** 查找工具对应的渲染器, 未注册时返回默认渲染器 */
    fun resolve(toolName: String): ToolUIRenderer = renderers[toolName] ?: DefaultToolUIRenderer
}

internal fun JsonElement?.getStringContent(key: String): String? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.contentOrNull

private const val TOOL_TITLE_ARG_MAX_CHARS = 60

private val TOOL_TITLE_PRIORITY_KEYS = listOf(
    "command",
    "cmd",
    "query",
    "q",
    "url",
    "uri",
    "path",
    "file_path",
    "filepath",
    "dir",
    "directory",
    "folder",
    "source",
    "src",
    "target",
    "destination",
    "dest",
    "host",
    "hostname",
    "alias",
    "name",
    "skill_name",
    "title",
    "subject",
    "to",
    "recipient",
    "phone",
    "phone_number",
    "package",
    "package_name",
    "app",
    "app_name",
    "selector",
    "text",
    "message",
    "content",
    "expression",
    "code",
    "script",
    "action",
    "category",
    "id",
    "key",
)

private val SENSITIVE_ARG_TOKENS = listOf(
    "password",
    "passwd",
    "secret",
    "token",
    "api_key",
    "apikey",
    "auth",
    "credential",
    "private_key",
    "cookie",
    "header",
)

private fun isSensitiveArgKey(key: String): Boolean {
    val lower = key.lowercase()
    return SENSITIVE_ARG_TOKENS.any { it in lower }
}

private fun JsonElement?.toCompactArgText(): String? {
    return when (this) {
        is JsonPrimitive -> {
            val normalized = content.replace(Regex("\\s+"), " ").trim()
            normalized.takeIf { it.isNotEmpty() }
        }
        is JsonArray -> {
            val items = asSequence()
                .mapNotNull { (it as? JsonPrimitive)?.content?.replace(Regex("\\s+"), " ")?.trim() }
                .filter { it.isNotEmpty() }
                .take(3)
                .toList()
            items.takeIf { it.isNotEmpty() }?.joinToString(", ")
        }
        else -> null
    }
}

private fun truncateArgSummary(summary: String): String =
    if (summary.length > TOOL_TITLE_ARG_MAX_CHARS) summary.take(TOOL_TITLE_ARG_MAX_CHARS) + "…" else summary

internal fun formatToolArgumentSummary(toolName: String, arguments: JsonElement): String? {
    val obj = arguments.jsonObjectOrNull ?: return null
    if (obj.isEmpty()) return null

    fun firstNonBlank(vararg keys: String): String? {
        for (k in keys) {
            if (isSensitiveArgKey(k)) continue
            val value = obj[k].toCompactArgText()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    val rawSummary = when {
        toolName == "search_tools" -> {
            val query = firstNonBlank("query", "q")
            val category = firstNonBlank("category")
            when {
                query != null && category != null -> "$query ($category)"
                query != null -> query
                category != null -> "category=$category"
                else -> null
            }
        }
        toolName in setOf("sdcard_move_file", "sdcard_copy_file", "ssh_upload", "ssh_download") -> {
            val src = firstNonBlank("source", "src", "local_path")
            val dst = firstNonBlank("destination", "dest", "dst", "remote_path")
            if (src != null && dst != null) "$src → $dst" else null
        }
        toolName in setOf("ssh_exec", "ssh_exec_saved") -> {
            val host = firstNonBlank("host", "alias")
            val cmd = firstNonBlank("command", "cmd")
            if (host != null && cmd != null) "$host: $cmd" else null
        }
        else -> null
    } ?: run {
        var found: String? = null
        for (key in TOOL_TITLE_PRIORITY_KEYS) {
            if (isSensitiveArgKey(key)) continue
            val text = obj[key].toCompactArgText()
            if (!text.isNullOrBlank()) {
                found = text
                break
            }
        }
        found ?: obj.entries.firstNotNullOfOrNull { (k, v) ->
            if (isSensitiveArgKey(k)) null else v.toCompactArgText()
        }
    }

    return rawSummary?.takeIf { it.isNotBlank() }?.let(::truncateArgSummary)
}

internal fun formatToolTitle(
    displayName: String,
    toolName: String,
    arguments: JsonElement,
): String {
    val summary = formatToolArgumentSummary(toolName, arguments)
    return if (summary.isNullOrBlank()) displayName else "$displayName: $summary"
}

/**
 * 默认工具详情: 入参与输出的 JSON 高亮展示
 *
 * @param headerActions 标题栏右侧的附加操作区
 */
@Composable
fun DefaultToolPreview(
    context: ToolUIContext,
    headerActions: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.chat_message_tool_call_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            headerActions?.invoke()
        }
        FormItem(
            label = {
                Text(
                    stringResource(
                        R.string.chat_message_tool_call_label,
                        toolDisplayName(context.tool.toolName),
                    )
                )
            }
        ) {
            HighlightCodeBlock(
                code = JsonInstantPretty.encodeToString(context.arguments),
                language = "json",
                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
            )
        }
        if (context.tool.output.isNotEmpty()) {
            FormItem(
                label = {
                    Text(stringResource(R.string.chat_message_tool_call_result))
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    context.tool.output.fastForEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> HighlightCodeBlock(
                                code = runCatching {
                                    JsonInstantPretty.encodeToString(
                                        JsonInstant.parseToJsonElement(part.text)
                                    )
                                }.getOrElse { part.text },
                                language = "json",
                                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
                            )

                            is UIMessagePart.Image -> ZoomableAsyncImage(
                                model = part.url,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            else -> {}
                        }
                    }
                }
            }
        }
    }
}
