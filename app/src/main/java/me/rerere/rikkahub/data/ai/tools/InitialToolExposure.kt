package me.rerere.rikkahub.data.ai.tools

/**
 * Default set of tool names exposed directly on turn 1 before any `search_tools` call.
 * All other enabled tools (including `sdcard_*`, `workspace_run_background`,
 * `workspace_background_status`, `workspace_background_kill`, `workspace_export_to_downloads`,
 * device tools, and MCP tools) are discovered lazily via `search_tools`.
 */
internal val DEFAULT_INITIAL_EXPOSED_TOOLS: Set<String> = linkedSetOf(
    "ask_user",
    "get_time_info",
    "eval_javascript",
    "termux_run_command",
    "workspace_read_file",
    "workspace_write_file",
    "workspace_edit_file",
    "workspace_shell",
)

/**
 * Resolves the effective initial exposed tool name set from Settings (`null` = built-in defaults).
 */
internal fun effectiveInitialExposedTools(configured: Set<String>?): Set<String> =
    configured ?: DEFAULT_INITIAL_EXPOSED_TOOLS

/**
 * Known built-in tool names surfaced in Settings -> Initial Exposed Tools even before
 * `ToolRegistry` is populated by the first generation turn.
 */
internal val KNOWN_CONFIGURABLE_TOOLS: List<Pair<String, String>> = listOf(
    "ask_user" to "misc",
    "get_time_info" to "misc",
    "eval_javascript" to "misc",
    "termux_run_command" to "shell",
    "workspace_shell" to "workspace",
    "workspace_read_file" to "workspace",
    "workspace_write_file" to "workspace",
    "workspace_edit_file" to "workspace",
    "workspace_run_background" to "workspace",
    "workspace_background_status" to "workspace",
    "workspace_background_kill" to "workspace",
    "workspace_export_to_downloads" to "workspace",
    "sdcard_list_files" to "file",
    "sdcard_read_file" to "file",
    "sdcard_write_binary_file" to "file",
    "sdcard_delete_file" to "file",
    "sdcard_move_file" to "file",
    "sdcard_copy_file" to "file",
    "sdcard_create_directory" to "file",
    "sdcard_file_info" to "file",
    "sdcard_find_files" to "file",
    "sdcard_batch_copy" to "file",
    "sdcard_batch_move" to "file",
    "sdcard_batch_delete" to "file",
    "show_image" to "file",
    "open_file" to "file",
    "write_text_file" to "file",
)
