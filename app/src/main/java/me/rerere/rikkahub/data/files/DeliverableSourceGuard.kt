package me.rerere.rikkahub.data.files

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import me.rerere.rikkahub.data.ai.tools.local.AgentWorkspace
import me.rerere.workspace.WorkspaceSafeFiles
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Shared trusted-root and external-URI security boundary for file delivery tools
 * (`share_file` and `open_file`).
 *
 * Two explicit delivery policies:
 * 1. **Local host files (Cache-Staged Policy)**: resolved strictly relative to a known,
 *    uncontrolled allowlisted root directory and opened via FD-relative
 *    [WorkspaceSafeFiles.openInputStreamNoFollow] (`SecureDirectoryStream` + `O_NOFOLLOW`),
 *    then streamed into the restricted share cache with chunk-by-chunk size caps. Never
 *    resolves untrusted subpaths with `canonicalFile` before re-opening by string path.
 * 2. **External `content://` URIs (Direct Handoff Policy)**: accepted ONLY for
 *    (a) a user-persisted SAF grant verified by exact URI equality (single-document grant) or
 *    Android [DocumentsContract] tree semantics + [DocumentsContract.isChildDocument] (tree grant),
 *    never raw string `startsWith`; or
 *    (b) a readable public MediaStore URI. Own-app authorities are always rejected.
 */
object DeliverableSourceGuard {

    private val PRIMARY_STORAGE_ALIASES = listOf(
        "/sdcard",
        "/storage/self/primary",
        "/mnt/sdcard",
    )

    internal data class SafUriShape(
        val authority: String,
        val treeId: String?,
        val documentId: String?,
        val isTreeRootOnly: Boolean,
    )

    /**
     * Resolves [rawPath] strictly as a relative subpath under [trustedRoot] (or one of
     * [rootAliases] that maps onto [trustedRoot], such as `/sdcard` -> `/storage/emulated/0`).
     *
     * Does NOT dereference symlinks in the subpath; callers MUST open the returned
     * `(root, relPath)` via [WorkspaceSafeFiles.openInputStreamNoFollow] so the kernel enforces
     * `O_NOFOLLOW` on every segment.
     */
    fun resolveUnderTrustedRoot(
        trustedRoot: File,
        rawPath: String,
        rootAliases: List<String> = emptyList(),
    ): Pair<File, String>? {
        val cleaned = rawPath.replace('\\', '/').trim()
        if (cleaned.isEmpty() || cleaned.contains('\u0000') || !cleaned.startsWith("/")) return null
        val rootPath = trustedRoot.toPath()
        if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.isSymbolicLink(rootPath) || !Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            return null
        }

        val candidates = buildList {
            add(trustedRoot.absolutePath.replace('\\', '/').trimEnd('/'))
            rootAliases.forEach { alias ->
                val norm = alias.replace('\\', '/').trimEnd('/')
                if (norm.isNotEmpty()) add(norm)
            }
        }.distinct()

