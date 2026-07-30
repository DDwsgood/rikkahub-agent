package me.rerere.rikkahub.data.preferences

/**
 * Process-scoped @Volatile runtime holder for Termux-specific knobs. Pushed from
 * [TermuxPreferences.init] so non-suspend callers (TermuxTool.execute) always read the
 * latest persisted value without needing a coroutine context.
 *
 * Mirrors [me.rerere.rikkahub.browser.BrowserController]'s perToolTimeoutMs / singleTaskTimeoutMs
 * pattern. Initial values are the defaults so the very first tool call (before the
 * TermuxPreferences collector has emitted) gets sensible behavior.
 */
object TermuxRuntime {
    @Volatile var commandTimeoutMs: Long  = TermuxDefaults.DEFAULT_COMMAND_TIMEOUT_MS
    @Volatile var verifyTimeoutMs: Long   = TermuxDefaults.DEFAULT_VERIFY_TIMEOUT_MS
    @Volatile var defaultWorkingDir: String = TermuxDefaults.DEFAULT_WORKING_DIR
    @Volatile var maxStdoutBytes: Int     = TermuxDefaults.DEFAULT_MAX_STDOUT
    @Volatile var maxStderrBytes: Int     = TermuxDefaults.DEFAULT_MAX_STDERR
    @Volatile var aptWrapEnabled: Boolean = TermuxDefaults.DEFAULT_APT_WRAP_ENABLED

    // Embedded Termux bootstrap state. Set by TermuxInstaller after a successful
    // install/upgrade. Read by tools to decide whether to use the in-process runner
    // (embedded) or fall back to the external Termux RUN_COMMAND intent (legacy).
    @Volatile var embeddedTermuxInstalled: Boolean = false
    @Volatile var embeddedTermuxVersion: String?   = null
}
