package me.rerere.rikkahub.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider

private val Context.toolApprovalDataStore by preferencesDataStore(name = "tool_approval")

enum class ChatPermissionMode {
    MANUAL,
    AUTO_REVIEW,
    FULL_ACCESS;

    companion object {
        fun fromStored(value: String?, legacyYolo: Boolean): ChatPermissionMode {
            if (value == null) return if (legacyYolo) FULL_ACCESS else MANUAL
            return entries.firstOrNull { it.name == value } ?: MANUAL
        }
    }
}

internal fun Model.supportsApprovalReview(): Boolean =
    type == ModelType.CHAT && Modality.TEXT in inputModalities && Modality.TEXT in outputModalities

internal fun Settings.availableApprovalDecisionModel(): Model? =
    findModelById(approvalDecisionModelId)?.takeIf {
        it.supportsApprovalReview() && it.findProvider(providers, checkOverwrite = false)?.enabled == true
    }

/**
 * Persistent tool-approval prefs. Two pieces:
 *
 * 1. **"Always Allow" allow-list** — per-tool grants that survive app restart until the
 *    user revokes them from Settings → Tool approvals. The HARDLINE floor still applies
 *    even with Always Allow granted.
 *
 * 2. **"I AM STUPID" global auto-approve** — single boolean that, when true, treats every
 *    tool as pre-approved across every conversation and every surface (in-app, Telegram,
 *    cron). HARDLINE STILL APPLIES — there is no override for that. This is the user's
 *    explicit "I trust the agent fully, stop asking me" escape hatch. Surfaced as a
 *    bright-red toggle behind a confirm dialog because it's a live foot-gun.
 *
 * The companion in-memory "Allow for this chat" allow-list lives in
 * [me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList] and resets on /new or app
 * process restart.
 */
class ToolApprovalPreferences(private val context: Context) {
    private val store = context.toolApprovalDataStore
    private val K_ALWAYS_ALLOW = stringSetPreferencesKey("always_allow_tool_names")
    private val K_GLOBAL_YOLO = booleanPreferencesKey("global_auto_approve_yolo")
    private val K_PERMISSION_MODE = stringPreferencesKey("chat_permission_mode")

    val alwaysAllowFlow: Flow<Set<String>> = store.data.map {
        it[K_ALWAYS_ALLOW].orEmpty()
    }

    /** Global ordinary-chat permission mode. Existing YOLO users migrate to FULL_ACCESS. */
    val permissionModeFlow: Flow<ChatPermissionMode> = store.data.map {
        ChatPermissionMode.fromStored(it[K_PERMISSION_MODE], it[K_GLOBAL_YOLO] ?: false)
    }

    /** Live flow of the legacy "I AM STUPID" switch, backed by the three-mode setting. */
    val globalYoloFlow: Flow<Boolean> = permissionModeFlow.map { it == ChatPermissionMode.FULL_ACCESS }

    suspend fun current(): Set<String> = alwaysAllowFlow.first()

    suspend fun currentPermissionMode(): ChatPermissionMode = permissionModeFlow.first()

    suspend fun setPermissionMode(mode: ChatPermissionMode) {
        store.edit {
            it[K_PERMISSION_MODE] = mode.name
            it[K_GLOBAL_YOLO] = mode == ChatPermissionMode.FULL_ACCESS
        }
    }

    /** Snapshot read of the YOLO flag. Used by the per-call auto-approval check. */
    suspend fun currentYolo(): Boolean = currentPermissionMode() == ChatPermissionMode.FULL_ACCESS

    suspend fun setYolo(enabled: Boolean) {
        setPermissionMode(if (enabled) ChatPermissionMode.FULL_ACCESS else ChatPermissionMode.MANUAL)
    }

    suspend fun grantAlways(toolName: String) {
        store.edit { it[K_ALWAYS_ALLOW] = (it[K_ALWAYS_ALLOW].orEmpty()) + toolName }
    }

    suspend fun revoke(toolName: String) {
        store.edit { it[K_ALWAYS_ALLOW] = (it[K_ALWAYS_ALLOW].orEmpty()) - toolName }
    }

    suspend fun revokeAll() {
        store.edit { it.remove(K_ALWAYS_ALLOW) }
    }
}
