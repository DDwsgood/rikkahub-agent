package me.rerere.rikkahub.data.files

import android.Manifest
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.provider.WorkspaceDocumentsProvider
import me.rerere.workspace.WorkspaceSafeFiles
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.util.UUID

data class ExportedDownloadResult(
    val displayPath: String,
    val contentUri: String?,
    val bytesWritten: Long,
)

enum class ExportErrorCode {
    PERMISSION_REQUIRED,
    FILE_TOO_LARGE,
    MEDIASTORE_FAILED,
    IO_FAILED,
}

class DownloadExportException(
    val code: ExportErrorCode,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Controlled export, external-viewer staging, and SAF shortcuts for workspace files.
 *
 * Keeps PRoot workspaces in fast app-private storage (preserving POSIX permissions,
 * symlinks, git, npm, and Python venv) while giving users and the AI clean, zero-root
 * delivery paths to Android Downloads, external viewers, Share sheets, and SAF Files.
 */
object WorkspacePublicExport {

    private const val SHARE_CACHE_DIR = "workspace_share"
    private const val SHARE_CACHE_TTL_MS = 6L * 60 * 60 * 1000L // 6 hours
    const val DEFAULT_STAGE_MAX_BYTES = 100L * 1024 * 1024 // 100 MB cap

    /**
     * Lexically normalizes an absolute Rootfs path (`//` collapsed, `.` and `..` resolved).
     * Rejects NUL bytes and non-absolute paths.
     */
    fun normalizeRootfsPath(raw: String): String {
        val cleaned = raw.replace('\\', '/').trim()
        require(cleaned.isNotBlank()) { "Path is required" }
        require(cleaned.startsWith("/")) { "Path must be absolute: $raw" }
        require(!cleaned.contains('\u0000')) { "Path contains invalid character" }

        val segments = ArrayDeque<String>()
        for (part in cleaned.split('/')) {
            when (part) {
                "", "." -> continue
                ".." -> if (segments.isNotEmpty()) segments.removeLast()
                else -> segments.addLast(part)
            }
        }
        return if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
    }

    fun guessMimeType(fileName: String, fallback: String = "application/octet-stream"): String {
        val ext = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (ext.isBlank()) return fallback
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: fallback
    }

    fun sanitizeFileName(raw: String): String {
        val leaf = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim()
            .trim('.')
        return if (leaf.isBlank() || leaf == "." || leaf == "..") "export.bin" else leaf.take(180)
    }

    private fun sanitizeFolderName(raw: String): String {
        val cleaned = raw.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim()
            .trim('.')
            .take(48)
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "default" else cleaned
    }

    /**
     * Atomically creates a unique non-existing file inside [dir] using `CREATE_NEW | NOFOLLOW_LINKS`,
     * eliminating check-then-open race conditions on Android 8–9 legacy Downloads export.
     */
    internal fun claimUniqueTargetFile(dir: File, fileName: String): Pair<File, OutputStream> {
        val safeName = sanitizeFileName(fileName)
        val dotIdx = safeName.lastIndexOf('.')
        val stem = if (dotIdx > 0) safeName.substring(0, dotIdx) else safeName
        val ext = if (dotIdx > 0) safeName.substring(dotIdx) else ""
        val opts: Array<OpenOption> = arrayOf(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        var attempt = 0
        while (attempt <= 10_000) {
            val candidateName = if (attempt == 0) safeName else "$stem ($attempt)$ext"
            val candidate = File(dir, candidateName)
            try {
                val out = Files.newOutputStream(candidate.toPath(), *opts)
                return candidate to out
            } catch (_: FileAlreadyExistsException) {
                attempt++
            }
        }
        throw DownloadExportException(ExportErrorCode.IO_FAILED, "Could not allocate unique download filename")
    }

    /**
     * Exports a workspace file into Android's public Downloads folder:
     * `Download/RikkaHub/<workspaceName>/<fileName>`.
     *
     * - On Android 10+ (API 29+): uses `MediaStore.Downloads` (zero storage permissions),
     *   confirms `IS_PENDING = 0` publish update, and queries back the actual assigned display name.
     * - On Android 8–9 (API 26–28): verifies runtime `WRITE_EXTERNAL_STORAGE` grant, atomically
     *   claims a unique filename with `CREATE_NEW`, and notifies `MediaScannerConnection`;
     *   throws [DownloadExportException] with [ExportErrorCode.PERMISSION_REQUIRED] when ungranted.
     */
    fun exportToDownloads(
        context: Context,
        workspaceName: String,
        fileName: String,
        writeTo: (OutputStream) -> Unit,
    ): ExportedDownloadResult {
        val safeName = sanitizeFileName(fileName)
        val safeWorkspace = sanitizeFolderName(workspaceName)
        val mime = guessMimeType(safeName)
        val relativeDir = "${Environment.DIRECTORY_DOWNLOADS}/RikkaHub/$safeWorkspace"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, relativeDir)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw DownloadExportException(
                    ExportErrorCode.MEDIASTORE_FAILED,
                    "Failed to create download entry in MediaStore",
                )
            var written = 0L
            try {
                resolver.openOutputStream(uri)?.use { rawOut ->
                    val counting = BoundedCountingOutputStream(rawOut, maxBytes = Long.MAX_VALUE)
                    writeTo(counting)
                    counting.flush()
                    written = counting.bytesWritten
                } ?: throw DownloadExportException(
                    ExportErrorCode.MEDIASTORE_FAILED,
                    "Could not open MediaStore output stream",
                )
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                val updatedRows = resolver.update(uri, done, null, null)
                if (updatedRows <= 0) {
                    throw DownloadExportException(
                        ExportErrorCode.MEDIASTORE_FAILED,
                        "MediaStore failed to publish download entry",
                    )
                }
            } catch (e: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                if (e is DownloadExportException) throw e
                throw DownloadExportException(ExportErrorCode.IO_FAILED, e.message ?: "Export failed", e)
            }
            val actualName = runCatching {
                resolver.query(
                    uri,
                    arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.RELATIVE_PATH),
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: safeName
                        val rel = cursor.getString(1)?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: relativeDir
                        "$rel/$name"
                    } else null
                }
            }.getOrNull() ?: "$relativeDir/$safeName"
            return ExportedDownloadResult(
                displayPath = actualName,
                contentUri = uri.toString(),
                bytesWritten = written,
            )
        } else {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                throw DownloadExportException(
                    ExportErrorCode.PERMISSION_REQUIRED,
                    "WRITE_EXTERNAL_STORAGE required on API < 29",
                )
            }
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(File(downloadsDir, "RikkaHub"), safeWorkspace).apply { mkdirs() }
            if (!targetDir.isDirectory) {
                throw DownloadExportException(ExportErrorCode.IO_FAILED, "Could not create Downloads directory")
            }
            val (targetFile, rawOut) = claimUniqueTargetFile(targetDir, safeName)
            var written = 0L
            try {
                rawOut.use { stream ->
                    val counting = BoundedCountingOutputStream(stream, maxBytes = Long.MAX_VALUE)
                    writeTo(counting)
                    counting.flush()
                    written = counting.bytesWritten
                }
            } catch (e: Throwable) {
                runCatching { Files.deleteIfExists(targetFile.toPath()) }
                if (e is DownloadExportException) throw e
                throw DownloadExportException(ExportErrorCode.IO_FAILED, e.message ?: "Export failed", e)
            }
            MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf(mime), null)
            return ExportedDownloadResult(
                displayPath = "$relativeDir/${targetFile.name}",
                contentUri = null,
                bytesWritten = written,
            )
        }
    }

    /**
     * Cooperatively cancellable, chunk-bounded staging into `cacheDir/workspace_share/<uuid>/`.
     * Enforces [maxBytes] on every written chunk (cannot be bypassed by growing files after stat)
     * and cleans up the staging slot on error or coroutine cancellation.
     */
    suspend fun stageToRestrictedShareCacheSuspend(
        context: Context,
        fileName: String,
        maxBytes: Long = DEFAULT_STAGE_MAX_BYTES,
        writeTo: suspend (OutputStream) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val root = File(context.cacheDir, SHARE_CACHE_DIR).apply { mkdirs() }
        pruneStaleShareCache(root)
        val safeName = sanitizeFileName(fileName)
        val slotDir = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        val target = File(slotDir, safeName)
        val ctx = currentCoroutineContext()
        try {
            val opts: Array<OpenOption> = arrayOf(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
            Files.newOutputStream(target.toPath(), *opts).use { rawOut ->
                val bounded = BoundedCountingOutputStream(
                    delegate = rawOut,
                    maxBytes = maxBytes,
                    onChunk = { ctx.ensureActive() },
                )
                writeTo(bounded)
                bounded.flush()
            }
            target
        } catch (c: CancellationException) {
            runCatching { WorkspaceSafeFiles.deleteRecursivelyNoFollow(slotDir) }
            throw c
        } catch (e: Throwable) {
            runCatching { WorkspaceSafeFiles.deleteRecursivelyNoFollow(slotDir) }
            throw e
        }
    }

    /**
     * Copies [input] into [output] in chunks while enforcing [maxBytes] and checking coroutine
     * cancellation on every chunk.
     */
    suspend fun copyBoundedCancellable(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
    ): Long {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) {
                throw DownloadExportException(
                    ExportErrorCode.FILE_TOO_LARGE,
                    "File exceeds staging limit of $maxBytes bytes",
                )
            }
            output.write(buffer, 0, n)
        }
        output.flush()
        return total
    }

    fun stageToRestrictedShareCache(
        context: Context,
        fileName: String,
        maxBytes: Long = DEFAULT_STAGE_MAX_BYTES,
        writeTo: (OutputStream) -> Unit,
    ): File {
        val root = File(context.cacheDir, SHARE_CACHE_DIR).apply { mkdirs() }
        pruneStaleShareCache(root)
        val safeName = sanitizeFileName(fileName)
        val slotDir = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        val target = File(slotDir, safeName)
        try {
            val opts: Array<OpenOption> = arrayOf(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
            Files.newOutputStream(target.toPath(), *opts).use { rawOut ->
                val bounded = BoundedCountingOutputStream(rawOut, maxBytes = maxBytes)
                writeTo(bounded)
                bounded.flush()
            }
        } catch (e: Throwable) {
            runCatching { WorkspaceSafeFiles.deleteRecursivelyNoFollow(slotDir) }
            throw e
        }
        return target
    }

    fun openCachedFileExternally(context: Context, file: File, mimeOverride: String? = null): Boolean =
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mime = mimeOverride ?: guessMimeType(file.name, fallback = "*/*")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)

    fun shareCachedFile(context: Context, file: File): Boolean =
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mime = guessMimeType(file.name, fallback = "application/octet-stream")
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, file.name)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)

    /**
     * Opens the workspace in the system Files app via [WorkspaceDocumentsProvider] (SAF).
     * Returns `true` if an activity handled the request, `false` if the ROM lacks a SAF viewer.
     */
    fun openWorkspaceInSystemFiles(context: Context, workspaceRoot: String): Boolean {
        val authority = "${context.packageName}.documents"
        val docUri = DocumentsContract.buildDocumentUri(
            authority,
            WorkspaceDocumentsProvider.buildWorkspaceDocumentId(workspaceRoot),
        )
        val rootUri = DocumentsContract.buildRootUri(authority, WorkspaceDocumentsProvider.ROOT_ID)
        val candidates = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            },
            Intent(Intent.ACTION_VIEW, rootUri).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            },
        )
        for (intent in candidates) {
            if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) {
                return true
            }
        }
        return false
    }

    private fun pruneStaleShareCache(root: File) {
        val cutoff = System.currentTimeMillis() - SHARE_CACHE_TTL_MS
        runCatching {
            root.listFiles()?.forEach { child ->
                if (child.lastModified() < cutoff) {
                    WorkspaceSafeFiles.deleteRecursivelyNoFollow(child)
                }
            }
        }
    }

    internal class BoundedCountingOutputStream(
        private val delegate: OutputStream,
        private val maxBytes: Long,
        private val onChunk: () -> Unit = {},
    ) : OutputStream() {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            onChunk()
            if (bytesWritten + 1 > maxBytes) {
                throw DownloadExportException(
                    ExportErrorCode.FILE_TOO_LARGE,
                    "File exceeds maximum limit of $maxBytes bytes",
                )
            }
            delegate.write(b)
            bytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            onChunk()
            if (len < 0) return
            if (bytesWritten + len > maxBytes) {
                throw DownloadExportException(
                    ExportErrorCode.FILE_TOO_LARGE,
                    "File exceeds maximum limit of $maxBytes bytes",
                )
            }
            delegate.write(b, off, len)
            bytesWritten += len
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
}
