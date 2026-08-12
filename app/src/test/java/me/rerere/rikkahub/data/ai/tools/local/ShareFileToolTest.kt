package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.tools.LenientLocalToolListSerializer
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.termux.TermuxEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe
import java.io.File
import java.nio.file.Files

/**
 * `share_file` — JVM tests for pure helpers, early-return validation paths, and the
 * source allowlist.
 *
 * The pure helpers ([sanitizeShareFileName], [resolveShareMime], [cleanupStaleShareCache],
 * [resolveTermuxSharePath], [classifyShareSource], [validateMimeOverride]) are tested
 * directly. The tool-level tests exercise paths that early-return BEFORE any Android
 * Context / WorkspaceRepository / TermuxEnvironment method is called, using
 * Unsafe-allocated dummy instances (same pattern as [NULL_CONTEXT]).
 *
 * The full share path (copy → FileProvider → startActivity) requires an instrumented test
 * on a real device.
 */
class ShareFileToolTest {

    // ---- Unsafe-allocated dummies ----

    private val UNSAFE: Unsafe = run {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        field.get(null) as Unsafe
    }

    private val UNSAFE_WORKSPACE_REPO: WorkspaceRepository =
        UNSAFE.allocateInstance(WorkspaceRepository::class.java) as WorkspaceRepository

    private val UNSAFE_TERMUX_ENV: TermuxEnvironment =
        UNSAFE.allocateInstance(TermuxEnvironment::class.java) as TermuxEnvironment

    private fun makeTool(headless: Boolean = false) = shareFileTool(
        NULL_CONTEXT,
        UNSAFE_WORKSPACE_REPO,
        UNSAFE_TERMUX_ENV,
        ToolInvocationContext(isHeadless = headless),
    )

    private fun errCode(out: String): String =
        Json.parseToJsonElement(out).jsonObject["error"]?.jsonPrimitive?.contentOrNull ?: ""

    // ---- sanitizeShareFileName ----

    @Test fun `sanitize preserves a normal name`() {
        assertEquals("report.pdf", sanitizeShareFileName("report.pdf"))
    }

    @Test fun `sanitize extracts basename from path`() {
        assertEquals("file.txt", sanitizeShareFileName("/sdcard/Documents/file.txt"))
        assertEquals("file.txt", sanitizeShareFileName("C:\\Users\\me\\file.txt"))
        assertEquals("c", sanitizeShareFileName("a/b\\c"))
    }

    @Test fun `sanitize strips leading dots`() {
        assertEquals("hidden", sanitizeShareFileName(".hidden"))
        assertEquals("name", sanitizeShareFileName("...name"))
    }

    @Test fun `sanitize handles empty and all-dot input`() {
        assertEquals("shared_file", sanitizeShareFileName(""))
        assertEquals("shared_file", sanitizeShareFileName("..."))
        assertEquals("shared_file", sanitizeShareFileName("/"))
    }

    @Test fun `sanitize replaces null bytes in name`() {
        assertEquals("a_b", sanitizeShareFileName("a\u0000b"))
    }

    @Test fun `sanitize preserves unicode names`() {
        assertEquals("报告.pdf", sanitizeShareFileName("报告.pdf"))
    }

    // ---- resolveShareMime ----

    @Test fun `mime override takes priority`() {
        assertEquals("image/png", resolveShareMime("image/png", "text/plain", "txt"))
    }

    @Test fun `mime override trimmed`() {
        assertEquals("image/png", resolveShareMime("  image/png  ", null, null))
    }

    @Test fun `blank mime override falls through`() {
        assertEquals("text/plain", resolveShareMime("  ", "text/plain", null))
    }

    @Test fun `contentResolver type used when no override`() {
        assertEquals("application/pdf", resolveShareMime(null, "application/pdf", "txt"))
    }

