package me.rerere.rikkahub.data.termux

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

internal fun matchesRequiredTermuxEnvironment(
    values: Map<String, String>,
    expected: Map<String, String>,
): Boolean = listOf("PREFIX", "HOME", "LD_PRELOAD", "TERMUX_APP__PACKAGE_NAME")
    .all { key -> values[key] == expected[key] }

/**
 * 内嵌 Termux 环境的常量与状态检测。
 *
 * 与 workspace rootfs 隔离，并使用原生 Termux 的环境变量和路径约定。
 */
class TermuxEnvironment(private val context: Context) {

    /**
     * Embedded Termux root. Bootstrap binaries and scripts are compiled with this legacy
     * primary-user path, so environment strings must match it exactly instead of mixing in
     * Android's equivalent `/data/user/0` alias returned by Context.filesDir.
     */
    val termuxRoot: File = File("/data/data/${context.packageName}/files/termux")

    /** $PREFIX: {filesDir}/termux/usr */
    val prefix: File = File(termuxRoot, "usr")

    /** $HOME: {filesDir}/termux/home */
    val homeDir: File = File(termuxRoot, "home")

    /** $TMPDIR: {filesDir}/termux/usr/tmp */
    val tmpDir: File = File(prefix, "tmp")

    /** 环境变量文件: $PREFIX/etc/termux/termux.env */
    val envFile: File = File(prefix, "etc/termux/termux.env")

    /** $PREFIX/bin/bash */
    val bashPath: File = File(prefix, "bin/bash")

    val secondStageScript: File = File(
        prefix,
        "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh",
    )

    /** Written only after second-stage package configuration and a runtime smoke test pass. */
    val installationMarker: File = File(prefix, ".rikkahub-bootstrap-complete")

    /** termux-exec variant appropriate for the platform's app-data execution policy. */
    val termuxExecPreloadLibrary: File = File(
        prefix,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "lib/libtermux-exec-linker-ld-preload.so"
        } else {
            "lib/libtermux-exec-direct-ld-preload.so"
        },
    )

    /** staging 目录: {filesDir}/termux/usr-staging/ */
    val stagingDir: File = File(termuxRoot, "usr-staging")

    /** Rollback copy used only while atomically replacing an unrecoverable PREFIX. */
    val prefixBackupDir: File = File(termuxRoot, "usr-backup")

    /**
     * 检测 bootstrap 是否已安装: $PREFIX/bin/bash 存在且可执行。
     */
    fun isInstalled(): Boolean = runCatching {
        hasBootstrapCore() &&
            homeDir.isDirectory &&
            tmpDir.isDirectory &&
            hasValidEnvironmentFile() &&
            installationMarker.isFile
    }.getOrElse {
        Log.w(TAG, "isInstalled check failed", it)
        false
    }

    /**
     * 是否需要安装 bootstrap。
     */
    fun needsBootstrap(): Boolean = !isInstalled()

    /** Core files whose presence lets the installer repair an incomplete layout in place. */
    fun hasBootstrapCore(): Boolean = runCatching {
        bashPath.isFile &&
            bashPath.canExecute() &&
            termuxExecPreloadLibrary.isFile &&
            secondStageScript.isFile
    }.getOrDefault(false)

    /** Reject stale env files left by another build variant or execution mode. */
    fun hasValidEnvironmentFile(): Boolean = runCatching {
        if (!envFile.isFile) return@runCatching false
        val values = envFile.useLines { lines ->
            lines.mapNotNull { rawLine ->
                val line = rawLine.trim()
                val separator = line.indexOf('=')
                if (line.isEmpty() || line.startsWith("#") || separator <= 0) {
                    null
                } else {
                    line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                }
            }.toMap()
        }
        matchesRequiredTermuxEnvironment(values, defaultEnvironment())
    }.getOrDefault(false)

    /**
     * 默认环境变量。路径使用绝对字符串，与原生 Termux 一致。
     */
    fun defaultEnvironment(): Map<String, String> = mapOf(
        "PREFIX" to prefix.absolutePath,
        "TERMUX__PREFIX" to prefix.absolutePath,
        "HOME" to homeDir.absolutePath,
        "TERMUX__HOME" to homeDir.absolutePath,
        "PATH" to "${prefix.absolutePath}/bin:${prefix.absolutePath}/bin/applets",
        "LD_LIBRARY_PATH" to "${prefix.absolutePath}/lib",
        "LD_PRELOAD" to termuxExecPreloadLibrary.absolutePath,
        "TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE" to "enable",
        "TERMUX_APP__PACKAGE_NAME" to context.packageName,
        "TERMUX_APP__DATA_DIR" to "/data/data/${context.packageName}",
        "TERMUX_APP__LEGACY_DATA_DIR" to "/data/data/${context.packageName}",
        "TERMUX__ROOTFS" to termuxRoot.absolutePath,
        "TMPDIR" to tmpDir.absolutePath,
        "LANG" to "en_US.UTF-8",
        "TERM" to "xterm-256color",
        "TERMUX" to "true",
    )

    /**
     * 读取 termux.env 文件并解析为 key=value 对。
     * 如果文件不存在，返回默认环境变量。
     *
     * termux.env 格式：每行 `KEY=VALUE`，支持 `#` 注释行。
     */
    fun loadEnvironment(): Map<String, String> {
        if (!envFile.exists()) {
            Log.i(TAG, "env file not found, returning defaults")
            return defaultEnvironment()
        }
        val result = mutableMapOf<String, String>()
        runCatching {
            envFile.useLines { lines ->
                lines.forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEach
                    val eq = line.indexOf('=')
                    if (eq <= 0) return@forEach
                    val key = line.substring(0, eq).trim()
                    val value = line.substring(eq + 1).trim()
                    if (key.isNotEmpty()) result[key] = value
                }
            }
        }.onFailure {
            Log.w(TAG, "loadEnvironment failed, returning defaults", it)
            return defaultEnvironment()
        }
        // 确保默认值中存在但 env 文件未覆盖的 key 也被填充
        defaultEnvironment().forEach { (k, v) ->
            if (k !in result) result[k] = v
        }
        return result
    }

    /**
     * 构建 ProcessBuilder 可用的完整环境变量 map。
     *
     * 以系统当前环境（System.getenv()）为基底，叠加 termux.env 的覆盖。
     * 系统 PATH 会被保留并追加 termux bin 路径，确保不影响 Android 自身工具链。
     */
    fun buildProcessEnv(): Map<String, String> {
        val systemEnv = System.getenv().toMap()
        val systemPath = systemEnv["PATH"].orEmpty()
        val termuxEnv = loadEnvironment()
        val merged = systemEnv.toMutableMap()
        // termux 环境变量覆盖系统同名变量
        termuxEnv.forEach { (k, v) -> merged[k] = v }
        // 确保 PATH 包含 termux 的 bin 目录（优先放在前面）
        val termuxPath = termuxEnv["PATH"]
        if (termuxPath != null) {
            merged["PATH"] = if (systemPath.isBlank()) {
                termuxPath
            } else {
                "$termuxPath:$systemPath"
            }
        }
        return merged
    }

    companion object {
        private const val TAG = "TermuxEnvironment"
    }
}
