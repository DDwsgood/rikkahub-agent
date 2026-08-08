package me.rerere.rikkahub.browser

import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.lang.ref.WeakReference

/**
 * Singleton bridge between the LLM browser tools and the live WebView.
 * Mirrors the [me.rerere.rikkahub.service.RikkaAccessibilityService.instance] pattern: the
 * Activity (or headless session host) publishes itself in on bind and clears on unbind.
 *
 * The controller uses a [Mode] sealed class so it can serve two use cases without forking
 * the tool dispatcher:
 *  - [Mode.Foreground]: the on-screen [BrowserActivity] hosts the WebView. Used for
 *    user-initiated browser sessions (Settings → Open Browser, skill webview cards).
 *  - [Mode.Headless]: a [HeadlessBrowserSession] hosts the WebView in the application
 *    process, parented to an unattached layout. Used by all LLM-driven browser tools.
 *
 * The legacy [bind]/[unbind] entry points still work — they delegate to the foreground
 * bind so existing call sites in [BrowserActivity] compile unchanged. The
 * [WeakReference] reaches into [Mode.Foreground.activityRef]; [Mode.Headless] holds a
 * hard reference (the headless session is the WebView's only owner — letting it GC
 * mid-task would lose the session).
 */
object BrowserController {

    private const val MAX_RECENT_ACTIONS = 20

    /**
     * Hard cap on a single AI-driven task to bound runaway loops. User-configurable via
     * Settings → Browser (GitHub issue #4) — [BrowserPreferences] writes the persisted value
     * here at app start and on every edit. Defaults to 5 min until the first read settles.
     * Always holds a value clamped into [BrowserToolDefaults]'s supported range.
     */
    @Volatile
    var singleTaskTimeoutMs: Long = BrowserToolDefaults.DEFAULT_SINGLE_TASK_TIMEOUT_MS

    /**
     * Per-tool timeout — the `withTimeoutOrNull` budget every browser tool wraps its dispatch
     * in. User-configurable via Settings → Browser (GitHub issue #4); kept in sync by
     * [BrowserPreferences]. Defaults to 30 s until the first read settles. Always clamped.
     */
    @Volatile
    var perToolTimeoutMs: Long = BrowserToolDefaults.DEFAULT_PER_TOOL_TIMEOUT_MS

    /**
     * Global browser execution mode — controls whether browser tools run in the foreground
     * (visible Activity) or headless (background WebView). User-configurable via Settings →
     * Browser; kept in sync by [BrowserPreferences]. Defaults to ALWAYS_BACKGROUND.
     *
     * When set to ALWAYS_FOREGROUND, browser tools launch the browser Activity on screen.
     */
    @Volatile
    var backgroundMode: BrowserBackgroundMode = BrowserBackgroundMode.ALWAYS_BACKGROUND

    private const val TAG = "BrowserController"

    /**
     * Execution mode for the controller. Exactly one is active at a time; the [Mode.Idle]
     * case lets `isBound()` return false uniformly without a null check.
     */
    sealed class Mode {
        data object Idle : Mode()

        /** A visible [BrowserActivity] hosts the WebView. */
        data class Foreground(val activityRef: WeakReference<WebView>) : Mode()

        /**
         * A headless WebView lives in the application process, parented to an unattached
         * layout owned by [HeadlessBrowserSession].
         */
        data class Headless(val callerConvId: String, val webView: WebView) : Mode()
    }

    @Volatile
    private var mode: Mode = Mode.Idle

    /**
     * Serialises every read-modify-write of [mode]. The bind/unbind entry points mutate
     * shared state from multiple coroutines (Telegram polling loop, cron worker, sub-agent),
     * so a plain `@Volatile` on [mode] is not enough to make "check the current binding,
     * then replace it" atomic. Without it, two concurrent headless conversations can both
     * pass [bindHeadless]'s clobber check and the second silently overwrites the first.
     */
    private val bindLock = Any()

    /**
     * Pass 2 publishes a fresh deferred each time the binding is cleared, so a tool that
     * fires `browser_open` can `awaitBind` after starting the Activity. The Volatile lets
     * the awaiting coroutine see the new instance the moment unbind() swaps it in.
     */
    @Volatile
    private var bindDeferred: CompletableDeferred<Unit> = CompletableDeferred()

    /** Set on the first browser_open of a task. null = no task in flight. */
    @Volatile
    var currentTaskStartedAt: Long? = null

