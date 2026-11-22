package me.rerere.rikkahub.data.ai.approval

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.files.WorkspacePublicExport

enum class ToolReviewAction {
    ALLOW,
    ASK_USER,
}

data class ToolReviewRequest(
    val toolName: String,
    val args: JsonObject,
    val userIntent: String,
)

data class ToolReviewResult(
    val action: ToolReviewAction,
    val reason: String,
) {
    companion object {
        fun allow(reason: String) = ToolReviewResult(ToolReviewAction.ALLOW, reason)
        fun askUser(reason: String) = ToolReviewResult(ToolReviewAction.ASK_USER, reason)
    }
}

/**
 * Lightweight approval policy for ordinary chat. Static rules only fast-path operations whose
 * scope is visible locally; the decision model handles everything else that can affect privacy,
 * the network, shared storage, or important device state.
 */
class AutoApprovalPolicy(
    private val modelReviewer: suspend (ToolReviewRequest) -> ToolReviewResult,
) {
    suspend fun review(
        toolName: String,
        args: JsonObject,
        needsApproval: Boolean,
        userIntent: String,
    ): ToolReviewResult {
        if (!needsApproval && isOrdinaryWorkspaceFileCall(toolName, args)) {
            return ToolReviewResult.allow("ordinary_workspace_file_operation")
        }
        if (!needsApproval && toolName !in ALWAYS_REVIEWED_TOOLS && ALWAYS_REVIEWED_PREFIXES.none(toolName::startsWith)) {
            return ToolReviewResult.allow("locally_scoped_low_risk_tool")
        }
        return try {
            modelReviewer(ToolReviewRequest(toolName, args, userIntent.take(MAX_USER_INTENT_CHARS)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ToolReviewResult.askUser("decision_model_failed")
        }
    }

    private fun isOrdinaryWorkspaceFileCall(toolName: String, args: JsonObject): Boolean {
        if (toolName !in WORKSPACE_FILE_TOOLS || containsSensitiveMaterial(args)) return false
        val path = (args["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        val normalized = runCatching { WorkspacePublicExport.normalizeRootfsPath(path) }.getOrNull()
            ?: return false
        if (SENSITIVE_PATH_REGEX.containsMatchIn(normalized)) return false
        return WORKSPACE_ROOTS.any { normalized == it || normalized.startsWith("$it/") }
    }

    private fun containsSensitiveMaterial(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.any { (key, value) ->
            SENSITIVE_KEY_REGEX.containsMatchIn(key) || containsSensitiveMaterial(value)
        }
        is JsonArray -> element.any(::containsSensitiveMaterial)
        is JsonPrimitive -> element.contentOrNull?.let { value ->
            SENSITIVE_PATH_REGEX.containsMatchIn(value) || PRIVATE_KEY_REGEX.containsMatchIn(value)
        } == true
        else -> false
    }

    private companion object {
        const val MAX_USER_INTENT_CHARS = 4_000
        val WORKSPACE_FILE_TOOLS = setOf(
            "workspace_read_file",
            "workspace_write_file",
            "workspace_edit_file",
        )
        val WORKSPACE_ROOTS = setOf("/workspace", "/tmp")
        val ALWAYS_REVIEWED_TOOLS = setOf(
            "termux_run_command",
            "get_location",
            "get_contacts",
            "get_sms",
            "clipboard_tool",
            "get_telephony_info",
            "list_active_notifications",
            "list_recent_notifications",
            "recent_chats",
            "conversation_search",
            "take_screenshot",
            "read_screen",
            "read_window_tree",
        )
        val ALWAYS_REVIEWED_PREFIXES = setOf(
            "workspace_", // Only the argument-aware fast path can skip review for workspace tools.
            "browser_",
            "mcp__",
            "telegram_",
            "web_",
            "search_web",
            "download_",
            "share",
            "ssh_",
        )
        val SENSITIVE_KEY_REGEX = Regex(
            "(?:pass(?:word)?|secret|token|credential|cookie|authorization|api[_-]?key|private[_-]?key)",
            RegexOption.IGNORE_CASE,
        )
        val SENSITIVE_PATH_REGEX = Regex(
            "(?:^|[/\\\\])(?:\\.env(?:\\..*)?|id_(?:rsa|dsa|ecdsa|ed25519)|credentials?|secrets?|\\.ssh|\\.gnupg)(?:$|[/\\\\])",
            RegexOption.IGNORE_CASE,
        )
        val PRIVATE_KEY_REGEX = Regex("-----BEGIN (?:[A-Z ]+)?PRIVATE KEY-----")
    }
}