    @Test fun `fallback when nothing available`() {
        assertEquals(FALLBACK_MIME, resolveShareMime(null, null, null))
        assertEquals(FALLBACK_MIME, resolveShareMime(null, "", ""))
        assertEquals(FALLBACK_MIME, resolveShareMime(null, null, ""))
    }

    @Test fun `no-extension workspace path yields empty extension and fallback mime`() {
        // Workspace paths extract extension via substringAfterLast('.', missingDelimiterValue = "").
        // A no-dot filename like "README" must yield "" — matching File.extension semantics —
        // so resolveShareMime falls back to FALLBACK_MIME instead of treating "README" as an ext.
        val rootfsPath = "/workspace/README"
        val ext = rootfsPath.substringAfterLast('.', missingDelimiterValue = "")
        assertEquals("no-dot filename should produce empty extension", "", ext)
        assertEquals(FALLBACK_MIME, resolveShareMime(null, null, ext))
    }

    // ---- cleanupStaleShareCache ----

    @Test fun `cleanup removes old dirs but spares current`() {
        val root = Files.createTempDirectory("sharecache").toFile()
        val now = System.currentTimeMillis()
        val oldDir = File(root, "old").apply { mkdirs(); setLastModified(now - 2 * 3600_000L) }
        val currentDir = File(root, "current").apply { mkdirs(); setLastModified(now) }
        val removed = cleanupStaleShareCache(root, ttlMs = 3600_000L, nowMs = now, currentInvocationDir = currentDir)
        assertEquals(1, removed)
        assertFalse(oldDir.exists())
        assertTrue(currentDir.exists())
    }

    @Test fun `cleanup spares fresh dirs`() {
        val root = Files.createTempDirectory("sharecache").toFile()
        val now = System.currentTimeMillis()
        val freshDir = File(root, "fresh").apply { mkdirs(); setLastModified(now - 1000L) }
        val currentDir = File(root, "current").apply { mkdirs() }
        assertEquals(0, cleanupStaleShareCache(root, 3600_000L, now, currentDir))
        assertTrue(freshDir.exists())
    }

    @Test fun `cleanup ignores non-dir files`() {
        val root = Files.createTempDirectory("sharecache").toFile()
        File(root, "stray.txt").writeText("hello")
        val currentDir = File(root, "current").apply { mkdirs() }
        assertEquals(0, cleanupStaleShareCache(root, 3600_000L, System.currentTimeMillis(), currentDir))
    }

    @Test fun `cleanup handles missing root dir`() {
        val root = File(Files.createTempDirectory("sharecache").toFile(), "nonexistent")
        assertEquals(0, cleanupStaleShareCache(root, 3600_000L, 0, File(root, "current")))
    }

    // ---- resolveTermuxSharePath (lowercase only) ----

    @Test fun `termux path not a termux prefix returns null error`() {
        val result = resolveTermuxSharePath("/sdcard/foo", File("/data/data/app/files/termux"), File("/data/data/app/files/termux/home"))
        assertFalse(result.ok)
        assertNull(result.error)
    }

    @Test fun `termux tilde expands to home`() {
        val tmpRoot = Files.createTempDirectory("termux").toFile()
        val home = File(tmpRoot, "home").apply { mkdirs() }
        val target = File(home, "file.txt").apply { writeText("test") }
        val result = resolveTermuxSharePath("termux:~/file.txt", tmpRoot, home)
        assertTrue("expected ok, got error: ${result.error}", result.ok)
        assertEquals(target.canonicalPath, result.path)
    }

    @Test fun `termux bare tilde expands to home`() {
        val tmpRoot = Files.createTempDirectory("termux").toFile()
        val home = File(tmpRoot, "home").apply { mkdirs() }
        val result = resolveTermuxSharePath("termux:~", tmpRoot, home)
        assertTrue(result.ok)
        assertEquals(home.canonicalPath, result.path)
    }

