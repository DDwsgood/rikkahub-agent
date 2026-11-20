package me.rerere.workspace

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

class WorkspaceSafeFilesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun deleteRecursivelyNoFollowUnlinksDirectorySymlinkWithoutDeletingTarget() {
        val outsideDir = tempFolder.newFolder("outside")
        val sentinel = File(outsideDir, "sentinel.txt").apply { writeText("do-not-delete") }

        val workspaceDir = tempFolder.newFolder("ws")
        File(workspaceDir, "normal.txt").writeText("bye")
        val sub = File(workspaceDir, "sub").apply { mkdirs() }
        Files.createSymbolicLink(File(sub, "escape").toPath(), outsideDir.toPath())

        assertTrue(WorkspaceSafeFiles.deleteRecursivelyNoFollow(workspaceDir))
        assertFalse(Files.exists(workspaceDir.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertTrue("External directory must stay intact", outsideDir.exists())
        assertEquals("do-not-delete", sentinel.readText())
    }

    @Test
    fun clearDirectoryContentsNoFollowPreservesTmpDirectoryItself() {
        val tmpDir = tempFolder.newFolder("tmp")
        val nested = File(tmpDir, "nested").apply { mkdirs() }
        File(nested, "a.tmp").writeText("1")
        val outsideDir = tempFolder.newFolder("outside_tmp")
        val sentinel = File(outsideDir, "keep.txt").apply { writeText("safe") }
        Files.createSymbolicLink(File(tmpDir, "link").toPath(), outsideDir.toPath())

        assertTrue(WorkspaceSafeFiles.clearDirectoryContentsNoFollow(tmpDir))
        assertTrue("tmp directory itself must still exist", tmpDir.exists() && tmpDir.isDirectory)
        assertEquals(0, tmpDir.listFiles()?.size ?: -1)
        assertEquals("safe", sentinel.readText())
    }

    @Test
    fun cleanupAllTempDirsRejectsSymlinkedWorkspaceRootAndAncestorDirs() {
        val baseDir = tempFolder.newFolder("workspaces")
        val outsideVictim = tempFolder.newFolder("outside_victim")
        val outsideTmp = File(outsideVictim, "tmp").apply { mkdirs() }
        val sentinel1 = File(outsideTmp, "keep1.txt").apply { writeText("keep-root") }
        val outsideVarTmp = File(outsideVictim, "var/tmp").apply { mkdirs() }
        val sentinel2 = File(outsideVarTmp, "keep2.txt").apply { writeText("keep-ancestor") }

        // 1) Workspace root itself replaced with a symlink
        Files.createSymbolicLink(File(baseDir, "wsroot").toPath(), outsideVictim.toPath())

        // 2) Real workspace whose linux/ or linux/var ancestor is a symlink outside
        val realWs = File(baseDir, "wsreal").apply { mkdirs() }
        File(realWs, "tmp").mkdirs()
        File(realWs, "tmp/scratch.txt").writeText("delete-me")
        Files.createSymbolicLink(File(realWs, "linux").toPath(), outsideVictim.toPath())

        val manager = WorkspaceManager(baseDir = baseDir)
        manager.cleanupAllTempDirs()

        assertEquals("Symlinked workspace root target must stay untouched", "keep-root", sentinel1.readText())
        assertEquals("Symlinked linux ancestor target must stay untouched", "keep-ancestor", sentinel2.readText())
        assertFalse("Legitimate workspace tmp file should be cleared", File(realWs, "tmp/scratch.txt").exists())
        assertTrue("Legitimate workspace tmp directory must stay", File(realWs, "tmp").isDirectory)
    }

    @Test
    fun deleteWorkspaceUnlinksSymlinkedRootWithoutDeletingTarget() {
        val baseDir = tempFolder.newFolder("workspaces_del")
        val outsideDir = tempFolder.newFolder("outside_del")
        val sentinel = File(outsideDir, "precious.txt").apply { writeText("intact") }
        Files.createSymbolicLink(File(baseDir, "ws1").toPath(), outsideDir.toPath())

        val manager = WorkspaceManager(baseDir = baseDir)
        assertTrue(manager.deleteWorkspace("ws1"))
        assertFalse(Files.exists(File(baseDir, "ws1").toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("intact", sentinel.readText())
    }

    @Test
    fun writeRootfsTextNoFollowRejectsSymlinkInsideWorkspaceEscapingToOutsideDir() {
        val baseDir = tempFolder.newFolder("workspaces_write")
        val sharedVictim = tempFolder.newFolder("shared_victim")
        val victimFile = File(sharedVictim, "existing.txt").apply { writeText("original") }

        val manager = WorkspaceManager(baseDir = baseDir)
        manager.ensureWorkspace("ws1")
        // Create a symlink inside /workspace pointing to sharedVictim
        Files.createSymbolicLink(
            File(manager.filesDir("ws1"), "alias").toPath(),
            sharedVictim.toPath(),
        )

        assertThrows(Exception::class.java) {
            manager.writeRootfsTextNoFollow("ws1", "/workspace/alias/existing.txt", "pwned", overwrite = true)
        }
        assertEquals("Symlink escape write must be blocked", "original", victimFile.readText())
    }

    @Test
    fun copyRecursivelyNoFollowRejectsNestedSymlinkAndLeavesNoPartialOutput() {
        val outsideFile = tempFolder.newFile("secret.txt").apply { writeText("top-secret") }
        val srcDir = tempFolder.newFolder("src")
        File(srcDir, "a_ok.txt").writeText("hello")
        Files.createSymbolicLink(File(srcDir, "z_leak.txt").toPath(), outsideFile.toPath())
        val dstDir = File(tempFolder.root, "dst")

        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSafeFiles.copyRecursivelyNoFollow(srcDir, dstDir)
        }
        assertFalse("Failed copy must not leave partial target directory", Files.exists(dstDir.toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun copyRecursivelyNoFollowRejectsSymlinkedTargetParentDirectory() {
        val outsideTarget = tempFolder.newFolder("outside_copy_target")
        val linkParent = File(tempFolder.root, "link_parent").toPath()
        Files.createSymbolicLink(linkParent, outsideTarget.toPath())
        val srcFile = tempFolder.newFile("plain.txt").apply { writeText("hello") }

        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSafeFiles.copyRecursivelyNoFollow(srcFile, File(linkParent.toFile(), "out.txt"))
        }
        assertEquals(0, outsideTarget.listFiles()?.size ?: -1)
    }

    @Test
    fun copyRecursivelyNoFollowCopiesPlainTree() {
        val srcDir = tempFolder.newFolder("plain_src")
        File(srcDir, "a/b").mkdirs()
        File(srcDir, "a/b/file.txt").writeText("content")
        val dstDir = File(tempFolder.root, "plain_dst")

        assertTrue(WorkspaceSafeFiles.copyRecursivelyNoFollow(srcDir, dstDir))
        assertEquals("content", File(dstDir, "a/b/file.txt").readText())
    }

    @Test
    fun openChannelNoFollowReadsWritesAndRejectsSymlinkAncestorAndLeaf() {
        val root = tempFolder.newFolder("chan_root")
        val outside = tempFolder.newFolder("chan_outside")
        val secret = File(outside, "secret.txt").apply { writeText("top-secret") }
        File(root, "sub").mkdirs()
        File(root, "sub/ok.txt").writeText("hello")
        Files.createSymbolicLink(File(root, "sub/link.txt").toPath(), secret.toPath())
        Files.createSymbolicLink(File(root, "link_dir").toPath(), outside.toPath())

        // Normal read/write via FD-relative channel succeeds
        WorkspaceSafeFiles.openChannelNoFollow(
            root = root,
            dirSegments = listOf("sub"),
            leaf = "ok.txt",
            openOptions = setOf(java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE),
        ).use { ch ->
            val buf = java.nio.ByteBuffer.allocate(16)
            val n = ch.read(buf)
            assertEquals("hello", String(buf.array(), 0, n))
            ch.write(java.nio.ByteBuffer.wrap("!".toByteArray()))
        }
        assertEquals("hello!", File(root, "sub/ok.txt").readText())

        // Leaf symlink rejected
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSafeFiles.openChannelNoFollow(
                root = root,
                dirSegments = listOf("sub"),
                leaf = "link.txt",
                openOptions = setOf(java.nio.file.StandardOpenOption.READ),
            )
        }

        // Ancestor symlink rejected
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSafeFiles.openChannelNoFollow(
                root = root,
                dirSegments = listOf("link_dir"),
                leaf = "secret.txt",
                openOptions = setOf(java.nio.file.StandardOpenOption.READ),
            )
        }
    }

    @Test
    fun copyInRootNoFollowRejectsDirectoryIntoSelfByFileKey() {
        val base = tempFolder.newFolder("copy_base")
        File(base, "dir/sub").mkdirs()
        File(base, "dir/sub/f.txt").writeText("data")

        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSafeFiles.copyInRootNoFollow(
                srcRoot = base,
                srcRelPath = "dir",
                dstRoot = base,
                dstParentRelPath = "dir/sub",
                requestedName = "dir",
                allocateUniqueName = true,
            )
        }
    }

    @Test
    fun createDirectoryInPinnedDirCreatesViaRenameatAndRejectsSymlink() {
        val base = tempFolder.newFolder("mkdir_base")
        val outside = tempFolder.newFolder("mkdir_outside")
        File(base, "parent").mkdirs()
        Files.createSymbolicLink(File(base, "parent/sym").toPath(), outside.toPath())

        WorkspaceSafeFiles.withPinnedDirAndRoot(base, listOf("parent"), createMissing = false) { rootSds, rootPath, parentSds, _, _ ->
            WorkspaceSafeFiles.createDirectoryInPinnedDir(rootSds, rootPath, parentSds, "child", failIfExists = true)
            assertThrows(IllegalArgumentException::class.java) {
                WorkspaceSafeFiles.createDirectoryInPinnedDir(rootSds, rootPath, parentSds, "sym", failIfExists = false)
            }
            assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
                WorkspaceSafeFiles.createDirectoryInPinnedDir(rootSds, rootPath, parentSds, "child", failIfExists = true)
            }
        }
        assertTrue(File(base, "parent/child").isDirectory)
        assertTrue(
            "Staging directories must not be left behind",
            base.listFiles().orEmpty().none { it.name.startsWith(WorkspaceSafeFiles.MKDIR_STAGE_PREFIX) },
        )
    }

