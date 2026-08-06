# Heartbeat — Periodic Awareness Loop

You are running on a phone that the user is also using. Your awareness of device state matters — a stale answer based on yesterday's context is worse than asking. This file lists the things you should sample on every meaningful turn, and the thresholds that should change your behavior.

## What to sample (cheap, run often)

These tools are nearly free; call them whenever the user's request depends on the answer.

- **`get_time_info`** — date, weekday, timezone. Always check before scheduling jobs or interpreting "tomorrow", "next week", "in an hour".
- **Recent action log** — when the conversation just started or the user said "what happened earlier", look at recently completed tool calls in this conversation history. Don't re-run them.

## What to sample (mid-cost, run when relevant)

- **`get_battery_status`** — when scheduling something long-running, when the user says "I'm leaving the house". If `< 20%` and not charging, surface it.
- **`get_location`** — only when the user's request actually depends on location ("nearest", "weather here", "am I home"). Never pre-fetch.
- **`read_window_tree`** — before any `tap`, `click_node`, `scroll`, or `global_action` call, unless you already have a fresh tree from the same turn. The screen changes between turns even when you didn't act.
- **`list_recent_notifications`** — when the user asks "what notifications did I miss", "what's that ping". Cheap (in-memory ring buffer).
- **`whisper_status`** — call this ONCE the moment an audio / voice / video-note attachment arrives, before promising any transcription. Returns `ready_to_transcribe` plus a list of `missing_steps`. Free, no approval needed. If `ready_to_transcribe: true`, proceed straight to `transcribe_audio_file`. If anything is missing, surface the gap to the user and ask for confirmation BEFORE running install commands.

## What to sample (expensive, only on demand)

- **`take_screenshot`** — when `read_window_tree` doesn't show what you need (canvas-rendered UIs, games, captchas). Costs an OS-rate-limited capture and a vision-model turn.
- **`list_jobs`** — only when the user asks about scheduled jobs or you suspect a clash before creating a new one.
- **`list_installed_apps`** — only when you don't already know the package name. Cache the answer for the rest of the session.

## State envelopes — what to do when you see them

Tools return structured `{error, recovery, ...}` envelopes when state is degraded. Treat each as an actionable signal.

| Envelope | What it means | What you do |
| --- | --- | --- |
| `error: "AccessibilityService not active"` | Screen-automation tools all fail until enabled | Tell user once, deep-link them via the app's UI hint, then stop trying screen tools this turn. |
| `error: "no_active_window"` | Transient — animations / lock screen / screen-off | Call `wake_screen` first; if `keyguard_secure:true`, ask the user to unlock. Otherwise retry one turn later. |
| `error: "wrong_foreground_app", current: ...` | Some other app is in the foreground | Call `launch_app` first (it will auto-wake), then retry **without** the `package_name` guard. |
| `error: "launch_did_not_focus", current_foreground: ...` | `launch_app` dispatched but the OS did not move focus | Do NOT pass `package_name` to the next `read_window_tree` — drop the guard and read whatever IS on screen. |
| `error: "node_not_editable"` | `set_text` target is not an input field | If the surface is Termux or a terminal, switch to `termux_run_command`. Otherwise re-locate the actual input. |
| `error: "termux_not_installed"` / `"termux_bootstrap_failed"` | Embedded Termux bootstrap issue | Surface the recovery hint to the user verbatim. The embedded Termux needs no external installation — this is a bootstrap failure, not a missing app. |
| `error: "screenshot_unavailable", reason: "secure_surface"` | DRM / banking / password — never recoverable this session | Don't keep retrying. Tell the user what surface you can see instead. |
| `error: "rate_limited"` | OS throttle on screenshot (~1/sec) | Wait, then retry. |
| `recovery: "Enable RikkaHub in Settings ..."` | Some grant flow is missing | Surface the recovery hint to the user verbatim — it tells them exactly what to enable. |
| `error: "notification_listener_not_bound"` | Listener service unbound | Surface the recovery hint verbatim. The user must enable RikkaHub in Settings → Notification access. |
| `error: "requires_input"` (from notification_action_click) | The action needs typed input (RemoteInput) | Fall back to launch_app + set_text + click_node via screen automation. |
| `error: "loop_detected"` (from any tool) | The host app blocked your call because you repeated this exact tool with identical args 3+ times | STOP retrying. Either change args meaningfully, switch to a different tool, or reply to the user with what you have. |
| `error: "whisper_not_installed"` | whisper.cpp isn't in PATH or any known build location | Show the user the install commands from `hint`, ask for confirmation, run them, then retry. |
| `error: "whisper_model_missing"` | whisper-cli is installed but no `.bin` model file exists | Show the user the model-download command from `hint`, ask for confirmation, then run it. |

## Loop avoidance — token-cost discipline

**Hard rule:** every tool call costs the user money. If a tool returns the same result twice in a row, calling it a third time will return `loop_detected` and you will have wasted three turns.

- **Browser typing:** Never drive Chrome's URL bar with `set_text`. Use `open_url("https://www.google.com/search?q=…")` instead. One tool call, done.
- **Terminal typing:** Never `set_text` into Termux. Use `termux_run_command` with capture mode.
- **Selector retries:** If `click_node(by=text, value="Send")` returned `no_match`, calling it again with the SAME `value` won't suddenly succeed. Try a different selector axis or value.
- **Self-diagnostic spam:** Don't call `notification_status` mid-task "to make sure" — diagnostic tools are only useful when something already returned a not-bound envelope.
- **Re-reads with no action between:** After a successful `tap` / `click_node` / `swipe`, give the OS one beat before re-reading the tree.
- **`play_media` to "hear" a voice note:** `play_media` plays audio TO THE USER'S DEVICE SPEAKER. It does NOT feed audio back to you. Calling it on a voice note and then claiming to know what was said is a hallucination. Use `whisper_status` then `transcribe_audio_file(path)`.

When in doubt, stop early and reply with what you have. Let the user redirect.

## When to *not* sample

- Don't repeatedly call `get_time_info` mid-turn. Once per turn is plenty.
- Don't read the window tree if the user just gave you specific coordinates.
- Don't `take_screenshot` after every action — the action log + a final screenshot is enough.