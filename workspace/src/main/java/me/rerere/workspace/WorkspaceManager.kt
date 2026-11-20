package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ConcurrentHashMap

class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
    private val bindMounts: List<WorkspaceBindMount> = emptyList(),
    /**
     * 宿主侧共享存储根 (通常 /storage/emulated/0)。非 null 时, [executeCommand]/
     * [startBackground] 可按 workspace 的 sdcard 模式在静态绑定表之上追加
     * `/sdcard` 绑定。解析 (resolveRootfsPath) 同样用它把 rootfs 内 /sdcard 路径
     * 映射到宿主真实目录。
     */
    private val sdcardDir: File? = null,
) {
    private val fileSystem = WorkspaceFileSystem(config)
    private val background = WorkspaceBackgroundProcesses()

    // 长生命周期双工进程（stdio MCP server）注册表: root -> 存活进程集合。
    // 与 WorkspaceBackgroundProcesses 不同, 这些进程由调用方(MCP 会话层)直接读写
    // stdin/stdout 并负责生命周期, 这里只保留一份兜底引用, 保证 workspace 删除或
    // rootfs 重装时能无条件杀掉, 不会残留孤儿 proot 进程。
    private val managedProcesses = ConcurrentHashMap<String, MutableSet<ManagedWorkspaceProcess>>()

    // 正在执行的前台 shell 命令进程注册表: root -> 存活进程集合。
    // 与 startTrackedForegroundCommand / killAllForeground / deleteWorkspace 共享
    // backgroundLifecycleLock，确保挂载策略变更、权限撤销或工作区删除能即时终止所有运行中
    // 的前台命令。
    private val foregroundProcesses = ConcurrentHashMap<String, MutableSet<Process>>()

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

    val workspacesBaseDir: File
        get() = baseDir

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
        // Refuse symlinked baseDir or workspace root so a replaced root directory can never
        // redirect file reads/writes/deletes outside baseDir.
        require(!Files.isSymbolicLink(baseDir.toPath())) {
            "Workspace base directory must not be a symbolic link"
        }
        val child = WorkspaceSafeFiles.requireChildPathNoSymlink(baseDir, root)
        val base = baseDir.canonicalFile
        val resolved = child.canonicalFile
        require(resolved.path.startsWith(base.path + File.separator)) {
            "Workspace root escapes base directory: $root"
        }
        return child
    }

    fun filesDir(root: String): File =
        WorkspaceSafeFiles.requireChildPathNoSymlink(workspaceDir(root), FILES_DIR)

    fun linuxDir(root: String): File =
        WorkspaceSafeFiles.requireChildPathNoSymlink(workspaceDir(root), LINUX_DIR)

    fun tempDir(root: String): File =
        WorkspaceSafeFiles.requireChildPathNoSymlink(workspaceDir(root), TEMP_DIR)

    fun hasRootfs(root: String): Boolean {
        val sh = File(linuxDir(root), "bin/sh").toPath()
        return Files.isRegularFile(sh) || Files.isSymbolicLink(sh)
    }

    fun deleteWorkspace(root: String): Boolean = synchronized(backgroundLifecycleLock) {
        // 先杀掉该 workspace 所有前台、后台与托管进程, 再删目录, 避免进程仍持有已删除目录下的 fd
        killAllForeground(root)
        killAllBackground(root)
        closeAllManagedProcesses(root)
        requireValidRoot(root)
        require(!Files.isSymbolicLink(baseDir.toPath())) {
            "Workspace base directory must not be a symbolic link"
        }
        // Delete strictly relative to the pinned uncontrolled baseDir FD (unlinkat/NOFOLLOW).
        // If `root` was replaced with a symlink, only the symlink itself is unlinked.
        if (!Files.exists(baseDir.toPath().resolve(root), LinkOption.NOFOLLOW_LINKS)) {
            return@synchronized true
        }
        WorkspaceSafeFiles.deleteChildInRootNoFollow(baseDir, root, recursive = true)
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
    ): String {
        val baseRel = baseRelativePath(root, WorkspaceStorageArea.FILES, path)
        val attrs = try {
            WorkspaceSafeFiles.statRegularFileNoFollow(baseDir, baseRel, displayPath = path)
        } catch (e: java.nio.file.NoSuchFileException) {
            throw IllegalArgumentException("File does not exist: $path", e)
        }
        require(attrs.size() <= config.maxReadBytes) {
            "File is too large to read: ${attrs.size()} bytes"
        }
        return WorkspaceSafeFiles.openInputStreamNoFollow(baseDir, baseRel, displayPath = path).use { inp ->
            val bytes = inp.readBytes()
            require(bytes.size <= config.maxReadBytes) {
                "File is too large to read: ${bytes.size} bytes"
            }
            String(bytes, charset)
        }
    }

    fun writeText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry {
        val bytes = text.toByteArray(charset)
        require(bytes.size <= config.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes"
        }
        val relSegs = WorkspaceSafeFiles.parseRelativeSegments(path)
        require(relSegs.isNotEmpty()) { "Path is not a file: $path" }
        val written = WorkspaceSafeFiles.writeBytesNoFollowWithMeta(
            root = baseDir,
            relPath = baseRelativePath(root, WorkspaceStorageArea.FILES, path),
            bytes = bytes,
            overwrite = overwrite,
            displayPath = path,
        )
        return WorkspaceFileEntry(
            path = relSegs.joinToString("/"),
            name = written.leafName,
            isDirectory = false,
            sizeBytes = written.sizeBytes,
            updatedAt = written.lastModifiedMs,
        )
    }

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        val relSegs = WorkspaceSafeFiles.parseRelativeSegments(targetPath)
        require(relSegs.isNotEmpty()) { "Path is not a file: $targetPath" }
        val written = WorkspaceSafeFiles.importStreamNoFollowWithMeta(
            root = baseDir,
            relPath = baseRelativePath(root, area, targetPath),
            inputStream = inputStream,
            maxBytes = config.maxImportBytes,
        )
        val finalRel = (relSegs.dropLast(1) + written.leafName).joinToString("/")
        return WorkspaceFileEntry(
            path = finalRel,
            name = written.leafName,
            isDirectory = false,
            sizeBytes = written.sizeBytes,
            updatedAt = written.lastModifiedMs,
        )
    }

    private fun areaDirName(area: WorkspaceStorageArea): String = when (area) {
        WorkspaceStorageArea.FILES -> FILES_DIR
        WorkspaceStorageArea.LINUX -> LINUX_DIR
    }

    private fun baseRelativePath(root: String, area: WorkspaceStorageArea, relPath: String): String {
        requireValidRoot(root)
        val segments = WorkspaceSafeFiles.parseRelativeSegments(relPath)
        require(segments.isNotEmpty()) { "Path is not a file: $relPath" }
        return (listOf(root, areaDirName(area)) + segments).joinToString("/")
    }

    fun fileSize(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Long = try {
        WorkspaceSafeFiles.statRegularFileNoFollow(
            root = baseDir,
            relPath = baseRelativePath(root, area, path),
            displayPath = path,
        ).size()
    } catch (e: java.nio.file.NoSuchFileException) {
        throw IllegalArgumentException("File does not exist: $path", e)
    }

    fun exportFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
    ) {
        try {
            WorkspaceSafeFiles.exportFileNoFollow(
                root = baseDir,
                relPath = baseRelativePath(root, area, path),
                outputStream = outputStream,
                displayPath = path,
            )
        } catch (e: java.nio.file.NoSuchFileException) {
            throw IllegalArgumentException("File does not exist: $path", e)
        }
    }

    /**
     * 把 Rootfs 内的绝对路径映射到宿主机上的真实文件。
     *
     * bind mount 的 source 本身就是 Android 侧的普通目录, 因此 /skills 这类挂载路径
     * 可以直接用文件 IO 访问, 无需经过 PRoot; 只是 Rootfs 目录里对应位置是个空挂载点,
     * 按 [WorkspaceStorageArea.LINUX] 解析必然落空。
     */
    fun resolveRootfsPath(
        root: String,
        path: String,
        sdcardMode: WorkspaceSdcardMode = WorkspaceSdcardMode.NONE,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ): RootfsLocation {
        val raw = path.replace('\\', '/').trim()
        require(raw.startsWith("/")) { "Rootfs path must be absolute: $path" }
        require(!raw.contains('\u0000')) { "Rootfs path contains invalid character" }
        val normalizedSegments = ArrayDeque<String>()
        for (part in raw.split('/')) {
            when (part) {
                "", "." -> continue
                ".." -> throw IllegalArgumentException("Path escapes workspace root: $path")
                else -> normalizedSegments.addLast(part)
            }
        }
        val trimmed = if (normalizedSegments.isEmpty()) "/" else "/" + normalizedSegments.joinToString("/")

        // /sdcard 只在启用挂载时才是真实绑定; 与 resolveRootfsFile 的调用方约定:
        // 未挂载时保持既有回退 (linuxDir 下解析), 由调用方给出 "File does not
        // exist" 类错误 — 不在纯解析函数里抛 "未挂载", 保持函数无副作用。
        if (sdcardMode != WorkspaceSdcardMode.NONE && WorkspaceSdcard.isWithin(trimmed)) {
            val sdcard = requireNotNull(sdcardDir) { "sdcardDir is required for sdcard mode $sdcardMode" }
            return RootfsLocation(
                rootDir = sdcard,
                relativePath = trimmed.removePrefix(WorkspaceSdcard.MOUNT_TARGET).trimStart('/'),
            )
        }

        val allMounts = if (extraBindMounts.isEmpty()) {
            sortedBindMounts
        } else {
            (bindMounts + extraBindMounts).sortedByDescending { it.target.trimEnd('/').length }
        }
        allMounts.forEach { mount ->
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

    private data class PinnedRootfsTarget(
        val trustedRoot: File,
        val relativePath: String,
    )

    private fun joinRelativeSegments(prefix: String, suffix: String): String {
        val parts = WorkspaceSafeFiles.parseRelativeSegments(prefix) + WorkspaceSafeFiles.parseRelativeSegments(suffix)
        return parts.joinToString("/")
    }

    private fun lexicalRelativeIfUnder(ancestor: File, candidate: File): String? {
        val ancPath = ancestor.toPath().toAbsolutePath().normalize()
        val candPath = candidate.toPath().toAbsolutePath().normalize()
        if (!candPath.startsWith(ancPath)) return null
        val rel = ancPath.relativize(candPath)
        return rel.toString().replace(File.separatorChar, '/').trim('/')
    }

    /**
     * Resolves a Rootfs absolute path to a **trusted uncontrolled volume root** plus relative path
     * segments so every subsequent stat/read/write walks strictly by FD-relative `O_NOFOLLOW` from
     * the trusted root (`baseDir` for `/workspace` and rootfs interior, `sdcardDir` for `/sdcard`,
     * or `mount.trustedAnchorRoot` / containing volume root for bind mounts) — never promoting
     * mutable subdirectories like `filesDir(root)`, `linuxDir(root)`, or `/shared` into roots.
     */
    private fun resolvePinnedRootfsTarget(
        root: String,
        path: String,
        sdcardMode: WorkspaceSdcardMode = WorkspaceSdcardMode.NONE,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ): PinnedRootfsTarget {
        requireValidRoot(root)
        val raw = path.replace('\\', '/').trim()
        require(raw.startsWith("/")) { "Rootfs path must be absolute: $path" }
        require(!raw.contains('\u0000')) { "Rootfs path contains invalid character" }
        val normalizedSegments = ArrayDeque<String>()
        for (part in raw.split('/')) {
            when (part) {
                "", "." -> continue
                ".." -> throw IllegalArgumentException("Path escapes workspace root: $path")
                else -> normalizedSegments.addLast(part)
            }
        }
        val trimmed = if (normalizedSegments.isEmpty()) "/" else "/" + normalizedSegments.joinToString("/")

        if (sdcardMode != WorkspaceSdcardMode.NONE && WorkspaceSdcard.isWithin(trimmed)) {
            val sdcard = requireNotNull(sdcardDir) { "sdcardDir is required for sdcard mode $sdcardMode" }
            return PinnedRootfsTarget(
                trustedRoot = sdcard,
                relativePath = trimmed.removePrefix(WorkspaceSdcard.MOUNT_TARGET).trimStart('/'),
            )
        }

        val allMounts = if (extraBindMounts.isEmpty()) {
            sortedBindMounts
        } else {
            (bindMounts + extraBindMounts).sortedByDescending { it.target.trimEnd('/').length }
        }
        allMounts.forEach { mount ->
            val target = mount.target.trimEnd('/')
            val matched = trimmed == target || trimmed.startsWith("$target/")
            if (matched) {
                val subRel = if (trimmed == target) "" else trimmed.removePrefix("$target/")
                val explicitAnchor = mount.trustedAnchorRoot
                if (explicitAnchor != null) {
                    return PinnedRootfsTarget(
                        trustedRoot = explicitAnchor,
                        relativePath = joinRelativeSegments(mount.anchorRelativePath, subRel),
                    )
                }
                val sdcard = sdcardDir
                if (sdcard != null) {
                    val relUnderSdcard = lexicalRelativeIfUnder(sdcard, mount.source)
                    if (relUnderSdcard != null) {
                        return PinnedRootfsTarget(
                            trustedRoot = sdcard,
                            relativePath = joinRelativeSegments(relUnderSdcard, subRel),
                        )
                    }
                }
                val relUnderBase = lexicalRelativeIfUnder(baseDir, mount.source)
                if (relUnderBase != null) {
                    return PinnedRootfsTarget(
                        trustedRoot = baseDir,
                        relativePath = joinRelativeSegments(relUnderBase, subRel),
                    )
                }
                return PinnedRootfsTarget(
                    trustedRoot = mount.source,
                    relativePath = subRel,
                )
            }
        }

        if (trimmed == ROOTFS_WORKSPACE_DIR || trimmed.startsWith("$ROOTFS_WORKSPACE_DIR/")) {
            val subRel = trimmed.removePrefix(ROOTFS_WORKSPACE_DIR).trimStart('/')
            return PinnedRootfsTarget(
                trustedRoot = baseDir,
                relativePath = joinRelativeSegments("$root/$FILES_DIR", subRel),
            )
        }

        KERNEL_FS_MOUNTS.firstOrNull { trimmed == it || trimmed.startsWith("$it/") }?.let {
            error("$it is a kernel filesystem and cannot be read as a file, use workspace_shell instead")
        }

        return PinnedRootfsTarget(
            trustedRoot = baseDir,
            relativePath = joinRelativeSegments("$root/$LINUX_DIR", trimmed.trimStart('/')),
        )
    }

    fun rootfsFileSize(
        root: String,
        path: String,
        sdcardMode: WorkspaceSdcardMode = WorkspaceSdcardMode.NONE,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ): Long {
        val target = resolvePinnedRootfsTarget(root, path, sdcardMode, extraBindMounts)
        return try {
            WorkspaceSafeFiles.statRegularFileNoFollow(target.trustedRoot, target.relativePath, displayPath = path)
                .size()
        } catch (e: java.nio.file.NoSuchFileException) {
            throw IllegalArgumentException("File does not exist: $path", e)
        }
    }

    fun exportRootfsFile(
        root: String,
        path: String,
        outputStream: OutputStream,
        sdcardMode: WorkspaceSdcardMode = WorkspaceSdcardMode.NONE,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ) {
        val target = resolvePinnedRootfsTarget(root, path, sdcardMode, extraBindMounts)
        try {
            WorkspaceSafeFiles.exportFileNoFollow(target.trustedRoot, target.relativePath, outputStream, displayPath = path)
        } catch (e: java.nio.file.NoSuchFileException) {
            throw IllegalArgumentException("File does not exist: $path", e)
        }
    }

    /**
     * Writes UTF-8 text to a Rootfs absolute path starting from the trusted uncontrolled volume
     * root (`baseDir`, `sdcardDir`, or bind mount's trusted volume root) via FD-relative
     * `O_NOFOLLOW` traversal, and returns metadata captured directly from the open channel and
     * pinned directory handle (never re-opening or re-statting by path).
     */
    fun writeRootfsTextNoFollow(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        sdcardMode: WorkspaceSdcardMode = WorkspaceSdcardMode.NONE,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ): WorkspaceFileEntry {
        val target = resolvePinnedRootfsTarget(root, path, sdcardMode, extraBindMounts)
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= config.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes"
        }
        val written = WorkspaceSafeFiles.writeBytesNoFollowWithMeta(
            root = target.trustedRoot,
            relPath = target.relativePath,
            bytes = bytes,
            overwrite = overwrite,
            displayPath = path,
        )
        val normalizedPath = path.replace('\\', '/').trim()
        val leafName = normalizedPath.trimEnd('/').substringAfterLast('/').ifBlank { "/" }
        return WorkspaceFileEntry(
            path = normalizedPath,
            name = leafName,
            isDirectory = false,
            sizeBytes = written.sizeBytes,
            updatedAt = written.lastModifiedMs,
        )
    }

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean {
        require(path.isNotBlank()) { "Refusing to delete workspace root" }
        return WorkspaceSafeFiles.deleteChildInRootNoFollow(
            root = baseDir,
            relPath = baseRelativePath(root, area, path),
            recursive = recursive,
        )
    }

    fun moveFile(root: String, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry {
        require(source.isNotBlank()) { "Refusing to move workspace root" }
        val dstSegs = WorkspaceSafeFiles.parseRelativeSegments(target)
        require(dstSegs.isNotEmpty()) { "Refusing to overwrite workspace root" }
        val srcBaseRel = baseRelativePath(root, WorkspaceStorageArea.FILES, source)
        val dstBaseRel = baseRelativePath(root, WorkspaceStorageArea.FILES, target)
        WorkspaceSafeFiles.moveNoFollow(baseDir, srcBaseRel, dstBaseRel, overwrite)
        val attrs = WorkspaceSafeFiles.statEntryNoFollow(baseDir, dstBaseRel, displayPath = target)
        return WorkspaceFileEntry(
            path = dstSegs.joinToString("/"),
            name = dstSegs.last(),
            isDirectory = attrs.isDirectory,
            sizeBytes = if (attrs.isRegularFile) attrs.size() else 0L,
            updatedAt = attrs.lastModifiedTime().toMillis(),
        )
    }

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
        sdcardMode: WorkspaceSdcardMode = WorkspaceSdcardMode.NONE,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
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
                bindMounts = effectiveBindMounts(sdcardMode, extraBindMounts),
            )
        )
    }

    /**
     * Starts [command] as a long-lived background process for [root]. The process runs
     * in the foreground of its own proot invocation; the caller polls/kills it via
     * [backgroundStatus]/[killBackground]. Throws IllegalStateException if [root] is
     * already at the running-process cap.
     */
    fun startBackground(
        root: String,
        command: String,
        cwd: String = "",
        sdcardMode: WorkspaceSdcardMode = WorkspaceSdcardMode.NONE,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ): BackgroundStatus =
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
                    bindMounts = effectiveBindMounts(sdcardMode, extraBindMounts),
                )
            )
            background.start(root, process, command, cwd)
        }

    data class TrackedForegroundCommand(
        val root: String,
        val process: Process,
    )

    /**
     * Starts a foreground command process AND registers it under [backgroundLifecycleLock] in one
     * atomic critical section so concurrent mount revocation, workspace deletion, or rootfs
     * reinstall ([killAllForeground]) can never miss a newly launched foreground command.
     */
    fun startTrackedForegroundCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        sdcardMode: WorkspaceSdcardMode = WorkspaceSdcardMode.NONE,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ): TrackedForegroundCommand = synchronized(backgroundLifecycleLock) {
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
                timeoutMillis = timeoutMillis,
                stdin = stdin,
                bindMounts = effectiveBindMounts(sdcardMode, extraBindMounts),
            )
        )
        foregroundProcesses.computeIfAbsent(root) { ConcurrentHashMap.newKeySet() }.apply {
            removeIf { !it.isAlive }
            add(process)
        }
        TrackedForegroundCommand(root = root, process = process)
    }

    fun finishTrackedForegroundCommand(tracked: TrackedForegroundCommand) {
        synchronized(backgroundLifecycleLock) {
            foregroundProcesses[tracked.root]?.remove(tracked.process)
        }
    }

    fun killAllForeground(root: String) {
        val snapshot = synchronized(backgroundLifecycleLock) {
            foregroundProcesses.remove(root)?.toList().orEmpty()
        }
        snapshot.forEach { proc ->
            runCatching { terminateProcessTree(proc) }
        }
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
                    // stdio MCP 进程刻意不透传 /sdcard 绑定 (保持 NONE): MCP server
                    // 的文件面应由其自身协议约束, 工作区 sdcard 模式只作用于 shell 工具
                    // 与人类终端 (executeCommand/startBackground)。
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

    /**
     * 静态绑定表 + 按模式的 /sdcard 追加绑定 + 可选额外绑定 (如 `/shared`)。
     */
    fun effectiveBindMounts(
        sdcardMode: WorkspaceSdcardMode,
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    ): List<WorkspaceBindMount> {
        val sdcardBind = sdcardDir?.let { WorkspaceSdcard.bindMount(it, sdcardMode) }
        return buildList {
            addAll(bindMounts)
            if (sdcardBind != null) add(sdcardBind)
            addAll(extraBindMounts)
        }
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
        val basePath = baseDir.toPath()
        if (!Files.exists(basePath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(basePath)) return
        val roots = baseDir.listFiles()?.filter {
            !Files.isSymbolicLink(it.toPath()) && Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS)
        } ?: return
        for (dir in roots) {
            val root = dir.name
            if (!root.matches(ROOT_NAME_REGEX)) continue
            // Walk every ancestor segment (root / tmp / linux / var / tmp) pinned by FD with
            // NOFOLLOW_LINKS so a swapped root, linux, or var symlink can never clear outside baseDir.
            WorkspaceSafeFiles.clearChildDirContentsNoFollow(baseDir, root, TEMP_DIR)
            WorkspaceSafeFiles.clearChildDirContentsNoFollow(baseDir, root, LINUX_DIR, "tmp")
            WorkspaceSafeFiles.clearChildDirContentsNoFollow(baseDir, root, LINUX_DIR, "var", "tmp")
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