    /**
     * Pass 2: the in-flight tool dispatch coroutine, stored so the user-facing "Stop AI"
     * UI button can cancel a run mid-action (the visible Activity calls [stopCurrentTask]
     * which cancels this Job). Tool factories register their dispatch into here on entry
     * and clear on completion.
     */
    @Volatile
    var pendingTaskJob: Job? = null

    private val _recentActions = MutableStateFlow<List<String>>(emptyList())

    /** Compose-friendly observable of the last [MAX_RECENT_ACTIONS] AI actions, newest first. */
    fun recentActionsFlow(): StateFlow<List<String>> = _recentActions.asStateFlow()

    /** Returns the current execution mode. */
    fun currentMode(): Mode = mode

    // --- Foreground bindings ----------------------------------------------------------

    /**
     * Activity calls this in onCreate. Replaces any prior binding (only one BrowserActivity
     * at a time). Also routes around an existing [Mode.Headless] — installing a
     * foreground binding while a headless session is live is undefined; the headless session
     * MUST `unbindHeadless` before the foreground Activity binds.
     */
    fun bindForeground(webView: WebView) {
        mode = Mode.Foreground(WeakReference(webView))
        if (!bindDeferred.isCompleted) {
            bindDeferred.complete(Unit)
        }
        // Sweep stale cache files from any prior session (including ones killed by
        // process-stop). No-op when there are no cache subdirs to sweep.
        runCatching { BrowserCacheSweeper.sweep(webView.context.applicationContext) }
    }

    /** Activity calls this in onDestroy. Only clears if the live ref still points at the same WebView. */
    fun unbindForeground(webView: WebView) {
        val current = (mode as? Mode.Foreground)?.activityRef?.get()
        if (current === webView || current == null) {
            mode = Mode.Idle
            // Reset task timer + action log when the visible Activity is torn down. Headless
            // mode has its own teardown via [unbindHeadless]; this branch is foreground-only.
            currentTaskStartedAt = null
            _recentActions.value = emptyList()
            // Swap in a fresh deferred so the NEXT browser_open's awaitBind blocks correctly
            // until the next bind() — without this, a stale "completed" deferred from the
            // prior session would let awaitBind return immediately on a dead WebView.
            bindDeferred = CompletableDeferred()
        }
    }

    /** Pass 1/2 API surface — kept as a thin wrapper over [bindForeground] so call sites compile. */
    fun bind(webView: WebView) = bindForeground(webView)

    /** Pass 1/2 API surface — kept as a thin wrapper over [unbindForeground]. */
    fun unbind(webView: WebView) = unbindForeground(webView)

    // --- Headless bindings ------------------------------------------------------------

    /**
     * Bind a headless WebView for the conversation identified by [callerConvId].
     * The WebView is held by hard reference because the [HeadlessBrowserSession] is its
     * only owner — losing it to GC mid-task would silently lose the session.
     *
     * Sets the Mode to [Mode.Headless] and completes the bind deferred, mirroring the
     * foreground path so [awaitBind] can be reused if needed.
     *
     * Returns false WITHOUT mutating state if a DIFFERENT conversation already holds a live
     * headless (or foreground) binding — the controller's [mode] is a single global slot, so
     * letting a second concurrent conversation overwrite it would clobber the first's
     * session. The caller (browser_open) surfaces a clean
     * [bindBusyEnvelope] in that case. Re-binding the SAME conv id is always allowed (the
     * normal per-task reuse where browser_open fires again on a session that's already bound).
     */
    fun bindHeadless(callerConvId: String, webView: WebView): Boolean {
        synchronized(bindLock) {
            when (val current = mode) {
                is Mode.Headless ->
                    // A DIFFERENT conversation may take over the single controller slot only
                    // while the current owner's task is genuinely in flight — i.e. browser_open
                    // armed the task window and browser_done hasn't cleared it (and it hasn't
                    // expired). During that window a second conversation would clobber the
                    // owner's screenshot routing, so reject it (bindBusyEnvelope). Once the owner
                    // finishes (window cleared), its window expires (forgetful model), or its
                    // idle session is swept, the slot is free to hand off — without this the
                    // binding would pin to the finished conversation until its /new and block
                    // every other conversation forever. Same-conv re-bind always refreshes the ref.
                    if (current.callerConvId != callerConvId &&
                        currentTaskStartedAt != null && isWithinTaskWindow()
                    ) return false
                is Mode.Foreground ->
                    // The visible Activity is using the controller; don't steal it from under
                    // the user. (bindForeground itself routes around an existing headless bind
                    // per its own contract.) Reject only if the foreground WebView is still live.
                    if (current.activityRef.get() != null) return false
                Mode.Idle -> Unit
            }
            mode = Mode.Headless(callerConvId, webView)
        }
        if (!bindDeferred.isCompleted) {
            bindDeferred.complete(Unit)
        }
        // Sweep stale cache files — same reasoning as bindForeground.
        runCatching { BrowserCacheSweeper.sweep(webView.context.applicationContext) }
        return true
    }

