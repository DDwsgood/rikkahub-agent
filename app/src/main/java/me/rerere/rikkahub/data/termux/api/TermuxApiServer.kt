package me.rerere.rikkahub.data.termux.api

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一次 termux-* shim 命令的执行结果。
 */
data class TermuxApiResult(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
)

typealias TermuxApiHandler = (args: List<String>) -> TermuxApiResult

/**
 * 解析后的请求：token 已由字段携带，命令名和参数列表（base64 解码后）。
 */
internal data class TermuxApiRequest(
    val token: String,
    val command: String,
    val args: List<String>,
)

/**
 * 请求协议（单行）:
 *
 *     <token> <command> [<base64(arg)> ...]\n
 *
 * 每个参数独立 base64 编码（消除引号/空白/换行歧义），字段间以单个空格分隔。
 * 参数个数可为 0。
 *
 * 响应协议：
 *
 *     <exit-code> <stderr-byte-length>\n
 *     <stderr bytes><stdout bytes，直到连接关闭>
 *
 * shim 端先读 header 行，再按长度读 stderr，剩余内容即 stdout。
 */
internal fun parseApiRequestLine(line: String): TermuxApiRequest? {
    val fields = line.trimEnd('\r', '\n').split(' ').filter { it.isNotEmpty() }
    if (fields.size < 2) return null
    val args = fields.drop(2).map { encoded ->
        // 哨兵 "-": shim 端把空参数编码为 "-"，因为 base64("") 是空串，会被
        // 上面的 filter { it.isNotEmpty() } 吞掉导致参数位置前移。"-" 不是合法
        // base64，线上永远不会与真实编码冲突；真实的 "-" 参数会被编码成 "LQ=="。
        if (encoded == TermuxApiServer.EMPTY_ARG_SENTINEL) {
            ""
        } else {
            runCatching {
                String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            }.getOrElse { return null }
        }
    }
    return TermuxApiRequest(token = fields[0], command = fields[1], args = args)
}

internal fun apiTokenEquals(provided: String, expected: String): Boolean =
    MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

/**
 * App 内嵌的 Termux API 服务。在内置 Termux 环境运行期间监听 127.0.0.1 随机端口，
 * 为 $PREFIX/bin 下的 termux-* shim 脚本提供后端实现（对齐官方 Termux:API 语义）。
 *
 * 生命周期与 [me.rerere.rikkahub.data.termux.EmbeddedTermuxRunner] 绑定：
 * 首次 runCommand 时懒启动，app 进程退出时随进程消亡（socket 由系统回收）。
 * 端口与 token 经会话环境变量 RIKKA_API_HOST/PORT/TOKEN 注入子进程，
 * token 每次进程启动随机生成、不落盘。
 */
class TermuxApiServer(context: Context) {
    private val appContext = context.applicationContext
    private val handlers: Map<String, TermuxApiHandler> = buildTermuxApiHandlers(appContext)
    private val token: String = generateToken()

    private val stateLock = Any()
    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private val activeConnections = AtomicInteger(0)

    val isRunning: Boolean
        get() = synchronized(stateLock) { serverSocket != null }

    val port: Int
        get() = synchronized(stateLock) { serverSocket?.localPort ?: -1 }

    /**
     * 懒启动 server。幂等；绑定失败返回 false，此时 [sessionEnv] 为空 map，
     * shim 侧会以"RIKKA_API_* 未设置"的明确错误退出。
     */
    fun ensureStarted(): Boolean {
        synchronized(stateLock) {
            if (serverSocket != null) return true
            val socket = try {
                ServerSocket(BIND_PORT_ANY, BACKLOG, InetAddress.getByName(BIND_HOST))
            } catch (e: IOException) {
                Log.w(TAG, "Failed to bind Termux API server", e)
                return false
            }
            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            serverSocket = socket
            scope = newScope
            newScope.launch { acceptLoop(socket) }
            Log.i(TAG, "Termux API server listening on $BIND_HOST:${socket.localPort}")
            return true
        }
    }

    /**
     * 注入到内嵌 Termux 子进程的环境变量。server 未运行时返回空 map。
     */
    fun sessionEnv(): Map<String, String> {
        val boundPort = port
        if (boundPort <= 0) return emptyMap()
        return mapOf(
            ENV_HOST to BIND_HOST,
            ENV_PORT to boundPort.toString(),
            ENV_TOKEN to token,
        )
    }

