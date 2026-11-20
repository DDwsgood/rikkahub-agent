package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.name

class WorkspaceFileSystem(
    private val config: WorkspaceConfig = WorkspaceConfig(),
) {
    fun list(root: File, path: String = ""): List<WorkspaceFileEntry> {
        val dir = resolvePath(root, path)
        require(Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Path does not exist: $path" }
        require(Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Path is not a directory: $path" }
        return dir.listFiles()
            .orEmpty()
            .filter { !it.name.startsWith(".l2s.") }
            .filter { !Files.isSymbolicLink(it.toPath()) }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .take(config.maxListEntries)
            .map { it.toEntry(root) }
    }

    fun readText(root: File, path: String, charset: Charset = StandardCharsets.UTF_8): String {
        ensureRootReady(root)
        val attrs = WorkspaceSafeFiles.statRegularFileNoFollow(root, path)
        require(attrs.size() <= config.maxReadBytes) {
            "File is too large to read: ${attrs.size()} bytes"
        }
        return WorkspaceSafeFiles.openInputStreamNoFollow(root, path).use { inp ->
            val bytes = inp.readBytes()
            require(bytes.size <= config.maxReadBytes) {
                "File is too large to read: ${bytes.size} bytes"
            }
            String(bytes, charset)
        }
    }

    fun writeText(
        root: File,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry {
        ensureRootReady(root)
        val bytes = text.toByteArray(charset)
        require(bytes.size <= config.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes"
        }
        val written = WorkspaceSafeFiles.writeBytesNoFollow(root, path, bytes, overwrite)
        return written.toEntry(root)
    }

    fun importBytes(root: File, path: String, inputStream: InputStream): WorkspaceFileEntry {
        ensureRootReady(root)
        val imported = WorkspaceSafeFiles.importStreamNoFollow(
            root = root,
            relPath = path,
            inputStream = inputStream,
            maxBytes = config.maxImportBytes,
        )
        return imported.toEntry(root)
    }

    fun delete(root: File, path: String, recursive: Boolean = false): Boolean {
        require(path.isNotBlank()) { "Refusing to delete workspace root" }
        ensureRootReady(root)
        return WorkspaceSafeFiles.deleteChildInRootNoFollow(root, path, recursive)
    }

    fun move(root: File, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry {
        require(source.isNotBlank()) { "Refusing to move workspace root" }
        ensureRootReady(root)
        val moved = WorkspaceSafeFiles.moveNoFollow(root, source, target, overwrite)
        return moved.toEntry(root)
    }

    fun glob(root: File, pattern: String, path: String = ""): List<WorkspaceFileEntry> {
        require(pattern.isNotBlank()) { "Glob pattern is required" }
        val start = resolvePath(root, path)
        require(Files.exists(start.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Path does not exist: $path" }
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
        return walk(start) { paths ->
            paths
                .filter {
                    Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) ||
                        Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)
                }
                .filter { !it.toFile().name.startsWith(".l2s.") }
                .filter { matcher.matches(root.toPath().relativize(it).normalizeForMatch()) }
                .take(config.maxListEntries)
                .map { it.toFile().toEntry(root) }
                .toList()
        }
    }

    fun grep(
        root: File,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> {
        require(query.isNotBlank()) { "Search query is required" }
        val start = resolvePath(root, path)
        require(Files.exists(start.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Path does not exist: $path" }
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val matcher = if (regex) Regex(query, options) else Regex(Regex.escape(query), options)
        val includeMatcher = includeGlob
            ?.takeIf { it.isNotBlank() }
            ?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }

        val results = mutableListOf<WorkspaceSearchMatch>()
        walk(start) { paths ->
            paths
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .filter { !it.toFile().name.startsWith(".l2s.") }
                .forEach { filePath ->
                    if (results.size >= config.maxSearchResults) return@forEach
                    val relStr = root.toPath().relativize(filePath).relativeToString()
                    if (includeMatcher != null &&
                        !includeMatcher.matches(root.toPath().relativize(filePath).normalizeForMatch())
                    ) {
                        return@forEach
                    }
                    runCatching {
                        val attrs = WorkspaceSafeFiles.statRegularFileNoFollow(root, relStr)
                        if (attrs.size() > config.maxReadBytes) return@forEach
                        WorkspaceSafeFiles.openInputStreamNoFollow(root, relStr).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                            var index = 0
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (results.size >= config.maxSearchResults) break
                                if (matcher.containsMatchIn(line)) {
                                    results += WorkspaceSearchMatch(
                                        path = relStr,
                                        line = index + 1,
                                        text = line,
                                    )
                                }
                                index++
                            }
                        }
                    }
                }
        }
        return results
    }

    private fun ensureRootReady(root: File) {
        val rootPath = root.toPath()
        if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            root.mkdirs()
        }
        WorkspaceSafeFiles.requireRealDirNoSymlink(root)
    }

    private fun <T> walk(start: File, block: (Sequence<Path>) -> T): T =
        Files.walk(start.toPath()).use { stream ->
            block(stream.iterator().asSequence())
        }

    private fun resolvePath(root: File, path: String): File {
        ensureRootReady(root)
        return WorkspaceSafeFiles.resolveNoSymlinkPath(root, path, allowFinalSymlink = false)
    }

    fun resolve(root: File, path: String): File = resolvePath(root, path)

    private fun File.toEntry(root: File): WorkspaceFileEntry {
        val p = toPath()
        val isDir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
        val isRegular = Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
        return WorkspaceFileEntry(
            path = relativePath(root),
            name = name,
            isDirectory = isDir,
            sizeBytes = if (isRegular) Files.size(p) else 0L,
            updatedAt = lastModified(),
        )
    }

    private fun File.relativePath(root: File): String {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val selfPath = toPath().toAbsolutePath().normalize()
        return rootPath.relativize(selfPath).toString().replace(File.separatorChar, '/')
    }

    private fun Path.normalizeForMatch(): Path =
        FileSystems.getDefault().getPath(relativeToString())

    private fun Path.relativeToString(): String =
        joinToString("/") { it.name }
}
