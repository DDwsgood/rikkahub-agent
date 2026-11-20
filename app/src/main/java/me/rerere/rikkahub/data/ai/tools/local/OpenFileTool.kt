package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.files.DeliverableSourceGuard
import me.rerere.rikkahub.data.files.WorkspacePublicExport
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File
import java.nio.file.NoSuchFileException

private const val OPEN_FILE_STAGE_MAX_BYTES = WorkspacePublicExport.DEFAULT_STAGE_MAX_BYTES

/**
 * `open_file` — fire ACTION_VIEW to open a deliverable file in the user's preferred OS viewer
 * (Gallery, PDF reader, audio player, text editor, etc.).
 *
 * Explicit dual delivery policy (shared with `share_file` via [DeliverableSourceGuard]):
 * 1. **Direct External URI Handoff**: `content://` URIs are forwarded directly ONLY when they
 *    belong to a non-self authority and are verified as either a persisted SAF grant (exact URI
 *    for single-document grants; [android.provider.DocumentsContract.isChildDocument] for tree
 *    grants) or a readable public MediaStore URI.
 * 2. **Cache-Staged Delivery**: All local files (`/workspace/...` or allowlisted host roots via
 *    [DeliverableSourceGuard.resolveAllowedOpenFileSource]) are opened via FD-relative
 *    `O_NOFOLLOW` and copied into the restricted share cache with chunk-by-chunk byte caps and
 *    cooperative cancellation before granting a FileProvider URI.
 */
