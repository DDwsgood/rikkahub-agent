package me.rerere.rikkahub.data.files

import me.rerere.rikkahub.data.preferences.WorkspaceSharedDirPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class WorkspacePublicExportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun normalizeRootfsPathCollapsesTraversalAndRepeatedSlashes() {
        assertEquals("/sdcard/pwn", WorkspacePublicExport.normalizeRootfsPath("/workspace/../sdcard/pwn"))
        assertEquals("/sdcard/pwn", WorkspacePublicExport.normalizeRootfsPath("//sdcard//./pwn"))
        assertEquals("/workspace/report.pdf", WorkspacePublicExport.normalizeRootfsPath("/workspace/sub/../report.pdf"))
        assertEquals("/", WorkspacePublicExport.normalizeRootfsPath("/../../.."))
        assertEquals("/workspace/a/b", WorkspacePublicExport.normalizeRootfsPath("\\workspace\\a\\b"))
    }

    @Test
    fun normalizeRootfsPathRejectsRelativeAndNulPaths() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePublicExport.normalizeRootfsPath("workspace/relative.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePublicExport.normalizeRootfsPath("/workspace/bad\u0000name.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspacePublicExport.normalizeRootfsPath("   ")
        }
    }

    @Test
    fun sanitizeFileNameStripsPathSeparatorsAndIllegalChars() {
        assertEquals("report_2026.pdf", WorkspacePublicExport.sanitizeFileName("../nested/report:2026.pdf"))
        assertEquals("export.bin", WorkspacePublicExport.sanitizeFileName("..."))
    }

    @Test
    fun claimUniqueTargetFileAtomicallyAvoidsOverwritingExistingFiles() {
        val dir = tempFolder.newFolder("downloads")
        val (f1, out1) = WorkspacePublicExport.claimUniqueTargetFile(dir, "report.pdf")
        out1.use { it.write("first".toByteArray()) }

        val (f2, out2) = WorkspacePublicExport.claimUniqueTargetFile(dir, "report.pdf")
        out2.use { it.write("second".toByteArray()) }

        assertEquals("report.pdf", f1.name)
        assertEquals("report (1).pdf", f2.name)
        assertEquals("first", f1.readText())
        assertEquals("second", f2.readText())
    }

    @Test
    fun boundedCountingOutputStreamRejectsOversizedWriteMidStream() {
        val sink = ByteArrayOutputStream()
        val bounded = WorkspacePublicExport.BoundedCountingOutputStream(sink, maxBytes = 8L)
        bounded.write(ByteArray(5))
        assertEquals(5L, bounded.bytesWritten)

        val err = assertThrows(DownloadExportException::class.java) {
            bounded.write(ByteArray(4))
        }
        assertEquals(ExportErrorCode.FILE_TOO_LARGE, err.code)
    }

    @Test
    fun sharedDirFolderNameIsolatesWorkspacesByStableIdEvenWithSameName() {
        val pathA = WorkspaceSharedDirPreferences.displayPath("Project", "ws-id-111")
        val pathB = WorkspaceSharedDirPreferences.displayPath("Project", "ws-id-222")
        val pathARenamed = WorkspaceSharedDirPreferences.displayPath("Renamed Project", "ws-id-111")

        assertNotEquals("Same-name workspaces must never share a /shared directory", pathA, pathB)
        assertEquals("Renaming a workspace must keep its /shared directory stable", pathA, pathARenamed)
    }
}
