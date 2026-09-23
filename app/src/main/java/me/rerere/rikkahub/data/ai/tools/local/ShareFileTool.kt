package me.rerere.rikkahub.data.ai.tools.local

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.termux.TermuxEnvironment
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Single-file share size cap. 256 MiB — covers most photos, documents, audio, and short
 * videos while preventing OOM from a single very large file copy. The copy is streamed
 * (8 KB buffer) so memory usage is bounded regardless of file size; this cap protects
 * disk space in the share cache and gives the user a fast, structured error instead of
 * a silent multi-minute copy of an accidentally-huge file.
 */
internal const val SHARE_FILE_MAX_BYTES = 256L * 1024 * 1024

/** Buffer size for the stream copy into the share cache. */
private const val COPY_BUFFER = 8 * 1024

/** Stale share-cache invocation dirs older than this are reaped on each call. 1 hour. */
private const val SHARE_CACHE_TTL_MS = 60L * 60 * 1000

/** Subdirectory under [Context.cacheDir] used for share-file staging. */
private const val SHARE_CACHE_DIR = "share"

/** Strict lowercase prefix for embedded Termux paths. `TERMUX:` is rejected. */
private const val TERMUX_PREFIX = "termux:"

/** MIME used when neither the caller nor the OS can determine a type. */
internal const val FALLBACK_MIME = "application/octet-stream"

/** Max length for the EXTRA_TEXT caption. Android's Binder transaction limit is ~1 MB;
 * this is far below it and well above any reasonable share caption. */
internal const val SHARE_FILE_MAX_CAPTION = 10_000

/** Max length for EXTRA_SUBJECT. Email subjects are rarely longer; this prevents abuse. */
internal const val SHARE_FILE_MAX_SUBJECT = 200

/** RFC 2045 token characters for MIME type/subtype validation. */
private val MIME_TOKEN_REGEX = Regex("^[a-zA-Z0-9!#$&^_\\-.+]+/[a-zA-Z0-9!#$&^_\\-.+]+$")

/** Detects any `scheme://` URI (content://, file://, http://, custom://, …). */
private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

/**
 * Dedicated exception for file-size-limit violations. Lets the tool's error mapping
 * use a type check instead of fragile string matching on IOException messages.
 */
internal class ShareFileTooLargeException(message: String) : IOException(message)

/**
 * Allowed canonical roots for public storage. After canonical resolution, a
 * PublicStorage source path must start with one of these (followed by a separator)
 * or exactly equal one — this prevents symlinks from escaping to own-app sensitive
 * directories (e.g. `/sdcard/../../data/data/<pkg>/databases`).
 *
 * Pure function — no file I/O — so it is unit-testable directly.
 */
private val PUBLIC_STORAGE_ROOTS: List<String> = listOf(
    "/sdcard",
    "/storage/emulated",
    "/storage/self",
)

internal fun isWithinPublicStorage(canonicalPath: String): Boolean {
    // Android canonical paths always use '/' as separator. Using File.separator here
    // would break on Windows JVM tests (where separator is '\') — the function operates
    // on Android filesystem paths, not local-JVM paths.
    val sep = "/"
    return PUBLIC_STORAGE_ROOTS.any { root ->
        canonicalPath == root || canonicalPath.startsWith("$root$sep")
    } || (canonicalPath.startsWith("/storage/") && canonicalPath.removePrefix("/storage/").let {
        // /storage/<volume-id>/... — at least one non-empty path component after /storage/
        it.isNotEmpty() && !it.startsWith(sep)
    })
}

// ---------- source classification (pure, no file I/O) ----------

/**
 * The allowed source categories for [shareFileTool]. Classification is done by
 * [classifyShareSource] which is a pure function — no file I/O, no stat — so the
 * allowlist is enforced BEFORE any file is touched.
 */
