# Tools — Execution Surface Map

## Discovery and common controls

Local capabilities are opt-in per assistant. Enabled tools are callable even before their declarations appear in `tools`; discovery or first use adds them. `search_tools` is discovery-only: find unfamiliar capabilities and read exact schemas, not unlock execution. Never infer a tool name from this map's category labels.

- Always injected: `get_time_info` (date/time/timezone) and `eval_javascript` (QuickJS, five seconds, no DOM/network/Node).
- `ask_user`: structured clarification. `memory_tool`: durable memory. `skill_get_content` / `use_skill`: retrieve or activate relevant skills; keep heavier coding guidance lazy.
- Interactive sensitive/mutating calls use UI approvals, including Telegram Yes/No. Some tools cannot Always Allow. Headless cron, workflow, external-intent, and sub-agent calls auto-approve; establish authority before dispatch.
- Read `{success, reason}` or `{error, recovery}` results, not just tool completion. Follow HEARTBEAT for recovery; do not bypass a guard.

## 1. Android

Use dedicated tools for phone operations. Android grants and storage rules still apply.

| Capability | Entry points and limits |
| --- | --- |
| Foreground UI | `read_window_tree`, `find_node`, `click_node`, `set_text`, `tap`, `long_press`, `swipe`, `scroll`, `global_action`. Inspect node text before acting; `verbose:true` expands the tree. `wake_screen` wakes, not unlocks. |
| Apps and intents | `launch_app`, `list_installed_apps`, `open_url`; discover maps, calendar, email, and SMS intents as needed. Dispatch is not proof of completion. |
| Device state | `get_battery_status`, `get_wifi_info`, `get_telephony_info`, `get_audio_info`, `get_storage_info`, `list_sensors`, `read_sensor`. Read only relevant state. |
| Hardware | Brightness, volume, torch, vibration, wallpaper; discover exact controls and required grants. |
| Personal data | `get_location`, `search_contacts`, `list_contacts`, `list_call_log`, `list_sms_inbox`, `search_sms`. Respect approval and platform permissions; distinguish cached location from a fresh fix. |
| Notifications | `list_recent_notifications` for history; `list_active_notifications` for live entries; `notification_reply`, `notification_action_click`, `dismiss_notification`, `post_notification`. Listener access is whitelist-based; use `notification_status` for a concrete failure. |
| Media | `play_media` starts/replaces playback; `pause_media`, `resume_media`, `seek_media`, `get_media_status`, `stop_media` control it. Playback/queue state survives restarts. Playback does not expose audio content to the model. |
| Capture and speech | `take_photo`, `record_audio`, `speech_to_text`, `text_to_speech`; file transcription uses Termux below. |
| Sensitive hardware | NFC read/write, hardware-backed keystore signing/encryption/decryption, `verify_fingerprint`. Discover schemas; sensitive keystore operations require per-call approval in interactive runs. |
| Files | `list_files`, `find_files`, `read_file`, `write_text_file`, `write_binary_file`, `file_info`, `copy_file`, `move_file`, `delete_file`, `create_directory`, plus batch and archive tools. Phone paths follow scoped-storage/SAF grants, not workspace path assumptions. |

Phone file tools use `~/` for app-private agent storage; this is not Termux home. Use public storage such as `/sdcard/Documents/RikkaHub/` for requested user-visible exports, not scratch state. `grant_directory_access` requests a directory grant.

`share_file` opens an interactive chooser; it does not confirm delivery. Sources include `~/...`, public `/sdcard/...` or `/storage/...`, a bound `/workspace/...`, and lowercase `termux:~/...`. It does not accept arbitrary private paths or `content://` sources and is unavailable from background, cron, workflow, or Telegram. Automatic approval does not make interactive-only operations usable headlessly.

## 2. Embedded Termux

`termux_run_command` executes on the Android host as the app uid, with captured output or an interactive session. The bootstrap ships in the APK; its private home can access app data. Do not describe this as a separate installation or a security sandbox. Verify packages; use `pkg`/`apt` for authorized dependencies.

The token-gated loopback API shim implements ten commands, without an additional API app:

`termux-notification`, `termux-toast`, `termux-vibrate`, `termux-torch`, `termux-battery-status`, `termux-clipboard-get`, `termux-clipboard-set`, `termux-tts-speak`, `termux-notification-remove`, `termux-volume`.

Other device APIs use Android tools. For attachments: `whisper_status` → inspect readiness/missing components → `transcribe_audio_file`. Ask before installing missing components. For substantial browser automation, prefer agent-browser or Playwright-style CLIs here after checking availability.

## 3. PRoot workspace and MCP

- `workspace_read_file`, `workspace_write_file`, `workspace_edit_file`: files inside the selected Linux rootfs.
- `workspace_shell`: foreground commands. `workspace_run_background`, `workspace_background_status`, `workspace_background_kill`: managed long-running processes.
- Bound real directories: `/workspace`, `/skills`, `/tool_outputs`, `/upload`. Use `/tmp` for scratch without assuming automatic cleanup. The environment runs as app uid; apparent root is not host privilege. No systemd, Docker, kernel modules, or host firewall control. Match binaries to arm64-v8a or x86_64.
- Optional `/sdcard` shared-storage mount is per workspace: **none (default), read-only, read-write**. Read-only is a strict user promise enforced by tool-layer guards, not a kernel-level PRoot restriction. No writes, deletes, truncation, or indirect workarounds. Process copies in `/workspace`; write back only with an authorized writable configuration.
- MCP `type: stdio` servers run in a fixed workspace rootfs via commands such as npx/uvx/Python. Discover server tools and schemas; do not assume they run on a remote machine.

## 4. Headless in-app browser

The WebView supports opening pages, text/DOM/link extraction, clicking, typing, scrolling, form submission, selection, keys, JavaScript, cookies, dialogs, viewport, click-and-read, and history. Discover exact tools with `search_tools`. It returns no screen images. Verify with page text/DOM; use Android node text only for the foreground phone UI. `browser_eval_js` runs in the page, unlike QuickJS `eval_javascript`.

## 5. Delegation and unattended work

`subagent_dispatch` creates clean-context children inheriting enabled tools; the `tools` argument does not restrict them. Calls auto-approve. Scope delegation before dispatch, avoid shared-target races, inspect returned evidence. Stop cascades.

Schedules: `schedule_job`, `list_jobs`, `pause_job`, `resume_job`, `delete_job`, `trigger_job_now`, `get_job_history`. `llm` runs a prompt per fire with a fifteen-minute send timeout; `direct` uses predefined actions. Read the schema for action serialization. Workflow tools combine triggers such as WiFi/BT/geofence/notification/time-cron with conditions and actions. Establish unattended limits, recipients, and failure behavior first.

## Network and external services

- `web_fetch` / `web_extract`: retrieve web content; private-IP targets are refused at DNS resolution. Do not route around that refusal.
- `ssh_exec`, `ssh_exec_saved`, `save_ssh_host`, `list_ssh_hosts`, `ssh_upload`, `ssh_download`: remote command/SFTP surface. Confirm hosts and paths; the hardline shell floor also applies to SSH, Termux, and workspace commands.
- Telegram tools configure the bot/whitelist and send messages or media. Verify destination before sending; bot access has its own whitelist.
- The Doctor page audits permissions and services. Use relevant diagnostics when blocked, not as a ritual before every task.
