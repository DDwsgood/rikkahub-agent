package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.preferences.TermuxDefaults
import me.rerere.rikkahub.data.preferences.TermuxRuntime
import me.rerere.rikkahub.data.termux.EmbeddedTermuxRunner
import me.rerere.rikkahub.data.termux.TermuxEnvironment
import me.rerere.rikkahub.data.termux.TermuxResult

/**
 * Process-scoped holder for the [EmbeddedTermuxRunner] singleton.
 *
 * The legacy free-function [runCommandCapture] (still called by [TranscribeAudioTool]) needs
 * access to the runner without a DI parameter. [LocalTools] calls
 * [setRunner] during tool construction so all subsequent [runCommandCapture] invocations
 * route through the embedded engine.
 */
internal object EmbeddedRunnerHolder {
    @Volatile
    private var runner: EmbeddedTermuxRunner? = null

    fun setRunner(r: EmbeddedTermuxRunner) {
        runner = r
    }

    fun get(): EmbeddedTermuxRunner? = runner
}

/**
 * Termux installation + integration probe used by both the LLM tool and the toggle row in
 * the assistant tools page.
 *
 * After the embedded Termux migration, [state] checks the embedded bootstrap (not the
 * external Termux APK). The [State] enum retains [NOT_INSTALLED], [NO_PERMISSION], and
 * [READY] for compatibility with existing UI consumers.
 */
internal object TermuxIntegration {
    enum class State { NOT_INSTALLED, NO_PERMISSION, READY }

    /**
     * Process-scoped timestamp of the last successful end-to-end smoke test. The toggle row
     * in the assistant Local-tools page reads this so the green indicator persists across
     * navigations within the session - without it the dot would reset to orange every time
     * the user left and re-entered the page. Resets on app restart, which is acceptable
     * since re-verifying is one tap.
     */
    @Volatile
    var lastVerifiedOkAtMs: Long = 0L
        private set

    fun markVerifiedOk() {
        lastVerifiedOkAtMs = System.currentTimeMillis()
    }

    fun clearVerified() {
        lastVerifiedOkAtMs = 0L
    }

    /**
     * Check whether the embedded Termux bootstrap is installed. [NO_PERMISSION] is never
     * returned in the embedded model (the process inherits the app's own permissions), but
     * the state is retained for callers that still branch on it.
     */
    fun state(ctx: Context): State {
        val env = TermuxEnvironment(ctx)
        return if (env.isInstalled()) State.READY else State.NOT_INSTALLED
    }

    /**
     * Run a tiny `echo` smoke test through the embedded runner and check the output.
     * Returns true iff stdout contains our marker, proving the bootstrap bash works.
     */
    suspend fun verify(ctx: Context, timeoutMs: Long = TermuxRuntime.verifyTimeoutMs): VerifyResult {
        val runner = EmbeddedRunnerHolder.get()
            ?: return VerifyResult.OtherError("embedded termux runner not initialized")
        if (!runner.isInstalled()) return VerifyResult.NotInstalled
        val result = runner.runCommand(
            command = "echo RIKKAHUB_OK",
            timeoutMs = timeoutMs,
            wrapApt = false,
        )
        return if (result.exitCode == 0 && result.stdout.contains("RIKKAHUB_OK")) {
            VerifyResult.Ok
        } else {
            VerifyResult.OtherError("exit=${result.exitCode} stderr=${result.stderr}")
        }
    }

    sealed class VerifyResult {
        data object NotInstalled : VerifyResult()
        data object NoPermission : VerifyResult()
        data object AllowExternalAppsMissing : VerifyResult()
        data object Ok : VerifyResult()
        data class UnexpectedOutput(val stdout: String) : VerifyResult()
        data class OtherError(val message: String) : VerifyResult()
    }
}

internal sealed class CaptureResult {
    data class Success(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
    ) : CaptureResult()
    data object Timeout : CaptureResult()
    data object Denied : CaptureResult()
    data class OtherError(val message: String) : CaptureResult()
}

/**
 * Convert a [TermuxResult] from the embedded runner into the legacy [CaptureResult] sealed
 * type that [TranscribeAudioTool] pattern-matches on.
 *
 * - exitCode -1 with a timeout marker in stderr → [CaptureResult.Timeout]
 * - exitCode 127 with an IO failure message → [CaptureResult.OtherError]
 * - otherwise → [CaptureResult.Success]
 */
internal fun TermuxResult.toCaptureResult(): CaptureResult {
    return if (exitCode == 0 || (exitCode != -1 && exitCode != 127)) {
        CaptureResult.Success(stdout, stderr, exitCode)
    } else if (stderr.contains("[command timed out")) {
        CaptureResult.Timeout
    } else {
        CaptureResult.OtherError(stderr.ifBlank { "exit code $exitCode" })
    }
}

