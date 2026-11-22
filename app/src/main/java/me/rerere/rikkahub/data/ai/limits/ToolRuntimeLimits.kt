package me.rerere.rikkahub.data.ai.limits

import me.rerere.rikkahub.data.preferences.TermuxDefaults

/**
 * App-wide @Volatile runtime holder for tool execution limits that span all tool families
 * (not just Termux). Tool calls have no generation-loop wall-clock timeout; individual tools
 * may still enforce their own operation-specific timeouts.
 *
 * Pushed from [me.rerere.rikkahub.data.preferences.TermuxPreferences.init] because the
 * Termux settings page is where the user configures it. The field itself is app-wide and the
 * holder lives in data/ai/limits/ to reflect that.
 */
object ToolRuntimeLimits {
    /**
     * Tool-call iterations a single turn may run before it is force-ended. Was hardcoded at 32,
     * which truncated long Termux chains mid-task (issue #22).
     */
    @Volatile var maxToolSteps: Int = TermuxDefaults.DEFAULT_MAX_TOOL_STEPS
}
