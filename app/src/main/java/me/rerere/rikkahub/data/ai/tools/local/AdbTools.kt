package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.preferences.TermuxDefaults
import me.rerere.rikkahub.data.preferences.TermuxRuntime
import me.rerere.rikkahub.data.termux.EmbeddedTermuxRunner
import me.rerere.rikkahub.data.termux.TermuxEnvironment
import me.rerere.rikkahub.data.termux.TermuxResult
import java.io.File

/**
 * adb over TCP/IP through the embedded Termux `android-tools` package.
 *
 * Two LLM tools:
 *  - `adb_shell` — runs `adb -s <serial> shell <command>` against a TCP/IP-connected
 *    device. The killer use case is THIS device: Android 11+ wireless debugging listens
 *    on 127.0.0.1, and a shell there runs as uid 2000 (pm / settings / am / input /
 *    dumpsys / cmd — far beyond the app sandbox). Because of that, every command goes
 *    through HardlineCommandGuard (wired in `checkToolParsed` for "adb_shell") and the
 *    tool is approval-gated in ToolApprovalDefaults.
 *  - `adb_pair` — wraps `adb pair host:port code` for the one-time pairing ceremony.
 *    The pairing port (shown under "Pair device with pairing code") is DIFFERENT from
 *    the connect port (shown on the Wireless debugging main page); both are ephemeral
 *    and rotate on reboot / toggle.
 *
 * Port persistence: the last working local connect port is stored in a dedicated
 * DataStore (`adb_prefs`) so reconnects survive restarts. When the stored port goes
 * stale we fall back to `adb mdns services` (`_adb-tls-connect._tcp`) before giving up
 * and returning pairing guidance.
 */

// Dedicated DataStore — kept in this file so the adb feature doesn't contend for edits
// in the shared preference classes. preferencesDataStore delegates are process singletons
// per name, so constructing AdbStateStore more than once is safe.
private val Context.adbDataStore by preferencesDataStore(name = "adb_prefs")

/** One parsed row of `adb devices` output. */
internal data class AdbDevice(
    val serial: String,
    /** Raw state text after the serial ("device", "offline", "unauthorized", ...). */
    val state: String,
) {
    val authorized: Boolean get() = state == "device"
    val isLocal: Boolean
        get() = serial.startsWith("127.0.0.1:") || serial.startsWith("localhost:")

    val port: Int? get() = serial.substringAfterLast(':', "").toIntOrNull()
}

/** Parse `adb devices` stdout into rows. Shared with the Doctor check. */
internal fun parseAdbDevices(output: String): List<AdbDevice> =
    output.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") }
        .mapNotNull { line ->
            val tokens = line.split(Regex("\\s+"))
            val serial = tokens.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AdbDevice(serial, tokens.getOrElse(1) { "" }.lowercase())
        }
        .toList()

private class AdbStateStore(context: Context) {
    private val store = context.adbDataStore
    private val lastLocalPortKey = intPreferencesKey("last_local_connect_port")
    private val bootstrapHintShownKey = booleanPreferencesKey("bootstrap_hint_shown")

    suspend fun lastLocalPort(): Int = store.data.first()[lastLocalPortKey] ?: 0

    suspend fun setLastLocalPort(port: Int) {
        store.edit { it[lastLocalPortKey] = port }
    }

    /** True only once — the WRITE_SECURE_SETTINGS bootstrap hint is emitted a single time. */
    suspend fun claimBootstrapHint(): Boolean {
        val prefs = store.data.first()
        if (prefs[bootstrapHintShownKey] == true) return false
        store.edit { it[bootstrapHintShownKey] = true }
        return true
    }
}

/**
 * Shared adb state machine. All mutating adb operations (install / connect / pair /
 * disconnect) serialise through [opMutex] so concurrent tool calls can't interleave
 * half-finished connects.
 */
