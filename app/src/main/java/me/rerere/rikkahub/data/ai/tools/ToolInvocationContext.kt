package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool

/**
 * Phase 17 stability - context every tool factory in [LocalTools.getTools] sees about WHO
 * is invoking it. Until this layer existed, tools that needed to know the calling
 * conversation / assistant id (sub-agent recursion guard, workflow_create authoring-id
 * persistence) had no way to read it - both shipped with placeholder defaults and silent
 * gaps the audit caught.
 *
 * Convention: every getTools() caller MUST construct a ToolInvocationContext with the most
 * specific data it has. The default ([EMPTY]) is a no-op safe fallback used when the
 * caller doesn't track the data (legacy / one-off paths) - but factories should treat the
 * empty context as "I don't know" not "no constraints", and apply conservative defaults.
 *
 * Fields:
 *  - [callerAssistantId]: the assistant whose toggles are being dispatched. ChatService
 *    knows this from `settings.getCurrentAssistant().id`. Cron / workflow / sub-agent
 *    paths know it from their respective entity's assistant id.
 *  - [callerConversationId]: the conversation-uuid of the user-facing chat (interactive)
 *    or the headless conversation (cron / workflow / sub-agent / external-automation).
 *  - [callerWorkspaceId]: the workspace UUID (string form) bound to the calling assistant,
 *    if any. `share_file` uses this to resolve `/workspace/...` paths via the correct
 *    proot rootfs. Null when the assistant has no bound workspace — workspace-source
 *    shares are refused with a structured error in that case.
 *  - [isHeadless]: true when the dispatch is happening from a system flow rather than the
 *    user typing in a chat. Sub-agents, cron jobs, workflows, and external-automation
 *    runs all set this to true so the recursion guard fires.
 *  - [modelCanSeeImages]: true iff the model handling this turn has image input in its
 *    modalities. `show_image` reads this so a text-only model is told plainly it cannot
 *    see the picture (and must OCR / file-process it) instead of being handed dimensions
 *    that read like "I looked at it" - the root cause of confabulated image descriptions.
 *    Defaults to `true`: the no-knowledge fallback preserves the pre-fix behaviour, and
 *    ChatService (the only LLM-driven dispatch path) always sets it explicitly.
 *  - [dynamicToolsProvider]: returns the extra tools to DECLARE for this generation
 *    (merged into the request's tools field before every provider call). ChatService
 *    returns only the tools the model has already discovered via `search_tools` or
 *    actually executed, keeping the first request's tool list small.
 *  - [resolvableToolsProvider]: returns every tool the assistant may EXECUTE this
 *    generation, whether declared or not. Used as the resolveTool fallback so a tool
 *    the model learned about from search results or conversation history still runs
 *    even when it was never declared in the request.
 */
data class ToolInvocationContext(
    val callerAssistantId: String? = null,
    val callerConversationId: String? = null,
    val callerWorkspaceId: String? = null,
    val isHeadless: Boolean = false,
    val modelCanSeeImages: Boolean = true,
    val dynamicToolsProvider: (() -> List<Tool>)? = null,
    val resolvableToolsProvider: (() -> List<Tool>)? = null,
) {
    companion object {
        /** No-knowledge fallback. Factories that depend on context MUST handle this. */
        val EMPTY = ToolInvocationContext()
    }
}
