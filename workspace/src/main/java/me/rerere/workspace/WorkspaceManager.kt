package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
    private val bindMounts: List<WorkspaceBindMount> = emptyList(),
) {
    private val fileSystem = WorkspaceFileSystem(config)
    private val background = WorkspaceBackgroundProcesses()

    // 长生命周期双工进程（stdio MCP server）注册表: root -> 存活进程集合。
    // 与 WorkspaceBackgroundProcesses 不同, 这些进程由调用方(MCP 会话层)直接读写
    // stdin/stdout 并负责生命周期, 这里只保留一份兜底引用, 保证 workspace 删除或
    // rootfs 重装时能无条件杀掉, 不会残留孤儿 proot 进程。
    private val managedProcesses = ConcurrentHashMap<String, MutableSet<ManagedWorkspaceProcess>>()

    // 让 startBackground / startManagedProcess 的启动+注册 与 deleteWorkspace /
    // closeAllManagedProcesses 的 killAll+移除注册表+关闭 互斥: 要么启动先完成(随后被
    // killAll/closeAll 杀掉), 要么删除先完成(随后 start 因 rootfs 缺失而失败并抛出),
    // 不会出现"进程活着但 workspace 目录已删"的孤儿进程。startManagedProcess 也走这把锁:
    // 否则启动线程在"检查 rootfs → start → 注册"期间, 删除线程可能已完成"从 map 移除 →
    // 遍历关闭", 新进程会加入一个已被移除的 Set, 永远不被关闭。installRootfs 的重装关闭
    // (closeAllManagedProcesses) 同样走这把锁, 与启动互斥。
    private val backgroundLifecycleLock = Any()

    // 按 target 长度降序, 保证 /a/b 优先于 /a 匹配
    private val sortedBindMounts = bindMounts.sortedByDescending { it.target.trimEnd('/').length }

    init {
        baseDir.mkdirs()
    }

    fun ensureWorkspace(root: String): File {
        val dir = workspaceDir(root)
        filesDir(root).mkdirs()
        linuxDir(root).mkdirs()
        tempDir(root).mkdirs()
        return dir
    }

    fun workspaceDir(root: String): File {
        requireValidRoot(root)
        // canonicalFile 兜底: 即使 ROOT_NAME_REGEX 将来被放宽, ".." / 符号链接等
        // 也绝不能解析到 baseDir 之外 (deleteWorkspace 会递归删除返回值)。
        val base = baseDir.canonicalFile
        val dir = File(base, root).canonicalFile
        require(dir.path.startsWith(base.path + File.separator)) {
            "Workspace root escapes base directory: $root"
        }
        return dir
    }

    fun filesDir(root: String): File = File(workspaceDir(root), FILES_DIR)

    fun linuxDir(root: String): File = File(workspaceDir(root), LINUX_DIR)

    fun tempDir(root: String): File = File(workspaceDir(root), TEMP_DIR)

    fun hasRootfs(root: String): Boolean = File(linuxDir(root), "bin/sh").isFile

    fun deleteWorkspace(root: String): Boolean = synchronized(backgroundLifecycleLock) {
        // 先杀掉该 workspace 所有后台进程, 再删目录, 避免进程仍持有已删除目录下的 fd
        killAllBackground(root)
        closeAllManagedProcesses(root)
        val dir = workspaceDir(root)
        // 纵深防御: workspaceDir 已做 canonical 前缀校验, 这里再显式拒绝
        // "等于 baseDir 本身"的退化情形, 防止任何改动把整个 baseDir wipe 掉。
        require(dir != baseDir.canonicalFile) { "Refusing to delete workspace base directory" }
        dir.deleteRecursively()
    }

    fun listFiles(
        root: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> =
        fileSystem.list(areaDir(root, area), path)

    fun readText(
        root: String,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): String = fileSystem.readText(filesDir(root), path, charset)

    fun writeText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = fileSystem.writeText(filesDir(root), path, text, overwrite, charset)

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val areaRoot = areaDir(root, area)
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        return fileSystem.importBytes(areaRoot, targetPath, inputStream)
    }

    fun fileSize(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Long {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file.length()
    }

    fun exportFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
    ) {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /**
     * 把 Rootfs 内的绝对路径映射到宿主机上的真实文件。
     *
     * bind mount 的 source 本身就是 Android 侧的普通目录, 因此 /skills 这类挂载路径
     * 可以直接用文件 IO 访问, 无需经过 PRoot; 只是 Rootfs 目录里对应位置是个空挂载点,
     * 按 [WorkspaceStorageArea.LINUX] 解析必然落空。
     */
    fun resolveRootfsPath(root: String, path: String): RootfsLocation {
        val trimmed = path.trim().trimEnd('/').ifBlank { "/" }
        require(trimmed.startsWith("/")) { "Rootfs path must be absolute: $path" }

        sortedBindMounts.forEach { mount ->
            val target = mount.target.trimEnd('/')
            if (trimmed == target) return RootfsLocation(mount.source, "")
            if (trimmed.startsWith("$target/")) {
                return RootfsLocation(mount.source, trimmed.removePrefix("$target/"))
            }
        }

        if (trimmed == ROOTFS_WORKSPACE_DIR || trimmed.startsWith("$ROOTFS_WORKSPACE_DIR/")) {
            return RootfsLocation(
                rootDir = filesDir(root),
                relativePath = trimmed.removePrefix(ROOTFS_WORKSPACE_DIR).trimStart('/'),
            )
        }

        // 内核伪文件系统: 显式拒绝, 而不是回落到一个必然读不到的物理路径
        KERNEL_FS_MOUNTS.firstOrNull { trimmed == it || trimmed.startsWith("$it/") }?.let {
            error("$it is a kernel filesystem and cannot be read as a file, use workspace_shell instead")
        }

        return RootfsLocation(linuxDir(root), trimmed.trimStart('/'))
    }

    fun rootfsFileSize(root: String, path: String): Long =
        resolveRootfsFile(root, path).also { it.requireReadableFile(path) }.length()

    fun exportRootfsFile(root: String, path: String, outputStream: OutputStream) {
        val file = resolveRootfsFile(root, path)
        file.requireReadableFile(path)
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    private fun resolveRootfsFile(root: String, path: String): File {
        val location = resolveRootfsPath(root, path)
        return fileSystem.resolve(location.rootDir, location.relativePath)
    }

    private fun File.requireReadableFile(path: String) {
        require(exists()) { "File does not exist: $path" }
        require(isFile) { "Path is not a file: $path" }
    }

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean =
        fileSystem.delete(areaDir(root, area), path, recursive)

    fun moveFile(root: String, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry =
        fileSystem.move(filesDir(root), source, target, overwrite)

    fun glob(root: String, pattern: String, path: String = ""): List<WorkspaceFileEntry> =
        fileSystem.glob(filesDir(root), pattern, path)

    fun grep(
        root: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> =
        fileSystem.grep(filesDir(root), query, path, regex, ignoreCase, includeGlob)

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        val workingDir = resolveCommandWorkingDir(root, cwd)

        return shellRunner.execute(
            WorkspaceShellContext(
                root = root,
                command = command,
                cwd = cwd,
                filesDir = filesDir(root),
                linuxDir = linuxDir(root),
                tempDir = tempDir(root),
                workingDir = workingDir,
                timeoutMillis = timeoutMillis,
                stdin = stdin,
                bindMounts = bindMounts,
            )
        )
    }

    /**
     * Starts [command] as a long-lived background process for [root]. The process runs
     * in the foreground of its own proot invocation; the caller polls/kills it via
     * [backgroundStatus]/[killBackground]. Throws IllegalStateException if [root] is
     * already at the running-process cap.
     */
    fun startBackground(root: String, command: String, cwd: String = ""): BackgroundStatus =
        synchronized(backgroundLifecycleLock) {
            require(command.isNotBlank()) { "Command is required" }
            val workingDir = resolveCommandWorkingDir(root, cwd)

            val process = shellRunner.start(
                WorkspaceShellContext(
                    root = root,
                    command = command,
                    cwd = cwd,
                    filesDir = filesDir(root),
                    linuxDir = linuxDir(root),
                    tempDir = tempDir(root),
                    workingDir = workingDir,
                    timeoutMillis = 0L,
                    bindMounts = bindMounts,
                )
            )
            background.start(root, process, command, cwd)
        }

    fun backgroundStatus(root: String, id: String): BackgroundStatus? = background.status(root, id)

    fun listBackground(root: String): List<BackgroundStatus> = background.list(root)

    fun killBackground(root: String, id: String): Boolean = background.kill(root, id)

    fun killAllBackground(root: String) = background.killAll(root)

    /**
     * 在 [backgroundLifecycleLock] 下执行 [block]。供 [RootfsInstaller.install] 使用:
     * rootfs 重装全程与 startBackground / startManagedProcess / deleteWorkspace /
     * closeAllManagedProcesses 互斥, 避免安装中途目录被删或被新进程持有 fd。
     */
    internal fun <T> withProcessLifecycleLock(block: () -> T): T =
        synchronized(backgroundLifecycleLock) { block() }

    /**
     * Starts [command] (with structured [args], no shell evaluation) as a long-lived
     * duplex process inside [root]'s rootfs. The caller owns reading/writing the
     * returned streams and must call [ManagedWorkspaceProcess.close] when done; the
     * process is also registered so [deleteWorkspace] / [closeAllManagedProcesses] can
     * kill it unconditionally. [env] is merged over the base HOME/PATH/TERM/LANG env
     * inside the rootfs. [cwd] is a path relative to the workspace files dir.
     * Throws [IllegalStateException] if the rootfs is not installed.
     */
    fun startManagedProcess(
        root: String,
        command: String,
        args: List<String> = emptyList(),
        cwd: String = "",
        env: Map<String, String> = emptyMap(),
    ): ManagedWorkspaceProcess {
        require(command.isNotBlank()) { "Command is required" }
        // 与 deleteWorkspace / closeAllManagedProcesses 共享同一把生命周期锁, 把
        // "检查 rootfs → 启动 → 注册到 map"做成不可分割的事务。否则删除线程可能在
        // "检查"与"注册"之间完成"从 map 移除 → 遍历关闭 → 删目录", 新进程会注册进
        // 一个已被移除的 Set 并永远不被关闭。
        return synchronized(backgroundLifecycleLock) {
            require(hasRootfs(root)) { "Rootfs is not installed for workspace: $root" }
            val workingDir = resolveCommandWorkingDir(root, cwd)

            val process = shellRunner.startStructured(
                WorkspaceShellContext(
                    root = root,
                    command = command,
                    cwd = cwd,
                    filesDir = filesDir(root),
                    linuxDir = linuxDir(root),
                    tempDir = tempDir(root),
                    workingDir = workingDir,
                    timeoutMillis = 0L,
                    bindMounts = bindMounts,
                ),
                args = args,
                extraEnv = env,
            )
            val managed = ManagedWorkspaceProcess(
                inputStream = process.inputStream,
                outputStream = process.outputStream,
                errorStream = BoundedTailInputStream(process.errorStream),
                process = process,
            )
            // 清理该 workspace 已退出的托管进程, 再登记新进程, 避免注册表无限增长
            managedProcesses.computeIfAbsent(root) { ConcurrentHashMap.newKeySet() }.apply {
                removeIf { it.isClosed }
                add(managed)
            }
            managed
        }
    }

    /**
     * Kills every managed (stdio duplex) process for [root]. Called on workspace
     * deletion and rootfs reinstall — after this, no process can hold fds into a
     * rootfs that is about to be removed or replaced.
     */
    fun closeAllManagedProcesses(root: String) {
        // 与 startManagedProcess 的"检查 rootfs → 启动 → 注册"互斥: 从注册表移除和关闭
        // 必须是同一事务, 否则启动线程可能把新进程注册进一个刚被移除的 Set。synchronized
        // 可重入, deleteWorkspace 已在锁内调用这里也没问题。
        synchronized(backgroundLifecycleLock) {
            managedProcesses.remove(root)?.forEach { it.close() }
        }
    }

    private fun resolveCommandWorkingDir(root: String, cwd: String): File {
        val workingDir = fileSystem.resolve(filesDir(root), cwd)
        require(workingDir.exists()) { "Working directory does not exist: $cwd" }
        require(workingDir.isDirectory) { "Working path is not a directory: $cwd" }
        return workingDir
    }

    private fun requireValidRoot(root: String) {
        require(root.matches(ROOT_NAME_REGEX)) {
            "Invalid workspace root name: $root"
        }
    }

    private fun areaDir(root: String, area: WorkspaceStorageArea): File = when (area) {
        WorkspaceStorageArea.FILES -> filesDir(root)
        WorkspaceStorageArea.LINUX -> linuxDir(root)
    }

    fun cleanupAllTempDirs() {
        val roots = baseDir.listFiles()?.filter { it.isDirectory } ?: return
        for (dir in roots) {
            val root = dir.name
            if (!root.matches(ROOT_NAME_REGEX)) continue
            // PRoot temp files
            tempDir(root).let { if (it.exists()) it.deleteRecursively() }
            // Rootfs /tmp and /var/tmp
            File(linuxDir(root), "tmp").let { if (it.exists()) it.deleteRecursively() }
            File(linuxDir(root), "var/tmp").let { if (it.exists()) it.deleteRecursively() }
        }
    }

    companion object {
        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L

        /** Rootfs 内工作区文件区的挂载点 */
        const val ROOTFS_WORKSPACE_DIR = "/workspace"

        /** 由宿主机透传的内核伪文件系统, 只能通过 shell 访问 */
        val KERNEL_FS_MOUNTS = listOf("/dev", "/proc", "/sys")

        // 首字符必须是字母或数字: 排除 "." / ".." / ".hidden"。旧正则
        // [A-Za-z0-9._-]+ 允许 "..", workspaceDir("..") 会解析成 baseDir 本身,
        // deleteWorkspace("..") 即可 wipe 整个 filesDir。
        private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}

/** Rootfs 内绝对路径在宿主机上的落点 */
data class RootfsLocation(
    val rootDir: File,
    val relativePath: String,
)