internal class AdbController(
    context: Context,
    private val runner: EmbeddedTermuxRunner,
) {
    private val env = TermuxEnvironment(context)
    private val store = AdbStateStore(context)

    val adbBinary: File get() = File(env.prefix, "bin/adb")

    private fun isAdbInstalled(): Boolean = adbBinary.canExecute()

    /**
     * Ensure `adb` exists inside the embedded Termux prefix. The bootstrap itself is
     * installed on demand by [EmbeddedTermuxRunner.runCommand]; here we only need the
     * `android-tools` package. Auto-installs (network required) on first use.
     */
    suspend fun ensureAdbInstalled(): String? = opMutex.withLock {
        if (isAdbInstalled()) return null
        // `pkg update` first so a fresh bootstrap has package lists; tolerate its
        // failure (stale lists may still satisfy the install).
        val result = runner.runCommand(
            command = "pkg update -y >/dev/null 2>&1; pkg install -y android-tools",
            timeoutMs = INSTALL_TIMEOUT_MS,
            wrapApt = false,
        )
        if (isAdbInstalled()) null else buildJsonObject {
            put("error", "adb_not_installed")
            put(
                "reason",
                "pkg install android-tools failed (exit=${result.exitCode}): " +
                    (result.stderr.ifBlank { result.stdout }).take(500),
            )
            put(
                "recovery",
                "The embedded Termux needs the android-tools package. Check network " +
                    "connectivity and retry, or run `pkg install android-tools` via " +
                    "termux_run_command to see the full log.",
            )
        }.toString()
    }

    suspend fun listDevices(): List<AdbDevice> {
        val result = runner.runCommand(
            command = "adb devices",
            timeoutMs = DEVICE_LIST_TIMEOUT_MS,
            wrapApt = false,
        )
        return parseAdbDevices(result.stdout)
    }

    private suspend fun adbConnect(target: String): TermuxResult =
        runner.runCommand(
            command = "adb connect ${shellQuote(target)}",
            timeoutMs = CONNECT_TIMEOUT_MS,
            wrapApt = false,
        )

    private suspend fun adbDisconnect(serial: String) {
        runner.runCommand(
            command = "adb disconnect ${shellQuote(serial)}",
            timeoutMs = CONNECT_TIMEOUT_MS,
            wrapApt = false,
        )
    }

    /**
     * Best-effort connect-port discovery for the local device. `adb mdns services`
     * lists `_adb-tls-connect._tcp` rows whose last column is `ip:port`; we only need
     * the port because we always dial 127.0.0.1.
     */
    private suspend fun discoverLocalPorts(): List<Int> {
        val result = runner.runCommand(
            command = "adb mdns services",
            timeoutMs = MDNS_TIMEOUT_MS,
            wrapApt = false,
        )
        if (result.exitCode != 0) return emptyList()
        return result.stdout.lineSequence()
            .filter { it.contains("_adb-tls-connect._tcp") }
            .mapNotNull { line ->
                line.trim().split(Regex("\\s+")).lastOrNull()
                    ?.substringAfterLast(':', "")
                    ?.toIntOrNull()
            }
            .distinct()
            .toList()
    }

    sealed class LocalConnect {
        data class Connected(val device: AdbDevice) : LocalConnect()
        data class Unauthorized(val serial: String) : LocalConnect()
        data class NotConnected(val triedPorts: List<Int>) : LocalConnect()
    }

    /**
     * Ensure an authorised adbd session on THIS device via 127.0.0.1. Order:
     * already-authorised device -> persisted port -> mDNS-discovered ports.
     */
    suspend fun ensureLocalConnection(): LocalConnect = opMutex.withLock {
        val devices = listDevices()
        devices.firstOrNull { it.isLocal && it.authorized }?.let { existing ->
            existing.port?.let { store.setLastLocalPort(it) }
            return LocalConnect.Connected(existing)
        }

        // Drop stale "offline" entries so `connect` can redo the handshake cleanly.
        devices.filter { it.isLocal && it.state == "offline" }
            .forEach { adbDisconnect(it.serial) }

        // An "unauthorized" row means the pairing keys were rejected — connecting again
        // won't help; the user must re-pair.
        devices.firstOrNull { it.isLocal && it.state == "unauthorized" }
            ?.let { return LocalConnect.Unauthorized(it.serial) }

        val candidates = buildList {
            store.lastLocalPort().takeIf { it > 0 }?.let { add(it) }
            addAll(discoverLocalPorts())
        }.distinct()

        for (port in candidates) {
            adbConnect("127.0.0.1:$port")
            val device = listDevices().firstOrNull {
                it.isLocal && it.port == port
            } ?: continue
            when {
                device.authorized -> {
                    store.setLastLocalPort(port)
                    return LocalConnect.Connected(device)
                }
                device.state == "unauthorized" -> return LocalConnect.Unauthorized(device.serial)
            }
        }
        LocalConnect.NotConnected(candidates)
    }

    /**
     * Connect to an explicit `host:port` target (LAN device or a user-supplied local
     * port). Returns the authorised device, an Unauthorized marker, or null when the
     * connect didn't produce a device row at all.
     */
    suspend fun connectTarget(target: String): LocalConnect = opMutex.withLock {
        adbConnect(target)
        val device = listDevices().firstOrNull { it.serial == target }
        when {
            device == null -> LocalConnect.NotConnected(listOf())
            device.authorized -> {
                if (device.isLocal) device.port?.let { store.setLastLocalPort(it) }
                LocalConnect.Connected(device)
            }
            device.state == "unauthorized" -> LocalConnect.Unauthorized(device.serial)
            else -> LocalConnect.NotConnected(listOf())
        }
    }

    suspend fun pair(host: String, port: Int, code: String): TermuxResult =
        opMutex.withLock {
            runner.runCommand(
                command = "adb pair ${shellQuote("$host:$port")} ${shellQuote(code)}",
                timeoutMs = PAIR_TIMEOUT_MS,
                wrapApt = false,
            )
        }

    suspend fun runShell(serial: String, command: String, timeoutMs: Long): TermuxResult =
        runner.runCommand(
            command = "adb -s ${shellQuote(serial)} shell ${shellQuote(command)}",
            timeoutMs = timeoutMs,
            wrapApt = false,
        )

    /** One-time self-bootstrap hint surfaced after the first working local shell. */
    suspend fun consumeBootstrapHint(): String? =
        if (store.claimBootstrapHint()) {
            "Optional one-time upgrade: run adb_shell with " +
                "`pm grant ${BuildConfig.APPLICATION_ID} android.permission.WRITE_SECURE_SETTINGS` " +
                "so this app can re-enable wireless debugging and read the connect port itself " +
                "after reboots (requires the permission to be declared in the app manifest)."
        } else null

    private companion object {
        const val INSTALL_TIMEOUT_MS = 300_000L
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val PAIR_TIMEOUT_MS = 30_000L
        const val DEVICE_LIST_TIMEOUT_MS = 15_000L
        const val MDNS_TIMEOUT_MS = 15_000L

        /** Serialises mutating adb ops across all AdbController instances. */
        val opMutex = Mutex()
    }
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private fun adbError(error: String, reason: String, recovery: String): String =
    buildJsonObject {
        put("error", error)
        put("reason", reason)
        put("recovery", recovery)
    }.toString()

private const val PAIRING_GUIDE =
    "On this device: Settings -> Developer options -> Wireless debugging -> " +
        "'Pair device with pairing code' shows a pairing port and 6-digit code. " +
        "Call adb_pair with them, then retry. The connect port on the Wireless " +
        "debugging main page changes on every reboot/toggle."

private const val CONNECT_GUIDE =
    "No authorised local adb session. Enable Settings -> Developer options -> " +
        "Wireless debugging. If this device was never paired, call adb_pair first " +
        "(pairing port + code from 'Pair device with pairing code')."

private val HOST_PORT_PATTERN = Regex("^[A-Za-z0-9._-]+:\\d{1,5}$")
private val SERIAL_PATTERN = Regex("^[A-Za-z0-9._:-]{1,64}$")
private val HOST_PATTERN = Regex("^[A-Za-z0-9._-]{1,253}$")
private val PAIR_CODE_PATTERN = Regex("^[0-9]{5,8}$")

/**
 * `adb_shell` — run a command as shell (uid 2000) on a TCP/IP-connected device,
 * defaulting to this device via 127.0.0.1 wireless debugging.
 */
fun adbShellTool(
    context: Context,
    embeddedTermuxRunner: EmbeddedTermuxRunner,
): Tool = Tool(
    name = "adb_shell",
    description = """
        Run a command through `adb shell` via the embedded Termux adb client.
        On this device, pairing wireless debugging once (adb_pair) gives a 127.0.0.1
        shell as uid 2000 — pm, settings, am, input, dumpsys, logcat — far beyond the
        app sandbox; afterwards it reconnects automatically. For LAN devices pass
        device='host:port'. Commands are subject to a hard safety blocklist.
        Returns {success, exit_code, stdout, stderr, serial}.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Command executed by adb shell, e.g. 'pm list packages'.")
                })
                put("device", buildJsonObject {
                    put("type", "string")
                    put("description", "adb serial or 'host:port' to connect first. Omit for this device (127.0.0.1 auto-reconnect).")
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Timeout in seconds (0/omit = default, max ${TermuxDefaults.MAX_COMMAND_TIMEOUT_SECONDS}).")
                })
            },
            required = listOf("command")
        )
    },
    execute = { input ->
        val controller = AdbController(context, embeddedTermuxRunner)
        val command = input.jsonObject["command"]?.jsonPrimitive?.contentOrNull
        if (command.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(
                adbError("missing_argument", "'command' is required", "Pass the adb shell command text.")
            ))
        }
        val deviceArg = input.jsonObject["device"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
        if (deviceArg != null && !SERIAL_PATTERN.matches(deviceArg)) {
            return@Tool listOf(UIMessagePart.Text(
                adbError("invalid_device", "device '$deviceArg' is not a valid serial or host:port", "Use an adb serial (e.g. 'emulator-5554') or 'host:port'.")
            ))
        }
        val rawTimeout = input.jsonObject["timeout_seconds"]?.jsonPrimitive?.intOrNull
        val timeoutMs = when {
            rawTimeout == null || rawTimeout == 0 -> TermuxRuntime.commandTimeoutMs
            else -> rawTimeout.coerceIn(1, TermuxDefaults.MAX_COMMAND_TIMEOUT_SECONDS).toLong() * 1000
        }

        // 1. adb binary present (auto-installs android-tools on first use).
        controller.ensureAdbInstalled()?.let { err ->
            return@Tool listOf(UIMessagePart.Text(err))
        }

        // 2. Resolve the target serial.
        val connect: AdbController.LocalConnect = when {
            deviceArg == null -> controller.ensureLocalConnection()
            HOST_PORT_PATTERN.matches(deviceArg) -> controller.connectTarget(deviceArg)
            else -> {
                val found = controller.listDevices().firstOrNull { it.serial == deviceArg }
                when {
                    found == null -> return@Tool listOf(UIMessagePart.Text(
                        adbError(
                            "device_not_found",
                            "adb does not see serial '$deviceArg'",
                            "List connected devices with `adb devices` via termux_run_command, or pass 'host:port' as device to connect first.",
                        )
                    ))
                    found.authorized -> AdbController.LocalConnect.Connected(found)
                    found.state == "unauthorized" -> AdbController.LocalConnect.Unauthorized(found.serial)
                    else -> AdbController.LocalConnect.NotConnected(emptyList())
                }
            }
        }
        val device = when (connect) {
            is AdbController.LocalConnect.Connected -> connect.device
            is AdbController.LocalConnect.Unauthorized -> return@Tool listOf(UIMessagePart.Text(
                adbError(
                    "device_unauthorized",
                    "Device '${connect.serial}' rejected our adb keys",
                    "Stored pairing keys are stale. $PAIRING_GUIDE",
                )
            ))
            is AdbController.LocalConnect.NotConnected -> return@Tool listOf(UIMessagePart.Text(
                adbError(
                    "not_connected",
                    if (connect.triedPorts.isEmpty()) "Could not establish an adb connection"
                    else "Tried ports ${connect.triedPorts.joinToString()} without success",
                    CONNECT_GUIDE,
                )
            ))
        }

        // 3. Run the shell command.
        val result = controller.runShell(device.serial, command, timeoutMs)
        val combined = result.stdout + "\n" + result.stderr
        val payload = when {
            combined.contains("device unauthorized", ignoreCase = true) -> adbError(
                "device_unauthorized",
                "Device '${device.serial}' rejected our adb keys mid-session",
                PAIRING_GUIDE,
            )
            combined.contains("device offline", ignoreCase = true) -> adbError(
                "device_offline",
                "Device '${device.serial}' went offline",
                "Retry — the tool reconnects automatically. If it stays offline, toggle Wireless debugging off/on and re-check the port on its main page.",
            )
            combined.contains("not found", ignoreCase = true) &&
                combined.contains(device.serial, ignoreCase = true) -> adbError(
                "device_not_found",
                "Device '${device.serial}' is no longer connected",
                CONNECT_GUIDE,
            )
            else -> buildJsonObject {
                put("success", result.exitCode == 0)
                put("exit_code", result.exitCode)
                put("serial", device.serial)
                put("stdout", result.stdout.take(TermuxRuntime.maxStdoutBytes))
                if (result.stderr.isNotBlank()) {
                    put("stderr", result.stderr.take(TermuxRuntime.maxStderrBytes))
                }
                if (device.isLocal && result.exitCode == 0) {
                    controller.consumeBootstrapHint()?.let { put("bootstrap_hint", it) }
                }
            }.toString()
        }
        listOf(UIMessagePart.Text(payload))
    }
)

/**
 * `adb_pair` — one-time pairing ceremony for wireless debugging.
 */
fun adbPairTool(
    context: Context,
    embeddedTermuxRunner: EmbeddedTermuxRunner,
): Tool = Tool(
    name = "adb_pair",
    description = """
        Pair with a wireless-debugging target via `adb pair` (one time per device).
        On this device: Wireless debugging -> 'Pair device with pairing code' shows a
        pairing PORT + 6-digit code — pass both. The pairing port is NOT the connect
        port; pass connect_port if known, otherwise it is discovered automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("host", buildJsonObject {
                    put("type", "string")
                    put("description", "Target host. Default '127.0.0.1' = this device; a LAN IP pairs a remote device.")
                })
                put("port", buildJsonObject {
                    put("type", "integer")
                    put("description", "PAIRING port from the 'Pair device with pairing code' dialog (NOT the connect port).")
                })
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "The 6-digit pairing code shown in the pairing dialog.")
                })
                put("connect_port", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional connect port from the Wireless debugging main page. Omit to auto-try pairing port + mDNS discovery.")
                })
            },
            required = listOf("port", "code")
        )
    },
    execute = { input ->
        val controller = AdbController(context, embeddedTermuxRunner)
        val host = input.jsonObject["host"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
        // Tolerate ports sent as JSON strings ("38315") as well as numbers.
        val port = input.jsonObject["port"]?.jsonPrimitive?.let {
            it.intOrNull ?: it.contentOrNull?.toIntOrNull()
        }
        val code = input.jsonObject["code"]?.jsonPrimitive?.contentOrNull
        val connectPort = input.jsonObject["connect_port"]?.jsonPrimitive?.let {
            it.intOrNull ?: it.contentOrNull?.toIntOrNull()
        }?.takeIf { it in 1..65535 }

        if (!HOST_PATTERN.matches(host)) {
            return@Tool listOf(UIMessagePart.Text(
                adbError("invalid_host", "host '$host' is not a valid host/IP", "Use an IP address or hostname, e.g. '127.0.0.1' or '192.168.1.5'.")
            ))
        }
        if (port == null || port !in 1..65535) {
            return@Tool listOf(UIMessagePart.Text(
                adbError("invalid_port", "port must be 1-65535", "Read the pairing port from 'Pair device with pairing code'.")
            ))
        }
        if (code.isNullOrBlank() || !PAIR_CODE_PATTERN.matches(code.trim())) {
            return@Tool listOf(UIMessagePart.Text(
                adbError("invalid_code", "code must be the 6-digit pairing code", "It is shown next to the pairing port in the pairing dialog.")
            ))
        }

        controller.ensureAdbInstalled()?.let { err ->
            return@Tool listOf(UIMessagePart.Text(err))
        }

        val pairResult = controller.pair(host, port, code.trim())
        val pairOutput = pairResult.stdout + "\n" + pairResult.stderr
        if (!pairOutput.contains("Successfully paired", ignoreCase = true)) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("error", "pair_failed")
                    put("exit_code", pairResult.exitCode)
                    put("output", pairOutput.trim().take(1000))
                    put(
                        "recovery",
                        "Pairing failed. The pairing port and code rotate every time the " +
                            "pairing dialog opens — re-open 'Pair device with pairing code' " +
                            "and retry with the fresh values. Make sure Wireless debugging " +
                            "is ON and the code is entered before the dialog closes.",
                    )
                }.toString()
            ))
        }

        // Pairing succeeded — now find the connect port and establish the session.
        val connectCandidates = buildList {
            connectPort?.let { add(it) }
            if (connectPort == null) add(port)  // pairing port almost never works, but cheap to try
        }
        var connectedSerial: String? = null
        for (candidate in connectCandidates.distinct()) {
            val result = controller.connectTarget("$host:$candidate")
            if (result is AdbController.LocalConnect.Connected) {
                connectedSerial = result.device.serial
                break
            }
        }
        // Last resort for the local device: full auto path (persisted port + mDNS).
        if (connectedSerial == null && (host == "127.0.0.1" || host == "localhost")) {
            val result = controller.ensureLocalConnection()
            if (result is AdbController.LocalConnect.Connected) {
                connectedSerial = result.device.serial
            }
        }

        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("paired", true)
                put("connected", connectedSerial != null)
                connectedSerial?.let { put("serial", it) }
                if (connectedSerial == null) {
                    put(
                        "recovery",
                        "Paired OK but the connect port was not found. The connect port " +
                            "(Wireless debugging main page, 'IP address & Port') differs " +
                            "from the pairing port. Pass it as connect_port, or just call " +
                            "adb_shell — it auto-discovers the port via mDNS.",
                    )
                } else {
                    put("note", "adb_shell is ready. Pairing persists across reboots; only the connect port rotates.")
                }
            }.toString()
        ))
    }
)
