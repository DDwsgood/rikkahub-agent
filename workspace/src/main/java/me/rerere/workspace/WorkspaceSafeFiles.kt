package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * Symlink-safe, strictly directory-FD-relative filesystem utilities for workspace storage.
 *
 * Every traversal, open, creation, delete, move, and copy operates through NIO
 * [SecureDirectoryStream] (`openat` / `unlinkat` / `renameat` / `fstatat` with
 * `O_NOFOLLOW`) starting from an uncontrolled root directory. Operations refuse to fall back
 * to check-then-path access if [SecureDirectoryStream] is unavailable on the underlying
 * filesystem, closing parent-directory swap races (TOCTOU).
 */
object WorkspaceSafeFiles {

    const val MKDIR_STAGE_PREFIX = ".rk_mkdir_"

    data class DirEntryInfo(
        val name: String,
        val isDirectory: Boolean,
        val isRegularFile: Boolean,
        val isSymbolicLink: Boolean,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
    )

    data class WriteResult(
        val file: File,
        val leafName: String,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
    )

    private val jvmNonAtomicMoveLock = Any()

    /**
     * Parses a path relative to a trusted root into validated single-segment names.
     * Rejects NUL bytes and any `..` traversal escaping above the root.
     */
    fun parseRelativeSegments(rawPath: String): List<String> {
        val cleaned = rawPath.replace('\\', '/').trim()
        require(!cleaned.contains('\u0000')) { "Path contains invalid character" }
        val trimmed = cleaned.trimStart('/')
        if (trimmed.isEmpty() || trimmed == ".") return emptyList()
        val segments = ArrayDeque<String>()
        for (part in trimmed.split('/')) {
            when (part) {
                "", "." -> continue
                ".." -> {
                    require(segments.isNotEmpty()) { "Path escapes workspace root: $rawPath" }
                    segments.removeLast()
                }
                else -> segments.addLast(part)
            }
        }
        return segments.toList()
    }

    /**
     * Verifies [dir] exists as a real directory and is NOT a symbolic link.
     */
    fun requireRealDirNoSymlink(dir: File): Path {
        val p = dir.toPath()
        require(Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            "Directory does not exist: ${dir.name}"
        }
        require(!Files.isSymbolicLink(p)) {
            "Refusing to traverse symbolic link directory: ${dir.name}"
        }
        require(Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
            "Path is not a directory: ${dir.name}"
        }
        return p
    }

