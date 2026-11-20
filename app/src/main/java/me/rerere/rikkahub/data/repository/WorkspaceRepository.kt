package me.rerere.rikkahub.data.repository

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.ExportedDownloadResult
import me.rerere.rikkahub.data.files.WorkspacePublicExport
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
import me.rerere.rikkahub.data.preferences.WorkspaceSharedDirPreferences
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.BackgroundStatus
import me.rerere.workspace.ManagedWorkspaceProcess
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceSdcard
import me.rerere.workspace.WorkspaceSdcardMode
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.readResult
import me.rerere.workspace.terminateProcessTree
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
    private val context: Context? = null,
    private val sharedDirPreferences: WorkspaceSharedDirPreferences? = null,
) {
    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            val dir = manager.workspaceDir(workspace.root)
            if (!dir.exists()) {
                // 目录缺失时不删除记录(例如恢复备份后工作区文件未随数据库一起恢复),
                // 仅标记为 BROKEN 以保留记录与助手绑定, 避免误删用户工作区
                Log.w(TAG, "Workspace directory missing, marking as broken: id=${workspace.id}, root=${workspace.root}")
                if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                }
                continue
            }
            val statusName = workspace.shellStatus
            if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name)
                && !manager.hasRootfs(workspace.root)
            ) {
                Log.w(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    suspend fun getAll(): List<WorkspaceEntity> = withContext(Dispatchers.IO) { dao.getAll() }

    suspend fun create(name: String): WorkspaceEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
        }
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(workspace.root)
        dao.upsert(workspace)
        return workspace
    }

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        return dao.updateName(id, finalName, System.currentTimeMillis()) > 0
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean =
        mountLockFor(id).withLock {
            val workspace = dao.getById(id) ?: return@withLock false
            val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
            dao.updateToolApprovals(id, JsonInstant.encodeToString(overrides), System.currentTimeMillis()) > 0
        }

    data class TerminalMountSnapshot(
        val generation: Long,
        val bindMounts: List<WorkspaceBindMount>,
    )

    private val mountLifecycleLocks = ConcurrentHashMap<String, Mutex>()
    private fun mountLockFor(id: String): Mutex = mountLifecycleLocks.computeIfAbsent(id) { Mutex() }

    /** 设置工作区的共享存储挂载模式 (NONE / READ_ONLY / READ_WRITE)。 */
    suspend fun setSdcardMode(id: String, mode: WorkspaceSdcardMode): Boolean = mountLockFor(id).withLock {
        val workspace = dao.getById(id) ?: return@withLock false
        val changed = workspace.sdcardModeEnum() != mode
        withContext(NonCancellable) {
            if (changed) {
                dao.updateSdcardMode(id, mode.name, System.currentTimeMillis())
            }
            // A committed restriction and teardown form one operation under the mount lock.
            // Reapplying a restricted mode also reclaims processes left by an earlier failed attempt.
            if (changed || mode != WorkspaceSdcardMode.READ_WRITE) {
                teardownProcessesForWorkspaceLocked(id, workspace.root)
            }
        }
        true
    }

    /** Start a prepared PTY under the same lock that commits mount policy changes. */
    internal suspend fun startTerminalWithMountPolicy(id: String, start: () -> Boolean): Boolean =
        mountLockFor(id).withLock { start() }

    /** 当前工作区的共享存储挂载模式 (workspace 不存在时按 NONE 处理)。 */
    suspend fun sdcardMode(id: String): WorkspaceSdcardMode =
        dao.getById(id)?.sdcardModeEnum() ?: WorkspaceSdcardMode.NONE

    fun sharedDirEnabledFlow(id: String): Flow<Boolean> =
        sharedDirPreferences?.isEnabledFlow(id) ?: flowOf(false)

    suspend fun isSharedDirEnabled(id: String): Boolean =
        sharedDirPreferences?.isEnabled(id) ?: false

    suspend fun setSharedDirEnabled(id: String, enabled: Boolean) = mountLockFor(id).withLock {
        val workspace = dao.getById(id) ?: return@withLock
        withContext(NonCancellable) {
            sharedDirPreferences?.setEnabled(id, enabled)
            teardownProcessesForWorkspaceLocked(id, workspace.root)
        }
    }

    /**
     * Reclaims active PRoot processes if shared storage permission was revoked externally while
     * `/shared` or `/sdcard` was enabled, preventing old sessions from holding stale mounts.
     */
    suspend fun teardownProcessesIfSharedAccessRevoked(id: String) = mountLockFor(id).withLock {
        val ctx = context ?: return@withLock
        if (PermissionHelper.hasAllFilesAccess(ctx)) return@withLock
        val workspace = dao.getById(id) ?: return@withLock
        if (isSharedDirEnabled(id) || workspace.sdcardModeEnum() != WorkspaceSdcardMode.NONE) {
            teardownProcessesForWorkspaceLocked(id, workspace.root)
        }
    }

    /**
     * Global permission-revocation sweep invoked from Application lifecycle resume so revoking
     * shared-storage permission from OS Settings tears down stale PRoot sessions even when the
     * user never re-opens WorkspaceDetailPage.
     */
    suspend fun reconcileAllSharedAccessRevocations() {
        val ctx = context ?: return
        if (PermissionHelper.hasAllFilesAccess(ctx)) return
        val workspaces = runCatching { dao.getAll() }.getOrDefault(emptyList())
        for (workspace in workspaces) {
            mountLockFor(workspace.id).withLock {
                if (isSharedDirEnabled(workspace.id) || workspace.sdcardModeEnum() != WorkspaceSdcardMode.NONE) {
                    teardownProcessesForWorkspaceLocked(workspace.id, workspace.root)
                }
            }
        }
    }

    private suspend fun teardownProcessesForWorkspaceLocked(id: String, root: String) = withContext(NonCancellable) {
        me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalRegistry.bumpGenerationAndFinishAll(id)
        withContext(Dispatchers.IO) {
            runCatching { manager.killAllForeground(root) }
            runCatching { manager.killAllBackground(root) }
            runCatching { manager.closeAllManagedProcesses(root) }
        }
    }

    /**
     * Returns `true` when the workspace has `/shared` enabled AND the device has shared-storage
     * write access granted so the host directory under `Documents/RikkaHub/ws-<id>/` is usable.
     */
    suspend fun isSharedDirMounted(id: String): Boolean {
        val ctx = context ?: return false
        if (!isSharedDirEnabled(id)) return false
        return PermissionHelper.hasAllFilesAccess(ctx)
    }

    private suspend fun extraBindMountsFor(workspace: WorkspaceEntity): List<WorkspaceBindMount> {
        val ctx = context ?: return emptyList()
        if (!isSharedDirEnabled(workspace.id)) return emptyList()
        if (!PermissionHelper.hasAllFilesAccess(ctx)) return emptyList()
        val hostDir = WorkspaceSharedDirPreferences.hostDir(workspace.name, workspace.id)
        runCatching { hostDir.mkdirs() }
        if (!hostDir.isDirectory) return emptyList()
        @Suppress("DEPRECATION")
        val externalRoot = Environment.getExternalStorageDirectory()
        val extPath = externalRoot.toPath().toAbsolutePath().normalize()
        val hostPath = hostDir.toPath().toAbsolutePath().normalize()
        val (anchorRoot, anchorRel) = if (hostPath.startsWith(extPath)) {
            externalRoot to extPath.relativize(hostPath).toString().replace(java.io.File.separatorChar, '/').trim('/')
        } else {
            null to ""
        }
        return listOf(
            WorkspaceBindMount(
                source = hostDir,
                target = WorkspaceSharedDirPreferences.MOUNT_TARGET,
                trustedAnchorRoot = anchorRoot,
                anchorRelativePath = anchorRel,
            )
        )
    }

    /**
     * Effective bind mounts for interactive terminal sessions.
     *
     * Because PRoot bind mounts are kernel-writable and an interactive bash session bypasses the
     * AI tool-layer read-only guard, `/sdcard` is ONLY mounted into the interactive terminal when
     * `sdcardMode == READ_WRITE` (never mounted in `READ_ONLY` mode).
     */
    suspend fun terminalBindMountsSnapshot(id: String): TerminalMountSnapshot = mountLockFor(id).withLock {
        val generation = me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalRegistry.currentGeneration(id)
        val workspace = dao.getById(id) ?: return@withLock TerminalMountSnapshot(generation, emptyList())
        val extra = extraBindMountsFor(workspace)
        val terminalSdcardMode = effectiveProotSdcardMode(workspace.sdcardModeEnum())
        TerminalMountSnapshot(
            generation = generation,
            bindMounts = manager.effectiveBindMounts(terminalSdcardMode, extra),
        )
    }

    suspend fun terminalBindMounts(id: String): List<WorkspaceBindMount> =
        terminalBindMountsSnapshot(id).bindMounts

    private fun requireRootfsWritableForPolicy(
        mode: WorkspaceSdcardMode,
        extraBindMounts: List<WorkspaceBindMount>,
        normalizedPath: String,
        displayPath: String,
    ) {
        if (WorkspaceSdcard.isWithin(normalizedPath)) {
            when (mode) {
                WorkspaceSdcardMode.READ_ONLY -> error(
                    "Cannot write to $displayPath: the workspace's shared-storage (/sdcard) mount is " +
                        "read-only. Ask the user to switch the workspace sdcard mode to " +
                        "read-write in workspace settings if this write is intended."
                )
                WorkspaceSdcardMode.NONE -> error(
                    "Cannot write to $displayPath: shared-storage (/sdcard) is not mounted for this workspace."
                )
                WorkspaceSdcardMode.READ_WRITE -> Unit
            }
        }
        val sharedTarget = WorkspaceSharedDirPreferences.MOUNT_TARGET
        if (normalizedPath == sharedTarget || normalizedPath.startsWith("$sharedTarget/")) {
            if (extraBindMounts.none { it.target == sharedTarget }) {
                error("Cannot write to $displayPath: /shared is not mounted for this workspace.")
            }
        }
    }

    /**
     * 文件 API 层的 sdcard 写入门禁: READ_ONLY 模式下, 指向 /sdcard 挂载区的写入
     * 一律拒绝。先做词法规范化折叠 `..` 和 `//`，防止路径穿越绕过。
     */
    suspend fun requireRootfsWritable(id: String, path: String) {
        val workspace = dao.getById(id) ?: return
        val mode = workspace.sdcardModeEnum()
        if (mode != WorkspaceSdcardMode.READ_ONLY) return
        val normalized = runCatching { WorkspacePublicExport.normalizeRootfsPath(path) }
            .getOrElse { path.trim().trimEnd('/').ifBlank { "/" } }
        if (WorkspaceSdcard.isWithin(normalized)) {
            error(
                "Cannot write to $path: the workspace's shared-storage (/sdcard) mount is " +
                    "read-only. Ask the user to switch the workspace sdcard mode to " +
                    "read-write in workspace settings if this write is intended."
            )
        }
    }

    /**
     * Writes UTF-8 text to a Rootfs absolute path directly under the resolved host volume using
     * FD-relative `O_NOFOLLOW` traversal, serialized under [mountLockFor] against policy changes
     * (`setSdcardMode`, `setSharedDirEnabled`, permission revocation, and `delete`) using ONE
     * authoritative workspace snapshot for policy check, bind-mount resolution, and open/write.
     */
    suspend fun writeRootfsText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val normalized = WorkspacePublicExport.normalizeRootfsPath(path)
        mountLockFor(id).withLock {
            val workspace = dao.getById(id) ?: error("Workspace not found: $id")
            val mode = workspace.sdcardModeEnum()
            val extra = extraBindMountsFor(workspace)
            requireRootfsWritableForPolicy(
                mode = mode,
                extraBindMounts = extra,
                normalizedPath = normalized,
                displayPath = path,
            )
            manager.writeRootfsTextNoFollow(
                root = workspace.root,
                path = normalized,
                text = text,
                overwrite = overwrite,
                sdcardMode = mode,
                extraBindMounts = extra,
            )
        }
    }

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        // 重装会替换整个 rootfs: RootfsInstaller.install 在 WorkspaceManager 的进程
        // 生命周期锁内先 killAllBackground + closeAllManagedProcesses, 再解压替换,
        // 安装全程与 startBackground / startManagedProcess / deleteWorkspace 互斥,
        // 不会残留持有旧 rootfs fd 的进程或安装到一半的孤儿目录。
        try {
            // runInterruptible 让协程取消转成线程中断, 打断 install 内阻塞的下载/解压循环
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(workspace.root, url, onProgress)
            }
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            Log.e(TAG, "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}, url=$url", e)
            updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            throw e
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.listFiles(workspace.root, path, area)
    }

    suspend fun readText(
        id: String,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.readText(workspace.root, path)
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.writeText(workspace.root, path, text, overwrite)
    }

    /**
     * 读取文本用于应用内预览/编辑, 支持两个存储区.
     * FILES 区走 [WorkspaceManager.readText] (自带大小保护); LINUX 区通过 exportFile 读入内存,
     * 因此这里对 LINUX 区显式做大小限制, 避免大文件撑爆内存.
     */
    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        when (area) {
            WorkspaceStorageArea.FILES -> manager.readText(workspace.root, path)
            WorkspaceStorageArea.LINUX -> {
                val size = manager.fileSize(workspace.root, path, area)
                require(size <= MAX_PREVIEW_BYTES) {
                    "文件过大, 无法预览 (${size} bytes)"
                }
                ByteArrayOutputStream().use { out ->
                    manager.exportFile(workspace.root, path, area, out)
                    out.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.importFile(workspace.root, destinationPath, area, fileName, inputStream)
    }

    suspend fun fileSize(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.fileSize(workspace.root, path, area)
    }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.exportFile(workspace.root, path, area, outputStream)
    }

    /** 按 Rootfs 内绝对路径读取文件大小, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun rootfsFileSize(
        id: String,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val normalized = WorkspacePublicExport.normalizeRootfsPath(path)
        mountLockFor(id).withLock {
            val workspace = dao.getById(id) ?: error("Workspace not found: $id")
            val extra = extraBindMountsFor(workspace)
            manager.ensureWorkspace(workspace.root)
            manager.rootfsFileSize(workspace.root, normalized, workspace.sdcardModeEnum(), extra)
        }
    }

    /** 按 Rootfs 内绝对路径导出文件内容, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun exportRootfsFile(
        id: String,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val normalized = WorkspacePublicExport.normalizeRootfsPath(path)
        mountLockFor(id).withLock {
            val workspace = dao.getById(id) ?: error("Workspace not found: $id")
            val extra = extraBindMountsFor(workspace)
            manager.ensureWorkspace(workspace.root)
            manager.exportRootfsFile(workspace.root, normalized, outputStream, workspace.sdcardModeEnum(), extra)
        }
    }

    /**
     * Exports a file from the workspace's [area] into Android's public Downloads folder
     * (`Download/RikkaHub/<workspace>/`). Uses MediaStore.Downloads on Android 10+ (zero
     * storage permissions required).
     */
    suspend fun exportFileToDownloads(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        customFileName: String? = null,
    ): ExportedDownloadResult = withContext(Dispatchers.IO) {
        val ctx = requireNotNull(context) { "Android context is required for Downloads export" }
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        val fileName = customFileName?.takeIf { it.isNotBlank() }
            ?: path.substringAfterLast('/').ifBlank { "export.bin" }
        WorkspacePublicExport.exportToDownloads(
            context = ctx,
            workspaceName = workspace.name,
            fileName = fileName,
        ) { out ->
            manager.exportFile(workspace.root, path, area, out)
        }
    }

    /**
     * Exports a `/workspace/...` file to Android's public Downloads folder. Enforces lexical
     * normalization and containment inside `/workspace/` so rootfs or other bind mounts cannot
     * be exported via this path.
     */
    suspend fun exportWorkspacePathToDownloads(
        id: String,
        rootfsPath: String,
        customFileName: String? = null,
    ): ExportedDownloadResult = withContext(Dispatchers.IO) {
        val normalized = WorkspacePublicExport.normalizeRootfsPath(rootfsPath)
        require(normalized.startsWith("${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/")) {
            "Path must be a file inside /workspace/: $rootfsPath"
        }
        val relPath = normalized.removePrefix("${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/")
        require(relPath.isNotBlank()) { "Cannot export the /workspace directory itself" }
        exportFileToDownloads(id, WorkspaceStorageArea.FILES, relPath, customFileName)
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            manager.deleteFile(workspace.root, path, recursive, area)
        }
        return deleted
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.moveFile(workspace.root, source, target, overwrite)
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        // Bail out before entering NonCancellable if caller is already cancelled.
        currentCoroutineContext().ensureActive()

        // Hold mountLockFor(id) across policy read AND process launch+registration, assigning
        // `tracked` inside an outer `try/finally` BEFORE exiting `withContext(NonCancellable + IO)`.
        // If the caller is cancelled while launch is in progress, resumption throws
        // CancellationException into `finally`, which ALWAYS sees `tracked`, unregisters it,
        // and terminates the entire process tree (zero leaked foreground processes).
        var tracked: WorkspaceManager.TrackedForegroundCommand? = null
        return try {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    mountLockFor(id).withLock {
                        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
                        val effectiveSdcardMode = effectiveProotSdcardMode(workspace.sdcardModeEnum())
                        val extra = extraBindMountsFor(workspace)
                        manager.ensureWorkspace(workspace.root)
                        tracked = manager.startTrackedForegroundCommand(
                            root = workspace.root,
                            command = command,
                            cwd = cwd,
                            timeoutMillis = timeoutMillis,
                            stdin = stdin,
                            sdcardMode = effectiveSdcardMode,
                            extraBindMounts = extra,
                        )
                    }
                }
            } catch (e: IllegalStateException) {
                val msg = e.message.orEmpty()
                if (
                    msg.startsWith("Rootfs is not installed") ||
                    msg.startsWith("proot executable not found") ||
                    msg.startsWith("proot loader not found")
                ) {
                    return WorkspaceCommandResult(exitCode = 127, stdout = "", stderr = msg)
                }
                throw e
            }
            val launched = checkNotNull(tracked) { "Foreground command failed to initialize" }
            // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor 并杀掉进程
            runInterruptible(Dispatchers.IO) {
                launched.process.readResult(timeoutMillis, stdin)
            }
        } finally {
            val launched = tracked
            if (launched != null) {
                withContext(NonCancellable + Dispatchers.IO) {
                    manager.finishTrackedForegroundCommand(launched)
                    if (launched.process.isAlive) {
                        runCatching { terminateProcessTree(launched.process) }
                    }
                }
            }
        }
    }

    suspend fun startBackground(
        id: String,
        command: String,
        cwd: String = "",
    ): BackgroundStatus {
        // Hold the workspace mount lifecycle lock across snapshot + process launch/registration
        // so a concurrent /shared disable or /sdcard downgrade can never miss a newly launched
        // background daemon, and never mount writable /sdcard into long-lived background daemons
        // when sdcardMode is READ_ONLY (matches interactive terminal isolation).
        return withContext(NonCancellable + Dispatchers.IO) {
            mountLockFor(id).withLock {
                val workspace = dao.getById(id) ?: error("Workspace not found: $id")
                val effectiveSdcardMode = effectiveProotSdcardMode(workspace.sdcardModeEnum())
                val extra = extraBindMountsFor(workspace)
                manager.ensureWorkspace(workspace.root)
                manager.startBackground(workspace.root, command, cwd, effectiveSdcardMode, extra)
            }
        }
    }

    /** stdio MCP 依赖的 workspace 是否就绪: 存在且 shellStatus == READY (rootfs 已安装)。 */
    suspend fun isStdioWorkspaceReady(workspaceId: String): Boolean {
        if (workspaceId.isBlank()) return false
        val workspace = dao.getById(workspaceId) ?: return false
        return workspace.shellStatus == WorkspaceShellStatus.READY.name
    }

    /**
     * 在 [workspaceId] 的 rootfs 内启动一个长生命周期双工进程 (stdio MCP server)。
     * 要求 workspace 存在且 shellStatus == READY, 否则抛 [IllegalStateException]。
     * 返回的 [ManagedWorkspaceProcess] 由调用方负责读写与 close。
     */
    suspend fun startManagedMcpProcess(
        workspaceId: String,
        command: String,
        args: List<String>,
        cwd: String,
        env: Map<String, String>,
    ): ManagedWorkspaceProcess {
        val workspace = dao.getById(workspaceId) ?: error("Workspace not found: $workspaceId")
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            throw IllegalStateException(
                "Workspace ${workspace.name} is not ready (shellStatus=${workspace.shellStatus}); " +
                    "install the rootfs before starting a stdio MCP server"
            )
        }
        // 启动+注册是不可取消的原子交接: runInterruptible 会在进程已启动并注册到
        // WorkspaceManager 之后、结果交还调用方之前, 因外层协程取消抛出 CancellationException,
        // 调用方永远拿不到 ManagedWorkspaceProcess, session.managedProcess 不会被设置, 进程
        // 泄漏。NonCancellable 让启动+注册要么完整发生, 要么未发生; Dispatchers.IO 仍只负责
        // 把阻塞的进程启动挪到后台线程。
        return withContext(NonCancellable + Dispatchers.IO) {
            manager.startManagedProcess(workspace.root, command, args, cwd, env)
        }
    }

    suspend fun backgroundStatus(id: String, taskId: String): BackgroundStatus? {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return withContext(Dispatchers.IO) {
            manager.backgroundStatus(workspace.root, taskId)
        }
    }

    suspend fun listBackground(id: String): List<BackgroundStatus> {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return withContext(Dispatchers.IO) {
            manager.listBackground(workspace.root)
        }
    }

    suspend fun killBackground(id: String, taskId: String): Boolean {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return withContext(Dispatchers.IO) {
            manager.killBackground(workspace.root, taskId)
        }
    }

    suspend fun delete(id: String): Boolean = mountLockFor(id).withLock {
        val workspace = dao.getById(id) ?: return@withLock false
        // Bump mount generation + terminate any active interactive terminal sessions BEFORE removing files.
        me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalRegistry.bumpGenerationAndFinishAll(id)
        // 先杀进程 + 删目录, 成功后再删 DB 行: 顺序反过来时文件删除失败会留下
        // 无法从 UI 重试删除的孤儿目录
        val deleted = withContext(Dispatchers.IO) {
            manager.deleteWorkspace(workspace.root)
        }
        if (!deleted && manager.workspaceDir(workspace.root).exists()) {
            Log.w(TAG, "Workspace directory not fully deleted, keeping DB row for retry: id=$id")
            return@withLock false
        }
        dao.deleteById(id)
        sharedDirPreferences?.removeWorkspace(id)
        cleanupAssistantReferences(id)
        // workspace 已删除: 引用它的 stdio MCP 配置从此不可能连接, 禁用它们, 避免永久
        // 悬空配置 (会话停在 WaitingForWorkspace) 和残留的旧工具表。禁用会触发 McpManager
        // 的 settings reconcile 关闭对应会话。
        disableStdioMcpServersForWorkspace(id)
        true
    }

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == workspaceId) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    /** 禁用所有引用 [workspaceId] 且当前启用的 stdio MCP 配置。 */
    private suspend fun disableStdioMcpServersForWorkspace(workspaceId: String) {
        settingsStore.update { settings ->
            val updated = settings.mcpServers.map { server ->
                if (server is McpServerConfig.StdioTransportServer &&
                    server.workspaceId == workspaceId &&
                    server.commonOptions.enable
                ) {
                    server.clone(commonOptions = server.commonOptions.copy(enable = false))
                } else {
                    server
                }
            }
            settings.copy(mcpServers = updated)
        }
    }

    private suspend fun restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

    private suspend fun updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

    private suspend fun updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        dao.updateShellStatus(
            id = workspaceId,
            shellStatus = shellStatus,
            updatedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024

        /**
         * PRoot bind mounts are always kernel-writable at the app UID, so ALL PRoot processes
         * (foreground shell, background daemons, interactive terminal) ONLY mount `/sdcard` when
         * the workspace is explicitly configured as [WorkspaceSdcardMode.READ_WRITE].
         * In [WorkspaceSdcardMode.READ_ONLY], `/sdcard` is read directly on host via
         * `workspace_read_file` (`O_NOFOLLOW`) and never mounted into PRoot.
         */
        internal fun effectiveProotSdcardMode(mode: WorkspaceSdcardMode): WorkspaceSdcardMode =
            if (mode == WorkspaceSdcardMode.READ_WRITE) WorkspaceSdcardMode.READ_WRITE
            else WorkspaceSdcardMode.NONE
    }
}