/**
 * Dispatch a command through the embedded Termux runner and suspend until it completes (or
 * times out), returning the captured output as a [CaptureResult].
 *
 * This is the compatibility bridge for callers that still use the (executable, arguments,
 * workingDir) tuple shape - currently [TranscribeAudioTool]. The executable is expected to be
 * bash; the arguments array is joined into a single command string.
 */
internal suspend fun runCommandCapture(
    ctx: Context,
    executable: String,
    arguments: Array<String>,
    workingDir: String,
    timeoutMs: Long = TermuxRuntime.commandTimeoutMs,
): CaptureResult {
    val runner = EmbeddedRunnerHolder.get()
        ?: return CaptureResult.OtherError("embedded termux runner not initialized")

    if (!runner.isInstalled()) {
        return CaptureResult.OtherError("embedded termux bootstrap not installed")
    }

    // Build a single command string from the executable + arguments. Callers always use
    // bash -c "<script>", so we reconstruct: <executable> <arg1> <arg2> ...
    // The executable itself is bash, and the first arg is typically "-c" followed by the
    // script. We pass the script directly to runCommand which already uses bash -lc.
    val isBashLauncher = executable.endsWith("/bash") || executable.endsWith("/sh")
    val command = if (isBashLauncher && arguments.isNotEmpty()) {
        // arguments[0] is "-c" or "-lc", arguments[1] is the script
        val scriptIndex = arguments.indexOfFirst { !it.startsWith("-") }
        if (scriptIndex >= 0 && scriptIndex < arguments.size) {
            arguments[scriptIndex]
        } else {
            arguments.joinToString(" ")
        }
    } else {
        listOf(executable, *arguments).joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
    }

    val result = runner.runCommand(
        command = command,
        workdir = workingDir.takeIf { java.io.File(it).isDirectory },
        timeoutMs = timeoutMs,
        wrapApt = false,
    )
    return result.toCaptureResult()
}

/**
 * LLM-callable termux command tool. Defaults to capture mode (background command, output
 * returned in the JSON envelope so the model can reason about it). Pass `interactive=true`
 * for the legacy "open visible session" mode - in the embedded model this runs the command
 * in a foreground process; output is still captured (unlike the old external Termux path
 * where the foreground session couldn't be read back).
 */