    /**
     * Non-mutating peek: would [bindHeadless] for [callerConvId] currently succeed?
     * Mirrors [bindHeadless]'s reject rule EXACTLY (a different live headless owner whose
     * task is genuinely in flight, or a live foreground binding) so browser_open can avoid
     * allocating a ~30 MB WebView session it would only have to discard on rejection.
     *
     * This is advisory: [bindHeadless] stays authoritative and re-checks under [bindLock],
     * so a race between the peek and the bind can only cost the (now closed) allocation, not
     * a wrong binding. Reads [mode] / [currentTaskStartedAt] under the lock for a coherent
     * snapshot, matching how the real bind decides.
     */
    fun canBindHeadless(callerConvId: String): Boolean {
        synchronized(bindLock) {
            return when (val current = mode) {
                is Mode.Headless ->
                    !(current.callerConvId != callerConvId &&
                        currentTaskStartedAt != null && isWithinTaskWindow())
                is Mode.Foreground -> current.activityRef.get() == null
                Mode.Idle -> true
            }
        }
    }

    /**
     * Tear down the headless binding for [callerConvId]. Idempotent: if the current mode
     * isn't headless or doesn't match the conv id, this is a no-op (someone else already
     * tore it down or we're racing a foreground bind).
     */
    fun unbindHeadless(callerConvId: String) {
        synchronized(bindLock) {
            val m = mode
            if (m is Mode.Headless && m.callerConvId == callerConvId) {
                mode = Mode.Idle
                currentTaskStartedAt = null
                _recentActions.value = emptyList()
                bindDeferred = CompletableDeferred()
            }
        }
    }

    /**
     * Reset [mode] to [Mode.Idle] iff it is currently [Mode.Headless] for [callerConvId].
     * Called by [HeadlessBrowserSessionPool]'s idle sweep when it evicts (and destroys) a
     * session: without this the controller's [mode] keeps pointing at the now-destroyed
     * WebView, so the next tool call would dispatch onto a dead view (evaluateJavascript
     * throws) instead of cleanly returning `browser_session_lost`.
     *
     * Mirrors [unbindHeadless]'s teardown (task timer, action log, fresh bind deferred)
     * but ONLY when this conv still owns the slot — a different live owner or a
     * foreground binding is left untouched. Guarded by [bindLock] so it composes safely with
     * concurrent bind/unbind; the pool calls it while holding its OWN (separate) pool lock, and
     * this method never reaches back into the pool, so the two locks never nest in conflicting
     * order.
     */
    fun clearModeIfHeadless(callerConvId: String) {
        synchronized(bindLock) {
            val m = mode
            if (m is Mode.Headless && m.callerConvId == callerConvId) {
                mode = Mode.Idle
                currentTaskStartedAt = null
                _recentActions.value = emptyList()
                bindDeferred = CompletableDeferred()
            }
        }
    }

    // --- Status reads -----------------------------------------------------------------

    /** True iff a WebView is currently bound (foreground or headless) and not GC'd. */
    fun isBound(): Boolean = activeWebView() != null

    /** Cheap read for tools / UI status — null when no WebView is bound. */
    fun currentUrl(): String? = activeWebView()?.url

    /** Cheap read for tools / UI status — null when no WebView is bound. */
    fun currentTitle(): String? = activeWebView()?.title

