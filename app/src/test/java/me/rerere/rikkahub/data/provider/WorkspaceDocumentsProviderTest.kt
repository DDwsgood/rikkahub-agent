package me.rerere.rikkahub.data.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * End-to-end JVM regression tests exercising [WorkspaceDocumentsProvider]'s actual FD-pinned
 * open, proxy read/write callback, create (file + renameat dir), and copy code paths starting
 * from an uncontrolled `workspacesBaseDir`.
 */
class WorkspaceDocumentsProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun setupWorkspace(root: String = "ws-saf"): Pair<File, File> {
        val baseDir = tempFolder.newFolder("workspaces")
        val filesDir = File(baseDir, "$root/files").apply { mkdirs() }
        return baseDir to filesDir
    }

    @Test
    fun openWorkspaceChannelAndProxyCallbackReadWriteAppendTruncate() {
        val (baseDir, filesDir) = setupWorkspace()
        File(filesDir, "docs").mkdirs()
        File(filesDir, "docs/note.txt").writeText("hello")

        // Read + write via exact SAF ChannelProxyFileDescriptorCallback
        val rw = WorkspaceDocumentsProvider.openWorkspaceChannel(baseDir, "ws-saf", "docs/note.txt", "rw")
        val cb = WorkspaceDocumentsProvider.ChannelProxyFileDescriptorCallback(rw.channel, rw.appendOnly)
        try {
            assertEquals(5L, cb.onGetSize())
            val readBuf = ByteArray(10)
            assertEquals(5, cb.onRead(0L, 10, readBuf))
            assertEquals("hello", String(readBuf, 0, 5))

            assertEquals(6, cb.onWrite(5L, 6, " world".toByteArray()))
            cb.onFsync()
            assertEquals(11L, cb.onGetSize())
        } finally {
            cb.onRelease()
        }
        assertEquals("hello world", File(filesDir, "docs/note.txt").readText())

        // Append mode ignores caller offset and appends at EOF
        val wa = WorkspaceDocumentsProvider.openWorkspaceChannel(baseDir, "ws-saf", "docs/note.txt", "wa")
        val waCb = WorkspaceDocumentsProvider.ChannelProxyFileDescriptorCallback(wa.channel, wa.appendOnly)
        try {
            assertTrue(wa.appendOnly)
            waCb.onWrite(0L, 1, "!".toByteArray())
        } finally {
            waCb.onRelease()
        }
        assertEquals("hello world!", File(filesDir, "docs/note.txt").readText())

        // Truncate mode resets content before write
        val wt = WorkspaceDocumentsProvider.openWorkspaceChannel(baseDir, "ws-saf", "docs/note.txt", "wt")
        val wtCb = WorkspaceDocumentsProvider.ChannelProxyFileDescriptorCallback(wt.channel, wt.appendOnly)
        try {
            wtCb.onWrite(0L, 3, "new".toByteArray())
        } finally {
            wtCb.onRelease()
        }
        assertEquals("new", File(filesDir, "docs/note.txt").readText())
    }

    @Test
    fun openWorkspaceChannelRejectsSymlinkedWorkspaceRootFilesDirAncestorAndLeaf() {
        val (baseDir, filesDir) = setupWorkspace()
        val outside = tempFolder.newFolder("outside_secret")
        val secretFile = File(outside, "secret.txt").apply { writeText("classified") }

        // 1. Symlinked leaf inside files/
        Files.createSymbolicLink(File(filesDir, "leaf_sym.txt").toPath(), secretFile.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceDocumentsProvider.openWorkspaceChannel(baseDir, "ws-saf", "leaf_sym.txt", "r")
        }

        // 2. Symlinked ancestor dir inside files/
        Files.createSymbolicLink(File(filesDir, "dir_sym").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceDocumentsProvider.openWorkspaceChannel(baseDir, "ws-saf", "dir_sym/secret.txt", "r")
        }

        // 3. Symlinked files/ directory itself
        File(baseDir, "ws-bad").mkdirs()
        Files.createSymbolicLink(File(baseDir, "ws-bad/files").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceDocumentsProvider.openWorkspaceChannel(baseDir, "ws-bad", "secret.txt", "r")
        }

        // 4. Symlinked workspace root under baseDir
        Files.createSymbolicLink(File(baseDir, "ws-link").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceDocumentsProvider.openWorkspaceChannel(baseDir, "ws-link", "secret.txt", "r")
        }
        assertEquals("classified", secretFile.readText())
    }

    @Test
    fun createAndCopyWorkspaceDocumentUsePinnedFdAndBlockSymlinksAndSelfCopy() {
        val (baseDir, filesDir) = setupWorkspace()
        val outside = tempFolder.newFolder("copy_outside")
        val secret = File(outside, "leak.txt").apply { writeText("secret") }

        // Create directory (renameat) + colliding directory name + file
        val dir1 = WorkspaceDocumentsProvider.createWorkspaceDocument(baseDir, "ws-saf", "", isDir = true, displayName = "folder")
        val dir2 = WorkspaceDocumentsProvider.createWorkspaceDocument(baseDir, "ws-saf", "", isDir = true, displayName = "folder")
        assertEquals("folder", dir1)
        assertEquals("folder (1)", dir2)
        val file1 = WorkspaceDocumentsProvider.createWorkspaceDocument(baseDir, "ws-saf", "folder", isDir = false, displayName = "a.txt")
        assertEquals("folder/a.txt", file1)
        File(filesDir, file1).writeText("payload")

        // Copy directory cleanly into sibling
        val copied = WorkspaceDocumentsProvider.copyWorkspaceDocument(baseDir, "ws-saf", "folder", "ws-saf", "folder (1)")
        assertEquals("folder (1)/folder", copied)
        assertEquals("payload", File(filesDir, "folder (1)/folder/a.txt").readText())

        // Copying directory into itself is rejected by fileKey()
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceDocumentsProvider.copyWorkspaceDocument(baseDir, "ws-saf", "folder", "ws-saf", "folder")
        }

        // Copying directory containing symlink fails and leaves no partial output
        Files.createSymbolicLink(File(filesDir, "folder/escape.txt").toPath(), secret.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceDocumentsProvider.copyWorkspaceDocument(baseDir, "ws-saf", "folder", "ws-saf", "")
        }
        assertFalse(
            "Failed copy must leave no partial folder (2) directory",
            Files.exists(File(filesDir, "folder (2)").toPath(), LinkOption.NOFOLLOW_LINKS),
        )
    }

    @Test
    fun moveWorkspaceDocumentCrossWorkspaceIsSameVolumeAtomicAndNeverOverwritesExisting() {
        val baseDir = tempFolder.newFolder("workspaces_move")
        val wsAFiles = File(baseDir, "ws-a/files").apply { mkdirs() }
        val wsBFiles = File(baseDir, "ws-b/files").apply { mkdirs() }

        File(wsAFiles, "src").mkdirs()
        val srcFile = File(wsAFiles, "src/doc.txt").apply { writeText("from-a") }
        val srcFileKey = Files.readAttributes(
            srcFile.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).fileKey()

        // Existing destination file with same name must NOT be overwritten
        val existingTarget = File(wsBFiles, "doc.txt").apply { writeText("keep-b") }

        val movedRel = WorkspaceDocumentsProvider.moveWorkspaceDocument(
            baseDir = baseDir,
            srcRoot = "ws-a",
            srcRelPath = "src/doc.txt",
            dstRoot = "ws-b",
            dstParentRelPath = "",
        )
        assertEquals("doc (1).txt", movedRel)
        assertEquals("keep-b", existingTarget.readText())
        assertFalse(Files.exists(srcFile.toPath(), LinkOption.NOFOLLOW_LINKS))
        val dstFile = File(wsBFiles, "doc (1).txt")
        assertEquals("from-a", dstFile.readText())
        val dstFileKey = Files.readAttributes(
            dstFile.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).fileKey()
        if (srcFileKey != null && dstFileKey != null) {
            assertEquals("Same-volume cross-workspace move must preserve inode (no copy+delete)", srcFileKey, dstFileKey)
        }

        // Cross-workspace directory move preserves directory inode too
        val srcDir = File(wsAFiles, "bundle").apply { mkdirs() }
        File(srcDir, "nested.txt").writeText("nested-payload")
        val srcDirKey = Files.readAttributes(
            srcDir.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).fileKey()
        val movedDirRel = WorkspaceDocumentsProvider.moveWorkspaceDocument(
            baseDir = baseDir,
            srcRoot = "ws-a",
            srcRelPath = "bundle",
            dstRoot = "ws-b",
            dstParentRelPath = "",
        )
        assertEquals("bundle", movedDirRel)
        assertFalse(Files.exists(srcDir.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("nested-payload", File(wsBFiles, "bundle/nested.txt").readText())
        val dstDirKey = Files.readAttributes(
            File(wsBFiles, "bundle").toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).fileKey()
        if (srcDirKey != null && dstDirKey != null) {
            assertEquals("Cross-workspace directory move must preserve inode", srcDirKey, dstDirKey)
        }
    }

    @Test
    fun effectiveProotSdcardModeNeverMountsSdcardInReadOnlyMode() {
        assertEquals(
            me.rerere.workspace.WorkspaceSdcardMode.NONE,
            me.rerere.rikkahub.data.repository.WorkspaceRepository.effectiveProotSdcardMode(
                me.rerere.workspace.WorkspaceSdcardMode.NONE,
            ),
        )
        assertEquals(
            me.rerere.workspace.WorkspaceSdcardMode.NONE,
            me.rerere.rikkahub.data.repository.WorkspaceRepository.effectiveProotSdcardMode(
                me.rerere.workspace.WorkspaceSdcardMode.READ_ONLY,
            ),
        )
        assertEquals(
            me.rerere.workspace.WorkspaceSdcardMode.READ_WRITE,
            me.rerere.rikkahub.data.repository.WorkspaceRepository.effectiveProotSdcardMode(
                me.rerere.workspace.WorkspaceSdcardMode.READ_WRITE,
            ),
        )
    }
}