fun termuxRunCommandTool(
    @Suppress("UNUSED_PARAMETER") context: Context,
    embeddedTermuxRunner: EmbeddedTermuxRunner,
): Tool = Tool(
    name = "termux_run_command",
    description = """
        Execute a shell command in the embedded Termux environment. By default the command
        runs in the background and its stdout / stderr / exit_code are returned to you so
        you can reason on the output (e.g. check if a package is installed, read a file,
        run a script). Pass interactive=true to run in a foreground process; output is still
        captured. In command mode, apt/apt-get are automatically wrapped with
        DEBIAN_FRONTEND=noninteractive and safe dpkg defaults; do not add extra -y flags
        unless the user specifically asked for unattended upgrades.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command line, e.g. 'pkg update && pkg upgrade -y'. Mutually exclusive with executable+arguments.")
                })
                put("executable", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path to executable, e.g. /data/data/excp.rikkahub/files/termux/usr/bin/bash. Pairs with arguments[].")
                })
                put("arguments", buildJsonObject {
                    put("type", "array")
                    put("description", "Argument list when using executable mode")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("working_dir", buildJsonObject {
                    put("type", "string")
                    put("description", "Working directory. Defaults to the embedded Termux home.")
                })
                put("interactive", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, runs in a foreground process. In the embedded model output is still captured. Default false.")
                })
                put("background", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Command mode only. If true, launch the command fully detached (nohup, streams redirected) and return immediately with its PID. Use for servers / long-running processes that would otherwise block until timeout. Default false.")
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Capture-mode timeout in seconds. Omit or pass 0 to use the user-configured default (Settings -> Termux). Max ${TermuxDefaults.MAX_COMMAND_TIMEOUT_SECONDS} s.")
                })
            }
        )
    },
    execute = { input ->
        val rawCommand = input.jsonObject["command"]?.jsonPrimitive?.contentOrNull
        val executable = input.jsonObject["executable"]?.jsonPrimitive?.contentOrNull
        val argumentsArr = input.jsonObject["arguments"]?.jsonArray
        val workingDir = input.jsonObject["working_dir"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && java.io.File(it).isDirectory() }
            ?: TermuxRuntime.defaultWorkingDir
        val interactive = input.jsonObject["interactive"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false
        val background = input.jsonObject["background"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false
        // Read the user-configured default command timeout at call time. The LLM can override
        // per-call with timeout_seconds (0 = use runtime default, otherwise capped at
        // MAX_COMMAND_TIMEOUT_SECONDS = 600 s, raised from the old 300 s ceiling).
        val configuredTimeoutMs = TermuxRuntime.commandTimeoutMs
        val rawTimeout = input.jsonObject["timeout_seconds"]?.jsonPrimitive?.intOrNull
        val timeoutMs = when {
            rawTimeout == null || rawTimeout == 0 -> configuredTimeoutMs
            else -> rawTimeout.coerceIn(1, TermuxDefaults.MAX_COMMAND_TIMEOUT_SECONDS).toLong() * 1000
        }

        if (rawCommand.isNullOrBlank() && executable.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "either 'command' or 'executable' is required") }.toString()
                )
            )
        }
        if (!rawCommand.isNullOrBlank() && !executable.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "command and executable are mutually exclusive") }.toString()
                )
            )
        }

        // Pre-flight: embedded bootstrap installed?
        if (!embeddedTermuxRunner.isInstalled()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "termux_not_installed")
                        put("recovery", "The embedded Termux bootstrap is not installed. It is downloaded automatically on app startup. Restart the app and wait a moment, then retry. If the issue persists, check your network connection.")
                    }.toString()
                )
            )
        }

        // In the embedded model there is no external package to touch, so the
        // AgentTurnTracker.touchPackage() call is no longer needed.

        if (rawCommand != null) {
            // APT non-interactive wrapping is handled inside EmbeddedTermuxRunner when
            // wrapApt=true (gated on TermuxRuntime.aptWrapEnabled). Background mode is also
            // handled by the runner's runCommandBackground. Here we just pick the right path.
            val (result, effectiveTimeoutMs) = if (background) {
                embeddedTermuxRunner.runCommandBackground(
                    command = rawCommand,
                    workdir = workingDir,
                ) to 10_000L
            } else {
                embeddedTermuxRunner.runCommand(
                    command = rawCommand,
                    workdir = workingDir,
                    timeoutMs = timeoutMs,
                    wrapApt = TermuxRuntime.aptWrapEnabled,
                ) to timeoutMs
            }
            return@Tool listOf(UIMessagePart.Text(formatResult(result, "capture", effectiveTimeoutMs)))
        }

        // executable + arguments mode: no wrapping, direct execution.
        val args = argumentsArr?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.toTypedArray()
            ?: emptyArray()
        // Reconstruct a command string for the runner.
        val commandStr = listOf(executable!!, *args)
            .joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
        val result = embeddedTermuxRunner.runCommand(
            command = commandStr,
            workdir = workingDir,
            timeoutMs = timeoutMs,
            wrapApt = false,
        )
        // In interactive mode we still return the captured output (the embedded model can
        // always read it back, unlike the old external Termux foreground session).
        val mode = if (interactive) "interactive" else "capture"
        listOf(UIMessagePart.Text(formatResult(result, mode, timeoutMs)))
    }
)

/**
 * Format an [TermuxResult] into the JSON envelope the LLM expects.
 */
private fun formatResult(
    result: TermuxResult,
    mode: String,
    timeoutMs: Long,
): String {
    val isTimeout = result.exitCode == -1 && result.stderr.contains("[command timed out")
    val payload = if (isTimeout) {
        buildJsonObject {
            put("error", "timeout")
            put("recovery", "Command did not return within ${timeoutMs / 1000}s. Increase timeout_seconds or check if the command is waiting for input.")
        }
    } else if (result.exitCode == 127 && result.stderr.contains("Failed to start")) {
        buildJsonObject {
            put("error", "termux_run_failed")
            put("reason", result.stderr)
        }
    } else {
        buildJsonObject {
            put("success", result.exitCode == 0)
            put("mode", mode)
            put("exit_code", result.exitCode)
            val maxOut = TermuxRuntime.maxStdoutBytes
            val maxErr = TermuxRuntime.maxStderrBytes
            put(
                "stdout",
                result.stdout.let {
                    val outBytes = it.toByteArray(Charsets.UTF_8).size
                    if (outBytes > maxOut) takeFirstUtf8Bytes(it, maxOut) + "\n…[truncated; ${outBytes - maxOut} bytes more]" else it
                }
            )
            if (result.stderr.isNotBlank()) {
                put(
                    "stderr",
                    result.stderr.let {
                        if (it.toByteArray(Charsets.UTF_8).size > maxErr) takeFirstUtf8Bytes(it, maxErr) + "\n…[truncated]" else it
                    }
                )
            }
            if (result.exitCode != 0) {
                put("note", "Non-zero exit code; check stderr.")
            }
        }
    }
    return payload.toString()
}

private fun takeFirstUtf8Bytes(value: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val width = when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
        if (bytes + width > maxBytes) break
        bytes += width
        index += Character.charCount(codePoint)
    }
    return value.substring(0, index)
}