    @Test fun `termux absolute path inside root`() {
        val tmpRoot = Files.createTempDirectory("termux").toFile()
        val usr = File(tmpRoot, "usr").apply { mkdirs() }
        val result = resolveTermuxSharePath("termux:/usr/lib", tmpRoot, File(tmpRoot, "home"))
        assertTrue(result.ok)
        assertEquals(File(usr, "lib").canonicalPath, result.path)
    }

    @Test fun `termux escape via dotdot is blocked`() {
        val tmpRoot = Files.createTempDirectory("termux").toFile()
        val home = File(tmpRoot, "home").apply { mkdirs() }
        val result = resolveTermuxSharePath("termux:~/../../etc/passwd", tmpRoot, home)
        assertFalse(result.ok)
        assertNotNull(result.error)
        assertTrue("expected escape message, got: ${result.error}", result.error!!.contains("escapes"))
    }

    @Test fun `uppercase TERMUX prefix not recognized by resolveTermuxSharePath`() {
        // resolveTermuxSharePath only matches lowercase "termux:" — uppercase returns "not a termux path"
        val result = resolveTermuxSharePath("TERMUX:~/foo", File("/tmp/termux"), File("/tmp/termux/home"))
        assertFalse(result.ok)
        assertNull(result.error) // null error = "not a termux path", not a containment failure
    }

    // ---- classifyShareSource (allowlist) ----

    @Test fun `classify accepts AgentWorkspace tilde`() {
        assertTrue(classifyShareSource("~") is ShareSource.AgentWorkspace)
        assertTrue(classifyShareSource("~/file.txt") is ShareSource.AgentWorkspace)
        assertTrue(classifyShareSource("~/sub/dir/file.txt") is ShareSource.AgentWorkspace)
    }

    @Test fun `classify accepts public storage prefixes`() {
        assertTrue(classifyShareSource("/sdcard/file.txt") is ShareSource.PublicStorage)
        assertTrue(classifyShareSource("/storage/emulated/0/DCIM/photo.jpg") is ShareSource.PublicStorage)
        assertTrue(classifyShareSource("/storage/self/primary/file.txt") is ShareSource.PublicStorage)
        assertTrue(classifyShareSource("/storage/1234-5678/DCIM/photo.jpg") is ShareSource.PublicStorage)
    }

    @Test fun `classify rejects bare storage root`() {
        val s = classifyShareSource("/storage")
        assertTrue(s is ShareSource.Unsupported)
    }

    @Test fun `classify rejects bare storage slash`() {
        val s = classifyShareSource("/storage/")
        assertTrue(s is ShareSource.Unsupported)
    }

    @Test fun `classify accepts workspace`() {
        assertTrue(classifyShareSource("/workspace/file.txt") is ShareSource.Workspace)
        assertTrue(classifyShareSource("/workspace") is ShareSource.Workspace)
    }

    @Test fun `classify accepts lowercase termux only`() {
        assertTrue(classifyShareSource("termux:~/file.txt") is ShareSource.Termux)
        assertTrue(classifyShareSource("termux:/usr/bin") is ShareSource.Termux)
    }

    @Test fun `classify rejects uppercase TERMUX`() {
        val s = classifyShareSource("TERMUX:~/file.txt")
        assertTrue("$s", s is ShareSource.Unsupported)
    }

    @Test fun `classify rejects content uri`() {
        val s = classifyShareSource("content://media/external/images/media/123")
        assertTrue(s is ShareSource.Unsupported)
        assertTrue((s as ShareSource.Unsupported).reason.contains("content://"))
    }

    @Test fun `classify rejects file uri`() {
        val s = classifyShareSource("file:///sdcard/file.txt")
        assertTrue(s is ShareSource.Unsupported)
        assertTrue((s as ShareSource.Unsupported).reason.contains("file://"))
    }

    @Test fun `classify rejects other scheme uris`() {
        assertTrue(classifyShareSource("http://example.com/file.pdf") is ShareSource.Unsupported)
        assertTrue(classifyShareSource("https://example.com/file.pdf") is ShareSource.Unsupported)
        assertTrue(classifyShareSource("ftp://server/file.pdf") is ShareSource.Unsupported)
    }