    fun close() {
        synchronized(stateLock) {
            scope?.cancel()
            scope = null
            runCatching { serverSocket?.close() }
            serverSocket = null
        }
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        val coroutineScope = scope ?: return
        while (coroutineScope.isActive) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break // socket closed
            } catch (e: IOException) {
                if (coroutineScope.isActive) {
                    Log.w(TAG, "Accept failed", e)
                    continue
                }
                break
            }
            // 并发连接上限：超出直接拒绝新连接（loopback 上任意进程都能连，
            // 无上限时一个恶意/失控进程可以无限堆积 handler 协程）。
            if (activeConnections.incrementAndGet() > MAX_CONNECTIONS) {
                activeConnections.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            coroutineScope.launch {
                try {
                    handleClient(client)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to handle Termux API client", e)
                } finally {
                    activeConnections.decrementAndGet()
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = READ_TIMEOUT_MS
        val requestLine = runCatching {
            readRequestLine(socket.getInputStream())
        }.getOrNull() ?: return
        val result = dispatch(requestLine)
        val stderrBytes = result.stderr.toByteArray(Charsets.UTF_8)
        val stdoutBytes = result.stdout.toByteArray(Charsets.UTF_8)
        val output = socket.getOutputStream()
        output.write("${result.exitCode} ${stderrBytes.size}\n".toByteArray(Charsets.US_ASCII))
        output.write(stderrBytes)
        output.write(stdoutBytes)
        output.flush()
        // 关闭写方向让 shim 的 `cat` 读到 EOF；随后由调用方关闭整个 socket。
        runCatching { socket.shutdownOutput() }
    }

    /**
     * 有界地读取请求行。不能用 BufferedReader.readLine()——它在 token 校验之前会
     * 无限制累积输入，loopback 上的任意进程只要不发换行符就能耗尽内存。
     *
     * 这里顺带做最早的 token 校验：逐块读入时一旦遇到空格（token 字段结束）就比较
     * token，不匹配立即返回 null（连接随即关闭），不再读取任何后续字节。token 字段
     * 本身限长 [MAX_TOKEN_FIELD_BYTES]，整行限长 [MAX_REQUEST_LINE_BYTES]。
     */
    private fun readRequestLine(input: InputStream): String? {
        val out = ByteArrayOutputStream(MAX_TOKEN_FIELD_BYTES + 1)
        val chunk = ByteArray(READ_CHUNK_BYTES)
        var tokenChecked = false
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break // EOF（客户端中止；shim 协议下正常请求不会走到这）
            if (n == 0) continue
            if (out.size() + n > MAX_REQUEST_LINE_BYTES) return null
            out.write(chunk, 0, n)
            val bytes = out.toByteArray()
            if (!tokenChecked) {
                val spaceIdx = bytes.indexOf(' '.code.toByte())
                val newlineIdx = bytes.indexOf('\n'.code.toByte())
                when {
                    // 字段过长还没遇到分隔符：不是合法 token，直接拒绝。
                    spaceIdx < 0 && newlineIdx < 0 && bytes.size > MAX_TOKEN_FIELD_BYTES -> return null
                    spaceIdx >= 0 -> {
                        tokenChecked = true
                        val provided = String(bytes, 0, spaceIdx, Charsets.UTF_8)
                        if (!apiTokenEquals(provided, token)) return null // 立即关闭
                    }
                    newlineIdx >= 0 -> return null // 无空格的行：畸形请求
                }
            }
            if ('\n'.code.toByte() in bytes) break
        }
        if (out.size() == 0) return null
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun dispatch(line: String): TermuxApiResult {
        val request = parseApiRequestLine(line)
            ?: return TermuxApiResult(EXIT_MALFORMED, stderr = "termux-api: malformed request\n")
        if (!apiTokenEquals(request.token, token)) {
            return TermuxApiResult(EXIT_UNAUTHORIZED, stderr = "termux-api: unauthorized\n")
        }
        val handler = handlers[request.command]
            ?: return TermuxApiResult(
                EXIT_UNKNOWN_COMMAND,
                stderr = "${request.command}: command not supported by RikkaHub\n",
            )
        return runCatching { handler(request.args) }.getOrElse {
            Log.w(TAG, "Handler for ${request.command} failed", it)
            TermuxApiResult(EXIT_ERROR, stderr = "${request.command}: ${it.message ?: "internal error"}\n")
        }
    }

    companion object {
        private const val TAG = "TermuxApiServer"

        const val ENV_HOST = "RIKKA_API_HOST"
        const val ENV_PORT = "RIKKA_API_PORT"
        const val ENV_TOKEN = "RIKKA_API_TOKEN"

        private const val BIND_HOST = "127.0.0.1"
        private const val BIND_PORT_ANY = 0
        private const val BACKLOG = 16
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_CONNECTIONS = 8
        private const val MAX_TOKEN_FIELD_BYTES = 4 * 1024
        private const val MAX_REQUEST_LINE_BYTES = 64 * 1024
        private const val READ_CHUNK_BYTES = 4 * 1024

        /** shim 端对空字符串参数的编码占位符（base64("") 是空串会被字段过滤吞掉）。 */
        internal const val EMPTY_ARG_SENTINEL = "-"

        const val EXIT_OK = 0
        const val EXIT_ERROR = 1
        const val EXIT_MALFORMED = 1
        const val EXIT_UNAUTHORIZED = 1
        const val EXIT_UNKNOWN_COMMAND = 127

        private fun generateToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
