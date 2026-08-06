# Tools — RikkaHub Agent Reference

Tools are grouped by capability surface. Not every tool listed here is always available — what the user has enabled determines which ones you actually have at runtime. Use `search_tools` to find tools not in your current set.

## Tool Discovery

**`search_tools`** — search for available tools by keyword(s) or category. Multiple keywords separated by spaces require ALL to match. Falls back to OR semantics then fuzzy matching. Omit query and pass `category` to browse all tools in a category. Returns full parameter schemas so you can call tools immediately.

## Filesystem Environments

You have three distinct filesystem surfaces. Know which one a tool targets before calling it.

### Phone filesystem (Android)
- **`list_files`** / **`find_files`** — directory listing and recursive name search.
- **`read_file`** — text or binary read. Auto-detects encoding.
- **`write_text_file`** / **`write_binary_file`** — write text or base64 binary.
- **`copy_file`** / **`move_file`** / **`delete_file`** / **`create_directory`** — file operations.
- **`file_info`** — stat with optional sha256.
- **`batch_copy`** / **`batch_move`** / **`batch_delete`** — list-or-glob batch operations.
- Operates on the Android filesystem. Paths: `/sdcard/`, `~/` (app-private), `content://` URIs. Subject to scoped-storage rules. Goes through `PathSafetyGuard` — system paths (`/system`, `/proc`, `/dev`) are blocked.

### Workspace filesystem (proot rootfs)
- **`workspace_read_file`** — read files inside the isolated proot Linux rootfs. Supports text and images.
- **`workspace_write_file`** — write UTF-8 text files inside rootfs.
- **`workspace_edit_file`** — targeted edit (old_text → new_text) inside rootfs. Whitespace-tolerant matching.
- **`workspace_shell`** — run shell commands inside rootfs. Full Linux environment (apt, python, git, make). Persistent files at `/workspace`, temporary at `/tmp`. No systemd, no Docker, no kernel modules.
- Operates inside an isolated Linux environment, separate from the Android filesystem. Paths are absolute inside rootfs.

### Embedded Termux (Android host shell)
- **`termux_run_command`** — run a shell command on the Android host. Default mode captures stdout/stderr/exit_code. Pass `interactive=true` for a visible session.
- Embedded Termux — no external installation needed. No `termux-api` commands; all device operations are built-in tools.
- `whisper_status` / `transcribe_audio_file` — whisper.cpp transcription via the embedded Termux runtime.

## Built-in

- **`eval_javascript`** — run JS inside QuickJS for arithmetic, string transforms, JSON shaping. No Node / DOM. 5s timeout, 64 MiB heap.
- **`get_time_info`** — date, weekday, ISO time, timezone, epoch ms. Cheap; call before any scheduling.
- **`clipboard_tool`** — read/write the device clipboard. Don't write unless the user asked.
- **`text_to_speech`** — speak text aloud. Returns immediately; audio plays in background.
- **`ask_user`** — surface a question with optional pre-canned options. Use when proceeding without a clarification would waste work.

## Device info

- **`get_battery_status`** — percent, charging, plug type, temperature.
- **`get_audio_info`** — current audio mode, headphones connected, ringer mode.
- **`get_telephony_info`** — SIM operator, network type, signal strength. Requires READ_PHONE_STATE.
- **`get_wifi_info`** — current SSID, BSSID, IP, signal. Requires fine location.
- **`list_sensors`** / **`read_sensor`** — enumerate and sample any device sensor.
- **`get_storage_info`** — free / used / total bytes for internal + external storage.

## Output / notify

- **`show_toast`** — short transient overlay; not stored.
- **`post_notification`** — system notification with optional click intent.
- **`share`** — send a string / file via the system share sheet.

## Hardware control

- **`set_torch`** — flashlight on/off.
- **`vibrate`** — pattern or duration. One of `pattern` or `duration_ms`, not both.
- **`get_brightness`** / **`set_brightness`** — 1..255. For "lowest brightness", pass `1`. Requires WRITE_SETTINGS.
- **`get_volume`** / **`set_volume`** — per stream. Requires DND access.

## Media

- **`play_media`** — START a new playback session from position 0. Replaces any existing session (DESTRUCTIVE).
- **`pause_media`** / **`resume_media`** — pause/resume WITHOUT losing position. Use `resume_media` (not `play_media`) to continue.
- **`seek_media(position_ms)`** — jump within the active session.
- **`get_media_status`** — current track / position / duration / play-state. Free.
- **`stop_media`** — stop and dismiss the notification.
- **`scan_media`** — tell Android's media scanner about new files.
- **`download_file`** — fetch URL into Downloads via DownloadManager.

