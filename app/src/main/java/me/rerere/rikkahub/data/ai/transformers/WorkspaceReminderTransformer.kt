package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

/**
 * Workspace 系统提示注入转换器
 *
 * 根据 workspace 状态向系统提示注入不同内容, 让模型"认知"到 workspace 并能在需要时指导用户:
 * - 已绑定且 shell 就绪: 注入完整 <workspace> 引导 (workspace_* 工具可用)。
 * - 已绑定但 shell 未就绪: 注入 <workspace-setup>, 告知模型如何引导用户让 shell 就绪。
 * - 未绑定但存在至少一个 workspace: 注入 <workspace-setup>, 告知模型如何引导用户绑定。
 * - 完全没有 workspace: 不注入。
 *
 * 工具是否真正提供仍由 ChatService.createWorkspaceToolsIfReady 决定 (仅 READY 时提供),
 * 本转换器只扩展模型对 workspace 的认知, 不改变工具可用性。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString()
        val workspace = workspaceId?.let { workspaceRepository.getById(it) }
        // 仅在未解析到绑定的 workspace 时才需要查询是否存在其它 workspace (短路避免多余查询)
        val hasAnyWorkspace = workspace != null || workspaceRepository.getAll().isNotEmpty()
        val sharedDirMounted = workspace != null && workspaceRepository.isSharedDirMounted(workspace.id)

        val prompt = buildWorkspaceReminder(
            workspace = workspace,
            hasAnyWorkspace = hasAnyWorkspace,
            cwd = ctx.workspaceCwd,
            sharedDirMounted = sharedDirMounted,
        ) ?: return messages

        // 追加到第一条 system 消息; 若不存在则插入一条
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendText("\n\n$prompt")
            }
        } else {
            listOf(UIMessage.system(prompt)) + messages
        }
    }
}

/**
 * 纯函数: 根据 workspace 状态选择要注入的系统提示, 返回 null 表示不注入。
 * 抽离出来以便单元测试, 不依赖 Android / Repository。
 *
 * - workspace 就绪 (READY): 完整工具引导。
 * - workspace 已绑定但未就绪: 指导用户安装/修复 rootfs。
 * - 未绑定但存在 workspace: 指导用户绑定。
 * - 其它 (无 workspace): null。
 */
internal fun buildWorkspaceReminder(
    workspace: WorkspaceEntity?,
    hasAnyWorkspace: Boolean,
    cwd: String? = null,
    sharedDirMounted: Boolean = false,
): String? = when {
    workspace != null && workspace.shellStatus == WorkspaceShellStatus.READY.name ->
        buildWorkspacePrompt(workspace, cwd, sharedDirMounted)

    workspace != null -> buildWorkspaceNotReadyPrompt(workspace)

    hasAnyWorkspace -> buildWorkspaceUnboundPrompt()

    else -> null
}

private fun buildWorkspacePrompt(
    workspace: WorkspaceEntity,
    cwd: String? = null,
    sharedDirMounted: Boolean = false,
): String = buildString {
    appendLine("<workspace>")
    appendLine("PRoot workspace: ${workspace.name}. Use absolute rootfs paths; /workspace is persistent and app-private. workspace_shell runs here; workspace_run_background takes a foreground command (no &).")
    appendLine("Export user-visible files with workspace_export_to_downloads to Download/RikkaHub/${workspace.name}/.")
    val sdcardMode = workspace.sdcardModeEnum()
    when (sdcardMode) {
        me.rerere.workspace.WorkspaceSdcardMode.READ_ONLY ->
            appendLine("- Shared storage `/sdcard` is enabled in READ-ONLY mode (`READ_ONLY`). You may read files under `/sdcard` via `workspace_read_file`; writes to `/sdcard` are blocked and PRoot shells/background tasks do not mount `/sdcard`.")
        me.rerere.workspace.WorkspaceSdcardMode.READ_WRITE ->
            appendLine("- Shared storage `/sdcard` is mounted READ-WRITE (`READ_WRITE`). Writes to `/sdcard` require user approval.")
        me.rerere.workspace.WorkspaceSdcardMode.NONE -> Unit
    }
    if (sharedDirMounted) {
        val publicPath = me.rerere.rikkahub.data.preferences.WorkspaceSharedDirPreferences.displayPath(workspace.name, workspace.id)
        appendLine("- A user-visible shared folder is mounted at `/shared` (backed by `/sdcard/$publicPath`). Files written to `/shared` appear directly in Android file managers; writing to `/shared` still prompts for user approval.")
    }
    appendLine("Skills: /skills/<name>/SKILL.md. /upload is READ-ONLY; edit copies in /workspace.")
    if (!cwd.isNullOrBlank()) {
        appendLine("- Current working directory: `$cwd`. Use this as the default context for file operations and shell commands.")
    }
    append("</workspace>")
}

/**
 * 已绑定 workspace 但 shell 未就绪 (DISABLED / INSTALLING / BROKEN): 工具不可用, 指导用户使其就绪。
 */
private fun buildWorkspaceNotReadyPrompt(workspace: WorkspaceEntity): String = buildString {
    appendLine("<workspace-setup>")
    appendLine("A workspace named \"${workspace.name}\" is bound to this assistant, but its Linux shell is not ready (status: ${workspace.shellStatus}), so the workspace tools (workspace_read_file, workspace_write_file, workspace_edit_file, workspace_shell, workspace_run_background, workspace_background_status, workspace_background_kill) are NOT available right now.")
    val howto = when (workspace.shellStatus) {
        WorkspaceShellStatus.INSTALLING.name ->
            "the rootfs is currently installing; ask them to wait for the install to finish, then send a new message."
        WorkspaceShellStatus.BROKEN.name ->
            "the rootfs is broken; ask them to open Extensions > Workspace, open this workspace, and reinstall/repair its rootfs until the shell status shows Ready."
        else ->
            "ask them to open Extensions > Workspace, open this workspace, and install its rootfs until the shell status shows Ready."
    }
    appendLine("If the user wants to use the workspace, explain in the user's language how to make it ready: $howto")
    appendLine("Do not claim to have workspace tools or attempt to call them until the shell status is Ready.")
    append("</workspace-setup>")
}

/**
 * 存在 workspace 但未绑定到当前助手: 工具不可用, 指导用户绑定。
 */
private fun buildWorkspaceUnboundPrompt(): String = buildString {
    appendLine("<workspace-setup>")
    appendLine("The user has a workspace, but none is bound to this assistant, so the workspace tools (workspace_read_file, workspace_write_file, workspace_edit_file, workspace_shell, workspace_run_background, workspace_background_status, workspace_background_kill) are NOT available.")
    appendLine("If the user asks to save files or run shell / Linux commands in a workspace, explain in the user's language how to enable it:")
    appendLine("1. Tap the + button in the chat input bar and select a workspace to bind it to this assistant.")
    appendLine("2. If that workspace's shell is not Ready yet, open Extensions > Workspace, open the workspace, and install its rootfs until the shell status shows Ready.")
    appendLine("Do not claim to have workspace tools or attempt to call them until a workspace is bound and its shell is Ready.")
    append("</workspace-setup>")
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
