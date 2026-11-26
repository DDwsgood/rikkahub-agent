package me.rerere.rikkahub.ui.components.message.tools

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R

private val TOOL_DISPLAY_NAME_RES: Map<String, Int> = mapOf(
    // Core / AI / Memory / Search / Skills / Conversation
    "memory_tool" to R.string.tool_display_memory_tool,
    "search_web" to R.string.tool_display_search_web,
    "search_media" to R.string.tool_display_search_media,
    "scrape_web" to R.string.chat_message_tool_scrape_web,
    "recent_chats" to R.string.chat_message_tool_recent_chats,
    "conversation_search" to R.string.tool_display_conversation_search,
    "search_tools" to R.string.tool_display_search_tools,
    "use_skill" to R.string.tool_display_use_skill,
    "skill_get_content" to R.string.tool_display_skill_get_content,
    "skill_install_from_url" to R.string.tool_display_skill_install_from_url,
    "skill_install_from_text" to R.string.tool_display_skill_install_from_text,
    "run_js" to R.string.tool_display_run_js,
    "eval_javascript" to R.string.tool_display_eval_javascript,
    "get_time_info" to R.string.chat_message_tool_get_time,
    "clipboard_tool" to R.string.tool_display_clipboard_tool,
    "text_to_speech" to R.string.tool_display_text_to_speech,
    "ask_user" to R.string.tool_display_ask_user,
    "check_token_usage" to R.string.tool_display_check_token_usage,
    "get_screen_time" to R.string.chat_message_tool_screen_time,
    "calendar_query" to R.string.chat_message_tool_calendar_query,
    "calendar_create" to R.string.tool_display_calendar_create,

    // In-app browser
    "browser_open" to R.string.tool_display_browser_open,
    "browser_current_url" to R.string.setting_browser_tool_current_url_title,
    "browser_get_text" to R.string.setting_browser_tool_get_text_title,
    "browser_get_dom" to R.string.setting_browser_tool_get_dom_title,
    "browser_get_links" to R.string.setting_browser_tool_get_links_title,
    "browser_get_cookies" to R.string.setting_browser_tool_get_cookies_title,
    "browser_back" to R.string.setting_browser_tool_back_title,
    "browser_forward" to R.string.setting_browser_tool_forward_title,
    "browser_wait_for" to R.string.setting_browser_tool_wait_for_title,
    "browser_click" to R.string.setting_browser_tool_click_title,
    "browser_type" to R.string.setting_browser_tool_type_title,
    "browser_scroll" to R.string.setting_browser_tool_scroll_title,
    "browser_submit" to R.string.setting_browser_tool_submit_title,
    "browser_select" to R.string.setting_browser_tool_select_title,
    "browser_press_key" to R.string.setting_browser_tool_press_key_title,
    "browser_eval_js" to R.string.setting_browser_tool_eval_js_title,
    "browser_handle_dialog" to R.string.setting_browser_tool_handle_dialog_title,
    "browser_set_viewport" to R.string.setting_browser_tool_set_viewport_title,
    "browser_click_and_read" to R.string.setting_browser_tool_click_and_read_title,
    "browser_done" to R.string.setting_browser_tool_done_title,

    // Workspace
    "workspace_read_file" to R.string.tool_ui_read_file_default,
    "workspace_write_file" to R.string.tool_ui_write_file_default,
    "workspace_edit_file" to R.string.tool_ui_edit_file_default,
    "workspace_shell" to R.string.tool_ui_shell_default,
    "workspace_run_background" to R.string.tool_display_workspace_run_background,
    "workspace_background_status" to R.string.tool_display_workspace_background_status,
    "workspace_background_kill" to R.string.tool_display_workspace_background_kill,
    "workspace_export_to_downloads" to R.string.tool_display_workspace_export_to_downloads,

    // Termux & SSH
    "termux_run_command" to R.string.tool_display_termux_run_command,
    "ssh_exec" to R.string.tool_display_ssh_exec,
    "ssh_exec_saved" to R.string.tool_display_ssh_exec_saved,
    "save_ssh_host" to R.string.tool_display_save_ssh_host,
    "list_ssh_hosts" to R.string.tool_display_list_ssh_hosts,
    "delete_ssh_host" to R.string.tool_display_delete_ssh_host,
    "ssh_forget_host_key" to R.string.tool_display_ssh_forget_host_key,
    "ssh_upload" to R.string.tool_display_ssh_upload,
    "ssh_download" to R.string.tool_display_ssh_download,

    // SD Card / Storage / Files / Archive
    "sdcard_list_files" to R.string.tool_display_sdcard_list_files,
    "sdcard_read_file" to R.string.tool_display_sdcard_read_file,
    "sdcard_write_binary_file" to R.string.tool_display_sdcard_write_binary_file,
    "sdcard_delete_file" to R.string.tool_display_sdcard_delete_file,
    "sdcard_move_file" to R.string.tool_display_sdcard_move_file,
    "sdcard_copy_file" to R.string.tool_display_sdcard_copy_file,
    "sdcard_create_directory" to R.string.tool_display_sdcard_create_directory,
    "sdcard_file_info" to R.string.tool_display_sdcard_file_info,
    "sdcard_find_files" to R.string.tool_display_sdcard_find_files,
    "sdcard_batch_copy" to R.string.tool_display_sdcard_batch_copy,
    "sdcard_batch_move" to R.string.tool_display_sdcard_batch_move,
    "sdcard_batch_delete" to R.string.tool_display_sdcard_batch_delete,
    "list_storage_volumes" to R.string.tool_display_list_storage_volumes,
    "list_granted_directories" to R.string.tool_display_list_granted_directories,
    "grant_directory_access" to R.string.tool_display_grant_directory_access,
    "zip_files" to R.string.tool_display_zip_files,
    "unzip_file" to R.string.tool_display_unzip_file,
    "list_zip_contents" to R.string.tool_display_list_zip_contents,
    "write_text_file" to R.string.tool_display_write_text_file,
    "download_file" to R.string.tool_display_download_file,
    "open_file" to R.string.tool_display_open_file,
    "show_image" to R.string.tool_display_show_image,
    "share" to R.string.tool_display_share,
    "share_file" to R.string.tool_display_share_file,

    // Web Fetch / Extract
    "web_fetch" to R.string.tool_display_web_fetch,
    "web_extract" to R.string.tool_display_web_extract,

    // Device Info / Sensors / Media / Hardware / NFC
    "get_battery_status" to R.string.tool_display_get_battery_status,
    "get_audio_info" to R.string.tool_display_get_audio_info,
    "get_telephony_info" to R.string.tool_display_get_telephony_info,
    "get_wifi_info" to R.string.tool_display_get_wifi_info,
    "get_storage_info" to R.string.tool_display_get_storage_info,
    "get_location" to R.string.tool_display_get_location,
    "list_sensors" to R.string.tool_display_list_sensors,
    "read_sensor" to R.string.tool_display_read_sensor,
    "get_brightness" to R.string.tool_display_get_brightness,
    "set_brightness" to R.string.tool_display_set_brightness,
    "get_volume" to R.string.tool_display_get_volume,
    "set_volume" to R.string.tool_display_set_volume,
    "set_torch" to R.string.tool_display_set_torch,
    "vibrate" to R.string.tool_display_vibrate,
    "show_toast" to R.string.tool_display_show_toast,
    "set_wallpaper" to R.string.tool_display_set_wallpaper,
    "take_photo" to R.string.tool_display_take_photo,
    "record_audio" to R.string.tool_display_record_audio,
    "speech_to_text" to R.string.tool_display_speech_to_text,
    "transcribe_audio_file" to R.string.tool_display_transcribe_audio_file,
    "whisper_status" to R.string.tool_display_whisper_status,
    "verify_fingerprint" to R.string.tool_display_verify_fingerprint,
    "play_media" to R.string.tool_display_play_media,
    "stop_media" to R.string.tool_display_stop_media,
    "pause_media" to R.string.tool_display_pause_media,
    "resume_media" to R.string.tool_display_resume_media,
    "seek_media" to R.string.tool_display_seek_media,
    "get_media_status" to R.string.tool_display_get_media_status,
    "scan_media" to R.string.tool_display_scan_media,
    "nfc_read_tag" to R.string.tool_display_nfc_read_tag,
    "nfc_write_tag" to R.string.tool_display_nfc_write_tag,

    // Phone / Contacts / SMS / Notifications
    "list_call_log" to R.string.tool_display_list_call_log,
    "list_contacts" to R.string.tool_display_list_contacts,
    "search_contacts" to R.string.tool_display_search_contacts,
    "list_sms_inbox" to R.string.tool_display_list_sms_inbox,
    "search_sms" to R.string.tool_display_search_sms,
    "send_sms" to R.string.tool_display_send_sms,
    "post_notification" to R.string.tool_display_post_notification,
    "list_recent_notifications" to R.string.tool_display_list_recent_notifications,
    "list_active_notifications" to R.string.tool_display_list_active_notifications,
    "dismiss_notification" to R.string.tool_display_dismiss_notification,
    "notification_action_click" to R.string.tool_display_notification_action_click,
    "notification_reply" to R.string.tool_display_notification_reply,
    "notification_status" to R.string.tool_display_notification_status,

    // Screen / Accessibility / App Launcher / System Intents
    "read_window_tree" to R.string.tool_display_read_window_tree,
    "find_node" to R.string.tool_display_find_node,
    "click_node" to R.string.tool_display_click_node,
    "set_text" to R.string.tool_display_set_text,
    "tap" to R.string.tool_display_tap,
    "long_press" to R.string.tool_display_long_press,
    "swipe" to R.string.tool_display_swipe,
    "scroll" to R.string.tool_display_scroll,
    "global_action" to R.string.tool_display_global_action,
    "wake_screen" to R.string.tool_display_wake_screen,
    "launch_app" to R.string.tool_display_launch_app,
    "list_installed_apps" to R.string.tool_display_list_installed_apps,
    "list_app_activities" to R.string.tool_display_list_app_activities,
    "launch_activity" to R.string.tool_display_launch_activity,
    "open_url" to R.string.tool_display_open_url,
    "create_calendar_event" to R.string.tool_display_create_calendar_event,
    "create_contact" to R.string.tool_display_create_contact,
    "send_email_intent" to R.string.tool_display_send_email_intent,
    "send_sms_intent" to R.string.tool_display_send_sms_intent,
    "open_wifi_settings" to R.string.tool_display_open_wifi_settings,
    "show_location_on_map" to R.string.tool_display_show_location_on_map,

    // Keyboard
    "keyboard_type" to R.string.tool_display_keyboard_type,
    "keyboard_read_field" to R.string.tool_display_keyboard_read_field,
    "keyboard_press_key" to R.string.tool_display_keyboard_press_key,
    "keyboard_delete" to R.string.tool_display_keyboard_delete,
    "keyboard_clear" to R.string.tool_display_keyboard_clear,
    "keyboard_editor_info" to R.string.tool_display_keyboard_editor_info,
    "keyboard_set_cursor" to R.string.tool_display_keyboard_set_cursor,
    "keyboard_select_range" to R.string.tool_display_keyboard_select_range,

    // Cron / Scheduled Jobs
    "schedule_job" to R.string.tool_display_schedule_job,
    "list_jobs" to R.string.tool_display_list_jobs,
    "delete_job" to R.string.tool_display_delete_job,
    "pause_job" to R.string.tool_display_pause_job,
    "resume_job" to R.string.tool_display_resume_job,
    "trigger_job_now" to R.string.tool_display_trigger_job_now,
    "get_job_history" to R.string.tool_display_get_job_history,

    // Telegram Bot
    "telegram_set_token" to R.string.tool_display_telegram_set_token,
    "telegram_status" to R.string.tool_display_telegram_status,
    "telegram_enable" to R.string.tool_display_telegram_enable,
    "telegram_disable" to R.string.tool_display_telegram_disable,
    "telegram_add_whitelist" to R.string.tool_display_telegram_add_whitelist,
    "telegram_remove_whitelist" to R.string.tool_display_telegram_remove_whitelist,
    "telegram_set_default_chat" to R.string.tool_display_telegram_set_default_chat,
    "telegram_set_assistant" to R.string.tool_display_telegram_set_assistant,
    "telegram_send_message" to R.string.tool_display_telegram_send_message,
    "telegram_send_photo" to R.string.tool_display_telegram_send_photo,
    "telegram_send_document" to R.string.tool_display_telegram_send_document,
    "telegram_set_commands" to R.string.tool_display_telegram_set_commands,
    "telegram_get_commands" to R.string.tool_display_telegram_get_commands,
    "telegram_delete_commands" to R.string.tool_display_telegram_delete_commands,

    // MCP Control
    "mcp_list" to R.string.tool_display_mcp_list,
    "mcp_get" to R.string.tool_display_mcp_get,
    "mcp_add" to R.string.tool_display_mcp_add,
    "mcp_update" to R.string.tool_display_mcp_update,
    "mcp_delete" to R.string.tool_display_mcp_delete,
    "mcp_set_enabled" to R.string.tool_display_mcp_set_enabled,
    "mcp_test" to R.string.tool_display_mcp_test,
    "mcp_list_tools" to R.string.tool_display_mcp_list_tools,
    "mcp_set_tool_approval" to R.string.tool_display_mcp_set_tool_approval,

    // Workflows
    "workflow_create" to R.string.tool_display_workflow_create,
    "workflow_list" to R.string.tool_display_workflow_list,
    "workflow_get" to R.string.tool_display_workflow_get,
    "workflow_update" to R.string.tool_display_workflow_update,
    "workflow_delete" to R.string.tool_display_workflow_delete,
    "workflow_set_enabled" to R.string.tool_display_workflow_set_enabled,
    "workflow_run" to R.string.tool_display_workflow_run,

    // Sub-agents
    "subagent_dispatch" to R.string.tool_display_subagent_dispatch,
    "subagent_list" to R.string.tool_display_subagent_list,
    "subagent_get" to R.string.tool_display_subagent_get,
    "subagent_cancel" to R.string.tool_display_subagent_cancel,

    // Keystore Security
    "keystore_generate_key" to R.string.tool_display_keystore_generate_key,
    "keystore_sign" to R.string.tool_display_keystore_sign,
    "keystore_verify" to R.string.tool_display_keystore_verify,
    "keystore_encrypt" to R.string.tool_display_keystore_encrypt,
    "keystore_decrypt" to R.string.tool_display_keystore_decrypt,
    "keystore_delete_key" to R.string.tool_display_keystore_delete_key,
    "keystore_list_keys" to R.string.tool_display_keystore_list_keys,

    // External Automation & Reliability
    "external_automation_status" to R.string.tool_display_external_automation_status,
    "external_automation_set_enabled" to R.string.tool_display_external_automation_set_enabled,
    "external_automation_add_trusted_package" to R.string.tool_display_external_automation_add_trusted_package,
    "external_automation_remove_trusted_package" to R.string.tool_display_external_automation_remove_trusted_package,
    "check_app_updates" to R.string.tool_display_check_app_updates,
    "generate_bug_report" to R.string.tool_display_generate_bug_report,
)

