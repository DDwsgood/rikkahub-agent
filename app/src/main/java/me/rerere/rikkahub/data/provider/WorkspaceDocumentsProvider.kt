package me.rerere.rikkahub.data.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceSafeFiles
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * 通过 Storage Access Framework 将 workspace 的 files 目录暴露给系统文件管理器。
 *
 * 所有文件操作均从不受 PRoot 工作区控制的顶层 `workspacesBaseDir` 出发，沿
 * `SecureDirectoryStream` 句柄链逐段校验（`openat` / `renameat` / `unlinkat` +
 * `O_NOFOLLOW`），并通过 [StorageManager.openProxyFileDescriptor] 将同一句柄链打开的
 * [SeekableByteChannel] 直接暴露给调用方，杜绝父目录替换造成的路径逃逸。
 */
class WorkspaceDocumentsProvider : DocumentsProvider() {

    private fun manager(): WorkspaceManager = GlobalContext.get().get()

    private fun dao(): WorkspaceDAO = GlobalContext.get().get()

    private fun allWorkspaces(): List<WorkspaceEntity> = runBlocking { dao().getAll() }

    private fun workspaceName(root: String): String =
        allWorkspaces().firstOrNull { it.root == root }?.name ?: root

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val ctx = context ?: return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            add(Root.COLUMN_TITLE, ctx.getString(R.string.app_name))
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_CREATE,
            )
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val target = parseDocId(documentId)
        if (target.isRoot) {
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
                add(Document.COLUMN_DISPLAY_NAME, context?.getString(R.string.app_name))
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_FLAGS, 0)
                add(Document.COLUMN_SIZE, null)
                add(Document.COLUMN_LAST_MODIFIED, null)
            }
        } else {
            val baseDir = checkedBaseDir()
            val segments = WorkspaceSafeFiles.parseRelativeSegments(target.relPath)
            val attrs = WorkspaceSafeFiles.statEntryNoFollow(
                root = baseDir,
                relPath = baseRelPath(target.root, segments),
                displayPath = documentId,
            )
            val displayName = if (segments.isEmpty()) workspaceName(target.root) else segments.last()
            addEntryRow(
                cursor = cursor,
                root = target.root,
                relPath = segments.joinToString("/"),
                displayName = displayName,
                isDir = attrs.isDirectory,
                sizeBytes = if (attrs.isRegularFile) attrs.size() else 0L,
                lastModifiedMs = attrs.lastModifiedTime().toMillis(),
            )
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = parseDocId(parentDocumentId)
        val baseDir = checkedBaseDir()
        if (parent.isRoot) {
            for (ws in allWorkspaces()) {
                runCatching {
                    manager().ensureWorkspace(ws.root)
                    val attrs = WorkspaceSafeFiles.statEntryNoFollow(
                        root = baseDir,
                        relPath = baseRelPath(ws.root, emptyList()),
                    )
                    if (attrs.isDirectory) {
                        addEntryRow(
                            cursor = cursor,
                            root = ws.root,
                            relPath = "",
                            displayName = ws.name,
                            isDir = true,
                            sizeBytes = 0L,
                            lastModifiedMs = attrs.lastModifiedTime().toMillis(),
                        )
                    }
                }
            }
        } else {
            val parentSegments = WorkspaceSafeFiles.parseRelativeSegments(parent.relPath)
            val entries = WorkspaceSafeFiles.listDirNoFollow(
                root = baseDir,
                relPath = baseRelPath(parent.root, parentSegments),
            )
            for (entry in entries) {
                if (entry.name.startsWith(".l2s.")) continue
                // Never expose symbolic links through SAF.
                if (entry.isSymbolicLink) continue
                val childRel = (parentSegments + entry.name).joinToString("/")
                addEntryRow(
                    cursor = cursor,
                    root = parent.root,
                    relPath = childRel,
                    displayName = entry.name,
                    isDir = entry.isDirectory,
                    sizeBytes = entry.sizeBytes,
                    lastModifiedMs = entry.lastModifiedMs,
                )
            }
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("Provider context unavailable")
        val target = parseDocId(documentId)
        require(!target.isRoot && target.relPath.isNotEmpty()) { "Cannot open root as a document" }
        val baseDir = checkedBaseDir()
        val opened = try {
            openWorkspaceChannel(baseDir, target.root, target.relPath, mode)
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: NoSuchFileException) {
            throw FileNotFoundException("Document not found: $documentId").apply { initCause(e) }
        } catch (e: IllegalArgumentException) {
            throw FileNotFoundException(e.message ?: "Refused to open document").apply { initCause(e) }
        } catch (e: IOException) {
            throw FileNotFoundException("Failed to open document safely: ${e.message}").apply { initCause(e) }
        }

        val storageManager = ctx.getSystemService(StorageManager::class.java)
            ?: run {
                runCatching { opened.channel.close() }
                throw FileNotFoundException("StorageManager unavailable")
            }
        return try {
            storageManager.openProxyFileDescriptor(
                opened.pfdAccessMode,
                ChannelProxyFileDescriptorCallback(opened.channel, opened.appendOnly),
                proxyHandler,
            )
        } catch (t: Throwable) {
            runCatching { opened.channel.close() }
            throw FileNotFoundException("Failed to wrap safe channel: ${t.message}").apply { initCause(t) }
        }
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = parseDocId(parentDocumentId)
        require(!parent.isRoot) { "Cannot create document at root" }
        manager().ensureWorkspace(parent.root)
        val baseDir = checkedBaseDir()
        val createdRel = createWorkspaceDocument(
            baseDir = baseDir,
            root = parent.root,
            parentRelPath = parent.relPath,
            isDir = mimeType == Document.MIME_TYPE_DIR,
            displayName = displayName,
        )
        notifyChange(parentDocumentId)
        return buildDocId(parent.root, createdRel)
    }

    override fun deleteDocument(documentId: String) {
        val target = parseDocId(documentId)
        require(!target.isRoot && target.relPath.isNotEmpty()) { "Cannot delete this document" }
        val baseDir = checkedBaseDir()
        val segments = WorkspaceSafeFiles.parseRelativeSegments(target.relPath)
        require(segments.isNotEmpty()) { "Cannot delete workspace root" }
        val ok = WorkspaceSafeFiles.deleteChildInRootNoFollow(
            root = baseDir,
            relPath = baseRelPath(target.root, segments),
            recursive = true,
        )
        require(ok) { "Failed to delete: $documentId" }
        notifyChange(buildDocId(target.root, segments.dropLast(1).joinToString("/")))
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val target = parseDocId(documentId)
        require(!target.isRoot && target.relPath.isNotEmpty()) { "Cannot rename this document" }
        val baseDir = checkedBaseDir()
        val safeName = sanitizeSegment(displayName)
        val srcSegments = WorkspaceSafeFiles.parseRelativeSegments(target.relPath)
        require(srcSegments.isNotEmpty()) { "Cannot rename workspace root" }
        val dstSegments = srcSegments.dropLast(1) + safeName
        WorkspaceSafeFiles.moveNoFollow(
            root = baseDir,
            sourceRelPath = baseRelPath(target.root, srcSegments),
            targetRelPath = baseRelPath(target.root, dstSegments),
            overwrite = false,
        )
        notifyChange(buildDocId(target.root, srcSegments.dropLast(1).joinToString("/")))
        return buildDocId(target.root, dstSegments.joinToString("/"))
    }

    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
        val source = parseDocId(sourceDocumentId)
        val targetParent = parseDocId(targetParentDocumentId)
        require(!source.isRoot && source.relPath.isNotEmpty()) { "Cannot copy this document" }
        require(!targetParent.isRoot) { "Cannot copy to root" }
        val baseDir = checkedBaseDir()
        val copiedRel = copyWorkspaceDocument(
            baseDir = baseDir,
            srcRoot = source.root,
            srcRelPath = source.relPath,
            dstRoot = targetParent.root,
            dstParentRelPath = targetParent.relPath,
        )
        notifyChange(targetParentDocumentId)
        return buildDocId(targetParent.root, copiedRel)
    }

    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String?,
        targetParentDocumentId: String,
    ): String {
        val source = parseDocId(sourceDocumentId)
        val targetParent = parseDocId(targetParentDocumentId)
        require(!source.isRoot && source.relPath.isNotEmpty()) { "Cannot move this document" }
        require(!targetParent.isRoot) { "Cannot move to root" }
        val baseDir = checkedBaseDir()
        val srcSegments = WorkspaceSafeFiles.parseRelativeSegments(source.relPath)
        require(srcSegments.isNotEmpty()) { "Cannot move workspace root" }

        val finalRel = moveWorkspaceDocument(
            baseDir = baseDir,
            srcRoot = source.root,
            srcRelPath = source.relPath,
            dstRoot = targetParent.root,
            dstParentRelPath = targetParent.relPath,
        )

        notifyChange(buildDocId(source.root, srcSegments.dropLast(1).joinToString("/")))
        notifyChange(targetParentDocumentId)
        return buildDocId(targetParent.root, finalRel)
    }

    override fun getDocumentType(documentId: String): String {
        val target = parseDocId(documentId)
        if (target.isRoot) return Document.MIME_TYPE_DIR
        val baseDir = checkedBaseDir()
        val segments = WorkspaceSafeFiles.parseRelativeSegments(target.relPath)
        val attrs = WorkspaceSafeFiles.statEntryNoFollow(
            root = baseDir,
            relPath = baseRelPath(target.root, segments),
            displayPath = documentId,
        )
        return if (attrs.isDirectory) Document.MIME_TYPE_DIR else mimeOfName(segments.lastOrNull().orEmpty())
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = runCatching { parseDocId(parentDocumentId) }.getOrNull() ?: return false
        val child = runCatching { parseDocId(documentId) }.getOrNull() ?: return false
        if (child.isRoot) return false
        if (parent.isRoot) return true
        if (parent.root != child.root) return false
        val baseDir = runCatching { checkedBaseDir() }.getOrNull() ?: return false
        val parentSegs = runCatching { WorkspaceSafeFiles.parseRelativeSegments(parent.relPath) }.getOrNull() ?: return false
        val childSegs = runCatching { WorkspaceSafeFiles.parseRelativeSegments(child.relPath) }.getOrNull() ?: return false
        if (childSegs.size < parentSegs.size) return false
        if (childSegs.subList(0, parentSegs.size) != parentSegs) return false
        // Confirm neither parent nor child traverses any symlink.
        return runCatching {
            WorkspaceSafeFiles.statEntryNoFollow(baseDir, baseRelPath(parent.root, parentSegs))
            WorkspaceSafeFiles.statEntryNoFollow(baseDir, baseRelPath(child.root, childSegs))
            true
        }.getOrDefault(false)
    }

    // --- helpers ---

    private fun checkedBaseDir(): File {
        val baseDir = manager().workspacesBaseDir
        WorkspaceSafeFiles.requireRealDirNoSymlink(baseDir)
        return baseDir
    }

    private fun addEntryRow(
        cursor: MatrixCursor,
        root: String,
        relPath: String,
        displayName: String,
        isDir: Boolean,
        sizeBytes: Long,
        lastModifiedMs: Long,
    ) {
        val flags = when {
            relPath.isEmpty() -> Document.FLAG_DIR_SUPPORTS_CREATE
            isDir -> Document.FLAG_DIR_SUPPORTS_CREATE or
                Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                Document.FLAG_SUPPORTS_COPY or Document.FLAG_SUPPORTS_MOVE
            else -> Document.FLAG_SUPPORTS_WRITE or
                Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                Document.FLAG_SUPPORTS_COPY or Document.FLAG_SUPPORTS_MOVE
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, buildDocId(root, relPath))
            add(Document.COLUMN_DISPLAY_NAME, displayName)
            add(Document.COLUMN_MIME_TYPE, if (isDir) Document.MIME_TYPE_DIR else mimeOfName(displayName))
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (isDir) null else sizeBytes)
            add(Document.COLUMN_LAST_MODIFIED, lastModifiedMs)
        }
    }

    private fun mimeOfName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext.takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"
    }

    private fun parseDocId(documentId: String): DocId {
        if (documentId == ROOT_DOC_ID) return DocId(isRoot = true, root = "", relPath = "")
        require(documentId.startsWith(DOC_PREFIX)) { "Invalid documentId: $documentId" }
        val rest = documentId.removePrefix(DOC_PREFIX)
        val idx = rest.indexOf('/')
        val root = if (idx < 0) rest else rest.substring(0, idx)
        val relPath = if (idx < 0) "" else rest.substring(idx + 1)
        requireValidRoot(root)
        return DocId(isRoot = false, root = root, relPath = relPath)
    }

    private fun requireValidRoot(root: String) {
        require(
            root.isNotBlank() &&
                root != "." && root != ".." &&
                !root.contains('/') && !root.contains('\\') &&
                !root.contains("..") && !root.contains('\u0000')
        ) { "Invalid workspace root" }
        require(allWorkspaces().any { it.root == root }) { "Unknown workspace: $root" }
    }

    private fun notifyChange(parentDocumentId: String) {
        val ctx = context ?: return
        val uri = DocumentsContract.buildChildDocumentsUri(
            ctx.packageName + ".documents",
            parentDocumentId,
        )
        ctx.contentResolver.notifyChange(uri, null)
    }

    private fun buildDocId(root: String, relPath: String): String =
        if (relPath.isEmpty()) "$DOC_PREFIX$root" else "$DOC_PREFIX$root/$relPath"

    private data class DocId(
        val isRoot: Boolean,
        val root: String,
        val relPath: String,
    )

    internal data class OpenedSafChannel(
        val channel: SeekableByteChannel,
        val pfdAccessMode: Int,
        val appendOnly: Boolean,
    )

    /**
     * Bridges a pinned [SeekableByteChannel] (opened via `SecureDirectoryStream.newByteChannel`
     * with `O_NOFOLLOW`) directly to a [ParcelFileDescriptor] via [StorageManager.openProxyFileDescriptor].
     */
    internal class ChannelProxyFileDescriptorCallback(
        private val channel: SeekableByteChannel,
        private val appendOnly: Boolean,
    ) : ProxyFileDescriptorCallback() {

        @Throws(ErrnoException::class)
        override fun onGetSize(): Long = synchronized(channel) {
            try {
                channel.size()
            } catch (e: IOException) {
                throw ErrnoException("onGetSize", OsConstants.EIO, e)
            }
        }

        @Throws(ErrnoException::class)
        override fun onRead(offset: Long, size: Int, data: ByteArray): Int = synchronized(channel) {
            if (size <= 0) return 0
            try {
                if (offset >= channel.size()) return 0
                channel.position(offset)
                val buf = ByteBuffer.wrap(data, 0, size)
                var total = 0
                while (buf.hasRemaining()) {
                    val n = channel.read(buf)
                    if (n < 0) break
                    total += n
                }
                total
            } catch (e: IOException) {
                throw ErrnoException("onRead", OsConstants.EIO, e)
            }
        }

        @Throws(ErrnoException::class)
        override fun onWrite(offset: Long, size: Int, data: ByteArray): Int = synchronized(channel) {
            if (size <= 0) return 0
            try {
                val targetOffset = if (appendOnly) channel.size() else offset
                channel.position(targetOffset)
                val buf = ByteBuffer.wrap(data, 0, size)
                var total = 0
                while (buf.hasRemaining()) {
                    val n = channel.write(buf)
                    if (n <= 0) break
                    total += n
                }
                total
            } catch (e: IOException) {
                throw ErrnoException("onWrite", OsConstants.EIO, e)
            }
        }

        @Throws(ErrnoException::class)
        override fun onFsync() = synchronized(channel) {
            try {
                (channel as? FileChannel)?.force(true)
                Unit
            } catch (e: IOException) {
                throw ErrnoException("onFsync", OsConstants.EIO, e)
            }
        }

        override fun onRelease() {
            synchronized(channel) {
                runCatching { channel.close() }
            }
        }
    }

    companion object {
        const val ROOT_ID = "rikkahub_workspaces"
        const val ROOT_DOC_ID = "root"
        const val DOC_PREFIX = "ws/"
        private const val FILES_DIR = "files"

        private val createLocks = ConcurrentHashMap<String, Any>()
        private fun createLockFor(root: String): Any = createLocks.computeIfAbsent(root) { Any() }

        private val proxyHandler: Handler by lazy {
            val thread = HandlerThread("WorkspaceSafProxy", Process.THREAD_PRIORITY_BACKGROUND).apply {
                isDaemon = true
                start()
            }
            Handler(thread.looper)
        }

        fun buildWorkspaceDocumentId(workspaceRoot: String): String = "$DOC_PREFIX$workspaceRoot"

        internal fun baseRelPath(root: String, relSegments: List<String>): String =
            (listOf(root, FILES_DIR) + relSegments).joinToString("/")

        internal fun sanitizeSegment(name: String): String {
            val cleaned = name
                .replace('\u0000', '_')
                .replace('/', '_')
                .replace('\\', '_')
                .trim()
            require(cleaned.isNotEmpty() && cleaned != "." && cleaned != "..") {
                "Invalid document name: $name"
            }
            return cleaned
        }

        /**
         * Parses a SAF open mode string (`r`, `w`, `wt`, `wa`, `rw`, `rwt`, `rwa`) into
         * [ParcelFileDescriptor] access mode bits + NIO [OpenOption] set.
         */
        internal fun parseSafOpenMode(mode: String): Triple<Int, Set<OpenOption>, Boolean> {
            val normalized = mode.trim().lowercase()
            require(normalized.isNotEmpty() && normalized.all { it in "rwta" }) {
                "Invalid open mode: $mode"
            }
            val hasR = 'r' in normalized
            val hasW = 'w' in normalized
            val hasT = 't' in normalized
            val hasA = 'a' in normalized
            require(hasR || hasW) { "Open mode must include 'r' or 'w': $mode" }
            require(!(hasT && hasA)) { "Open mode cannot combine truncate and append: $mode" }

            val accessMode = when {
                hasR && hasW -> ParcelFileDescriptor.MODE_READ_WRITE
                hasW -> ParcelFileDescriptor.MODE_WRITE_ONLY
                else -> ParcelFileDescriptor.MODE_READ_ONLY
            }
            val options = mutableSetOf<OpenOption>(LinkOption.NOFOLLOW_LINKS)
            if (hasR) options += StandardOpenOption.READ
            if (hasW) {
                options += StandardOpenOption.WRITE
                options += StandardOpenOption.CREATE
            }
            if (hasT) options += StandardOpenOption.TRUNCATE_EXISTING
            return Triple(accessMode, options, hasA)
        }

        /**
         * Core SAF document opener: walks from uncontrolled [baseDir] through
         * `[root, "files", ...dirSegments]` strictly via [WorkspaceSafeFiles.openChannelNoFollow].
         * Exposed `internal` so JVM unit tests can exercise the exact SAF open chain.
         */
        internal fun openWorkspaceChannel(
            baseDir: File,
            root: String,
            relPath: String,
            mode: String,
        ): OpenedSafChannel {
            val segments = WorkspaceSafeFiles.parseRelativeSegments(relPath)
            require(segments.isNotEmpty()) { "Cannot open workspace root as a document" }
            val (accessMode, openOptions, appendOnly) = parseSafOpenMode(mode)
            val dirSegments = listOf(root, FILES_DIR) + segments.dropLast(1)
            val leaf = segments.last()
            val channel = WorkspaceSafeFiles.openChannelNoFollow(
                root = baseDir,
                dirSegments = dirSegments,
                leaf = leaf,
                openOptions = openOptions,
                createMissingDirs = false,
                displayPath = "$root/$relPath",
            )
            return OpenedSafChannel(channel, accessMode, appendOnly)
        }

        /**
         * Core SAF document/directory creator using pinned FD-relative creation (`renameat` for
         * directories, `CREATE_NEW | NOFOLLOW_LINKS` for files) starting from uncontrolled [baseDir].
         */
        internal fun createWorkspaceDocument(
            baseDir: File,
            root: String,
            parentRelPath: String,
            isDir: Boolean,
            displayName: String,
        ): String {
            val parentSegments = WorkspaceSafeFiles.parseRelativeSegments(parentRelPath)
            val safeBaseName = runCatching { sanitizeSegment(displayName) }.getOrDefault("untitled")
            val dotIdx = safeBaseName.lastIndexOf('.')
            val stem = if (dotIdx > 0) safeBaseName.substring(0, dotIdx) else safeBaseName
            val ext = if (dotIdx > 0) safeBaseName.substring(dotIdx) else ""

            return synchronized(createLockFor(root)) {
                WorkspaceSafeFiles.withPinnedDirAndRoot(
                    root = baseDir,
                    dirSegments = listOf(root, FILES_DIR) + parentSegments,
                    createMissing = false,
                ) { rootSds, rootPath, parentSds, dirPath, _ ->
                    var attempt = 0
                    while (attempt <= 10_000) {
                        val candidateName = if (attempt == 0) safeBaseName else "$stem ($attempt)$ext"
                        try {
                            if (isDir) {
                                WorkspaceSafeFiles.createDirectoryInPinnedDir(
                                    rootSds = rootSds,
                                    rootPath = rootPath,
                                    parentSds = parentSds,
                                    childName = candidateName,
                                    failIfExists = true,
                                )
                            } else {
                                val opts: Set<OpenOption> = setOf(
                                    StandardOpenOption.CREATE_NEW,
                                    StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS,
                                )
                                parentSds.newByteChannel(dirPath.fileSystem.getPath(candidateName), opts).close()
                            }
                            return@withPinnedDirAndRoot (parentSegments + candidateName).joinToString("/")
                        } catch (_: FileAlreadyExistsException) {
                            attempt++
                        }
                    }
                    throw IllegalStateException("Failed to allocate unique name for: $displayName")
                }
            }
        }

        private inline fun <T> withOrderedCreateLocks(firstRoot: String, secondRoot: String, block: () -> T): T {
            if (firstRoot == secondRoot) {
                return synchronized(createLockFor(firstRoot), block)
            }
            val (a, b) = if (firstRoot < secondRoot) firstRoot to secondRoot else secondRoot to firstRoot
            return synchronized(createLockFor(a)) {
                synchronized(createLockFor(b), block)
            }
        }

        /**
         * Core SAF document/directory mover for BOTH same-workspace and cross-workspace moves.
         * Because every workspace lives under the same trusted [baseDir] volume, ALL moves use a
         * single FD-relative atomic non-overwrite move ([WorkspaceSafeFiles.moveNoFollow] with
         * `overwrite = false`, backed by kernel `renameat2(RENAME_NOREPLACE)` on Android) — never
         * copy-then-delete.
         */
        internal fun moveWorkspaceDocument(
            baseDir: File,
            srcRoot: String,
            srcRelPath: String,
            dstRoot: String,
            dstParentRelPath: String,
        ): String {
            val srcSegments = WorkspaceSafeFiles.parseRelativeSegments(srcRelPath)
            require(srcSegments.isNotEmpty()) { "Cannot move workspace root" }
            val dstParentSegments = WorkspaceSafeFiles.parseRelativeSegments(dstParentRelPath)
            return withOrderedCreateLocks(srcRoot, dstRoot) {
                val baseName = srcSegments.last()
                val dotIdx = baseName.lastIndexOf('.')
                val stem = if (dotIdx > 0) baseName.substring(0, dotIdx) else baseName
                val ext = if (dotIdx > 0) baseName.substring(dotIdx) else ""
                var attempt = 0
                while (attempt <= 10_000) {
                    val candidate = if (attempt == 0) baseName else "$stem ($attempt)$ext"
                    val dstSegments = dstParentSegments + candidate
                    try {
                        WorkspaceSafeFiles.moveNoFollow(
                            root = baseDir,
                            sourceRelPath = baseRelPath(srcRoot, srcSegments),
                            targetRelPath = baseRelPath(dstRoot, dstSegments),
                            overwrite = false,
                        )
                        return@withOrderedCreateLocks dstSegments.joinToString("/")
                    } catch (e: IllegalArgumentException) {
                        if (e.message?.startsWith("Target already exists:") == true) {
                            attempt++
                        } else {
                            throw e
                        }
                    }
                }
                throw IllegalStateException("Too many conflicting names for $baseName")
            }
        }

        /**
         * Core SAF document/directory copier pinned from uncontrolled [baseDir] via
         * [WorkspaceSafeFiles.copyInRootNoFollow] with `fileKey()` cycle detection.
         */
        internal fun copyWorkspaceDocument(
            baseDir: File,
            srcRoot: String,
            srcRelPath: String,
            dstRoot: String,
            dstParentRelPath: String,
        ): String {
            val srcSegments = WorkspaceSafeFiles.parseRelativeSegments(srcRelPath)
            require(srcSegments.isNotEmpty()) { "Cannot copy workspace root" }
            val dstParentSegments = WorkspaceSafeFiles.parseRelativeSegments(dstParentRelPath)
            val createdName = synchronized(createLockFor(dstRoot)) {
                WorkspaceSafeFiles.copyInRootNoFollow(
                    srcRoot = baseDir,
                    srcRelPath = baseRelPath(srcRoot, srcSegments),
                    dstRoot = baseDir,
                    dstParentRelPath = baseRelPath(dstRoot, dstParentSegments),
                    requestedName = srcSegments.last(),
                    allocateUniqueName = true,
                )
            }
            return (dstParentSegments + createdName).joinToString("/")
        }

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
