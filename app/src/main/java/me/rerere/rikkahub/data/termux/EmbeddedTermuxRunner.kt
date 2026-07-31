package me.rerere.rikkahub.data.termux

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 内嵌 Termux 命令执行引擎。
 * 替代外部 Termux 的 RunCommandService Intent 通信，
 * 通过 Android system linker 启动 bootstrap shell，并由 termux-exec 处理子进程。
 */
class EmbeddedTermuxRunner(val env: TermuxEnvironment) {

    /**
     * Check whether the embedded Termux bootstrap is installed and ready.
     * Delegates to [TermuxEnvironment.isInstalled].
     */
    fun isInstalled(): Boolean = env.isInstalled()

    companion object {
        private const val TAG = "EmbeddedTermuxRunner"
        // 单个流保留的最大字符数, 防止命令疯狂输出导致 OOM 或撑爆 LLM 上下文
        private const val MAX_OUTPUT_CHARS = 128 * 1024
        // 进程超时后等待流读取线程退出的宽限时间
        private const val STREAM_JOIN_TIMEOUT_MS = 1_000L
        private const val SYSTEM_LINKER_64 = "/system/bin/linker64"
    }

    /**
     * 执行 shell 命令，捕获 stdout/stderr/exitCode。
     * 等价于外部 Termux 的 runCommandCapture()。
     *
     * 使用 bash login shell (-lc) 执行命令, 确保 profile 环境变量被加载。
     * stdout/stderr 分别在独立线程中读取以避免管道死锁。
     * 超时后进程会被 destroyForcibly 杀掉。
     */
    suspend fun runCommand(
        command: String,
        workdir: String? = null,
        timeoutMs: Long = 60_000,
        wrapApt: Boolean = true,
    ): TermuxResult = withContext(Dispatchers.IO) {
        val effectiveCommand = if (wrapApt) wrapAptNonInteractive(command) else command
        val process = try {
            buildProcess(effectiveCommand, workdir).start()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start process", e)
            return@withContext TermuxResult(
                stdout = "",
                stderr = "Failed to start process: ${e.message}",
                exitCode = 127,
            )
        }

        val stdoutCollector = StreamCollector(process.inputStream, MAX_OUTPUT_CHARS)
        val stderrCollector = StreamCollector(process.errorStream, MAX_OUTPUT_CHARS)

        val finished = withTimeoutOrNull(timeoutMs) {
            // runInterruptible 确保协程取消时中断阻塞的 waitFor 线程
            runInterruptible { process.waitFor() }
        }

        if (finished == null) {
            // 超时: 杀掉进程, 回收读取线程
            Log.w(TAG, "Command timed out after ${timeoutMs}ms, killing process")
            process.destroyForcibly()
            stdoutCollector.join(STREAM_JOIN_TIMEOUT_MS)
            stderrCollector.join(STREAM_JOIN_TIMEOUT_MS)
            return@withContext TermuxResult(
                stdout = stdoutCollector.text(),
                stderr = stderrCollector.text() + "\n[command timed out after ${timeoutMs}ms]",
                exitCode = -1,
            )
        }

        // 进程已退出, 回收读取线程确保拿到完整输出
        stdoutCollector.join(STREAM_JOIN_TIMEOUT_MS)
        stderrCollector.join(STREAM_JOIN_TIMEOUT_MS)

        val exitCode = runCatching { process.exitValue() }.getOrElse { -1 }
        TermuxResult(
            stdout = stdoutCollector.text(),
            stderr = stderrCollector.text(),
            exitCode = exitCode,
        )
    }

    /**
     * 后台执行命令（不等待完成），返回 PID。
     *
     * 命令被 nohup 包装后完全脱离父进程, stdout/stderr 重定向到 /dev/null,
     * 返回的 stdout 中包含 "rikkahub_bg_pid=<pid>" 行。
     */
    suspend fun runCommandBackground(
        command: String,
        workdir: String? = null,
    ): TermuxResult = withContext(Dispatchers.IO) {
        val wrapped = wrapBackground(command)
        // 后台命令不需要 APT 包装, 因为 wrapBackground 已经做了 shell 转义
        runCommand(wrapped, workdir, timeoutMs = 10_000, wrapApt = false)
    }

    /**
     * 构建 ProcessBuilder 用于执行命令。
     * 使用 bash login shell (-lc) 确保加载 profile 环境变量。
     */
    private fun buildProcess(command: String, workdir: String?): ProcessBuilder {
        val launcher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ forbids execve() of writable app-data files for targetSdk >= 29.
            // The system linker is executable from /system and can load the signed APK's
            // bundled bootstrap binary. LD_PRELOAD installs termux-exec so child commands
            // under the same prefix are routed through the linker as well.
            listOf(SYSTEM_LINKER_64, env.bashPath.absolutePath, "-lc", command)
        } else {
            listOf(env.bashPath.absolutePath, "-lc", command)
        }
        return ProcessBuilder(launcher).apply {
            directory(File(workdir ?: env.homeDir.absolutePath))
            environment().putAll(env.buildProcessEnv())
            redirectErrorStream(false)
        }
    }

    /**
     * APT 非交互包装: 为 apt/apt-get 命令注入 DEBIAN_FRONTEND=noninteractive
     * 和 dpkg 的 --force-confdef/--force-confold 选项, 防止升级时
     * 卡在 debconf 交互提示上。
     */
    private fun wrapAptNonInteractive(command: String): String {
        return """
            export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a;
            apt(){ command apt -o Dpkg::Options::='--force-confdef' -o Dpkg::Options::='--force-confold' "${'$'}@"; };
            apt-get(){ command apt-get -o Dpkg::Options::='--force-confdef' -o Dpkg::Options::='--force-confold' "${'$'}@"; };
            export -f apt apt-get;
            $command
        """.trimIndent()
    }

    /**
     * 后台模式包装: 用 nohup 启动命令, 重定向所有 std 流到 /dev/null,
     * 后台运行并输出 PID。命令中的单引号被转义以安全嵌入 sh -c '...' 中。
     */
    private fun wrapBackground(command: String): String {
        val escaped = command.replace("'", "'\\''")
        return "nohup sh -c '$escaped' >/dev/null 2>&1 </dev/null & echo \"rikkahub_bg_pid=\$!\""
    }

}

/**
 * 命令执行结果。
 */
data class TermuxResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
)

/**
 * 在独立守护线程中读取进程输出流, 防止管道写满导致子进程阻塞死锁。
 * 超出 [maxChars] 后继续读到 EOF 并丢弃, 但保持管道畅通让子进程能正常退出。
 */
private class StreamCollector(
    stream: InputStream,
    private val maxChars: Int = MAX_OUTPUT_CHARS_DEFAULT,
) {
    private val builder = StringBuilder()

    private val thread = Thread {
        try {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    synchronized(builder) {
                        val remaining = maxChars - builder.length
                        if (remaining > 0) {
                            builder.append(buffer, 0, minOf(read, remaining))
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // 进程被强杀（超时/取消）时流会被关闭, 保留已读取的内容即可
        }
    }.apply {
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)

    fun text(): String = synchronized(builder) { builder.toString() }

    companion object {
        private const val MAX_OUTPUT_CHARS_DEFAULT = 128 * 1024
    }
}