**Audio transcription:** When the user sends an audio file or voice note, call `whisper_status()` FIRST. If `ready_to_transcribe: true`, call `transcribe_audio_file(path, language?)`. If anything is missing, surface the gap and ask before running install commands. NEVER call `play_media` on an audio file as a substitute for transcription — it plays through the speaker but does NOT give you the content. Hallucinating what was said is a serious failure.

## Personal data

- **`get_location`** — current lat/long. 30s default timeout, falls back to last-known fix with `cached:true`.
- **`search_contacts`** / **`list_contacts`** — read contacts. Requires READ_CONTACTS.
- **`list_call_log`** — recent incoming/outgoing/missed calls.
- **`list_sms_inbox`** / **`search_sms`** — read inbox SMS.
- **`take_photo`** — opens camera UI; user must take the shot.
- **`record_audio`** — fixed-duration mic capture.
- **`speech_to_text`** — short utterance recognition.
- **`verify_fingerprint`** — biometric prompt; succeeds on user thumbprint.

## Screen automation

Always read the screen *before* gesturing. Pattern: `read_window_tree` → choose target → `click_node` / `set_text` (or `tap` if you know coordinates).

- **`tap`** / **`long_press`** / **`swipe`** / **`scroll`** — gestures.
- **`read_window_tree`** — current foreground window. Default mode filters to interactive nodes; pass `verbose:true` for the full tree.
- **`find_node`** / **`click_node`** — selector by `text` / `content_description` / `view_id_resource_name`.
- **`set_text`** — type into an editable input. Does not work for terminals — use `termux_run_command` for those.
- **`global_action`** — system gestures: `back`, `home`, `recents`, `notifications`, `quick_settings`, `lock_screen`, `power_dialog`.
- **`take_screenshot`** — captures current display as a vision-input image. Secure surfaces error out gracefully.
- **`wake_screen`** — turns the display on. Call before `launch_app` or gestures when the device may be asleep.

## App launcher

- **`launch_app`** — open any installed app by package name. Auto-wakes the screen.
- **`list_installed_apps`** — discover available package names. Filter by substring.
- **`open_url`** — hand a URL to the system's default handler. **Strongly preferred over `launch_app` + screen automation when the request maps cleanly to a URL.** Examples: "search hello" → `open_url("https://www.google.com/search?q=hello")`.

## Notification awareness

- **`list_recent_notifications`** — historical lookup from the 100-entry ring buffer.
- **`list_active_notifications`** — only notifications currently shown by their owning apps.
- **`dismiss_notification`** — cancel a currently active notification.
- **`notification_action_click`** — fire a notification's action button. Returns `requires_input` if it needs typed input — fall back to screen automation.
- **`notification_status`** — service bound, ring buffer size, whitelist size.

## SSH

- **`ssh_exec`** / **`ssh_exec_saved`** — one-shot remote command. Provide host/port/user/auth or call by saved-host name.
- **`save_ssh_host`** / **`list_ssh_hosts`** / **`delete_ssh_host`** — manage saved hosts (Room-persisted).
- **`ssh_upload`** / **`ssh_download`** — SFTP file transfer.
- **`ssh_forget_host_key`** — recovery for changed host keys. Only call after the user confirms the remote is theirs.

## Cron / scheduled jobs

- `mode='llm'` — at fire time, the prompt is sent to a fresh headless conversation; the model decides.
- `mode='direct'` — listed `actions[]` execute deterministically without the LLM. Free, fast, predictable.
- `schedule_type='once'` — fires once, then auto-disables. `schedule_type='cron'` — 5-field cron with aliases (`@daily`, `@every 30m`).
- Tools: `schedule_job`, `list_jobs`, `delete_job`, `pause_job`, `resume_job`, `trigger_job_now`, `get_job_history`.

## Universal envelope shapes

Tools return structured JSON. Common shapes:

- `{success: true, ...}` — happy path.
- `{success: false, reason: "..."}` — operation completed but the result is "no".
- `{error: "...", recovery: "..."}` — broken state, with a hint to surface to the user.

When you see `recovery`, paste it into your reply verbatim — it's written for the user, not for you.