    /**
     * Append a one-line description of an AI-driven action to the recent-actions log.
     * The BrowserAiStripe observes the resulting flow and renders the trail.
     */
    fun appendAction(label: String) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        val current = _recentActions.value
        val next = (listOf(trimmed) + current).take(MAX_RECENT_ACTIONS)
        _recentActions.value = next
    }

    /**
     * Cancel the in-flight tool dispatch (if any) and clear the single-task timer. Wired
     * to the Activity's "Stop AI" kebab item; the cancelled coroutine surfaces as a normal
     * CancellationException inside the tool's withTimeoutOrNull and the LLM gets a clean
     * envelope instead of a stack trace.
     */
    fun stopCurrentTask() {
        pendingTaskJob?.cancel()
        pendingTaskJob = null
        currentTaskStartedAt = null
        appendAction("AI task stopped by user")
    }

    /**
     * Start (or refresh) the single-task window. browser_open calls this on every successful
     * navigation; once a task starts, every browser_* call after the window expires gets
     * [taskTimeoutEnvelope] until browser_done fires (which clears the timer). The window
     * length is [singleTaskTimeoutMs] — user-configurable in Settings → Browser.
     */
    fun startTaskWindow() {
        currentTaskStartedAt = System.currentTimeMillis()
    }

    /** browser_done clears the task window (and stops the in-flight job log). */
    fun clearTaskWindow() {
        currentTaskStartedAt = null
    }

    /**
     * Returns true if no task is in flight OR the in-flight task hasn't yet exhausted its
     * configured single-task budget ([singleTaskTimeoutMs]). Tools call this BEFORE doing any
     * work so a runaway loop costs at most one envelope per call after the cap.
     */
    fun isWithinTaskWindow(): Boolean {
        val started = currentTaskStartedAt ?: return true
        return System.currentTimeMillis() - started < singleTaskTimeoutMs
    }

    /**
     * Suspend until a bind happens or [timeoutMs] elapses. browser_open uses this after
     * firing the BrowserActivity launch intent — the Activity's onCreate publishes its
     * WebView, the deferred completes, and the tool can then call loadUrl. 5 s is the
     * spec-mandated cap; on a slow device the user's click on Settings → Open Browser
     * also takes about that long.
     */
    suspend fun awaitBind(timeoutMs: Long = 5_000L): Boolean {
        if (isBound()) return true
        return withTimeoutOrNull(timeoutMs) { bindDeferred.await(); true } ?: false
    }

    /**
     * Internal accessor used by [BrowserControllerHandle]. Returns the live WebView or null
     * (the WeakReference has been GC'd, no Activity, or no headless session).
     */
    internal fun activeWebView(): WebView? = when (val m = mode) {
        is Mode.Foreground -> m.activityRef.get()
        is Mode.Headless -> m.webView
        Mode.Idle -> null
    }

    fun notOpenEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_not_open")
        put("recovery", "Call browser_open with a URL to launch the browser before invoking this tool.")
    }

    /** Returned when the 5-min single-task window has elapsed without a browser_done call. */
    fun taskTimeoutEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_task_timeout")
        put("recovery", "Call browser_done with a summary; the per-task 5-minute cap has been reached.")
    }

    /**
     * Returned when a headless session was torn down mid-task (the calling FGS died) and a
     * subsequent tool call lands on an Idle controller. Distinct from `browser_not_open`
     * so the LLM can tell the user "your remote session ended" rather than retry forever.
     */
    fun sessionLostEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_session_lost")
        put("recovery", "The headless browser session ended (the calling foreground service was killed). Ask the user to retry.")
    }

    /**
     * Returned when a headless browser_open lands while a DIFFERENT conversation already
     * holds the (single, global) controller binding. The controller can drive one WebView at
     * a time; binding a second concurrently would clobber the first conversation's session,
     * so the second is rejected here instead.
     */
    fun bindBusyEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_busy")
        put("recovery", "Another conversation is currently driving the browser. Wait for it to finish (it calls browser_done), then retry browser_open.")
    }
}

/**
 * Handle / dispatch helper for the browser tools. Mirrors
 * [me.rerere.rikkahub.data.ai.tools.local.AccessibilityServiceHandle.withService] in
 * shape: tools wrap their entire execute body in [withController], get the WebView if
 * one is bound, and uniformly fall back to the [BrowserController.notOpenEnvelope] error
 * shape if not.
 *
 * Pass 2 also exposes [WithControllerScope] so the per-tool helpers in BrowserTools can
 * round-trip JS via `webView.evaluateJavascript` on the main thread without each tool
 * re-implementing the bridge.
 */
object BrowserControllerHandle {

    /**
     * Scope passed into [withController]'s block. Carries the controller (for
     * appendAction / startTaskWindow) and the live WebView. Helpers that need the main
     * thread should use [me.rerere.rikkahub.browser.evaluateJavascriptAsync] which posts
     * onto the WebView's looper directly.
     */
    data class WithControllerScope(
        val controller: BrowserController,
        val webView: WebView,
    )

