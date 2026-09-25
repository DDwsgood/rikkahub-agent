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

/**
 * Path-validation regression tests: workspace root names must never resolve outside
 * the manager's base directory, and file ops must not be able to reach the workspace
 * root itself or follow symlinks out of it.
 */
class WorkspaceSecurityTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newManager(): Pair<WorkspaceManager, File> {
        val baseDir = tempFolder.newFolder("workspaces")
        return WorkspaceManager(baseDir) to baseDir
    }

    // --- item 1: ROOT_NAME_REGEX -------------------------------------------------

    @Test
    fun `dotdot root name is rejected by workspaceDir`() {
        val (manager) = newManager()
        assertThrows(IllegalArgumentException::class.java) {
            manager.workspaceDir("..")
        }
    }

    @Test
    fun `dot root name is rejected by workspaceDir`() {
        val (manager) = newManager()
        assertThrows(IllegalArgumentException::class.java) {
            manager.workspaceDir(".")
        }
    }

    @Test
    fun `leading-dot hidden root name is rejected`() {
        val (manager) = newManager()
        assertThrows(IllegalArgumentException::class.java) {
            manager.workspaceDir(".hidden")
        }
    }

    @Test
    fun `deleteWorkspace with dotdot cannot wipe baseDir`() {
        val (manager, baseDir) = newManager()
        File(baseDir, "sentinel").writeText("keep me")

        assertThrows(IllegalArgumentException::class.java) {
            manager.deleteWorkspace("..")
        }
        assertTrue(File(baseDir, "sentinel").isFile)
    }

    @Test
    fun `normal root names are still accepted`() {
        val (manager, baseDir) = newManager()
        val dir = manager.workspaceDir("abc-DEF_1.2")
        assertEquals(File(baseDir, "abc-DEF_1.2").canonicalFile, dir)
    }

    // --- item 2: delete/move root protection -------------------------------------

    @Test
    fun `delete refuses paths that normalize to the workspace root`() {
        val root = tempFolder.newFolder("ws")
        File(root, "keep.txt").writeText("x")
        val fs = WorkspaceFileSystem()

        for (path in listOf(".", "/", "/./", "./", "a/..")) {
            assertThrows("path=$path", IllegalArgumentException::class.java) {
                fs.delete(root, path, recursive = true)
            }
        }
        assertTrue(File(root, "keep.txt").isFile)
    }

    @Test
    fun `move refuses source paths that normalize to the workspace root`() {
        val root = tempFolder.newFolder("ws")
        File(root, "keep.txt").writeText("x")
        val fs = WorkspaceFileSystem()

        for (path in listOf(".", "/", "/./", "a/..")) {
            assertThrows("path=$path", IllegalArgumentException::class.java) {
                fs.move(root, path, "elsewhere", overwrite = true)
            }
        }
        assertTrue(File(root, "keep.txt").isFile)
    }

    @Test
    fun `move refuses target paths that normalize to the workspace root`() {
        val root = tempFolder.newFolder("ws")
        File(root, "a.txt").writeText("x")
        val fs = WorkspaceFileSystem()

        for (path in listOf(".", "/", "/./", "b/..")) {
            assertThrows("target=$path", IllegalArgumentException::class.java) {
                fs.move(root, "a.txt", path, overwrite = true)
            }
        }
        assertTrue(File(root, "a.txt").isFile)
    }

    @Test
    fun `read operations still accept the workspace root`() {
        val root = tempFolder.newFolder("ws")
        File(root, "file.txt").writeText("x")
        val fs = WorkspaceFileSystem()

        // 读操作对 "." 放行; 返回的条目应包含 file.txt
        assertTrue(fs.list(root, ".").any { it.name == "file.txt" })
        assertTrue(fs.list(root, "/").any { it.name == "file.txt" })
    }

    // --- item 3: symlink escape through glob/grep --------------------------------

    @Test
    fun `grep does not read through symlinks pointing outside the root`() {
        val root = tempFolder.newFolder("ws")
        val outside = tempFolder.newFile("secret.txt")
        outside.writeText("topsecret needle")

        // 符号链接指向 root 外的文件: grep 必须跳过它而不是跟随读出内容
        Files.createSymbolicLink(File(root, "link.txt").toPath(), outside.toPath())

        val fs = WorkspaceFileSystem()
        val matches = fs.grep(root, "needle")
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `glob does not list symlinks pointing outside the root`() {
        val root = tempFolder.newFolder("ws")
        val outsideDir = tempFolder.newFolder("outside-dir")
        File(outsideDir, "inner.txt").writeText("x")
        val outsideFile = tempFolder.newFile("secret.md")
        outsideFile.writeText("x")
        File(root, "real.md").writeText("real")

        Files.createSymbolicLink(File(root, "dir-link").toPath(), outsideDir.toPath())
        Files.createSymbolicLink(File(root, "file-link.md").toPath(), outsideFile.toPath())

        val fs = WorkspaceFileSystem()
        val entries = fs.glob(root, "**")
        val names = entries.map { it.name }.toSet()

        // 链接本身不应作为普通文件/目录出现, 链接目标里的 inner.txt 更不应出现
        assertFalse("dir-link" in names)
        assertFalse("file-link.md" in names)
        assertFalse("inner.txt" in names)
        assertTrue("real.md" in names)
    }
}