    @Test
    fun rootfsOperationsPinFromBaseDirAndRejectSymlinkedWorkspaceOrAreaDirs() {
        val baseDir = tempFolder.newFolder("rootfs_pin_base")
        val outsideVictim = tempFolder.newFolder("rootfs_pin_victim")
        val victimFile = File(outsideVictim, "victim.txt").apply { writeText("intact") }
        File(outsideVictim, "etc").mkdirs()
        val victimEtc = File(outsideVictim, "etc/passwd").apply { writeText("root:x:0:0") }

        val manager = WorkspaceManager(baseDir = baseDir)

        // 1. Symlinked `files` area under workspace root
        File(baseDir, "ws1").mkdirs()
        Files.createSymbolicLink(File(baseDir, "ws1/files").toPath(), outsideVictim.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            manager.rootfsFileSize("ws1", "/workspace/victim.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            manager.exportRootfsFile("ws1", "/workspace/victim.txt", java.io.ByteArrayOutputStream())
        }
        assertThrows(IllegalArgumentException::class.java) {
            manager.writeRootfsTextNoFollow("ws1", "/workspace/victim.txt", "pwned", overwrite = true)
        }

        // 2. Symlinked `linux` area under workspace root
        File(baseDir, "ws2").mkdirs()
        Files.createSymbolicLink(File(baseDir, "ws2/linux").toPath(), outsideVictim.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            manager.rootfsFileSize("ws2", "/etc/passwd")
        }
        assertThrows(IllegalArgumentException::class.java) {
            manager.exportRootfsFile("ws2", "/etc/passwd", java.io.ByteArrayOutputStream())
        }
        assertThrows(IllegalArgumentException::class.java) {
            manager.writeRootfsTextNoFollow("ws2", "/etc/passwd", "pwned", overwrite = true)
        }

        // 3. Symlinked workspace root itself under baseDir
        Files.createSymbolicLink(File(baseDir, "ws3").toPath(), outsideVictim.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            manager.rootfsFileSize("ws3", "/workspace/victim.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            manager.writeRootfsTextNoFollow("ws3", "/workspace/victim.txt", "pwned", overwrite = true)
        }

        assertEquals("intact", victimFile.readText())
        assertEquals("root:x:0:0", victimEtc.readText())
    }

    @Test
    fun rootfsBindMountWithTrustedAnchorRejectsSymlinkedMountSubdir() {
        val baseDir = tempFolder.newFolder("bind_pin_base")
        val fakeExternalRoot = tempFolder.newFolder("fake_sdcard_root")
        val outsideVictim = tempFolder.newFolder("bind_pin_victim")
        val victimFile = File(outsideVictim, "note.txt").apply { writeText("keep-me") }

        val rikkaDocs = File(fakeExternalRoot, "Documents/RikkaHub").apply { mkdirs() }
        val symlinkedShared = File(rikkaDocs, "ws-1")
        Files.createSymbolicLink(symlinkedShared.toPath(), outsideVictim.toPath())

        val sharedBind = WorkspaceBindMount(
            source = symlinkedShared,
            target = "/shared",
            trustedAnchorRoot = fakeExternalRoot,
            anchorRelativePath = "Documents/RikkaHub/ws-1",
        )
        val manager = WorkspaceManager(baseDir = baseDir, sdcardDir = fakeExternalRoot)
        manager.ensureWorkspace("ws1")

        assertThrows(IllegalArgumentException::class.java) {
            manager.rootfsFileSize("ws1", "/shared/note.txt", extraBindMounts = listOf(sharedBind))
        }
        assertThrows(IllegalArgumentException::class.java) {
            manager.exportRootfsFile(
                "ws1",
                "/shared/note.txt",
                java.io.ByteArrayOutputStream(),
                extraBindMounts = listOf(sharedBind),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            manager.writeRootfsTextNoFollow(
                "ws1",
                "/shared/note.txt",
                "overwritten",
                overwrite = true,
                extraBindMounts = listOf(sharedBind),
            )
        }
        assertEquals("keep-me", victimFile.readText())
    }

    @Test
    fun writeRootfsTextNoFollowCreatesDirsPinnedFromBaseDirAndReturnsHandleMetadata() {
        val baseDir = tempFolder.newFolder("write_meta_base")
        val manager = WorkspaceManager(baseDir = baseDir)

        val entry = manager.writeRootfsTextNoFollow("wsfresh", "/workspace/deep/sub/hello.txt", "hello world")
        assertEquals("/workspace/deep/sub/hello.txt", entry.path)
        assertEquals("hello.txt", entry.name)
        assertFalse(entry.isDirectory)
        assertEquals(11L, entry.sizeBytes)
        assertTrue(entry.updatedAt > 0L)
        assertEquals("hello world", File(baseDir, "wsfresh/files/deep/sub/hello.txt").readText())
    }

    @Test
    fun trackedForegroundCommandIsImmediatelyReclaimedByKillAllForeground() {
        val baseDir = tempFolder.newFolder("fg_track_base")
        val manager = WorkspaceManager(baseDir = baseDir)
        manager.ensureWorkspace("wsfg")

        val tracked = manager.startTrackedForegroundCommand("wsfg", "sleep 30")
        try {
            assertTrue("Foreground command process should be alive after launch", tracked.process.isAlive)
            manager.killAllForeground("wsfg")
            assertTrue(
                "Foreground process must terminate promptly after killAllForeground",
                tracked.process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS),
            )
            assertFalse(tracked.process.isAlive)
        } finally {
            manager.finishTrackedForegroundCommand(tracked)
            if (tracked.process.isAlive) {
                tracked.process.destroyForcibly()
            }
        }
    }

    @Test
    fun nativeSafeFilesEncodesStandardUtf8ForSupplementaryPlaneAndFailsClosedOnEnosys() {
        val emojiName = "笔记_📝😀.txt"
        val utf8 = NativeSafeFiles.encodeUtf8PathBytes(emojiName)
        // Standard UTF-8 encodes 📝 (U+1F4DD) as F0 9F 93 9D and 😀 (U+1F600) as F0 9F 98 80
        // (4 bytes each, NOT 6-byte JNI Modified UTF-8 surrogate pairs).
        assertEquals(emojiName, String(utf8, Charsets.UTF_8))
        assertTrue(utf8.toList().windowed(4).any { it == listOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte()) })

        assertThrows(IllegalArgumentException::class.java) {
            NativeSafeFiles.encodeUtf8PathBytes("bad\u0000name.txt")
        }

        val root = tempFolder.newFolder("native_map_root")
        val enosys = assertThrows(java.io.IOException::class.java) {
            NativeSafeFiles.mapNativeMoveResult(
                rc = NativeSafeFiles.ERR_ENOSYS,
                root = root,
                dstDirSegments = listOf("ws-b", "files"),
                dstLeaf = emojiName,
                sourceDisplay = "src",
                targetDisplay = "dst",
            )
        }
        assertTrue(enosys.message!!.contains("RENAME_NOREPLACE"))

        val eexist = assertThrows(IllegalArgumentException::class.java) {
            NativeSafeFiles.mapNativeMoveResult(
                rc = NativeSafeFiles.ERR_EEXIST,
                root = root,
                dstDirSegments = listOf("ws-b", "files"),
                dstLeaf = emojiName,
                sourceDisplay = "src",
                targetDisplay = "dst",
            )
        }
        assertTrue(eexist.message!!.startsWith("Target already exists:"))

        assertThrows(java.io.IOException::class.java) {
            NativeSafeFiles.mapNativeMoveResult(
                rc = NativeSafeFiles.ERR_EXDEV,
                root = root,
                dstDirSegments = listOf("ws-b", "files"),
                dstLeaf = emojiName,
                sourceDisplay = "src",
                targetDisplay = "dst",
            )
        }
    }

    @Test
    fun moveNoFollowHandlesNonAsciiAndSupplementaryPlaneEmojiFilenames() {
        val baseDir = tempFolder.newFolder("emoji_move_base")
        val srcDir = File(baseDir, "ws-a/files/目录").apply { mkdirs() }
        val dstDir = File(baseDir, "ws-b/files/归档").apply { mkdirs() }
        val srcName = "笔记_📝😀.txt"
        val dstName = "结果_✅🎉.txt"
        File(srcDir, srcName).writeText("unicode-ok")

        WorkspaceSafeFiles.moveNoFollow(
            root = baseDir,
            sourceRelPath = "ws-a/files/目录/$srcName",
            targetRelPath = "ws-b/files/归档/$dstName",
            overwrite = false,
        )
        assertFalse(Files.exists(File(srcDir, srcName).toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("unicode-ok", File(dstDir, dstName).readText())
    }
}
