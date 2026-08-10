package me.rerere.rikkahub.data.ai.limits

import me.rerere.rikkahub.data.preferences.TermuxDefaults

/**
 * App-wide @Volatile runtime holder for tool execution limits that span all tool families
 * (not just Termux). Currently holds the per-turn tool-call step cap and the per-tool
 * execution timeout; the per-turn wall-clock budget was removed.
 *
 * Pushed from [me.rerere.rikkahub.data.preferences.TermuxPreferences.init] because the
 * Termux settings page is where the user configures it. The fields themselves are app-wide
 * and the holder lives in data/ai/limits/ to reflect that.
 */
object ToolRuntimeLimits {
    /**
     * Default hard cap (5 min) on how long a SINGLE tool execution may run before it is
     * cancelled and a "tool_cancelled_timeout" envelope is returned to the model. This
     * replaces the protection the removed per-turn wall-clock budget gave against
     * indefinitely-hanging tools (e.g. fused location awaiting a fix) without re-introducing
     * a whole-turn cap.
     */
    const val DEFAULT_PER_TOOL_EXECUTION_TIMEOUT_MS = 300_000L

    /**
     * Live per-tool execution timeout, read by [me.rerere.rikkahub.data.ai.executeToolWithTimeout].
     * Unlike [maxToolSteps] this is not user-configurable in Settings; it exists as a holder
     * so tests can shrink the threshold without rebuilding.
     */
    @Volatile var perToolExecutionTimeoutMs: Long = DEFAULT_PER_TOOL_EXECUTION_TIMEOUT_MS

    /**
     * Tool-call iterations a single turn may run before it is force-ended. Was hardcoded at 32,
     * which truncated long Termux chains mid-task (issue #22).
     */
    @Volatile var maxToolSteps: Int = TermuxDefaults.DEFAULT_MAX_TOOL_STEPS
}