internal sealed class ShareSource {
    /** `~` or `~/...` — app-private workspace (AgentWorkspace). */
    data class AgentWorkspace(val rawPath: String) : ShareSource()
    /** Public/shared storage: `/sdcard/`, `/storage/emulated/`, `/storage/self/`, `/storage/<id>/`. */
    data class PublicStorage(val rawPath: String) : ShareSource()
    /** `/workspace/...` — proot rootfs, exported via the bound workspace. */
    data class Workspace(val rawPath: String) : ShareSource()
    /** `termux:~/...` — embedded Termux (lowercase prefix only). */
    data class Termux(val rawPath: String) : ShareSource()
    /** Everything else — rejected with [reason] before any file access. */
    data class Unsupported(val rawPath: String, val reason: String) : ShareSource()
}

/**
 * Classify [rawPath] into an allowed source category without any file I/O.
 *
 * Allowlist:
 *  - `~` or `~/...` → AgentWorkspace (canonical containment verified later)
 *  - `/sdcard/...` → PublicStorage
 *  - `/storage/emulated/...` or `/storage/self/...` → PublicStorage
 *  - `/storage/<volume-id>/...` (removable storage) → PublicStorage
 *  - `/workspace/...` → Workspace
 *  - `termux:~/...` or `termux:/...` (lowercase only) → Termux
 *
 * Rejected (`unsupported_source`):
 *  - `content://`, `file://`, any other `scheme://` URI
 *  - `TERMUX:` (uppercase) — only lowercase `termux:` is accepted
 *  - Bare `/storage` or `/storage/` (no volume-id)
 *  - Direct `/data/data/<own-package>/...` (use `~/` or `termux:~/` instead)
 *  - Relative paths, unknown absolute paths
 */
internal fun classifyShareSource(rawPath: String): ShareSource {
    // -- URI schemes: all rejected --
    if (rawPath.startsWith("content://")) {
        return ShareSource.Unsupported(rawPath, "content:// URIs are not supported")
    }
    if (rawPath.startsWith("file://")) {
        return ShareSource.Unsupported(rawPath, "file:// URIs are not supported")
    }
    if (SCHEME_REGEX.matches(rawPath)) {
        return ShareSource.Unsupported(rawPath, "URI scheme is not supported")
    }

    // -- termux: (strict lowercase) --
    if (rawPath.startsWith(TERMUX_PREFIX)) {
        return ShareSource.Termux(rawPath)
    }

    // -- AgentWorkspace: ~ or ~/... --
    if (rawPath == "~" || rawPath.startsWith("~/")) {
        return ShareSource.AgentWorkspace(rawPath)
    }

    // -- /workspace/... --
    if (rawPath == "/workspace" || rawPath.startsWith("/workspace/")) {
        return ShareSource.Workspace(rawPath)
    }

    // -- Public storage --
    if (rawPath.startsWith("/sdcard/")) {
        return ShareSource.PublicStorage(rawPath)
    }
    if (rawPath.startsWith("/storage/emulated/") || rawPath.startsWith("/storage/self/")) {
        return ShareSource.PublicStorage(rawPath)
    }
    if (rawPath.startsWith("/storage/")) {
        // Removable storage: /storage/<volume-id>/... — need at least one path component.
        // Bare /storage/ (no volume-id) is rejected.
        val afterStorage = rawPath.removePrefix("/storage/")
        if (afterStorage.isNotEmpty() && !afterStorage.startsWith("/")) {
            return ShareSource.PublicStorage(rawPath)
        }
        return ShareSource.Unsupported(rawPath, "bare /storage/ root is not a valid file path")
    }

    // -- Everything else: rejected (including /data/data/<own-package>/...) --
    return ShareSource.Unsupported(rawPath, "path is not in an allowed source location")
}

// ---------- MIME validation (pure) ----------

/**
 * Validate a caller-supplied MIME override. Returns null when the value is null/blank
 * (meaning "don't override") or when it's a well-formed `type/subtype` token. Returns
 * an error message string when the format is invalid.
 */
internal fun validateMimeOverride(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val trimmed = raw.trim()
    if (!MIME_TOKEN_REGEX.matches(trimmed)) {
        return "mime_type must be a valid type/subtype (e.g. image/png); got: '$trimmed'"
    }
    return null
}

// ---------- caption/subject length validation (pure) ----------