    /**
     * Opens [dir] as a [SecureDirectoryStream], refusing symlinks and refusing filesystems
     * that do not support FD-relative directory streams (never falls back to path resolution).
     */
    private fun openSecureRoot(dir: File): Pair<SecureDirectoryStream<Path>, Path> {
        val rootPath = requireRealDirNoSymlink(dir)
        val preAttrs = Files.readAttributes(rootPath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(!preAttrs.isSymbolicLink && preAttrs.isDirectory) {
            "Refusing to traverse symbolic link or non-directory root: ${dir.name}"
        }
        val rawStream = Files.newDirectoryStream(rootPath)
        if (rawStream !is SecureDirectoryStream<Path>) {
            runCatching { rawStream.close() }
            throw IOException("SecureDirectoryStream is required for symlink-safe access: ${dir.name}")
        }
        val openedAttrs = runCatching {
            rawStream.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes()
        }.getOrNull()
        val preKey = preAttrs.fileKey()
        val openedKey = openedAttrs?.fileKey()
        // Verify both that the path is still not a symlink AND that the opened directory FD's
        // inode identity (fileKey) matches the pre-validated uncontrolled root directory.
        if (
            Files.isSymbolicLink(rootPath) ||
            openedAttrs == null ||
            !openedAttrs.isDirectory ||
            (preKey != null && openedKey != null && preKey != openedKey)
        ) {
            runCatching { rawStream.close() }
            throw IllegalArgumentException("Root directory identity changed during open: ${dir.name}")
        }
        return rawStream to rootPath
    }

    /**
     * Verifies that [childName] directly inside [parentDir] (and [parentDir] itself) are not
     * symbolic links. If [childName] does not yet exist, only [parentDir] is validated.
     */
    fun requireChildPathNoSymlink(parentDir: File, childName: String): File {
        require(
            childName.isNotEmpty() &&
                childName != "." &&
                childName != ".." &&
                !childName.contains('/') &&
                !childName.contains('\\') &&
                !childName.contains('\u0000')
        ) {
            "Invalid path segment: $childName"
        }
        val parentPath = parentDir.toPath()
        require(!Files.isSymbolicLink(parentPath)) {
            "Refusing to use symbolic link base directory: ${parentDir.name}"
        }
        val childPath = parentPath.resolve(childName)
        if (Files.exists(childPath, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(childPath)) {
                "Refusing to follow symbolic link: $childName"
            }
        }
        return childPath.toFile()
    }

    /**
     * Creates a child directory named [childName] directly inside [parentSds] WITHOUT resolving
     * [parentSds]'s path string: stages an empty directory directly under the trusted uncontrolled
     * [rootPath] (verified inside [rootSds]) and places it into [parentSds] via kernel `renameat`
     * ([SecureDirectoryStream.move]).
     *
     * Because POSIX `renameat` into an existing regular file or symlink fails with `ENOTDIR` and
     * into a non-empty directory fails with `ENOTEMPTY`, and we pre-check existence via
     * `fstatat(NOFOLLOW_LINKS)`, this never traverses swapped ancestor symlinks and never
     * overwrites existing files/symlinks/non-empty directories.
     */
    fun createDirectoryInPinnedDir(
        rootSds: SecureDirectoryStream<Path>,
        rootPath: Path,
        parentSds: SecureDirectoryStream<Path>,
        childName: String,
        failIfExists: Boolean = true,
    ) {
        require(
            childName.isNotEmpty() &&
                childName != "." &&
                childName != ".." &&
                !childName.contains('/') &&
                !childName.contains('\\') &&
                !childName.contains('\u0000')
        ) {
            "Invalid directory name: $childName"
        }
        val fs = rootPath.fileSystem
        val targetSeg = fs.getPath(childName)
        val existing = runCatching {
            parentSds.getFileAttributeView(targetSeg, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                .readAttributes()
        }.getOrNull()
        if (existing != null) {
            require(!existing.isSymbolicLink) {
                "Refusing to create directory over symbolic link: $childName"
            }
            if (failIfExists) {
                throw FileAlreadyExistsException(childName)
            }
            require(existing.isDirectory) {
                "Ancestor is not a directory: $childName"
            }
            return
        }

        val tmpName = "$MKDIR_STAGE_PREFIX${UUID.randomUUID()}"
        val tmpSeg = fs.getPath(tmpName)
        val tmpAbs = rootPath.resolve(tmpName)
        Files.createDirectory(tmpAbs)
        var moved = false
        try {
            // Confirm the newly created staging dir lives inside the pinned uncontrolled root FD.
            val stagedAttrs = rootSds.getFileAttributeView(
                tmpSeg,
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ).readAttributes()
            if (stagedAttrs.isSymbolicLink || !stagedAttrs.isDirectory) {
                throw IOException("Staging directory integrity check failed")
            }
            rootSds.move(tmpSeg, parentSds, targetSeg)
            moved = true
        } finally {
            if (!moved) {
                runCatching { rootSds.deleteDirectory(tmpSeg) }
            }
        }
    }

    /**
     * Walks from uncontrolled [root] through [dirSegments] opening each directory strictly
     * relative to its parent FD via [SecureDirectoryStream] with [LinkOption.NOFOLLOW_LINKS].
     * Never falls back to path-based traversal.
     */
    fun <T> withPinnedDir(
        root: File,
        dirSegments: List<String>,
        createMissing: Boolean = false,
        block: (sds: SecureDirectoryStream<Path>, currentDir: Path) -> T,
    ): T = withPinnedDirAndRoot(root, dirSegments, createMissing) { _, _, sds, currentDir, _ ->
        block(sds, currentDir)
    }

    /**
     * Same as [withPinnedDir], also providing the pinned root [SecureDirectoryStream], root path,
     * and the list of `fileKey()` inode identities for every visited directory along the chain.
     */
    fun <T> withPinnedDirAndRoot(
        root: File,
        dirSegments: List<String>,
        createMissing: Boolean = false,
        block: (
            rootSds: SecureDirectoryStream<Path>,
            rootPath: Path,
            sds: SecureDirectoryStream<Path>,
            currentDir: Path,
            ancestorFileKeys: List<Any?>,
        ) -> T,
    ): T {
        val (rootSds, rootPath) = openSecureRoot(root)
        val opened = ArrayDeque<SecureDirectoryStream<Path>>()
        opened.addLast(rootSds)
        val keys = mutableListOf<Any?>()
        try {
            keys.add(
                runCatching {
                    rootSds.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey()
                }.getOrNull()
            )
            var curSds: SecureDirectoryStream<Path> = rootSds
            var curPath = rootPath
            for (seg in dirSegments) {
                val segPath = curPath.fileSystem.getPath(seg)
                val nextPath = curPath.resolve(seg)
                val nextSds: SecureDirectoryStream<Path> = try {
                    curSds.newDirectoryStream(segPath, LinkOption.NOFOLLOW_LINKS)
                } catch (e: NoSuchFileException) {
                    if (!createMissing) throw e
                    createDirectoryInPinnedDir(
                        rootSds = rootSds,
                        rootPath = rootPath,
                        parentSds = curSds,
                        childName = seg,
                        failIfExists = false,
                    )
                    curSds.newDirectoryStream(segPath, LinkOption.NOFOLLOW_LINKS)
                } catch (e: IOException) {
                    // Opening a symlink with NOFOLLOW_LINKS throws FileSystemException/NotDirectoryException.
                    val probe = runCatching {
                        curSds.getFileAttributeView(segPath, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                            .readAttributes()
                    }.getOrNull()
                    if (probe?.isSymbolicLink == true) {
                        throw IllegalArgumentException("Refusing to traverse symbolic link ancestor: $seg", e)
                    }
                    if (probe != null && !probe.isDirectory) {
                        throw IllegalArgumentException("Ancestor is not a directory: $seg", e)
                    }
                    throw e
                }
                opened.addLast(nextSds)
                keys.add(
                    runCatching {
                        nextSds.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey()
                    }.getOrNull()
                )
                curSds = nextSds
                curPath = nextPath
            }
            return block(rootSds, rootPath, curSds, curPath, keys)
        } finally {
            while (opened.isNotEmpty()) {
                runCatching { opened.removeLast().close() }
            }
        }
    }

    /**
     * Resolves and verifies that [root] and every existing component along [relPath] is free of
     * symlinks (optionally permitting the final segment itself to be a symlink when
     * [allowFinalSymlink] is `true`, e.g. for safe unlinking).
     */
    fun resolveNoSymlinkPath(
        root: File,
        relPath: String,
        allowFinalSymlink: Boolean = false,
    ): File {
        val segments = parseRelativeSegments(relPath)
        val rootPath = requireRealDirNoSymlink(root)
        if (segments.isEmpty()) return rootPath.toFile()
        var cur = rootPath
        for (i in segments.indices) {
            val seg = segments[i]
            val isLast = i == segments.lastIndex
            cur = cur.resolve(seg)
            if (Files.exists(cur, LinkOption.NOFOLLOW_LINKS)) {
                val isLink = Files.isSymbolicLink(cur)
                if (isLink && (!isLast || !allowFinalSymlink)) {
                    throw IllegalArgumentException("Refusing to follow symbolic link: $relPath")
                }
                if (!isLast && !Files.isDirectory(cur, LinkOption.NOFOLLOW_LINKS)) {
                    throw IllegalArgumentException("Ancestor is not a directory: $seg")
                }
            }
        }
        return cur.toFile()
    }

    /**
     * Stats any entry (file or directory) relative to pinned [root] without following symlinks.
     * Refuses symlinks at any ancestor or leaf.
     */
    fun statEntryNoFollow(
        root: File,
        relPath: String,
        displayPath: String = relPath,
    ): BasicFileAttributes {
        val segments = parseRelativeSegments(relPath)
        if (segments.isEmpty()) {
            return withPinnedDir(root, emptyList(), createMissing = false) { sds, _ ->
                sds.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes()
            }
        }
        val dirSegments = segments.dropLast(1)
        val leaf = segments.last()
        return withPinnedDir(root, dirSegments, createMissing = false) { sds, dirPath ->
            val attrs = sds.getFileAttributeView(
                dirPath.fileSystem.getPath(leaf),
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ).readAttributes()
            require(!attrs.isSymbolicLink) { "Refusing to access symbolic link: $displayPath" }
            attrs
        }
    }

    /**
     * Lists direct children of [relPath] under pinned [root] via [SecureDirectoryStream],
     * using `fstatat(NOFOLLOW_LINKS)` on each child.
     */
    fun listDirNoFollow(root: File, relPath: String): List<DirEntryInfo> {
        val segments = parseRelativeSegments(relPath)
        return withPinnedDir(root, segments, createMissing = false) { sds, _ ->
            val result = mutableListOf<DirEntryInfo>()
            for (entry in sds) {
                val childName = entry.fileName
                val nameStr = childName.toString()
                if (nameStr.startsWith(MKDIR_STAGE_PREFIX)) continue
                val attrs = runCatching {
                    sds.getFileAttributeView(childName, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                        .readAttributes()
                }.getOrNull() ?: continue
                result.add(
                    DirEntryInfo(
                        name = nameStr,
                        isDirectory = !attrs.isSymbolicLink && attrs.isDirectory,
                        isRegularFile = !attrs.isSymbolicLink && attrs.isRegularFile,
                        isSymbolicLink = attrs.isSymbolicLink,
                        sizeBytes = if (attrs.isRegularFile) attrs.size() else 0L,
                        lastModifiedMs = attrs.lastModifiedTime().toMillis(),
                    )
                )
            }
            result.sortedWith(compareByDescending<DirEntryInfo> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    /**
     * Stats a regular file relative to pinned [root] without following any symlinks.
     */
    fun statRegularFileNoFollow(
        root: File,
        relPath: String,
        displayPath: String = relPath,
    ): BasicFileAttributes {
        val segments = parseRelativeSegments(relPath)
        require(segments.isNotEmpty()) { "Path is not a file: $displayPath" }
        val dirSegments = segments.dropLast(1)
        val leaf = segments.last()
        return withPinnedDir(root, dirSegments, createMissing = false) { sds, dirPath ->
            val attrs = sds.getFileAttributeView(
                dirPath.fileSystem.getPath(leaf),
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ).readAttributes()
            require(!attrs.isSymbolicLink) { "Refusing to access symbolic link: $displayPath" }
            require(attrs.isRegularFile) { "Path is not a file: $displayPath" }
            attrs
        }
    }

    /**
     * Opens a regular file relative to pinned uncontrolled [root] via kernel `openat`
     * (`SecureDirectoryStream.newByteChannel` with [LinkOption.NOFOLLOW_LINKS]).
     *
     * Used by SAF (`WorkspaceDocumentsProvider.openDocument`) and internal readers/writers so
     * the returned channel is guaranteed to originate from the exact pinned FD chain.
     */
    fun openChannelNoFollow(
        root: File,
        dirSegments: List<String>,
        leaf: String,
        openOptions: Set<OpenOption>,
        createMissingDirs: Boolean = false,
        displayPath: String = (dirSegments + leaf).joinToString("/"),
    ): SeekableByteChannel {
        require(
            leaf.isNotEmpty() &&
                leaf != "." &&
                leaf != ".." &&
                !leaf.contains('/') &&
                !leaf.contains('\\') &&
                !leaf.contains('\u0000')
        ) { "Invalid file name: $leaf" }
        val effectiveOptions = openOptions + LinkOption.NOFOLLOW_LINKS
        return withPinnedDir(root, dirSegments, createMissing = createMissingDirs) { sds, dirPath ->
            val leafName = dirPath.fileSystem.getPath(leaf)
            val existingAttrs = runCatching {
                sds.getFileAttributeView(leafName, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes()
            }.getOrNull()
            if (existingAttrs != null) {
                require(!existingAttrs.isSymbolicLink) { "Refusing to open symbolic link: $displayPath" }
                require(existingAttrs.isRegularFile) { "Path is not a file: $displayPath" }
            }
            val channel = try {
                sds.newByteChannel(leafName, effectiveOptions)
            } catch (e: IOException) {
                val probe = runCatching {
                    sds.getFileAttributeView(leafName, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                        .readAttributes()
                }.getOrNull()
                if (probe?.isSymbolicLink == true) {
                    throw IllegalArgumentException("Refusing to open symbolic link: $displayPath", e)
                }
                if (probe != null && !probe.isRegularFile) {
                    throw IllegalArgumentException("Path is not a file: $displayPath", e)
                }
                throw e
            }
            // Post-open verification: ensure the opened target inside the pinned dir is regular.
            val postAttrs = runCatching {
                sds.getFileAttributeView(leafName, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes()
            }.getOrNull()
            if (postAttrs != null && (postAttrs.isSymbolicLink || !postAttrs.isRegularFile)) {
                runCatching { channel.close() }
                throw IllegalArgumentException("Opened target is not a regular file: $displayPath")
            }
            channel
        }
    }

    /**
     * Opens a regular file relative to pinned [root] for reading via FD-relative `O_NOFOLLOW`.
     */
    fun openInputStreamNoFollow(
        root: File,
        relPath: String,
        displayPath: String = relPath,
    ): InputStream {
        val segments = parseRelativeSegments(relPath)
        require(segments.isNotEmpty()) { "Path is not a file: $displayPath" }
        val channel = openChannelNoFollow(
            root = root,
            dirSegments = segments.dropLast(1),
            leaf = segments.last(),
            openOptions = setOf(StandardOpenOption.READ),
            createMissingDirs = false,
            displayPath = displayPath,
        )
        return Channels.newInputStream(channel)
    }

    /**
     * Writes [bytes] to [relPath] under pinned [root] using FD-relative `O_NOFOLLOW`, returning
     * metadata read directly from the open channel / pinned directory handle (never re-statting
     * by path after closing).
     */
    fun writeBytesNoFollowWithMeta(
        root: File,
        relPath: String,
        bytes: ByteArray,
        overwrite: Boolean,
        displayPath: String = relPath,
    ): WriteResult {
        val segments = parseRelativeSegments(relPath)
        require(segments.isNotEmpty()) { "Path is not a file: $displayPath" }
        val dirSegments = segments.dropLast(1)
        val leaf = segments.last()
        return withPinnedDir(root, dirSegments, createMissing = true) { sds, dirPath ->
            val leafPath = dirPath.resolve(leaf)
            val leafName = dirPath.fileSystem.getPath(leaf)
            val existingAttrs = runCatching {
                sds.getFileAttributeView(leafName, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes()
            }.getOrNull()
            if (existingAttrs != null) {
                require(!existingAttrs.isSymbolicLink) { "Refusing to write through symbolic link: $displayPath" }
                require(existingAttrs.isRegularFile) { "Path is not a file: $displayPath" }
                require(overwrite) { "File already exists: $displayPath" }
            }
            val options: Set<OpenOption> = if (overwrite) {
                setOf(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } else {
                setOf(
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                )
            }
            val writtenSize: Long = try {
                sds.newByteChannel(leafName, options).use { ch ->
                    val buf = ByteBuffer.wrap(bytes)
                    while (buf.hasRemaining()) {
                        ch.write(buf)
                    }
                    ch.size()
                }
            } catch (e: FileAlreadyExistsException) {
                throw IllegalArgumentException("File already exists: $displayPath", e)
            }
            val lastModifiedMs = runCatching {
                sds.getFileAttributeView(leafName, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes()
                    .lastModifiedTime()
                    .toMillis()
            }.getOrElse { System.currentTimeMillis() }
            WriteResult(
                file = leafPath.toFile(),
                leafName = leaf,
                sizeBytes = writtenSize,
                lastModifiedMs = lastModifiedMs,
            )
        }
    }

    /**
     * Writes [bytes] to [relPath] under pinned [root] using FD-relative `O_NOFOLLOW`.
     * Refuses to follow any symlink along the root, ancestor directories, or target file.
     */
    fun writeBytesNoFollow(
        root: File,
        relPath: String,
        bytes: ByteArray,
        overwrite: Boolean,
    ): File = writeBytesNoFollowWithMeta(root, relPath, bytes, overwrite).file

    /**
     * Atomically creates a unique non-existing file under pinned [root]/[relPath] using
     * `CREATE_NEW | WRITE | NOFOLLOW_LINKS`, streams up to [maxBytes], and returns metadata
     * captured from the open stream and pinned parent FD (deleting via pinned parent FD on error).
     */
    fun importStreamNoFollowWithMeta(
        root: File,
        relPath: String,
        inputStream: InputStream,
        maxBytes: Long,
    ): WriteResult {
        val segments = parseRelativeSegments(relPath)
        require(segments.isNotEmpty()) { "Path is not a file: $relPath" }
        val dirSegments = segments.dropLast(1)
        val baseLeaf = segments.last()
        val dotIdx = baseLeaf.lastIndexOf('.')
        val stem = if (dotIdx > 0) baseLeaf.substring(0, dotIdx) else baseLeaf
        val ext = if (dotIdx > 0) baseLeaf.substring(dotIdx) else ""

        return withPinnedDir(root, dirSegments, createMissing = true) { sds, dirPath ->
            val options: Set<OpenOption> = setOf(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
            var attempt = 0
            var claimedName = baseLeaf
            var channel: SeekableByteChannel? = null
            while (channel == null) {
                claimedName = if (attempt == 0) baseLeaf else "$stem ($attempt)$ext"
                try {
                    channel = sds.newByteChannel(dirPath.fileSystem.getPath(claimedName), options)
                } catch (_: FileAlreadyExistsException) {
                    attempt++
                    if (attempt > 10_000) throw IOException("Too many conflicting files for $baseLeaf")
                }
            }
            val claimedSeg = dirPath.fileSystem.getPath(claimedName)
            val claimedPath = dirPath.resolve(claimedName)
            var total = 0L
            try {
                inputStream.use { input ->
                    Channels.newOutputStream(channel).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= maxBytes) {
                                "File is too large to import: more than $maxBytes bytes"
                            }
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }
            } catch (e: Throwable) {
                runCatching { sds.deleteFile(claimedSeg) }
                throw e
            }
            val lastModifiedMs = runCatching {
                sds.getFileAttributeView(claimedSeg, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes()
                    .lastModifiedTime()
                    .toMillis()
            }.getOrElse { System.currentTimeMillis() }
            WriteResult(
                file = claimedPath.toFile(),
                leafName = claimedName,
                sizeBytes = total,
                lastModifiedMs = lastModifiedMs,
            )
        }
    }

    /**
     * Atomically creates a unique non-existing file under pinned [root]/[relPath] using
     * `CREATE_NEW | WRITE | NOFOLLOW_LINKS`, streams up to [maxBytes], and deletes via
     * pinned parent FD on failure.
     */
    fun importStreamNoFollow(
        root: File,
        relPath: String,
        inputStream: InputStream,
        maxBytes: Long,
    ): File = importStreamNoFollowWithMeta(root, relPath, inputStream, maxBytes).file

    /**
     * Deletes [relPath] inside pinned [root] strictly via directory-FD-relative operations.
     * Symlinks at the final segment are unlinked directly; symlink ancestors are rejected.
     */
    fun deleteChildInRootNoFollow(
        root: File,
        relPath: String,
        recursive: Boolean,
    ): Boolean {
        val segments = parseRelativeSegments(relPath)
        require(segments.isNotEmpty()) { "Refusing to delete workspace root" }
        val dirSegments = segments.dropLast(1)
        val leaf = segments.last()
        return try {
            withPinnedDir(root, dirSegments, createMissing = false) { sds, dirPath ->
                val leafName = dirPath.fileSystem.getPath(leaf)
                val attrs = try {
                    sds.getFileAttributeView(leafName, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                        .readAttributes()
                } catch (_: NoSuchFileException) {
                    return@withPinnedDir false
                }
                if (attrs.isSymbolicLink || !attrs.isDirectory) {
                    sds.deleteFile(leafName)
                    true
                } else {
                    require(recursive) { "Directory delete requires recursive = true" }
                    sds.newDirectoryStream(leafName, LinkOption.NOFOLLOW_LINKS).use { childSds ->
                        clearSecureDirectory(childSds)
                    }
                    sds.deleteDirectory(leafName)
                    true
                }
            }
        } catch (_: NoSuchFileException) {
            false
        }
    }

    /**
     * Moves [sourceRelPath] to [targetRelPath] within pinned [root] strictly via FD-relative
     * operations without following any symlinks.
     *
     * When [overwrite] is `false`:
     * - Uses kernel `renameat2(RENAME_NOREPLACE)` via `libworkspace.so` ([NativeSafeFiles]) for
     *   true atomic non-overwrite move on the same volume (works for files, directories, and
     *   same-volume cross-workspace moves without copying or deleting).
     * - On Android, fails closed if `libworkspace.so` is unavailable rather than falling back to
     *   a racy check-then-rename.
     * - On non-Android host JVM unit tests where Android `.so` binaries are absent, serializes
     *   the pinned check-and-rename fallback under [jvmNonAtomicMoveLock].
     */
    fun moveNoFollow(
        root: File,
        sourceRelPath: String,
        targetRelPath: String,
        overwrite: Boolean,
    ): File {
        val srcSegments = parseRelativeSegments(sourceRelPath)
        val dstSegments = parseRelativeSegments(targetRelPath)
        require(srcSegments.isNotEmpty()) { "Refusing to move workspace root" }
        require(dstSegments.isNotEmpty()) { "Refusing to overwrite workspace root" }
        if (srcSegments == dstSegments) {
            statEntryNoFollow(root, sourceRelPath)
            var cur = root.toPath()
            for (seg in srcSegments) cur = cur.resolve(seg)
            return cur.toFile()
        }
        if (dstSegments.size > srcSegments.size && dstSegments.subList(0, srcSegments.size) == srcSegments) {
            throw IllegalArgumentException("Cannot move a directory into itself")
        }

        val srcDirSegments = srcSegments.dropLast(1)
        val srcLeaf = srcSegments.last()
        val dstDirSegments = dstSegments.dropLast(1)
        val dstLeaf = dstSegments.last()

        if (!overwrite) {
            if (NativeSafeFiles.isLoaded) {
                return NativeSafeFiles.moveNoReplace(
                    root = root,
                    srcDirSegments = srcDirSegments,
                    srcLeaf = srcLeaf,
                    dstDirSegments = dstDirSegments,
                    dstLeaf = dstLeaf,
                    createMissingDstDirs = true,
                    sourceDisplay = sourceRelPath,
                    targetDisplay = targetRelPath,
                )
            }
            if (NativeSafeFiles.isAndroidRuntime()) {
                throw IOException(
                    "Native atomic non-overwrite move (libworkspace / renameat2 RENAME_NOREPLACE) is unavailable"
                )
            }
            return synchronized(jvmNonAtomicMoveLock) {
                movePinnedSecureStreams(
                    root = root,
                    srcDirSegments = srcDirSegments,
                    srcLeaf = srcLeaf,
                    dstDirSegments = dstDirSegments,
                    dstLeaf = dstLeaf,
                    sourceRelPath = sourceRelPath,
                    targetRelPath = targetRelPath,
                    overwrite = false,
                )
            }
        }

        return movePinnedSecureStreams(
            root = root,
            srcDirSegments = srcDirSegments,
            srcLeaf = srcLeaf,
            dstDirSegments = dstDirSegments,
            dstLeaf = dstLeaf,
            sourceRelPath = sourceRelPath,
            targetRelPath = targetRelPath,
            overwrite = true,
        )
    }

    private fun movePinnedSecureStreams(
        root: File,
        srcDirSegments: List<String>,
        srcLeaf: String,
        dstDirSegments: List<String>,
        dstLeaf: String,
        sourceRelPath: String,
        targetRelPath: String,
        overwrite: Boolean,
    ): File = withPinnedDir(root, srcDirSegments, createMissing = false) { srcSds, srcDirPath ->
        val srcName = srcDirPath.fileSystem.getPath(srcLeaf)
        val srcAttrs = try {
            srcSds.getFileAttributeView(srcName, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                .readAttributes()
        } catch (_: NoSuchFileException) {
            throw IllegalArgumentException("Source does not exist: $sourceRelPath")
        }
        require(!srcAttrs.isSymbolicLink) { "Refusing to move symbolic link: $sourceRelPath" }

        withPinnedDirAndRoot(root, dstDirSegments, createMissing = true) { _, _, dstSds, dstDirPath, dstAncestorKeys ->
            if (srcAttrs.isDirectory && srcAttrs.fileKey() != null && srcAttrs.fileKey() in dstAncestorKeys) {
                throw IllegalArgumentException("Cannot move a directory into itself")
            }
            val dstName = dstDirPath.fileSystem.getPath(dstLeaf)
            val dstPath = dstDirPath.resolve(dstLeaf)
            val dstAttrs = runCatching {
                dstSds.getFileAttributeView(dstName, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes()
            }.getOrNull()

            if (dstAttrs != null) {
                require(overwrite) { "Target already exists: $targetRelPath" }
                if (dstAttrs.isSymbolicLink || !dstAttrs.isDirectory) {
                    dstSds.deleteFile(dstName)
                } else {
                    dstSds.newDirectoryStream(dstName, LinkOption.NOFOLLOW_LINKS).use { childSds ->
                        clearSecureDirectory(childSds)
                    }
                    dstSds.deleteDirectory(dstName)
                }
            }

            srcSds.move(srcName, dstSds, dstName)
            dstPath.toFile()
        }
    }

    /**
     * Deletes [target] and, if it is a real directory (not a symlink), its contents using
     * FD-relative [SecureDirectoryStream] traversal. Directory symlinks are unlinked directly
     * without touching their target.
     */
    fun deleteRecursivelyNoFollow(target: File): Boolean {
        val rootPath = target.toPath()
        if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            return true
        }
        if (Files.isSymbolicLink(rootPath) || !Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            return runCatching {
                Files.deleteIfExists(rootPath)
                !Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)
            }.getOrDefault(false)
        }
        val cleared = clearDirectoryContentsNoFollow(target)
        val removedSelf = runCatching {
            Files.deleteIfExists(rootPath)
            !Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)
        }.getOrDefault(false)
        return cleared && removedSelf
    }

    /**
     * Removes all children inside [dir] without deleting [dir] itself and without following
     * symlinks. Strictly requires [SecureDirectoryStream].
     */
    fun clearDirectoryContentsNoFollow(dir: File): Boolean {
        val dirPath = dir.toPath()
        if (!Files.exists(dirPath, LinkOption.NOFOLLOW_LINKS)) return true
        if (Files.isSymbolicLink(dirPath) || !Files.isDirectory(dirPath, LinkOption.NOFOLLOW_LINKS)) {
            return false
        }
        return runCatching {
            val (sds, _) = openSecureRoot(dir)
            sds.use { clearSecureDirectory(it) }
        }.getOrDefault(false)
    }

    /**
     * Safely clears the contents of `[baseDir]/[segments...]` by pinning [baseDir] and stepping
     * through every ancestor segment with `NOFOLLOW_LINKS`. If any ancestor (e.g. workspace root,
     * `linux`, `var`, `tmp`) is missing or is a symlink, returns safely without touching anything
     * outside [baseDir].
     */
    fun clearChildDirContentsNoFollow(baseDir: File, vararg segments: String): Boolean {
        if (!Files.exists(baseDir.toPath(), LinkOption.NOFOLLOW_LINKS)) return true
        if (Files.isSymbolicLink(baseDir.toPath())) return false
        return try {
            withPinnedDir(baseDir, segments.toList(), createMissing = false) { sds, _ ->
                clearSecureDirectory(sds)
            }
        } catch (_: NoSuchFileException) {
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun clearSecureDirectory(sds: SecureDirectoryStream<Path>): Boolean {
        var allDeleted = true
        val childNames = mutableListOf<Path>()
        for (entry in sds) {
            childNames.add(entry.fileName)
        }
        for (name in childNames) {
            val attrs = runCatching {
                sds.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes()
            }.getOrNull() ?: continue
            if (attrs.isSymbolicLink || !attrs.isDirectory) {
                if (!runCatching { sds.deleteFile(name); true }.getOrDefault(false)) {
                    allDeleted = false
                }
            } else {
                val subOk = runCatching {
                    sds.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { sub ->
                        clearSecureDirectory(sub)
                    }
                }.getOrDefault(false)
                val rmDirOk = runCatching { sds.deleteDirectory(name); true }.getOrDefault(false)
                if (!subOk || !rmDirOk) allDeleted = false
            }
        }
        return allDeleted
    }

    /**
     * Recursively copies `[srcRoot]/[srcRelPath]` into `[dstRoot]/[dstParentRelPath]/[requestedName]`
     * strictly through pinned [SecureDirectoryStream] handles from both uncontrolled roots.
     *
     * - Detects directory-into-self cycles via kernel `fileKey()` inode identity across all
     *   pinned destination ancestors (never relies on string `canonicalPath`).
     * - When [allocateUniqueName] is true, chooses a non-conflicting name (`name`, `stem (1).ext`,
     *   ...) inside the pinned destination directory.
     * - Creates subdirectories via [createDirectoryInPinnedDir] (`renameat`) and copies files via
     *   `srcSds.newByteChannel` -> `dstSds.newByteChannel` (`O_NOFOLLOW | CREATE_NEW`).
     * - Cleans up partial output via pinned `dstParentSds` on any error.
     *
     * Returns the final created leaf name inside `[dstRoot]/[dstParentRelPath]`.
     */
    fun copyInRootNoFollow(
        srcRoot: File,
        srcRelPath: String,
        dstRoot: File,
        dstParentRelPath: String,
        requestedName: String,
        allocateUniqueName: Boolean = false,
    ): String {
        val srcSegments = parseRelativeSegments(srcRelPath)
        require(srcSegments.isNotEmpty()) { "Source cannot be root directory" }
        val srcParentSegments = srcSegments.dropLast(1)
        val srcLeaf = srcSegments.last()
        val dstParentSegments = parseRelativeSegments(dstParentRelPath)

        require(
            requestedName.isNotEmpty() &&
                requestedName != "." &&
                requestedName != ".." &&
                !requestedName.contains('/') &&
                !requestedName.contains('\\') &&
                !requestedName.contains('\u0000')
        ) { "Invalid destination name: $requestedName" }

        val dotIdx = requestedName.lastIndexOf('.')
        val stem = if (dotIdx > 0) requestedName.substring(0, dotIdx) else requestedName
        val ext = if (dotIdx > 0) requestedName.substring(dotIdx) else ""

        return withPinnedDir(srcRoot, srcParentSegments, createMissing = false) { srcParentSds, srcParentPath ->
            val srcName = srcParentPath.fileSystem.getPath(srcLeaf)
            val srcAttrs = try {
                srcParentSds.getFileAttributeView(srcName, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes()
            } catch (e: NoSuchFileException) {
                throw IllegalArgumentException("Source does not exist: $srcLeaf", e)
            }
            require(!srcAttrs.isSymbolicLink) { "Refusing to copy symbolic link: $srcLeaf" }
            val srcKey = srcAttrs.fileKey()

            withPinnedDirAndRoot(
                root = dstRoot,
                dirSegments = dstParentSegments,
                createMissing = false,
            ) { dstRootSds, dstRootPath, dstParentSds, dstParentPath, dstAncestorKeys ->
                if (srcAttrs.isDirectory && srcKey != null && srcKey in dstAncestorKeys) {
                    throw IllegalArgumentException("Cannot copy a directory into itself")
                }

                val fs = dstParentPath.fileSystem
                val readOpts: Set<OpenOption> = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
                val writeOpts: Set<OpenOption> = setOf(
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                )

                var attempt = 0
                while (true) {
                    val candidateName = if (attempt == 0) requestedName else "$stem ($attempt)$ext"
                    val candidateSeg = fs.getPath(candidateName)
                    if (!srcAttrs.isDirectory) {
                        val outCh = try {
                            dstParentSds.newByteChannel(candidateSeg, writeOpts)
                        } catch (e: FileAlreadyExistsException) {
                            if (!allocateUniqueName) {
                                throw IllegalArgumentException("Target already exists: $requestedName", e)
                            }
                            attempt++
                            if (attempt > 10_000) throw IOException("Too many conflicting names for $requestedName")
                            continue
                        }
                        try {
                            srcParentSds.newByteChannel(srcName, readOpts).use { inCh ->
                                outCh.use { out ->
                                    Channels.newInputStream(inCh).use { inp ->
                                        Channels.newOutputStream(out).use { os ->
                                            inp.copyTo(os)
                                            os.flush()
                                        }
                                    }
                                }
                            }
                            return@withPinnedDirAndRoot candidateName
                        } catch (e: Throwable) {
                            runCatching { dstParentSds.deleteFile(candidateSeg) }
                            throw e
                        }
                    } else {
                        try {
                            createDirectoryInPinnedDir(
                                rootSds = dstRootSds,
                                rootPath = dstRootPath,
                                parentSds = dstParentSds,
                                childName = candidateName,
                                failIfExists = true,
                            )
                        } catch (e: FileAlreadyExistsException) {
                            if (!allocateUniqueName) {
                                throw IllegalArgumentException("Target already exists: $requestedName", e)
                            }
                            attempt++
                            if (attempt > 10_000) throw IOException("Too many conflicting names for $requestedName")
                            continue
                        }
                        try {
                            srcParentSds.newDirectoryStream(srcName, LinkOption.NOFOLLOW_LINKS).use { subSrc ->
                                dstParentSds.newDirectoryStream(candidateSeg, LinkOption.NOFOLLOW_LINKS).use { subDst ->
                                    copySecureDirectory(dstRootSds, dstRootPath, subSrc, subDst)
                                }
                            }
                            return@withPinnedDirAndRoot candidateName
                        } catch (e: Throwable) {
                            runCatching {
                                dstParentSds.newDirectoryStream(candidateSeg, LinkOption.NOFOLLOW_LINKS).use { subDst ->
                                    clearSecureDirectory(subDst)
                                }
                                dstParentSds.deleteDirectory(candidateSeg)
                            }
                            throw e
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                throw IllegalStateException("Unreachable")
            }
        }
    }

    /**
     * Convenience wrapper over [copyInRootNoFollow] pinning both parent directories via
     * [SecureDirectoryStream]. Never follows symlinks and cleans up partial output on failure.
     */
    fun copyRecursivelyNoFollow(source: File, target: File): Boolean {
        val srcParent = source.parentFile
            ?: throw IllegalArgumentException("Source has no parent directory: ${source.name}")
        val dstParent = target.parentFile
            ?: throw IllegalArgumentException("Target has no parent directory: ${target.name}")
        copyInRootNoFollow(
            srcRoot = srcParent,
            srcRelPath = source.name,
            dstRoot = dstParent,
            dstParentRelPath = "",
            requestedName = target.name,
            allocateUniqueName = false,
        )
        return true
    }

    private fun copySecureDirectory(
        dstRootSds: SecureDirectoryStream<Path>,
        dstRootPath: Path,
        srcSds: SecureDirectoryStream<Path>,
        dstSds: SecureDirectoryStream<Path>,
    ) {
        val readOpts: Set<OpenOption> = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        val writeOpts: Set<OpenOption> = setOf(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        for (entry in srcSds) {
            val name = entry.fileName
            val nameStr = name.toString()
            if (nameStr.startsWith(MKDIR_STAGE_PREFIX)) continue
            val attrs = srcSds.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                .readAttributes()
            if (attrs.isSymbolicLink) {
                throw IllegalArgumentException("Refusing to copy symbolic link: $name")
            }
            if (attrs.isDirectory) {
                createDirectoryInPinnedDir(
                    rootSds = dstRootSds,
                    rootPath = dstRootPath,
                    parentSds = dstSds,
                    childName = nameStr,
                    failIfExists = true,
                )
                srcSds.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { subSrc ->
                    dstSds.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { subDst ->
                        copySecureDirectory(dstRootSds, dstRootPath, subSrc, subDst)
                    }
                }
            } else if (attrs.isRegularFile) {
                srcSds.newByteChannel(name, readOpts).use { inCh ->
                    dstSds.newByteChannel(name, writeOpts).use { outCh ->
                        Channels.newInputStream(inCh).use { inp ->
                            Channels.newOutputStream(outCh).use { out ->
                                inp.copyTo(out)
                                out.flush()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Copies a regular file inside pinned [root] directly into [outputStream] via `O_NOFOLLOW`.
     */
    fun exportFileNoFollow(
        root: File,
        relPath: String,
        outputStream: OutputStream,
        displayPath: String = relPath,
    ) {
        outputStream.use { out ->
            openInputStreamNoFollow(root, relPath, displayPath = displayPath).use { inp ->
                inp.copyTo(out)
                out.flush()
            }
        }
    }
}
