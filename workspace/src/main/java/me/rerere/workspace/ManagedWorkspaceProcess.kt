package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** stderr 保留的最大字节数（尾部环形缓冲，防止日志刷爆内存 / LLM 上下文） */
const val MAX_STDIO_STDERR_BYTES = 8 * 1024

/** destroy() 后等待进程自行退出的宽限期; 超时后枚举进程树强杀残留 */
const val PROCESS_CLOSE_GRACE_SECONDS = 5L

/**
 * A long-lived, workspace-managed duplex process used as a stdio MCP server transport.
 *
 * [inputStream] is the child's stdout (the JSON-RPC channel from server to client),
 * [outputStream] the child's stdin (client to server), and [errorStream] the child's
 * stderr wrapped in a bounded tail buffer — it never mixes into the JSON-RPC channel.
 * The proot process tree is owned here: [close] destroys it.
 */
class ManagedWorkspaceProcess(
    val inputStream: InputStream,
    val outputStream: OutputStream,
    val errorStream: InputStream,
    private val process: Process,
    private val onClosed: (() -> Unit)? = null,
) {
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean
        get() = closed.get()

    /**
     * Kills the underlying proot process tree and fires [onClosed]. Idempotent.
     *
     * Android's Process.destroy() only SIGTERMs the direct child; the real MCP server is
     * a proot grandchild, so a bare destroy can leave the tree running. Sequence: graceful
     * destroy → short grace period → if still alive, enumerate descendants, kill them in
     * reverse order, then destroyForcibly and waitFor. Streams are closed explicitly so a
     * transport reader can't stay blocked on a pipe the child no longer drains.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { process.destroy() }
        if (!runCatching { process.waitFor(PROCESS_CLOSE_GRACE_SECONDS, TimeUnit.SECONDS) }
                .getOrDefault(false)
        ) {
            terminateProcessTree(process)
        }
        closeStreams()
        runCatching { onClosed?.invoke() }
    }

    private fun closeStreams() {
        runCatching { outputStream.close() }
        runCatching { errorStream.close() }
        runCatching { inputStream.close() }
    }
}

/**
 * 强杀 [process] 的完整进程树: 枚举 /proc 下的 descendants, 逆序逐个 SIGKILL, 最后
 * destroyForcibly + waitFor 兜底。Android 的 Process.destroy* 只覆盖直接子进程, 而
 * proot 的孙进程 (真正的 MCP server / 后台命令) 不会因此退出, 必须显式枚举杀掉。
 *
 * 本函数是 workspace 模块内唯一的进程树终止实现: [ManagedWorkspaceProcess.close]、
 * [WorkspaceBackgroundProcesses] 的 kill/killAll、[Process.readResult] 的超时/中断
 * 清理都复用它。app 模块的 EmbeddedTermuxRunner 也复用同一实现。
 */
fun terminateProcessTree(process: Process, graceSeconds: Long = PROCESS_CLOSE_GRACE_SECONDS) {
    val rootPid = processPid(process)
    val descendants = rootPid?.let(::collectDescendantPids).orEmpty()
    descendants.asReversed().forEach { pid ->
        runCatching { android.os.Process.killProcess(pid) }
    }
    process.destroyForcibly()
    runCatching { process.waitFor(graceSeconds, TimeUnit.SECONDS) }
    descendants.forEach { pid ->
        runCatching { android.os.Process.killProcess(pid) }
    }
}

private fun processPid(process: Process): Int? = runCatching {
    val pidMethod = process.javaClass.methods.firstOrNull {
        (it.name == "pid" || it.name == "getPid") && it.parameterCount == 0
    }
    (pidMethod?.invoke(process) as? Number)?.toInt() ?: run {
        var type: Class<*>? = process.javaClass
        var pid: Int? = null
        while (type != null && pid == null) {
            val currentType = type
            val field = runCatching { currentType.getDeclaredField("pid") }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                pid = (field.get(process) as? Number)?.toInt()
            }
            type = currentType.superclass
        }
        pid
    }
}.getOrNull()

private fun collectDescendantPids(rootPid: Int): List<Int> {
    val result = mutableListOf<Int>()
    fun visit(pid: Int) {
        val children = runCatching {
            File("/proc/$pid/task/$pid/children").readText()
                .trim()
                .split(Regex("\\s+"))
                .mapNotNull(String::toIntOrNull)
        }.getOrDefault(emptyList())
        children.forEach { child ->
            visit(child)
            result += child
        }
    }
    visit(rootPid)
    return result.distinct()
}

/**
 * Pass-through [InputStream] that streams every byte to the consumer while retaining
 * only the most recent [maxBytes] bytes in an internal ring buffer. The consumer (the
 * MCP transport's stderr reader) drains the stream so the child's stderr pipe can never
 * fill up and block it; the retained tail is available via [tailText] for diagnostics.
 */
class BoundedTailInputStream(
    private val delegate: InputStream,
    private val maxBytes: Int = MAX_STDIO_STDERR_BYTES,
) : InputStream() {
    private val buffer = ByteArray(maxBytes)
    private var writeIndex = 0
    private var totalBytes = 0L

    @Synchronized
    private fun record(byte: Byte) {
        buffer[writeIndex] = byte
        writeIndex = (writeIndex + 1) % buffer.size
        totalBytes++
    }

    override fun read(): Int {
        val b = delegate.read()
        if (b >= 0) record(b.toByte())
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) {
            for (i in 0 until n) record(b[off + i])
        }
        return n
    }

    /** Returns the retained tail (most recent up to [maxBytes] bytes) as text. */
    @Synchronized
    fun tailText(): String {
        val retained = minOf(buffer.size.toLong(), totalBytes).toInt()
        val sb = StringBuilder(retained)
        var i = if (totalBytes < buffer.size) 0 else writeIndex
        repeat(retained) {
            sb.append((buffer[i].toInt() and 0xFF).toChar())
            i = (i + 1) % buffer.size
        }
        return sb.toString()
    }

    override fun close() {
        delegate.close()
    }
}