/** Returns null when ok, an error message when the caption is too long. */
internal fun validateCaptionLength(raw: String?): String? {
    if (raw == null) return null
    if (raw.length > SHARE_FILE_MAX_CAPTION) {
        return "text caption must be <= $SHARE_FILE_MAX_CAPTION characters (got ${raw.length})"
    }
    return null
}

/** Returns null when ok, an error message when the subject is too long. */
internal fun validateSubjectLength(raw: String?): String? {
    if (raw == null) return null
    if (raw.length > SHARE_FILE_MAX_SUBJECT) {
        return "subject must be <= $SHARE_FILE_MAX_SUBJECT characters (got ${raw.length})"
    }
    return null
}

// ---------- pure helpers (unit-testable without Android Context) ----------

/**
 * Sanitise a filename for use inside the share cache: strip path separators, leading
 * dots, and collapse to a safe basename. An empty or all-stripped name falls back to
 * `"shared_file"`.
 *
 * Pure function — no File I/O — so it can be unit-tested directly.
 */
internal fun sanitizeShareFileName(rawName: String): String {
    val basename = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
    val stripped = basename.dropWhile { it == '.' || it == ' ' }
    val safe = stripped.ifEmpty { "shared_file" }
    return safe.replace(Regex("[/\\\\\\u0000]"), "_")
        .ifEmpty { "shared_file" }
}

/**
 * Resolve a MIME type for the share Intent. Priority:
 * 1. Caller-supplied non-blank [override]
 * 2. [ContentResolver.getType] for content:// URIs (caller passes null for file paths)
 * 3. Extension lookup via [MimeTypeMap]
 * 4. [FALLBACK_MIME]
 */
internal fun resolveShareMime(
    override: String?,
    contentResolverType: String?,
    extensionOrNull: String?,
): String {
    if (!override.isNullOrBlank()) return override.trim()
    contentResolverType?.let { if (it.isNotBlank()) return it }
    extensionOrNull?.let { ext ->
        if (ext.isNotBlank()) {
            MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext.lowercase())
                ?.let { return it }
        }
    }
    return FALLBACK_MIME
}

/**
 * Reap stale invocation directories from the share cache. Only top-level entries inside
 * [cacheRoot] that are older than [ttlMs] are deleted; the [currentInvocationDir] is
 * always spared even if it somehow qualifies (clock skew, etc.).
 *
 * Returns the number of invocation dirs removed (for diagnostics).
 */
internal fun cleanupStaleShareCache(
    cacheRoot: File,
    ttlMs: Long,
    nowMs: Long,
    currentInvocationDir: File,
): Int {
    if (!cacheRoot.isDirectory) return 0
    var removed = 0
    cacheRoot.listFiles()?.forEach { entry ->
        if (!entry.isDirectory) return@forEach
        if (entry.absolutePath == currentInvocationDir.absolutePath) return@forEach
        val age = nowMs - entry.lastModified()
        if (age >= ttlMs) {
            runCatching { entry.deleteRecursively() }.onSuccess { removed++ }
        }
    }
    return removed
}

// ---------- Termux path containment ----------

internal data class TermuxPathResult(
    val ok: Boolean,
    val path: String?,
    val error: String?,
)

/**
 * Expand a `termux:~/...` or `termux:/usr/...` path to an absolute Android filesystem
 * path inside the embedded Termux root, then verify the canonical form stays inside
 * [termuxRoot]. Returns `ok=false, error=null` when [raw] is not a `termux:` path
 * (caller should try other sources); returns a [TermuxPathResult] with `error` on
 * containment failure.
 *
 * NOTE: Only lowercase `termux:` is accepted. The caller ([classifyShareSource])
 * already enforces this — `TERMUX:` is classified as `Unsupported` before this function
 * is ever called.
 */