        for (prefix in candidates) {
            if (cleaned.startsWith("$prefix/")) {
                val rawRel = cleaned.removePrefix("$prefix/")
                val segments = runCatching { WorkspaceSafeFiles.parseRelativeSegments(rawRel) }
                    .getOrNull() ?: return null
                if (segments.isEmpty()) return null
                return trustedRoot to segments.joinToString("/")
            }
        }
        return null
    }

    /**
     * Resolves a public shared-storage path (`/sdcard/...`, `/storage/emulated/0/...`,
     * `/storage/<volId>/...`) to `(realRootDir, relPath)` without following symlinks in the
     * subpath.
     */
    fun resolvePublicStorageSource(
        rawPath: String,
        primaryRootOverride: File? = null,
    ): Pair<File, String>? {
        val cleaned = rawPath.replace('\\', '/').trim()
        if (cleaned.isEmpty() || cleaned.contains('\u0000') || !cleaned.startsWith("/")) return null

        val primaryRoot = primaryRootOverride
            ?: runCatching {
                @Suppress("DEPRECATION")
                Environment.getExternalStorageDirectory()
            }.getOrNull()
            ?: File("/storage/emulated/0")

        // Real primary root (on Android, Environment.getExternalStorageDirectory() is typically
        // /storage/emulated/0 which is a real dir, while /sdcard is a root-owned symlink to it).
        val realPrimary = runCatching { primaryRoot.canonicalFile }.getOrNull()
        if (realPrimary != null &&
            Files.exists(realPrimary.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(realPrimary.toPath()) &&
            Files.isDirectory(realPrimary.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            val aliases = buildList {
                addAll(PRIMARY_STORAGE_ALIASES)
                add(primaryRoot.absolutePath)
                add("/storage/emulated/0")
            }
            resolveUnderTrustedRoot(realPrimary, cleaned, aliases)?.let { return it }
        }

        // Secondary removable storage volume: /storage/<volId>/<subpath>
        if (cleaned.startsWith("/storage/")) {
            val parts = cleaned.removePrefix("/storage/").split('/', limit = 2)
            val volId = parts.getOrNull(0).orEmpty()
            val rest = parts.getOrNull(1).orEmpty()
            if (volId.isNotEmpty() &&
                volId != "self" &&
                volId != "emulated" &&
                volId != "." &&
                volId != ".." &&
                rest.isNotEmpty()
            ) {
                val volRoot = File("/storage", volId)
                resolveUnderTrustedRoot(volRoot, cleaned)?.let { return it }
            }
        }
        return null
    }

    /**
     * Resolves a host path against the explicit allowlist of deliverable roots for `open_file`.
     * Never permits app databases, DataStore, SharedPreferences, or symlinked roots/ancestors.
     */
    fun resolveAllowedOpenFileSource(
        context: Context,
        rawPath: String,
    ): Pair<File, String>? {
        val cleaned = rawPath.replace('\\', '/').trim()
        if (cleaned.isEmpty() || cleaned.contains('\u0000')) return null

        // Check app-owned deliverable directories first (explicit allowlist).
        val allowedAppDirs = buildList {
            runCatching { File(AgentWorkspace.rootPath()) }.getOrNull()?.let { add(it) }
            add(File(context.filesDir, "upload"))
            add(File(context.cacheDir, "workspace_share"))
            add(File(context.cacheDir, "photos"))
            add(File(context.cacheDir, "recordings"))
            context.getExternalFilesDir(null)?.let { add(it) }
        }
        for (dir in allowedAppDirs) {
            val canonicalRoot = runCatching {
                if (!Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) null
                else if (Files.isSymbolicLink(dir.toPath())) null
                else dir.canonicalFile
            }.getOrNull() ?: continue
            val aliases = if (canonicalRoot.absolutePath != dir.absolutePath) {
                listOf(dir.absolutePath)
            } else {
                emptyList()
            }
            resolveUnderTrustedRoot(canonicalRoot, cleaned, aliases)?.let { return it }
        }

        // Otherwise allow public shared storage (checked via real storage root + O_NOFOLLOW).
        return resolvePublicStorageSource(cleaned)
    }

    /**
     * Opens a regular file relative to [trustedRoot] strictly via FD-relative `O_NOFOLLOW`.
     */
    fun openTrustedFileNoFollow(
        trustedRoot: File,
        relPath: String,
        displayPath: String = relPath,
    ): InputStream = WorkspaceSafeFiles.openInputStreamNoFollow(
        root = trustedRoot,
        relPath = relPath,
        displayPath = displayPath,
    )

    /**
     * Pure parser for `content://` SAF URI shapes (`tree/<id>`, `tree/<id>/document/<id>`,
     * `document/<id>`) that works identically on Android and JVM unit tests.
     */
    internal fun parseSafUriShape(uriStr: String): SafUriShape? {
        val parsed = runCatching { URI(uriStr) }.getOrNull() ?: return null
        if (!parsed.scheme.equals("content", ignoreCase = true)) return null
        val authority = parsed.rawAuthority?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val rawPath = parsed.rawPath ?: return null
        val segs = rawPath.split('/').filter { it.isNotEmpty() }
        return when {
            segs.size == 2 && segs[0] == "tree" -> SafUriShape(
                authority = authority,
                treeId = segs[1],
                documentId = null,
                isTreeRootOnly = true,
            )
            segs.size == 4 && segs[0] == "tree" && segs[2] == "document" -> SafUriShape(
                authority = authority,
                treeId = segs[1],
                documentId = segs[3],
                isTreeRootOnly = false,
            )
            segs.size == 2 && segs[0] == "document" -> SafUriShape(
                authority = authority,
                treeId = null,
                documentId = segs[1],
                isTreeRootOnly = false,
            )
            else -> SafUriShape(
                authority = authority,
                treeId = null,
                documentId = null,
                isTreeRootOnly = false,
            )
        }
    }

    /**
     * Verifies whether [targetUriStr] is legitimately authorized by a persisted SAF permission
     * [grantedUriStr]:
     * - Single-document grant: requires EXACT URI equality (never `startsWith`!).
     * - Tree grant (`content://<auth>/tree/<treeId>`): requires [targetUriStr] to be a tree-scoped
     *   document URI with matching authority and exact `treeId`, AND requires [isChildVerifier]
     *   (backed by [DocumentsContract.isChildDocument] on Android) to confirm containment.
     *   If containment cannot be verified, returns `false`.
     */
    internal fun isSafUriCoveredByGrant(
        targetUriStr: String,
        grantedUriStr: String,
        isChildVerifier: (parentTreeDocUriStr: String, targetUriStr: String) -> Boolean,
    ): Boolean {
        val targetShape = parseSafUriShape(targetUriStr) ?: return false
        val grantShape = parseSafUriShape(grantedUriStr) ?: return false
        if (targetShape.authority != grantShape.authority) return false

        // Single-document (or non-tree) grant: ONLY exact equality is allowed.
        if (!grantShape.isTreeRootOnly) {
            return targetUriStr == grantedUriStr
        }

        // Tree grant: target must be a document URI scoped to this exact tree AND verified as child.
        val grantTreeId = grantShape.treeId ?: return false
        if (targetShape.isTreeRootOnly) return false
        if (targetShape.treeId != grantTreeId) return false
        if (targetShape.documentId.isNullOrEmpty()) return false
        val parentTreeDocUriStr = "content://${grantShape.authority}/tree/$grantTreeId/document/$grantTreeId"
        return runCatching { isChildVerifier(parentTreeDocUriStr, targetUriStr) }.getOrDefault(false)
    }

    /**
     * Direct-handoff policy check for external `content://` URIs in `open_file`.
     */
    fun isSafeExternalContentUri(context: Context, uri: Uri): Boolean {
        val authority = uri.authority?.lowercase() ?: return false
        val ownPkg = context.packageName.lowercase()
        // Never allow own-app private providers (caller should use /workspace/... or deliverable path).
        if (authority == ownPkg || authority.startsWith("$ownPkg.")) return false

        val isPublicMedia = authority == "media" || authority == "com.android.providers.media.documents"
        val isPersistedSaf = if (!isPublicMedia) {
            val targetStr = uri.toString()
            context.contentResolver.persistedUriPermissions.any { perm ->
                if (!perm.isReadPermission) return@any false
                isSafUriCoveredByGrant(
                    targetUriStr = targetStr,
                    grantedUriStr = perm.uri.toString(),
                    isChildVerifier = { _, _ ->
                        runCatching {
                            if (!DocumentsContract.isTreeUri(perm.uri) || !DocumentsContract.isTreeUri(uri)) {
                                false
                            } else if (!DocumentsContract.isDocumentUri(context, uri)) {
                                false
                            } else {
                                val treeDocId = DocumentsContract.getTreeDocumentId(perm.uri)
                                val parentTreeDocUri = DocumentsContract.buildDocumentUriUsingTree(perm.uri, treeDocId)
                                DocumentsContract.isChildDocument(context.contentResolver, parentTreeDocUri, uri)
                            }
                        }.getOrDefault(false)
                    },
                )
            }
        } else {
            false
        }

        if (!isPersistedSaf && !isPublicMedia) return false
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }
}
