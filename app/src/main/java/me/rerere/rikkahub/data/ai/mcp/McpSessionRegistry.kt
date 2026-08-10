package me.rerere.rikkahub.data.ai.mcp

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.util.StringValues
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.ManagedWorkspaceProcess
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val TAG = "McpSessionRegistry"
private const val MAX_RECONNECT_ATTEMPTS = 5
private const val BASE_RECONNECT_DELAY_MS = 1000L
private const val MAX_RECONNECT_DELAY_MS = 30000L

/** tools/list 分页安全上限: 防止恶意/异常 server 返回无限 nextCursor 死循环 */
private const val MAX_TOOL_LIST_PAGES = 10

/** 单个 MCP Server 的全部运行时状态。 */
private class McpSession(initialConfig: McpServerConfig) {
    @Volatile
    var config: McpServerConfig = initialConfig

    @Volatile
    var client: Client? = null

    @Volatile
    var connectedConfig: McpServerConfig? = null

    /** stdio 传输对应的托管进程; 传输关闭时由会话负责回收。 */
    @Volatile
    var managedProcess: ManagedWorkspaceProcess? = null

    val lifecycleMutex = Mutex()
    var reconnectJob: Job? = null
    var reconnectAttempt: Int = 0
}

private sealed interface ConnectResult {
    data object Success : ConnectResult
    data object Stale : ConnectResult
    data object NeedsAuthorization : ConnectResult
    data object Failed : ConnectResult

    /** workspace 未 READY, 本次不连接; 不触发重连退避, 等下一次 sync/reconcile 再试。 */
    data object WaitingForWorkspace : ConnectResult
}

internal class McpClientUnavailableException(message: String) : IllegalStateException(message)

internal class McpStatusStore {
    private val _status = MutableStateFlow<Map<Uuid, McpStatus>>(emptyMap())
    val status: StateFlow<Map<Uuid, McpStatus>> = _status.asStateFlow()

    fun get(configId: Uuid): Flow<McpStatus> =
        status.map { it[configId] ?: McpStatus.Idle }.distinctUntilChanged()

    fun update(configId: Uuid, status: McpStatus) {
        _status.update { current -> current + (configId to status) }
    }

    fun remove(configId: Uuid) {
        _status.update { current -> current - configId }
    }
}

/**
 * MCP 连接运行时注册表。
 *
 * 每个 serverId 对应一个 [McpSession]，该 Session 的连接、同步、关闭和重连通过同一把 Mutex 串行执行。
 * Client 只有在 connect 与首次工具同步都成功后才对外可见。
 */