internal fun resolveTermuxSharePath(
    raw: String,
    termuxRoot: File,
    termuxHome: File,
): TermuxPathResult {
    if (!raw.startsWith(TERMUX_PREFIX)) {
        return TermuxPathResult(ok = false, path = null, error = null)
    }
    val rest = raw.removePrefix(TERMUX_PREFIX)
    val sep = File.separator
    val expanded = when {
        rest.isEmpty() || rest == "/" -> termuxHome.absolutePath
        rest.startsWith("~/") -> termuxHome.absolutePath + sep + rest.removePrefix("~/")
        rest == "~" -> termuxHome.absolutePath
        rest.startsWith("/") -> File(termuxRoot, rest).absolutePath
        else -> File(termuxHome, rest).absolutePath
    }
    val canonical = try {
        File(expanded).canonicalPath
    } catch (_: IOException) {
        return TermuxPathResult(ok = false, path = null, error = "Termux path could not be resolved.")
    }
    val rootCanonical = try {
        termuxRoot.canonicalPath
    } catch (_: IOException) {
        return TermuxPathResult(ok = false, path = null, error = "Termux root could not be resolved.")
    }
    if (canonical != rootCanonical && !canonical.startsWith("$rootCanonical$sep")) {
        return TermuxPathResult(
            ok = false, path = null,
            error = "Termux path escapes the embedded Termux root directory.",
        )
    }
    return TermuxPathResult(ok = true, path = canonical, error = null)
}

// ---------- error helpers ----------

private fun shareFileErr(code: String, detail: String, extra: Map<String, String> = emptyMap()): String =
    buildJsonObject {
        put("error", code)
        put("detail", detail)
        extra.forEach { (k, v) -> put(k, v) }
    }.toString()

private fun shareFileText(s: String) = listOf(UIMessagePart.Text(s))

// ---------- counting output stream (enforces size limit during copy) ----------

/**
 * Wraps [delegate] and counts bytes written, throwing [ShareFileTooLargeException] when
 * [maxBytes] is exceeded. Used by both the local-file copy and the workspace export so
 * a lying stat (or a file that grows during export) is caught mid-stream and mapped to
 * a structured `too_large` error — not via string matching.
 */