private val MCP_SLUG_REGEX = Regex("^[0-9a-fA-F]{8}_")

private val ACRONYMS = setOf(
    "AI", "API", "DOM", "ID", "JS", "MCP", "NFC", "SD", "SFTP", "SMS", "SSH", "TTS", "UI", "URL",
)

/**
 * Resolves a localized, human-readable display name for [toolName] inside Compose.
 */
@Composable
fun toolDisplayName(toolName: String): String {
    val resId = TOOL_DISPLAY_NAME_RES[toolName]
    if (resId != null) return stringResource(resId)
    return formatDynamicToolName(toolName)
}

/**
 * Resolves a localized, human-readable display name for [toolName] using [context].
 */
fun toolDisplayName(context: Context, toolName: String): String {
    val resId = TOOL_DISPLAY_NAME_RES[toolName]
    if (resId != null) return context.getString(resId)
    return formatDynamicToolName(toolName)
}

private fun formatDynamicToolName(toolName: String): String {
    if (toolName.startsWith("mcp__")) {
        val stripped = toolName.removePrefix("mcp__")
        val sepIdx = stripped.indexOf("__")
        if (sepIdx >= 0) {
            val rawServer = stripped.substring(0, sepIdx)
            val rawTool = stripped.substring(sepIdx + 2)
            val serverName = rawServer.replaceFirst(MCP_SLUG_REGEX, "").trim()
            val toolTitle = humanizeToolName(rawTool)
            return if (serverName.isNotBlank()) "$serverName: $toolTitle" else toolTitle
        }
        return humanizeToolName(stripped)
    }
    return humanizeToolName(toolName)
}

private fun humanizeToolName(name: String): String {
    val words = name.split('_', '-', '.')
        .filter { it.isNotBlank() }
    if (words.isEmpty()) return name
    return words.joinToString(" ") { word ->
        val upper = word.uppercase()
        if (upper in ACRONYMS) upper
        else word.lowercase().replaceFirstChar { it.titlecase() }
    }
}