internal class McpSessionRegistry(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val httpClient: HttpClient,
    private val oauthCoordinator: McpOAuthCoordinator,
    private val statusStore: McpStatusStore,
    private val workspaceRepository: WorkspaceRepository,
) {
    private val sessions = ConcurrentHashMap<Uuid, McpSession>()

    fun getClient(configId: Uuid): Client? = sessions[configId]?.client

    /**
     * 当前真正已连接 (有 client 且状态为 Connected) 的 server id 集合。
     * 运行时工具表 (McpManager.getAllAvailableTools) 只暴露这些 server 的工具 ——
     * 断线 / Waiting / workspace 重装期间持久化配置里的旧工具仍在, 但不能注入模型。
     */
    fun getConnectedServerIds(): Set<Uuid> {
        val connected = statusStore.status.value
        return sessions.entries
            .asSequence()
            .filter { (id, session) -> session.client != null && connected[id] == McpStatus.Connected }
            .mapTo(mutableSetOf()) { it.key }
    }

    fun getStatus(configId: Uuid): Flow<McpStatus> = statusStore.get(configId)

    suspend fun reconcile(configs: List<McpServerConfig>) {
        val activeConfigs = configs
            .filter { it.commonOptions.enable && it.commonOptions.name.isNotBlank() }
            .associateBy { it.id }

        (sessions.keys - activeConfigs.keys).forEach { configId ->
            val detached = sessions.remove(configId) ?: return@forEach
            oauthCoordinator.forget(configId)
            statusStore.remove(configId)
            appScope.launch { closeSession(detached) }
        }

        activeConfigs.values.forEach { newConfig ->
            val existing = sessions[newConfig.id]
            if (existing == null) {
                val session = McpSession(newConfig)
                if (sessions.putIfAbsent(newConfig.id, session) == null) {
                    appScope.launch { addClient(newConfig) }
                }
                return@forEach
            }

            val mustReconnect = !hasSameConnectionParameters(existing.config, newConfig)
            // Written under the same lifecycleMutex connectSession/syncSession use to
            // read-modify-write session.config, so this can't land in the middle of their
            // critical section and get lost (or silently clobber a config they just wrote).
            // Not nested: reconcile runs on the settings-collector coroutine and never holds
            // this lock itself; any reconnect it triggers happens afterwards via
            // appScope.launch, outside this block, so it cannot deadlock.
            existing.lifecycleMutex.withLock { existing.config = newConfig }
            if (mustReconnect) {
                appScope.launch { addClient(newConfig) }
            }
        }
    }

    suspend fun callTool(serverId: Uuid, toolName: String, args: JsonObject): CallToolResult {
        val session = sessions[serverId]
            ?: throw McpClientUnavailableException("No MCP session for server $serverId")
        val freshConfig = oauthCoordinator.ensureFreshToken(session.config)
        if (!hasSameConnectionParameters(session.connectedConfig, freshConfig)) {
            // Written under the same lifecycleMutex connectSession/syncSession use to
            // read-modify-write session.config, so this refresh can't land in the middle
            // of their critical section and get lost. Not nested: the lock is released
            // before addClient() runs, and addClient()/connectSession() acquire it fresh
            // (this call site is not itself inside a withLock), so this cannot deadlock.
            session.lifecycleMutex.withLock { session.config = freshConfig }
            addClient(freshConfig)
        }

        val sdkClient = session.client
            ?: throw McpClientUnavailableException("MCP client $serverId is not connected")
        val config = session.connectedConfig ?: session.config
        Log.i(TAG, "Calling tool $toolName on $serverId (${config.commonOptions.name})")
        return try {
            sdkClient.callTool(
                request = CallToolRequest(
                    params = CallToolRequestParams(name = toolName, arguments = args),
                ),
                options = RequestOptions(timeout = 120.seconds),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (oauthCoordinator.needsAuthorization(config, e)) {
                statusStore.update(config.id, McpStatus.NeedsAuthorization)
            }
            throw e
        }
    }

    suspend fun addClient(configInput: McpServerConfig) {
        // SettingsStore 是配置真源。旧任务排队后可能晚于新配置执行，不能再写回旧快照。
        val desiredConfig = settingsStore.settingsFlow.value.mcpServers.find { it.id == configInput.id }
        if (desiredConfig == null) {
            removeClient(configInput)
            return
        }
        if (!desiredConfig.commonOptions.enable || desiredConfig.commonOptions.name.isBlank()) {
            removeClient(desiredConfig)
            return
        }

        val session = sessions.computeIfAbsent(desiredConfig.id) { McpSession(desiredConfig) }
        // Guarded with the same lifecycleMutex connectSession's read-modify-write of
        // session.config uses, so this fast-path config adoption can't race with (and lose
        // to, or clobber) an in-flight connectSession/syncSession for the same session. Not
        // nested: the lock is released before connectSession(...) below, which acquires it
        // fresh (this call site is not itself inside a withLock), so this cannot deadlock.
        // The write still happens before connectSession runs, so its "no reconnect needed"
        // fast path (which never re-reads requestedConfig) still adopts this config.
        session.lifecycleMutex.withLock { session.config = desiredConfig }
        connectSession(
            session = session,
            requestedConfig = desiredConfig,
            cancelPendingReconnect = true,
            forceReconnect = false,
        )
    }

    suspend fun removeClient(config: McpServerConfig) {
        val session = sessions.remove(config.id)
        oauthCoordinator.forget(config.id)
        if (session != null) closeSession(session)
        statusStore.remove(config.id)
    }

    suspend fun syncAll() {
        sessions.values.toList().forEach { session -> syncSession(session) }
    }

    /**
     * 强制对指定 server 做一次完整的断开重连 (mcp_test / 手动 resync 工具用):
     * 复用 connectSession 的 forceReconnect 路径, 并重置退避计数。
     */
    suspend fun forceResync(serverId: Uuid) {
        val config = settingsStore.settingsFlow.value.mcpServers.firstOrNull { it.id == serverId } ?: return
        val session = sessions.computeIfAbsent(serverId) { McpSession(config) }
        session.config = config
        connectSession(
            session = session,
            requestedConfig = config,
            cancelPendingReconnect = true,
            forceReconnect = true,
        )
    }

    private suspend fun connectSession(
        session: McpSession,
        requestedConfig: McpServerConfig,
        cancelPendingReconnect: Boolean,
        forceReconnect: Boolean,
    ): ConnectResult = withContext(Dispatchers.IO) {
        session.lifecycleMutex.withLock {
            if (sessions[requestedConfig.id] !== session) return@withLock ConnectResult.Stale
            if (!hasSameConnectionParameters(session.config, requestedConfig)) {
                return@withLock ConnectResult.Stale
            }

            if (cancelPendingReconnect) {
                session.reconnectJob?.cancel()
                session.reconnectJob = null
                session.reconnectAttempt = 0
            }

            val config = oauthCoordinator.ensureFreshToken(session.config)
            session.config = config
            if (!forceReconnect &&
                session.client != null &&
                hasSameConnectionParameters(session.connectedConfig, config)
            ) {
                return@withLock ConnectResult.Success
            }

            // stdio 依赖的 workspace 未就绪: 不启动进程, 关闭已有连接, 标记 WaitingForWorkspace。
            // 下次 reconcile / syncAll / mcp_test 时 workspace 若已 READY 会自动连接。
            if (config is McpServerConfig.StdioTransportServer &&
                !workspaceRepository.isStdioWorkspaceReady(config.workspaceId)
            ) {
                val idleClient = session.client
                session.client = null
                session.connectedConfig = null
                idleClient?.let { closeClient(it, config.commonOptions.name) }
                closeManagedProcess(session)
                statusStore.update(config.id, McpStatus.WaitingForWorkspace)
                return@withLock ConnectResult.WaitingForWorkspace
            }

            statusStore.update(config.id, McpStatus.Connecting)
            val oldClient = session.client
            session.client = null
            session.connectedConfig = null
            oldClient?.let { closeClient(it, config.commonOptions.name) }
            // 兜底: 即使旧 transport 的 onClose 没有触发, 也要回收旧的 stdio 进程
            closeManagedProcess(session)

            val sdkClient = createSdkClient(config)
            try {
                val transport = createTransport(config, session)
                installTransportCallbacks(config, sdkClient, transport, session)

                sdkClient.connect(transport)
                val syncedConfig = syncTools(session, sdkClient, config)
                if (sessions[config.id] !== session ||
                    !hasSameConnectionParameters(config, syncedConfig)
                ) {
                    closeClient(sdkClient, config.commonOptions.name)
                    closeManagedProcess(session)
                    return@withLock ConnectResult.Stale
                }

                session.config = syncedConfig
                session.connectedConfig = syncedConfig
                session.client = sdkClient
                session.reconnectAttempt = 0
                statusStore.update(config.id, McpStatus.Connected)
                Log.i(TAG, "Connected MCP server ${config.id} (${config.commonOptions.name})")
                ConnectResult.Success
            } catch (e: CancellationException) {
                closeClient(sdkClient, config.commonOptions.name)
                closeManagedProcess(session)
                throw e
            } catch (e: Exception) {
                closeClient(sdkClient, config.commonOptions.name)
                closeManagedProcess(session)
                Log.e(TAG, "Failed to connect MCP server ${config.id}", e)
                if (oauthCoordinator.needsAuthorization(config, e)) {
                    statusStore.update(config.id, McpStatus.NeedsAuthorization)
                    ConnectResult.NeedsAuthorization
                } else {
                    statusStore.update(config.id, McpStatus.Error.from(e))
                    ConnectResult.Failed
                }
            }
        }
    }

    private suspend fun syncSession(session: McpSession) {
        val config = session.config
        if (session.client == null) {
            addClient(config)
            return
        }

        var reconnectConfig: McpServerConfig? = null
        withContext(Dispatchers.IO) {
            session.lifecycleMutex.withLock {
                val sdkClient = session.client ?: return@withLock
                val connectedConfig = session.connectedConfig ?: return@withLock
                statusStore.update(config.id, McpStatus.Connecting)
                try {
                    val syncedConfig = syncTools(session, sdkClient, session.config)
                    session.config = syncedConfig
                    if (hasSameConnectionParameters(connectedConfig, syncedConfig)) {
                        session.connectedConfig = syncedConfig
                        statusStore.update(config.id, McpStatus.Connected)
                    } else {
                        reconnectConfig = syncedConfig
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (oauthCoordinator.needsAuthorization(config, e)) {
                        statusStore.update(config.id, McpStatus.NeedsAuthorization)
                    } else {
                        statusStore.update(config.id, McpStatus.Error.from(e))
                    }
                }
            }
        }
        reconnectConfig?.let { addClient(it) }
    }

    private suspend fun syncTools(
        session: McpSession,
        sdkClient: Client,
        connectionConfig: McpServerConfig,
    ): McpServerConfig {
        val serverTools = listAllTools(sdkClient)
        Log.i(TAG, "Synced ${serverTools.size} tools from ${connectionConfig.id}")
        var updatedConfig = connectionConfig
        settingsStore.update { old ->
            old.copy(
                mcpServers = old.mcpServers.map { storedConfig ->
                    if (storedConfig.id != connectionConfig.id) return@map storedConfig
                    val tools = mergeTools(storedConfig.commonOptions.tools, serverTools)
                    storedConfig.clone(commonOptions = storedConfig.commonOptions.copy(tools = tools))
                        .also { updatedConfig = it }
                }
            )
        }
        session.config = updatedConfig
        return updatedConfig
    }

    /**
     * 拉取 server 的全部工具, 自动翻页 (ListToolsResult.nextCursor)。
     * 翻页循环是防环的: 重复 cursor 立即失败, 超过 MAX_TOOL_LIST_PAGES 页仍有 nextCursor
     * 时明确抛错 (由调用方转成 Error 状态) 而不是静默截断, 防止模型拿到不完整工具表。
     */
    private suspend fun listAllTools(sdkClient: Client): List<Tool> =
        paginateTools { cursor ->
            val page = if (cursor == null) {
                sdkClient.listTools()
            } else {
                sdkClient.listTools(ListToolsRequest(PaginatedRequestParams(cursor = cursor)))
            }
            ToolListPage(tools = page.tools, nextCursor = page.nextCursor)
        }

    private fun installTransportCallbacks(
        config: McpServerConfig,
        sdkClient: Client,
        transport: AbstractTransport,
        session: McpSession,
    ) {
        // stdio 进程的生命周期挂在 transport 的 onClose 上: 无论传输是主动关闭
        // (closeClient/closeSession) 还是进程自然退出导致 EOF, 都在这里回收进程。
        // 这里捕获"本 transport 安装时挂到 session 上的那个进程", 并只在 session 当前
        // 仍持有同一进程时才回收 —— 旧传输延迟触发的 onClose 绝不能误杀重连后的新进程
        // (重连临界区 session.client 为 null, 不能用作唯一判断依据)。
        val installedProcess = session.managedProcess
        transport.onClose {
            if (session.managedProcess === installedProcess) {
                session.managedProcess = null
                installedProcess?.close()
            }
            Log.i(TAG, "Transport closed for ${config.id} (${config.commonOptions.name})")
            requestReconnect(config.id, sdkClient)
        }
        transport.onError { error ->
            Log.e(TAG, "Transport error for ${config.id}: ${error.message}")
            if (!isSseStreamGiveUpError(error)) requestReconnect(config.id, sdkClient)
        }
    }

    /** 无条件关闭并清空 session 的托管进程 (会话拆除 / 重连前清理用)。 */
    private fun closeManagedProcess(session: McpSession) {
        val process = session.managedProcess ?: return
        session.managedProcess = null
        process.close()
    }

    /** 合并重复的 onError/onClose 通知，并保证每个 Session 最多只有一个重连任务。 */
    private fun requestReconnect(
        configId: Uuid,
        sourceClient: Client?,
        retryAfterFailure: Boolean = false,
    ) {
        appScope.launch {
            val session = sessions[configId] ?: return@launch
            session.lifecycleMutex.withLock {
                if (sessions[configId] !== session) return@withLock
                if (sourceClient != null && session.client !== sourceClient) return@withLock
                if (!retryAfterFailure && statusStore.status.value[configId] != McpStatus.Connected) {
                    return@withLock
                }
                if (session.reconnectJob?.isActive == true) return@withLock

                val attempt = session.reconnectAttempt + 1
                if (attempt > MAX_RECONNECT_ATTEMPTS) {
                    val failedClient = session.client
                    session.client = null
                    session.connectedConfig = null
                    failedClient?.let { closeClient(it, session.config.commonOptions.name) }
                    closeManagedProcess(session)
                    statusStore.update(configId, McpStatus.Error("连接断开，已达最大重连次数"))
                    return@withLock
                }

                session.reconnectAttempt = attempt
                statusStore.update(configId, McpStatus.Reconnecting(attempt, MAX_RECONNECT_ATTEMPTS))
                session.reconnectJob = appScope.launch {
                    reconnectAfterDelay(session, calculateBackoffDelay(attempt))
                }
            }
        }
    }

    private suspend fun reconnectAfterDelay(session: McpSession, delayMs: Long) {
        val runningJob = currentCoroutineContext().job
        var retry = false
        try {
            delay(delayMs)
            val latestConfig = settingsStore.settingsFlow.value.mcpServers.find {
                it.id == session.config.id &&
                    it.commonOptions.enable &&
                    it.commonOptions.name.isNotBlank()
            } ?: return
            session.config = latestConfig
            retry = connectSession(
                session = session,
                requestedConfig = latestConfig,
                cancelPendingReconnect = false,
                forceReconnect = true,
            ) == ConnectResult.Failed
        } catch (e: CancellationException) {
            throw e
        } finally {
            withContext(NonCancellable) {
                session.lifecycleMutex.withLock {
                    if (session.reconnectJob === runningJob) session.reconnectJob = null
                }
            }
        }

        if (retry) requestReconnect(session.config.id, sourceClient = null, retryAfterFailure = true)
    }

    private suspend fun closeSession(session: McpSession) = withContext(Dispatchers.IO) {
        session.lifecycleMutex.withLock {
            session.reconnectJob?.cancel()
            session.reconnectJob = null
            session.reconnectAttempt = 0
            val sdkClient = session.client
            session.client = null
            session.connectedConfig = null
            sdkClient?.let { closeClient(it, session.config.commonOptions.name) }
            closeManagedProcess(session)
        }
    }

    private suspend fun closeClient(client: Client, serverName: String) {
        runCatching { client.close() }
            .onFailure { Log.w(TAG, "Failed to close MCP client $serverName", it) }
    }

    private fun createSdkClient(config: McpServerConfig): Client = Client(
        clientInfo = Implementation(name = config.commonOptions.name, version = "1.0")
    )

    /** 检查 stdio 配置依赖的 workspace 是否已就绪 (存在且 shellStatus == READY)。 */
    private suspend fun isStdioWorkspaceReady(workspaceId: String): Boolean =
        workspaceRepository.isStdioWorkspaceReady(workspaceId)

    private suspend fun createTransport(
        config: McpServerConfig,
        session: McpSession,
    ): AbstractTransport = when (config) {
        is McpServerConfig.SseTransportServer -> SseClientTransport(
            urlString = config.url,
            client = httpClient,
            requestBuilder = { appendResolvedHeaders(config) },
        )

        is McpServerConfig.StreamableHTTPServer -> StreamableHttpClientTransport(
            url = config.url,
            client = httpClient,
            requestBuilder = { appendResolvedHeaders(config) },
        )

        is McpServerConfig.StdioTransportServer -> createStdioTransport(config, session)
    }

    /**
     * 在 workspace rootfs 内启动 stdio MCP server 进程并包装为 SDK 传输。
     * 进程挂到 [session.managedProcess], 由传输 onClose 统一回收。
     */
    private suspend fun createStdioTransport(
        config: McpServerConfig.StdioTransportServer,
        session: McpSession,
    ): StdioClientTransport {
        // 纵深防御: 从备份/导入恢复的 settings.json 也可能带恶意 command, 启动前再过一次
        // argv-aware 硬线检查 (逐元素 + shell -c 脚本识别, 防 `/bin/sh -c reboot` 拼接绕过)
        val blocked = HardlineCommandGuard.checkCommandArgv(config.command, config.args)
        if (blocked != null) {
            throw IllegalStateException("stdio command blocked by hardline guard: $blocked")
        }
        // 启动 + 向 session 安装所有权是不可取消的原子交接: 进程一旦在 WorkspaceManager
        // 注册, 即使外层协程在结果交还前被取消, 也必须先挂到 session.managedProcess 让
        // 会话的取消/拆除路径能回收它, 否则调用方拿不到引用而泄漏进程。
        val process = withContext(NonCancellable) {
            workspaceRepository.startManagedMcpProcess(
                workspaceId = config.workspaceId,
                command = config.command,
                args = config.args,
                cwd = config.cwd,
                env = config.env,
            ).also { session.managedProcess = it }
        }
        return StdioClientTransport(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
            error = process.errorStream.asSource().buffered(),
        )
    }

    private fun HttpRequestBuilder.appendResolvedHeaders(config: McpServerConfig) {
        headers.appendAll(StringValues.build {
            config.resolvedHeaders().forEach { (name, value) -> append(name, value) }
        })
    }

    private fun calculateBackoffDelay(attempt: Int): Long {
        val exponentialDelay = BASE_RECONNECT_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(10))
        return exponentialDelay.coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private fun isSseStreamGiveUpError(error: Throwable): Boolean {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        return message.contains("Maximum reconnection attempts exceeded", ignoreCase = true)
    }
}

/** 只包含会影响实际连接的字段；工具开关和 Schema 变化不会触发重连。 */
internal data class McpConnectionKey(
    val transportType: String,
    val serverUrl: String,
    val clientName: String,
    val headers: List<Pair<String, String>>,
    /** stdio 传输专用: argv 变化会触发重连 */
    val stdioArgs: List<String> = emptyList(),
    /** stdio 传输专用: 工作目录变化会触发重连 */
    val stdioCwd: String = "",
    /** stdio 传输专用: 环境变量变化会触发重连 (key 排序后的稳定视图) */
    val stdioEnv: List<Pair<String, String>> = emptyList(),
)

internal fun McpServerConfig.connectionKey(): McpConnectionKey = McpConnectionKey(
    transportType = when (this) {
        is McpServerConfig.SseTransportServer -> "sse"
        is McpServerConfig.StreamableHTTPServer -> "streamable_http"
        is McpServerConfig.StdioTransportServer -> "stdio"
    },
    serverUrl = serverUrl,
    clientName = commonOptions.name,
    headers = resolvedHeaders(),
    stdioArgs = (this as? McpServerConfig.StdioTransportServer)?.args ?: emptyList(),
    stdioCwd = (this as? McpServerConfig.StdioTransportServer)?.cwd ?: "",
    stdioEnv = (this as? McpServerConfig.StdioTransportServer)
        ?.env
        ?.entries
        ?.sortedBy { it.key }
        ?.map { it.key to it.value }
        ?: emptyList(),
)

private fun hasSameConnectionParameters(
    left: McpServerConfig?,
    right: McpServerConfig?,
): Boolean = left != null && right != null && left.connectionKey() == right.connectionKey()

private fun McpServerConfig.resolvedHeaders(): List<Pair<String, String>> {
    val base = commonOptions.headers
    val token = commonOptions.oauth?.takeIf { it.enabled }?.accessToken
    val hasAuthorization = base.any { it.first.equals("Authorization", ignoreCase = true) }
    return if (!token.isNullOrBlank() && !hasAuthorization) {
        base + ("Authorization" to "Bearer $token")
    } else {
        base
    }
}

/** tools/list 翻页的一个页面。 */
internal data class ToolListPage(
    val tools: List<Tool>,
    val nextCursor: String?,
)

/**
 * 防环的翻页循环: 维护 seenCursors, 重复 cursor 立即失败; 达到 [MAX_TOOL_LIST_PAGES]
 * 页上限仍有 nextCursor 时抛错 (明确标记而非静默 partial)。抽成纯函数便于单测。
 */
internal suspend fun paginateTools(fetchPage: suspend (cursor: String?) -> ToolListPage): List<Tool> {
    val seenCursors = HashSet<String>()
    var cursor: String? = null
    val tools = mutableListOf<Tool>()
    var pages = 0
    while (true) {
        if (pages >= MAX_TOOL_LIST_PAGES) {
            throw IllegalStateException(
                "MCP server paginated beyond $MAX_TOOL_LIST_PAGES pages; aborting tools/list"
            )
        }
        if (cursor != null && !seenCursors.add(cursor)) {
            throw IllegalStateException(
                "MCP server repeated a pagination cursor (${cursor.take(32)}…); aborting tools/list"
            )
        }
        val page = fetchPage(cursor)
        tools += page.tools
        cursor = page.nextCursor
        pages++
        if (cursor.isNullOrBlank()) break
    }
    return tools
}

internal fun mergeTools(storedTools: List<McpTool>, serverTools: List<Tool>): List<McpTool> {
    val toolsByName = storedTools.associateBy { it.name }
    // 同一 server 返回重复名工具时保留第一个, 丢弃后续重复项, 避免运行时工具表出现同名冲突
    val seenNames = HashSet<String>()
    return serverTools.mapNotNull { serverTool ->
        if (!seenNames.add(serverTool.name)) {
            return@mapNotNull null
        }
        toolsByName[serverTool.name]?.copy(
            description = serverTool.description,
            inputSchema = serverTool.inputSchema.toSchema(),
        ) ?: McpTool(
            name = serverTool.name,
            description = serverTool.description,
            enable = true,
            inputSchema = serverTool.inputSchema.toSchema(),
        )
    }
}

private fun ToolSchema.toSchema(): InputSchema =
    InputSchema.Obj(properties = properties ?: JsonObject(emptyMap()), required = required)