    /**
     * Runs [block] with a [WithControllerScope] if a WebView is bound; otherwise returns
     * the standard browser_not_open envelope. The 5-minute single-task cap is enforced up
     * front — browser_open re-arms it via [BrowserController.startTaskWindow] and
     * browser_done clears it via [BrowserController.clearTaskWindow] (both routed through
     * tool factories, so they remain reachable inside the cap).
     *
     * The block runs on [Dispatchers.Main]. WebView APIs are main-thread-only and will
     * throw `WebViewMethodCalledOnWrongThreadViolation` from any other dispatcher, so
     * baking the bridge in here means every tool author gets safe direct access to
     * `webView.url`, `webView.title`, `webView.canGoBack()`, etc. without re-wrapping.
     * For network or heavy CPU work that must run off-main, suspend out of [block] via
     * `withContext(Dispatchers.IO)` explicitly. The async JS helpers
     * ([evaluateJavascriptAsync], [awaitReadyState]) post via the WebView's looper and
     * suspend on a `CompletableDeferred`, so they stay non-blocking even from main.
     */
    suspend fun withController(
        block: suspend WithControllerScope.() -> JsonObject,
    ): JsonObject {
        val wv = BrowserController.activeWebView() ?: return BrowserController.notOpenEnvelope()
        if (!BrowserController.isWithinTaskWindow()) {
            return BrowserController.taskTimeoutEnvelope()
        }
        return withContext(Dispatchers.Main) {
            WithControllerScope(BrowserController, wv).block()
        }
    }
}

/**
 * Run [code] on the WebView's required main thread and return the JSON-encoded result
 * string the page produced (or "null" on any error / timeout). `evaluateJavascript`
 * itself is documented as main-thread only and routes its result callback onto the UI
 * thread; the [withContext] gets us there and the [CompletableDeferred] bridges the
 * callback back into a coroutine.
 *
 * **Why no `webView.post { ... }` wrapper.** The earlier version posted into the
 * WebView's run-queue. For an unattached WebView (the headless `HeadlessBrowserSession`
 * parent LinearLayout never reaches a Window), `View.post` queues the runnable until
 * attach — which never happens, so `evaluateJavascript` was never called and the
 * deferred timed out at 8 s on every call. Calling `evaluateJavascript` directly from
 * the main-thread context fixes both attached and unattached cases.
 *
 * The result is the raw string evaluateJavascript returns: a valid JSON value (number,
 * "string", true, null, [...], {...}). Callers parse it themselves, since JSON shape
 * varies per tool.
 */
suspend fun WebView.evaluateJavascriptAsync(code: String, timeoutMs: Long = 8_000L): String? {
    val deferred = CompletableDeferred<String?>()
    withContext(Dispatchers.Main) {
        try {
            evaluateJavascript(code) { result -> deferred.complete(result) }
        } catch (e: Exception) {
            // evaluateJavascript can throw if the WebView has been destroyed underneath
            // us (Activity finished, headless session stopped). Log so the cause is
            // visible — the caller still gets a clean null and falls back. Narrowed from
            // Throwable so JVM Errors (OOM etc.) still propagate.
            android.util.Log.w("BrowserController", "evaluateJavascriptAsync: evaluateJavascript threw", e)
            deferred.complete(null)
        }
    }
    return withTimeoutOrNull(timeoutMs) { deferred.await() }
}

/**
 * Wait for `document.readyState === "complete"` for up to [timeoutMs] ms. Used after
 * state-changing tools (click, type, submit) so the next read tool sees the post-action
 * page rather than a half-rendered intermediate state. Polls every 200 ms, exits early
 * on first complete reading.
 */
suspend fun WebView.awaitReadyState(timeoutMs: Long = 8_000L): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val raw = evaluateJavascriptAsync("(function(){return document.readyState;})()", 1_500L)
        // evaluateJavascript wraps string returns in JSON quotes — `"complete"` comes
        // back as the 10-char literal `"\"complete\""`. Match the exact form so a page
        // that overrides document.readyState to a string merely containing "complete"
        // (e.g. "incomplete", or some adversarial value) doesn't trip the early-exit.
        if (raw != null && raw.trim() == "\"complete\"") return true
        kotlinx.coroutines.delay(200)
    }
    return false
}
