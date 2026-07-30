package me.rerere.rikkahub.data.termux

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 内嵌 Termux 环境的常量与状态检测。
 *
 * 与 workspace (proot) 完全隔离：不使用 proot，直接以 app UID 运行。
 * 环境变量和路径约定复制自原生 Termux。
 */
class TermuxEnvironment(private val context: Context) {

    /** 内嵌 Termux 根目录: {filesDir}/termux/ */
    val termuxRoot: File = File(context.filesDir, "termux")

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

    /** staging 目录: {filesDir}/termux/usr-staging/ */
    val stagingDir: File = File(termuxRoot, "usr-staging")

    /**
     * 检测 bootstrap 是否已安装: $PREFIX/bin/bash 存在且可执行。
     */
    fun isInstalled(): Boolean = runCatching {
        bashPath.exists() && bashPath.canExecute()
    }.getOrElse {
        Log.w(TAG, "isInstalled check failed", it)
        false
    }

    /**
     * 是否需要安装 bootstrap。
     */
    fun needsBootstrap(): Boolean = !isInstalled()

    /**
     * 默认环境变量。路径使用绝对字符串，与原生 Termux 一致。
     */
    fun defaultEnvironment(): Map<String, String> = mapOf(
        "PREFIX" to prefix.absolutePath,
        "HOME" to homeDir.absolutePath,
        "PATH" to "${prefix.absolutePath}/bin:${prefix.absolutePath}/bin/applets",
        "LD_LIBRARY_PATH" to "${prefix.absolutePath}/lib",
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
        val termuxEnv = loadEnvironment()
        val merged = systemEnv.toMutableMap()
        // termux 环境变量覆盖系统同名变量
        termuxEnv.forEach { (k, v) -> merged[k] = v }
        // 确保 PATH 包含 termux 的 bin 目录（优先放在前面）
        val termuxPath = termuxEnv["PATH"]
        if (termuxPath != null) {
            val systemPath = merged["PATH"] ?: ""
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