internal class CountingOutputStream(
    private val delegate: OutputStream,
    private val maxBytes: Long,
) : OutputStream() {
    var total: Long = 0L
        private set

    override fun write(b: Int) {
        delegate.write(b)
        total++
        checkLimit()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        total += len
        checkLimit()
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()

    private fun checkLimit() {
        if (total > maxBytes) {
            throw ShareFileTooLargeException(
                "File exceeds the $maxBytes-byte share limit.",
            )
        }
    }
}

// ---------- tool ----------

/**
 * `share_file` — open the system share sheet (Sharesheet) so the user can send a single
 * file to another app. The file is always copied into an app-controlled cache directory
 * and exposed via a `content://` FileProvider URI — never `file://`.
 *
 * Source allowlist (no arbitrary absolute paths):
 *  - `~/...`       — app-private workspace (AgentWorkspace); canonical containment enforced
 *  - `/sdcard/...` — public/shared storage (all-files-access gated)
 *  - `/storage/emulated/...`, `/storage/self/...`, `/storage/<id>/...` — public/removable storage
 *  - `/workspace/...` — proot rootfs, exported via the assistant's bound workspace
 *  - `termux:~/...`  — embedded Termux (lowercase prefix, canonical containment checked)
 *
 * Rejected before any file access: `content://`, `file://`, other `scheme://`, direct
 * `/data/data/<package>/...`, `TERMUX:` (uppercase), bare `/storage`, relative paths.
 *
 * **Headless guard**: in a headless dispatch (cron / workflow / sub-agent / Telegram)
 * the tool returns `interactive_only` **before** any file is read or copied.
 *
 * Approval-gated + NO_ALWAYS_ALLOW: exfiltrating a file from the device is a sensitive
 * operation that deserves a per-call confirmation every time.
 */
fun shareFileTool(
    context: Context,
    workspaceRepository: WorkspaceRepository,
    termuxEnvironment: TermuxEnvironment,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
): Tool = Tool(
    name = "share_file",
    description = """
        Open the system share sheet so the user can send a single file to another app (messages, email, Bluetooth, drive, etc.). Only opens the chooser — does NOT guarantee the file was sent. Allowed path sources: ~/... (AgentWorkspace app-private), /sdcard/ or /storage/... (public storage), /workspace/... (proot rootfs, requires a workspace bound to the current assistant), termux:~/... (embedded Termux home, lowercase prefix only). All other paths — including content://, file://, direct /data/data paths, and TERMUX: (uppercase) — are rejected. Optional mime_type overrides type detection (must be valid type/subtype); optional text adds a caption; optional subject sets the share subject. Interactive only — cannot be used from background/cron/workflow/Telegram contexts.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Path to the file to share. Allowed: ~/... (AgentWorkspace), /sdcard/... (public storage), /storage/... (public/removable storage), /workspace/... (proot rootfs), termux:~/... (embedded Termux, lowercase). Rejected: content://, file://, /data/data/... paths, TERMUX: uppercase.",
                    )
                })
                put("mime_type", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional MIME type override (e.g. image/png). Must be valid type/subtype. Defaults to OS guess from file extension.")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional caption / accompanying text passed as EXTRA_TEXT. Max 10000 chars.")
                })
                put("subject", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional subject (e.g. email title) passed as EXTRA_SUBJECT. Max 200 chars.")
                })
            },
            required = listOf("path"),
        )
    },
    execute = { input ->
        // 1. Headless guard — FIRST, before any file access or validation
        if (invocationContext.isHeadless) {
            return@Tool shareFileText(shareFileErr(
                "interactive_only",
                "share_file opens a system Sharesheet Activity that requires a foreground app context. " +
                    "It cannot be used from cron jobs, workflows, sub-agents, or Telegram bot sessions — " +
                    "starting a background Activity would violate Android's background activity launch restrictions.",
                mapOf(
                    "recovery" to "Ask the user to run this from the foreground chat, or use a tool that sends directly " +
                        "to the target (e.g. telegram_send_document for Telegram, ssh_upload for remote hosts).",
                ),
            ))
        }

        // 2. Parse + missing-path check
        val params = input.jsonObject
        val rawPath = params["path"]?.jsonPrimitive?.contentOrNull
        if (rawPath.isNullOrBlank()) {
            return@Tool shareFileText(shareFileErr("missing_path", "path is required"))
        }
        val mimeOverride = params["mime_type"]?.jsonPrimitive?.contentOrNull
        val caption = params["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val subject = params["subject"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        // 3. MIME validation — BEFORE any file I/O
        validateMimeOverride(mimeOverride)?.let { err ->
            return@Tool shareFileText(shareFileErr("unsupported_mime", err))
        }

        // 4. Caption/subject length validation — BEFORE any file I/O
        validateCaptionLength(caption)?.let { err ->
            return@Tool shareFileText(shareFileErr("text_too_long", err))
        }
        validateSubjectLength(subject)?.let { err ->
            return@Tool shareFileText(shareFileErr("subject_too_long", err))
        }

        // 5. Source classification — allowlist check, BEFORE any file I/O
        val source = classifyShareSource(rawPath)
        if (source is ShareSource.Unsupported) {
            return@Tool shareFileText(shareFileErr("unsupported_source", source.reason))
        }

        wakeScreenIfNeeded(context)

        // 6. Prepare share cache
        val shareCacheRoot = File(context.cacheDir, SHARE_CACHE_DIR).apply { mkdirs() }
        val invocationDir = File(shareCacheRoot, UUID.randomUUID().toString())

        // Stale cleanup (best-effort, never touches the current invocation dir)
        runCatching {
            cleanupStaleShareCache(
                cacheRoot = shareCacheRoot,
                ttlMs = SHARE_CACHE_TTL_MS,
                nowMs = System.currentTimeMillis(),
                currentInvocationDir = invocationDir,
            )
        }

        var chooserOpened = false
        try {
            // 7. Resolve source, copy into cache
            val copyResult = withContext(Dispatchers.IO) {
                resolveAndCopyToCache(
                    workspaceRepository = workspaceRepository,
                    termuxEnvironment = termuxEnvironment,
                    invocationContext = invocationContext,
                    source = source,
                    invocationDir = invocationDir,
                )
            }.getOrElse { e ->
                // Never swallow coroutine cancellation — user Stop / per-tool timeout
                // must propagate so the generation loop can abort cleanly.
                if (e is CancellationException) throw e
                if (e is ShareFileTooLargeException) {
                    return@Tool shareFileText(shareFileErr(
                        "too_large",
                        e.message ?: "File exceeds the share size limit.",
                        mapOf("recovery" to "Choose a smaller file or split it before sharing."),
                    ))
                }
                return@Tool shareFileText(shareFileErr(
                    "copy_failed",
                    "Failed to stage file for sharing: ${e.message ?: e::class.simpleName}",
                ))
            }

            // 8. Generate FileProvider URI
            val uri = runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    copyResult.cachedFile,
                )
            }.getOrElse { e ->
                return@Tool shareFileText(shareFileErr(
                    "fileprovider_failed",
                    "Could not expose staged file via FileProvider: ${e.message ?: e::class.simpleName}",
                ))
            }

            // 9. MIME resolution
            val mime = resolveShareMime(
                override = mimeOverride,
                contentResolverType = null,
                extensionOrNull = copyResult.extension,
            )

            // 10. Build Intent + chooser
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                if (!caption.isNullOrBlank()) {
                    putExtra(Intent.EXTRA_TEXT, caption)
                }
                if (!subject.isNullOrBlank()) {
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                }
                clipData = ClipData.newUri(context.contentResolver, "shared_file", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // 11. Launch chooser
            runCatching {
                context.startActivity(chooser)
            }.onFailure { e ->
                // Classify the failure rather than blanket "no_handler":
                //  - ActivityNotFoundException: no app handles the MIME type → no_handler
                //  - SecurityException: background activity launch blocked → background_launch_blocked
                //  - anything else → share_failed
                val (code, detail) = when (e) {
                    is ActivityNotFoundException -> "no_handler" to
                        "No installed app can share files of type '$mime'."
                    is SecurityException -> "background_launch_blocked" to
                        "Blocked from launching the share sheet: ${e.message ?: "background activity launch restriction"}."
                    is CancellationException -> throw e // never swallow
                    else -> "share_failed" to
                        "Could not open the system share sheet: ${e.message ?: e::class.simpleName}"
                }
                return@Tool shareFileText(shareFileErr(code, detail))
            }

            // Success: chooser opened. We do NOT claim the file was sent.
            chooserOpened = true
            shareFileText(buildJsonObject {
                put("success", true)
                put("chooser_opened", true)
                put("mime", mime)
                put("source_type", copyResult.sourceType)
                put("bytes_copied", copyResult.bytesCopied)
            }.toString())
        } finally {
            // Clean up the invocation dir on ALL failure paths (copy error, FileProvider
            // failure, startActivity failure). On success, the target app may still be
            // reading the URI, so the dir is left for stale cleanup on the next call.
            if (!chooserOpened) {
                runCatching { invocationDir.deleteRecursively() }
            }
        }
    },
)

// ---------- source resolution + copy ----------

internal data class ShareCopyResult(
    val cachedFile: File,
    val extension: String,
    val sourceType: String,
    val bytesCopied: Long,
)

internal suspend fun resolveAndCopyToCache(
    workspaceRepository: WorkspaceRepository,
    termuxEnvironment: TermuxEnvironment,
    invocationContext: ToolInvocationContext,
    source: ShareSource,
    invocationDir: File,
): Result<ShareCopyResult> = runCatching {
    invocationDir.mkdirs()

    when (source) {
        is ShareSource.Termux -> {
            val termuxResult = resolveTermuxSharePath(
                raw = source.rawPath,
                termuxRoot = termuxEnvironment.termuxRoot,
                termuxHome = termuxEnvironment.homeDir,
            )
            if (termuxResult.error != null) {
                throw IOException(termuxResult.error)
            }
            val termuxPath = termuxResult.path!!
            PathSafetyGuard.check(termuxPath)?.let { v ->
                throw IOException(v.detail)
            }
            val srcFile = File(termuxPath)
            validateSourceFile(srcFile, source.rawPath)
            val sanitizedName = sanitizeShareFileName(srcFile.name)
            val dest = File(invocationDir, sanitizedName)
            val bytes = streamCopyWithLimit(srcFile, dest, SHARE_FILE_MAX_BYTES)
            ShareCopyResult(dest, srcFile.extension, "termux", bytes)
        }

        is ShareSource.Workspace -> {
            val workspaceId = invocationContext.callerWorkspaceId
            if (workspaceId.isNullOrBlank()) {
                throw IOException("No workspace is bound to the current assistant. /workspace/ paths require a bound workspace.")
            }
            val workspace = workspaceRepository.getById(workspaceId)
                ?: throw IOException("Bound workspace not found: $workspaceId")
            if (workspace.shellStatus != me.rerere.workspace.WorkspaceShellStatus.READY.name) {
                throw IOException("Bound workspace is not ready (status: ${workspace.shellStatus}). Wait for it to finish installing.")
            }
            val rootfsPath = source.rawPath.removePrefix("/workspace")
            if (rootfsPath.isBlank() || rootfsPath == "/") {
                throw IOException("Cannot share a directory; provide a file path inside /workspace/.")
            }
            // Pre-check size via stat (fast path); export also enforces the limit mid-stream.
            // A 0-byte file is valid — only reject negative sizes (which shouldn't occur).
            val fileSize = try {
                workspaceRepository.rootfsFileSize(workspaceId, rootfsPath)
            } catch (e: Exception) {
                // Never swallow coroutine cancellation
                if (e is CancellationException) throw e
                throw IOException("Could not stat file in workspace: ${e.message}")
            }
            if (fileSize < 0) {
                throw IOException("Invalid file size returned by workspace: $fileSize")
            }
            if (fileSize > SHARE_FILE_MAX_BYTES) {
                throw ShareFileTooLargeException(
                    "File is too large to share ($fileSize bytes; limit is $SHARE_FILE_MAX_BYTES bytes).",
                )
            }
            val sanitizedName = sanitizeShareFileName(rootfsPath.substringAfterLast('/'))
            val dest = File(invocationDir, sanitizedName)
            val bytes = streamCopyWorkspaceFile(
                workspaceRepository, workspaceId, rootfsPath, dest, SHARE_FILE_MAX_BYTES,
            )
            ShareCopyResult(
                dest,
                rootfsPath.substringAfterLast('.', missingDelimiterValue = ""),
                "workspace",
                bytes,
            )
        }

        is ShareSource.AgentWorkspace -> {
            val expanded = AgentWorkspace.expand(source.rawPath)
            // Canonical containment: expanded path must stay inside AgentWorkspace.rootPath()
            val wsRootCanonical = try {
                File(AgentWorkspace.rootPath()).canonicalPath
            } catch (_: IOException) {
                throw IOException("AgentWorkspace root could not be resolved.")
            }
            val expandedCanonical = try {
                File(expanded).canonicalPath
            } catch (_: IOException) {
                throw IOException("Path could not be resolved: ${source.rawPath}")
            }
            val sep = File.separator
            if (expandedCanonical != wsRootCanonical && !expandedCanonical.startsWith("$wsRootCanonical$sep")) {
                throw IOException("Path escapes the app workspace directory.")
            }
            PathSafetyGuard.check(expanded)?.let { v ->
                throw IOException(v.detail)
            }
            val srcFile = File(expanded)
            validateSourceFile(srcFile, source.rawPath)
            val sanitizedName = sanitizeShareFileName(srcFile.name)
            val dest = File(invocationDir, sanitizedName)
            val bytes = streamCopyWithLimit(srcFile, dest, SHARE_FILE_MAX_BYTES)
            ShareCopyResult(dest, srcFile.extension, "agent_workspace", bytes)
        }

        is ShareSource.PublicStorage -> {
            val expanded = AgentWorkspace.expand(source.rawPath) // no-op for /sdcard paths
            PathSafetyGuard.check(expanded)?.let { v ->
                throw IOException(v.detail)
            }
            // Check all-files-access BEFORE exists/stat so a permission error is returned
            // without touching the file. On JVM tests this path is never reached (only
            // early-return validation paths are exercised there).
            allFilesAccessGuard(expanded)?.let { guardMsg ->
                val detail = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(guardMsg)
                        .jsonObject["detail"]?.jsonPrimitive?.contentOrNull
                }.getOrNull() ?: "All files access required for shared storage."
                throw IOException(detail)
            }
            val srcFile = File(expanded)
            // Canonical containment: after resolving symlinks, the path must still be
            // within public storage — prevents symlink/alias escape to own-app dirs.
            val srcCanonical = try {
                srcFile.canonicalPath
            } catch (_: IOException) {
                throw IOException("Path could not be resolved: ${source.rawPath}")
            }
            if (!isWithinPublicStorage(srcCanonical)) {
                throw IOException("Path resolves outside public storage and is not allowed.")
            }
            validateSourceFile(srcFile, source.rawPath)
            val sanitizedName = sanitizeShareFileName(srcFile.name)
            val dest = File(invocationDir, sanitizedName)
            val bytes = streamCopyWithLimit(srcFile, dest, SHARE_FILE_MAX_BYTES)
            ShareCopyResult(dest, srcFile.extension, "public_storage", bytes)
        }

        is ShareSource.Unsupported -> {
            // Should never reach here — the tool's execute block rejects Unsupported
            // before calling resolveAndCopyToCache. Defence-in-depth:
            throw IOException("unsupported_source: ${source.reason}")
        }
    }
}

private fun validateSourceFile(file: File, rawPath: String) {
    if (!file.exists()) {
        throw IOException("File not found: $rawPath")
    }
    if (!file.isFile) {
        throw IOException("Path is a directory, not a file: $rawPath")
    }
    if (!file.canRead()) {
        throw IOException("File exists but cannot be read (permission denied): $rawPath")
    }
}

/**
 * Stream-copy [src] → [dest] with a byte-count cap of [maxBytes].
 * Throws [IOException] with a `too_large`-friendly message when the cap is exceeded,
 * and deletes [dest] on any failure. Symlink defence: re-runs [PathSafetyGuard] on the
 * canonical target in case a symlink resolves outside the allowed area.
 *
 * The [maxBytes] parameter defaults to [SHARE_FILE_MAX_BYTES] but can be overridden
 * in tests to verify the limit without allocating a 256 MB file.
 */
internal fun streamCopyWithLimit(src: File, dest: File, maxBytes: Long = SHARE_FILE_MAX_BYTES): Long {
    val srcCanonical = try { src.canonicalFile } catch (_: IOException) { src }
    if (srcCanonical.absolutePath != src.absolutePath) {
        PathSafetyGuard.check(srcCanonical.absolutePath)?.let { v ->
            throw IOException("Symlink target blocked: ${v.detail}")
        }
    }
    return try {
        src.inputStream().use { input ->
            dest.outputStream().use { output ->
                val counting = CountingOutputStream(output, maxBytes)
                val buf = ByteArray(COPY_BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    counting.write(buf, 0, n)
                }
                counting.flush()
                counting.total
            }
        }
    } catch (e: ShareFileTooLargeException) {
        dest.delete()
        throw e
    } catch (e: IOException) {
        dest.delete()
        throw e
    }
}

/**
 * Stream-copy a rootfs file via [WorkspaceRepository.exportRootfsFile] into [dest].
 * A [CountingOutputStream] enforces the [maxBytes] limit mid-stream — a lying stat
 * or a file that grows during export is caught and the partial copy is deleted.
 */
private suspend fun streamCopyWorkspaceFile(
    workspaceRepository: WorkspaceRepository,
    workspaceId: String,
    rootfsPath: String,
    dest: File,
    maxBytes: Long,
): Long {
    val fos = java.io.FileOutputStream(dest)
    try {
        val counting = CountingOutputStream(fos, maxBytes)
        workspaceRepository.exportRootfsFile(workspaceId, rootfsPath, counting)
        counting.flush()
        return counting.total
    } catch (e: ShareFileTooLargeException) {
        dest.delete()
        throw e
    } catch (e: IOException) {
        dest.delete()
        throw e
    } finally {
        fos.close()
    }
}