    @Test fun `classify rejects direct data-data paths`() {
        // Even own-package direct paths are rejected — use ~/ instead
        val s = classifyShareSource("/data/data/excp.rikkahub/files/workspace/file.txt")
        assertTrue(s is ShareSource.Unsupported)
    }

    @Test fun `classify rejects direct termux home path`() {
        // Direct /data/data/<pkg>/files/termux/home/... is rejected — use termux:~/ instead
        val s = classifyShareSource("/data/data/excp.rikkahub/files/termux/home/file.txt")
        assertTrue(s is ShareSource.Unsupported)
    }

    @Test fun `classify rejects relative paths`() {
        assertTrue(classifyShareSource("relative/path.txt") is ShareSource.Unsupported)
        assertTrue(classifyShareSource("./file.txt") is ShareSource.Unsupported)
        assertTrue(classifyShareSource("../file.txt") is ShareSource.Unsupported)
    }

    // ---- validateMimeOverride ----

    @Test fun `mime validation accepts valid types`() {
        assertNull(validateMimeOverride("image/png"))
        assertNull(validateMimeOverride("application/pdf"))
        assertNull(validateMimeOverride("text/plain"))
        assertNull(validateMimeOverride("video/mp4"))
        assertNull(validateMimeOverride("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
    }

    @Test fun `mime validation accepts null and blank`() {
        assertNull(validateMimeOverride(null))
        assertNull(validateMimeOverride(""))
        assertNull(validateMimeOverride("   "))
    }

    @Test fun `mime validation rejects missing slash`() {
        assertNotNull(validateMimeOverride("image"))
        assertNotNull(validateMimeOverride("imagepng"))
    }

    @Test fun `mime validation rejects extra slash`() {
        assertNotNull(validateMimeOverride("image/png/foo"))
    }

    @Test fun `mime validation rejects spaces in tokens`() {
        assertNotNull(validateMimeOverride("image /png"))
        assertNotNull(validateMimeOverride("image/ png"))
    }

    @Test fun `mime validation trims whitespace`() {
        assertNull(validateMimeOverride("  image/png  "))
    }

    // ---- validateCaptionLength / validateSubjectLength ----

    @Test fun `caption validation accepts null and short`() {
        assertNull(validateCaptionLength(null))
        assertNull(validateCaptionLength("hello"))
        assertNull(validateCaptionLength("a".repeat(SHARE_FILE_MAX_CAPTION)))
    }

    @Test fun `caption validation rejects too long`() {
        assertNotNull(validateCaptionLength("a".repeat(SHARE_FILE_MAX_CAPTION + 1)))
    }

    @Test fun `subject validation accepts null and short`() {
        assertNull(validateSubjectLength(null))
        assertNull(validateSubjectLength("hello"))
        assertNull(validateSubjectLength("a".repeat(SHARE_FILE_MAX_SUBJECT)))
    }

    @Test fun `subject validation rejects too long`() {
        assertNotNull(validateSubjectLength("a".repeat(SHARE_FILE_MAX_SUBJECT + 1)))
    }

    // ---- CountingOutputStream (size limit enforcement) ----

    @Test fun `counting output stream tracks bytes`() {
        val tmp = File(Files.createTempDirectory("counting").toFile(), "out.bin")
        val fos = java.io.FileOutputStream(tmp)
        val cos = CountingOutputStream(fos, 1000L)
        cos.write(ByteArray(100))
        cos.write(ByteArray(50))
        cos.flush()
        cos.close()
        assertEquals(150L, cos.total)
        assertEquals(150L, tmp.length())
    }

    @Test fun `counting output stream throws when limit exceeded`() {
        val tmp = File(Files.createTempDirectory("counting").toFile(), "out.bin")
        val fos = java.io.FileOutputStream(tmp)
        val cos = CountingOutputStream(fos, 100L)
        cos.write(ByteArray(50))
        try {
            cos.write(ByteArray(60)) // total would be 110 > 100
            assertFails("expected ShareFileTooLargeException")
        } catch (e: ShareFileTooLargeException) {
            // Correct exception type — no string matching needed
        }
    }

    @Test fun `counting output stream single-byte write also enforces limit`() {
        val tmp = File(Files.createTempDirectory("counting").toFile(), "out.bin")
        val fos = java.io.FileOutputStream(tmp)
        val cos = CountingOutputStream(fos, 2L)
        cos.write(0)
        cos.write(1)
        try {
            cos.write(2) // third byte exceeds 2
            assertFails("expected ShareFileTooLargeException")
        } catch (e: ShareFileTooLargeException) {
            // Correct exception type — no string matching needed
        }
    }

    @Test fun `ShareFileTooLargeException is an IOException`() {
        // Ensures the exception can be caught by existing IOException handlers
        val e = ShareFileTooLargeException("test")
        assertTrue(e is java.io.IOException)
    }

    // ---- streamCopyWithLimit with injected limit ----

    @Test fun `stream copy respects small limit and cleans up dest`() {
        val tmpDir = Files.createTempDirectory("copytest").toFile()
        val src = File(tmpDir, "src.bin").apply { writeBytes(ByteArray(200)) }
        val dest = File(tmpDir, "dest.bin")
        try {
            streamCopyWithLimit(src, dest, maxBytes = 100)
            assertFails("expected ShareFileTooLargeException")
        } catch (e: ShareFileTooLargeException) {
            // Correct exception type — no string matching needed
        }
        assertFalse("dest should be deleted on failure", dest.exists())
    }

    @Test fun `stream copy succeeds within limit`() {
        val tmpDir = Files.createTempDirectory("copytest").toFile()
        val src = File(tmpDir, "src.bin").apply { writeBytes(ByteArray(50)) }
        val dest = File(tmpDir, "dest.bin")
        val bytes = streamCopyWithLimit(src, dest, maxBytes = 100)
        assertEquals(50L, bytes)
        assertTrue(dest.exists())
        assertEquals(50L, dest.length())
    }

    @Test fun `stream copy succeeds for 0-byte file`() {
        val tmpDir = Files.createTempDirectory("copytest").toFile()
        val src = File(tmpDir, "empty.bin").apply { createNewFile() }
        val dest = File(tmpDir, "dest.bin")
        val bytes = streamCopyWithLimit(src, dest, maxBytes = 100)
        assertEquals(0L, bytes)
        assertTrue(dest.exists())
        assertEquals(0L, dest.length())
    }

    // ---- isWithinPublicStorage (containment) ----

    @Test fun `isWithinPublicStorage accepts sdcard paths`() {
        assertTrue(isWithinPublicStorage("/sdcard"))
        assertTrue(isWithinPublicStorage("/sdcard/file.txt"))
        assertTrue(isWithinPublicStorage("/sdcard/DCIM/photo.jpg"))
    }

    @Test fun `isWithinPublicStorage accepts storage emulated paths`() {
        assertTrue(isWithinPublicStorage("/storage/emulated"))
        assertTrue(isWithinPublicStorage("/storage/emulated/0/DCIM/photo.jpg"))
    }

    @Test fun `isWithinPublicStorage accepts storage self paths`() {
        assertTrue(isWithinPublicStorage("/storage/self"))
        assertTrue(isWithinPublicStorage("/storage/self/primary/file.txt"))
    }

    @Test fun `isWithinPublicStorage accepts removable storage volume`() {
        assertTrue(isWithinPublicStorage("/storage/1234-5678/DCIM/photo.jpg"))
        assertTrue(isWithinPublicStorage("/storage/abcd-efgh/file.txt"))
    }

    @Test fun `isWithinPublicStorage rejects own app data paths`() {
        assertFalse(isWithinPublicStorage("/data/data/excp.rikkahub/files/workspace"))
        assertFalse(isWithinPublicStorage("/data/data/excp.rikkahub/databases/secret.db"))
    }

    @Test fun `isWithinPublicStorage rejects system paths`() {
        assertFalse(isWithinPublicStorage("/system/lib/libc.so"))
        assertFalse(isWithinPublicStorage("/proc/self/status"))
        assertFalse(isWithinPublicStorage("/dev/null"))
    }

    @Test fun `isWithinPublicStorage rejects bare storage root`() {
        assertFalse(isWithinPublicStorage("/storage"))
        assertFalse(isWithinPublicStorage("/storage/"))
    }

    @Test fun `isWithinPublicStorage rejects unrelated paths`() {
        assertFalse(isWithinPublicStorage("/tmp/file.txt"))
        assertFalse(isWithinPublicStorage("/etc/passwd"))
    }

    // ---- tool-level early-return tests ----

    @Test fun `headless returns interactive_only before any file access`() {
        val out = execTool(makeTool(headless = true), """{"path":"/sdcard/test.txt"}""")
        assertEquals("interactive_only", errCode(out))
        val detail = Json.parseToJsonElement(out).jsonObject["detail"]?.jsonPrimitive?.content ?: ""
        assertTrue("detail should mention foreground: $detail", detail.contains("foreground"))
    }

    @Test fun `headless check happens before missing path check`() {
        val out = execTool(makeTool(headless = true), """{}""")
        assertEquals("interactive_only", errCode(out))
    }

    @Test fun `headless check happens before content uri check`() {
        val out = execTool(makeTool(headless = true), """{"path":"content://media/123"}""")
        assertEquals("interactive_only", errCode(out))
    }

    @Test fun `headless check happens before unsupported source check`() {
        val out = execTool(makeTool(headless = true), """{"path":"/data/data/excp.rikkahub/files/databases/secret.db"}""")
        assertEquals("interactive_only", errCode(out))
    }

    @Test fun `missing path returns error`() {
        assertEquals("missing_path", errCode(execTool(makeTool(), """{}""")))
    }

    @Test fun `blank path returns error`() {
        assertEquals("missing_path", errCode(execTool(makeTool(), """{"path":""}""")))
    }

    // ---- MIME validation at tool level (before any file access) ----

    @Test fun `invalid mime override returns unsupported_mime`() {
        assertEquals("unsupported_mime", errCode(execTool(makeTool(), """{"path":"/sdcard/x.txt","mime_type":"not-a-mime"}""")))
    }

    @Test fun `mime override missing slash returns unsupported_mime`() {
        assertEquals("unsupported_mime", errCode(execTool(makeTool(), """{"path":"/sdcard/x.txt","mime_type":"imagepng"}""")))
    }

    @Test fun `valid mime override with unsupported source still returns unsupported_source`() {
        // MIME is valid, but source is rejected — both are checked before file access
        val out = execTool(makeTool(), """{"path":"/data/data/excp.rikkahub/files/secret","mime_type":"image/png"}""")
        assertEquals("unsupported_source", errCode(out))
    }

    // ---- caption/subject length at tool level ----

    @Test fun `oversized caption returns text_too_long`() {
        val longCaption = "a".repeat(SHARE_FILE_MAX_CAPTION + 1)
        assertEquals("text_too_long", errCode(execTool(makeTool(), """{"path":"/sdcard/x.txt","text":"$longCaption"}""")))
    }

    @Test fun `oversized subject returns subject_too_long`() {
        val longSubject = "a".repeat(SHARE_FILE_MAX_SUBJECT + 1)
        assertEquals("subject_too_long", errCode(execTool(makeTool(), """{"path":"/sdcard/x.txt","subject":"$longSubject"}""")))
    }

    // ---- source allowlist at tool level (all before any file access) ----

    @Test fun `content uri returns unsupported_source`() {
        assertEquals("unsupported_source", errCode(execTool(makeTool(), """{"path":"content://media/external/images/media/123"}""")))
    }

    @Test fun `file uri returns unsupported_source`() {
        assertEquals("unsupported_source", errCode(execTool(makeTool(), """{"path":"file:///sdcard/test.txt"}""")))
    }

    @Test fun `http uri returns unsupported_source`() {
        assertEquals("unsupported_source", errCode(execTool(makeTool(), """{"path":"https://example.com/file.pdf"}""")))
    }

    @Test fun `uppercase TERMUX returns unsupported_source`() {
        assertEquals("unsupported_source", errCode(execTool(makeTool(), """{"path":"TERMUX:~/file.txt"}""")))
    }

    @Test fun `direct data-data own package returns unsupported_source`() {
        assertEquals("unsupported_source", errCode(execTool(makeTool(), """{"path":"/data/data/excp.rikkahub/files/workspace/secret.txt"}""")))
    }

    @Test fun `direct data-data databases returns unsupported_source`() {
        assertEquals("unsupported_source", errCode(execTool(makeTool(), """{"path":"/data/data/excp.rikkahub/databases/secret.db"}""")))
    }

    @Test fun `direct data-data shared_prefs returns unsupported_source`() {
        assertEquals("unsupported_source", errCode(execTool(makeTool(), """{"path":"/data/data/excp.rikkahub/shared_prefs/settings.xml"}""")))
    }

    @Test fun `direct termux home path returns unsupported_source`() {
        assertEquals("unsupported_source", errCode(execTool(makeTool(), """{"path":"/data/data/excp.rikkahub/files/termux/home/file.txt"}""")))
    }

    @Test fun `bare storage returns unsupported_source`() {
        assertEquals("unsupported_source", errCode(execTool(makeTool(), """{"path":"/storage"}""")))
    }

    @Test fun `bare storage slash returns unsupported_source`() {
        assertEquals("unsupported_source", errCode(execTool(makeTool(), """{"path":"/storage/"}""")))
    }

    @Test fun `relative path returns unsupported_source`() {
        assertEquals("unsupported_source", errCode(execTool(makeTool(), """{"path":"relative/file.txt"}""")))
    }

    @Test fun `dotdot relative path returns unsupported_source`() {
        assertEquals("unsupported_source", errCode(execTool(makeTool(), """{"path":"../file.txt"}""")))
    }

    // ---- approval + serialization ----

    @Test fun `share_file requires approval`() {
        assertTrue("share_file must be in ALWAYS_ASK", "share_file" in ToolApprovalDefaults.ALWAYS_ASK)
        assertTrue(ToolApprovalDefaults.requiresApproval("share_file"))
    }

    @Test fun `share_file is in NO_ALWAYS_ALLOW`() {
        assertTrue("share_file must be in NO_ALWAYS_ALLOW", "share_file" in ToolApprovalDefaults.NO_ALWAYS_ALLOW)
        assertFalse(ToolApprovalDefaults.allowsAlwaysAllow("share_file"))
    }

    @Test fun `LocalToolOption ShareFile serializes with stable serial name`() {
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(LocalToolOption.serializer(), LocalToolOption.ShareFile)
        assertTrue("expected serial name 'share_file' in: $encoded", encoded.contains("\"share_file\""))
        val decoded = json.decodeFromString(LocalToolOption.serializer(), encoded)
        assertEquals(LocalToolOption.ShareFile, decoded)
    }

    @Test fun `ShareFile survives lenient list round-trip`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val input = """[{"type":"share"},{"type":"share_file"},{"type":"time_info"}]"""
        val decoded = json.decodeFromString(LenientLocalToolListSerializer, input)
        assertEquals(
            listOf(LocalToolOption.Share, LocalToolOption.ShareFile, LocalToolOption.TimeInfo),
            decoded,
        )
    }

    // ---- helper ----

    private fun assertFails(message: String) {
        throw AssertionError(message)
    }
}