fun openFileTool(
    context: Context,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "open_file",
    description = "Open a file in the user's OS viewer (Gallery, PDF reader, player, editor). Path accepts /workspace/..., ~/..., external storage path, or user-granted content:// URI. Optional mime_type forces a specific viewer.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path (/workspace/..., ~/..., /sdcard/...) or user-granted content:// URI")
                })
                put("mime_type", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional override — defaults to the OS guess from the file extension")
                })
            },
            required = listOf("path"),
        )
    },
    execute = { input ->
        wakeScreenIfNeeded(context)
        val params = input.jsonObject
        val rawPath = params["path"]?.jsonPrimitive?.contentOrNull
        if (rawPath.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "missing_path"); put("detail", "path is required") }.toString()
            ))
        }
        val mimeOverride = params["mime_type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        // Policy 1: Direct external handoff for verified persisted SAF / public MediaStore URIs.
        if (ContentUriSafetyGuard.isContentUri(rawPath)) {
            ContentUriSafetyGuard.check(rawPath)?.let { v ->
                return@Tool listOf(UIMessagePart.Text(fmErrEnvelope(v.code, v.detail)))
            }
            val uri = Uri.parse(rawPath)
            if (!DeliverableSourceGuard.isSafeExternalContentUri(context, uri)) {
                return@Tool listOf(UIMessagePart.Text(fmErrEnvelope(
                    "uri_not_allowed",
                    "Only verified user-granted SAF or readable MediaStore content:// URIs can be forwarded to external viewers.",
                )))
            }
            val mime = mimeOverride
                ?: context.contentResolver.getType(uri)
                ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(intent)
            } catch (c: CancellationException) {
                throw c
            } catch (err: Throwable) {
                return@Tool listOf(UIMessagePart.Text(fmErrEnvelope(
                    "no_handler",
                    "No installed app can open content of type '$mime': ${err.message ?: err::class.simpleName}",
                )))
            }
            val result = listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("policy", "direct_external_uri")
                put("path", rawPath)
                put("mime", mime)
            }.toString()))
            streamer.streamIfHeadless(invocationContext, "OpenFile: content URI")
            return@Tool result
        }

        // Policy 2a: Cache-staged delivery from bound PRoot workspace FILES area.
        if (rawPath.startsWith("/workspace/")) {
            val workspaceId = invocationContext.callerWorkspaceId
            if (workspaceId.isNullOrBlank()) {
                return@Tool listOf(UIMessagePart.Text(fmErrEnvelope(
                    "no_workspace",
                    "No workspace is bound to the current assistant; /workspace/ paths require a bound workspace.",
                )))
            }
            val normalized = try {
                WorkspacePublicExport.normalizeRootfsPath(rawPath)
            } catch (e: IllegalArgumentException) {
                return@Tool listOf(UIMessagePart.Text(fmErrEnvelope("invalid_path", e.message ?: "Invalid path")))
            }
            if (!normalized.startsWith("/workspace/")) {
                return@Tool listOf(UIMessagePart.Text(fmErrEnvelope("invalid_path", "Path escapes /workspace/: $rawPath")))
            }
            val relPath = normalized.removePrefix("/workspace/")
            val repo = runCatching { getKoin().get<WorkspaceRepository>() }.getOrNull()
                ?: return@Tool listOf(UIMessagePart.Text(fmErrEnvelope("workspace_unavailable", "WorkspaceRepository unavailable")))
            val staged = try {
                WorkspacePublicExport.stageToRestrictedShareCacheSuspend(
                    context = context,
                    fileName = relPath.substringAfterLast('/'),
                    maxBytes = OPEN_FILE_STAGE_MAX_BYTES,
                ) { out ->
                    repo.exportFile(workspaceId, WorkspaceStorageArea.FILES, relPath, out)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (err: Throwable) {
                return@Tool listOf(UIMessagePart.Text(fmErrEnvelope(
                    "open_failed",
                    err.message ?: "Failed to stage workspace file",
                )))
            }
            val mime = mimeOverride ?: WorkspacePublicExport.guessMimeType(staged.name, fallback = "*/*")
            val opened = WorkspacePublicExport.openCachedFileExternally(context, staged, mimeOverride = mime)
            if (!opened) {
                return@Tool listOf(UIMessagePart.Text(fmErrEnvelope(
                    "no_handler",
                    "No installed app can open files of type '$mime'",
                )))
            }
            val result = listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("policy", "cache_staged")
                put("path", normalized)
                put("mime", mime)
            }.toString()))
            streamer.streamIfHeadless(invocationContext, "OpenFile: ${staged.name}")
            return@Tool result
        }

        // Policy 2b: Cache-staged delivery from allowlisted host deliverable root via FD-relative O_NOFOLLOW.
        val path = AgentWorkspace.expand(rawPath)
        PathSafetyGuard.check(path)?.let { v ->
            return@Tool listOf(UIMessagePart.Text(fmErrEnvelope(v.code, v.detail)))
        }
        val (allowedRoot, relPath) = DeliverableSourceGuard.resolveAllowedOpenFileSource(context, path)
            ?: return@Tool listOf(UIMessagePart.Text(fmErrEnvelope(
                "path_not_deliverable",
                "Refusing to expose private or disallowed path to external viewers: $rawPath",
            )))

        val leafName = relPath.substringAfterLast('/')
        val staged = try {
            WorkspacePublicExport.stageToRestrictedShareCacheSuspend(
                context = context,
                fileName = leafName,
                maxBytes = OPEN_FILE_STAGE_MAX_BYTES,
            ) { out ->
                DeliverableSourceGuard.openTrustedFileNoFollow(allowedRoot, relPath, displayPath = rawPath).use { inp ->
                    WorkspacePublicExport.copyBoundedCancellable(inp, out, OPEN_FILE_STAGE_MAX_BYTES)
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: NoSuchFileException) {
            return@Tool listOf(UIMessagePart.Text(fmErrEnvelope("not_found", "File not found: $rawPath")))
        } catch (err: Throwable) {
            return@Tool listOf(UIMessagePart.Text(fmErrEnvelope(
                "open_failed",
                "Could not stage file safely: ${err.message ?: err::class.simpleName}",
            )))
        }

        val mime = mimeOverride
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(staged.extension.lowercase())
            ?: "*/*"
        val opened = WorkspacePublicExport.openCachedFileExternally(context, staged, mimeOverride = mime)
        if (!opened) {
            return@Tool listOf(UIMessagePart.Text(fmErrEnvelope(
                "no_handler",
                "No installed app can open files of type '$mime'",
            )))
        }
        val result = listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("policy", "cache_staged")
            put("path", File(allowedRoot, relPath).absolutePath)
            put("mime", mime)
        }.toString()))
        streamer.streamIfHeadless(invocationContext, "OpenFile: ${staged.name}")
        result
    },
)
