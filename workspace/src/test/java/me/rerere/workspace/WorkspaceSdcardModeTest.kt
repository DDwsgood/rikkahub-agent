package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Workspace sdcard mode: enum parsing, bind computation on the shell paths, and
 * rootfs path resolution for /sdcard under each mode.
 */
class WorkspaceSdcardModeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ------------------------------------------------------------------
    // Enum / column serialization
    // ------------------------------------------------------------------

    @Test
    fun `mode parsing is case-insensitive and falls back to NONE`() {
        assertEquals(WorkspaceSdcardMode.NONE, WorkspaceSdcardMode.fromName(null))
        assertEquals(WorkspaceSdcardMode.NONE, WorkspaceSdcardMode.fromName(""))
        assertEquals(WorkspaceSdcardMode.NONE, WorkspaceSdcardMode.fromName("garbage"))
        assertEquals(WorkspaceSdcardMode.READ_ONLY, WorkspaceSdcardMode.fromName("READ_ONLY"))
        assertEquals(WorkspaceSdcardMode.READ_WRITE, WorkspaceSdcardMode.fromName("read_write"))
        assertEquals(WorkspaceSdcardMode.NONE, WorkspaceSdcardMode.fromName(" none "))
    }

    @Test
    fun `zone predicate matches the mount root and descendants only`() {
        assertTrue(WorkspaceSdcard.isWithin("/sdcard"))
        assertTrue(WorkspaceSdcard.isWithin("/sdcard/DCIM"))
        assertTrue(WorkspaceSdcard.isWithin("/sdcard/DCIM/a.jpg"))
        assertTrue(WorkspaceSdcard.isWithin("/sdcard/"))
        assertFalse(WorkspaceSdcard.isWithin("/sdcardx"))
        assertFalse(WorkspaceSdcard.isWithin("/workspace"))
        assertFalse(WorkspaceSdcard.isWithin("/etc/sdcard"))
    }

    // ------------------------------------------------------------------
    // Bind computation on the shell paths
    // ------------------------------------------------------------------

    /** Records the last WorkspaceShellContext instead of running anything. */
    private class RecordingRunner : WorkspaceShellRunner {
        var lastContext: WorkspaceShellContext? = null
        override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
            lastContext = context
            return WorkspaceCommandResult(exitCode = 0, stdout = "", stderr = "")
        }

        override fun start(context: WorkspaceShellContext): Process {
            lastContext = context
            return ProcessBuilder("true").start()
        }
    }

    private fun newManager(runner: WorkspaceShellRunner, sdcardDir: File?): WorkspaceManager {
        val manager = WorkspaceManager(
            baseDir = tempFolder.newFolder(),
            shellRunner = runner,
            bindMounts = listOf(WorkspaceBindMount(tempFolder.newFolder(), "/skills")),
            sdcardDir = sdcardDir,
        )
        manager.ensureWorkspace("ws")
        return manager
    }

    @Test
    fun `NONE mode mounts no sdcard bind`() {
        val runner = RecordingRunner()
        val manager = newManager(runner, tempFolder.newFolder("sdcard-src"))
        manager.executeCommand("ws", "true", sdcardMode = WorkspaceSdcardMode.NONE)
        val binds = runner.lastContext!!.bindMounts
        assertEquals(1, binds.size)
        assertEquals("/skills", binds.single().target)
    }

    @Test
    fun `READ_ONLY and READ_WRITE append the sdcard bind after the static binds`() {
        val sdcardDir = tempFolder.newFolder("sdcard-src")
        for (mode in listOf(WorkspaceSdcardMode.READ_ONLY, WorkspaceSdcardMode.READ_WRITE)) {
            val runner = RecordingRunner()
            val manager = newManager(runner, sdcardDir)
            manager.executeCommand("ws", "true", sdcardMode = mode)
            val binds = runner.lastContext!!.bindMounts
            assertEquals(2, binds.size)
            assertEquals("/skills", binds[0].target)
            assertEquals(WorkspaceSdcard.MOUNT_TARGET, binds[1].target)
            assertEquals(sdcardDir, binds[1].source)
        }
    }

    @Test
    fun `startBackground applies the same bind computation`() {
        val runner = RecordingRunner()
        val sdcardDir = tempFolder.newFolder("sdcard-src")
        val manager = newManager(runner, sdcardDir)
        manager.startBackground("ws", "true", sdcardMode = WorkspaceSdcardMode.READ_WRITE)
        val binds = runner.lastContext!!.bindMounts
        assertTrue(binds.any { it.target == WorkspaceSdcard.MOUNT_TARGET && it.source == sdcardDir })
    }

    @Test
    fun `missing sdcardDir degrades to no bind even in mounted modes`() {
        val runner = RecordingRunner()
        val manager = newManager(runner, sdcardDir = null)
        manager.executeCommand("ws", "true", sdcardMode = WorkspaceSdcardMode.READ_WRITE)
        assertEquals(1, runner.lastContext!!.bindMounts.size)
    }

    // ------------------------------------------------------------------
    // Rootfs path resolution
    // ------------------------------------------------------------------

    @Test
    fun `sdcard paths resolve to the host shared-storage dir only when mounted`() {
        val sdcardDir = tempFolder.newFolder("sdcard-src")
        val manager = newManager(RecordingRunner(), sdcardDir)

        // NONE: falls through to the rootfs tree (legacy behaviour preserved)
        val unmounted = manager.resolveRootfsPath("ws", "/sdcard/a.txt")
        assertEquals(manager.linuxDir("ws"), unmounted.rootDir)

        val mounted = manager.resolveRootfsPath("ws", "/sdcard/a.txt", WorkspaceSdcardMode.READ_ONLY)
        assertEquals(sdcardDir, mounted.rootDir)
        assertEquals("a.txt", mounted.relativePath)

        val mountRoot = manager.resolveRootfsPath("ws", "/sdcard", WorkspaceSdcardMode.READ_WRITE)
        assertEquals(sdcardDir, mountRoot.rootDir)
        assertEquals("", mountRoot.relativePath)
    }

    @Test
    fun `non-sdcard paths ignore the mode`() {
        val manager = newManager(RecordingRunner(), tempFolder.newFolder("sdcard-src"))
        val location = manager.resolveRootfsPath("ws", "/etc/hostname", WorkspaceSdcardMode.READ_WRITE)
        assertEquals(manager.linuxDir("ws"), location.rootDir)
        assertEquals("etc/hostname", location.relativePath)
    }

    @Test
    fun `mounted sdcard mode without sdcardDir fails loudly`() {
        val manager = newManager(RecordingRunner(), sdcardDir = null)
        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.resolveRootfsPath("ws", "/sdcard/a.txt", WorkspaceSdcardMode.READ_ONLY)
        }
        assertTrue(error.message!!.contains("sdcardDir"))
    }

    @Test
    fun `bind helper returns null only for NONE`() {
        val source = tempFolder.newFolder("sd")
        assertNull(WorkspaceSdcard.bindMount(source, WorkspaceSdcardMode.NONE))
        assertEquals(
            WorkspaceBindMount(source, "/sdcard"),
            WorkspaceSdcard.bindMount(source, WorkspaceSdcardMode.READ_ONLY),
        )
        assertEquals(
            WorkspaceBindMount(source, "/sdcard"),
            WorkspaceSdcard.bindMount(source, WorkspaceSdcardMode.READ_WRITE),
        )
    }
}