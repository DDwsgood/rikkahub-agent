package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * P1-1 / P1-2 对抗性审查回归测试:
 *
 *  - [WorkspaceManager.startManagedProcess] 的"检查 rootfs → 启动 → 注册"与
 *    [WorkspaceManager.deleteWorkspace] 的"移除注册表 → 关闭"在同一把生命周期锁内,
 *    并发删除不会留下永远不被关闭的孤儿托管进程。
 *  - [ManagedWorkspaceProcess.close] 幂等; destroy 宽限期后仍存活时走进程树强杀;
 *    显式关闭三个流, onClosed 只触发一次。
 */
class ManagedWorkspaceLifecycleTest {

    // ---- P1-2: close() ----

    @Test
    fun `close is idempotent and closes streams and fires onClosed once`() {
        val process = FakeProcess()
        val input = TrackingInputStream()
        val output = TrackingOutputStream()
        val error = TrackingInputStream()
        var onClosedCount = 0
        val managed = ManagedWorkspaceProcess(
            inputStream = input,
            outputStream = output,
            errorStream = error,
            process = process,
            onClosed = { onClosedCount++ },
        )

        managed.close()
        managed.close()

        assertTrue(managed.isClosed)
        assertEquals(1, process.destroyCount)
        assertEquals(0, process.destroyForciblyCount)
        assertEquals(1, onClosedCount)
        assertEquals(1, input.closeCount)
        assertEquals(1, output.closeCount)
        assertEquals(1, error.closeCount)
        assertFalse(process.isAlive)
    }

    @Test
    fun `close forcibly kills the tree when the child survives destroy`() {
        // 模拟 proot 孙进程仍存活: destroy (SIGTERM) 后进程不退出, close 必须走进程树强杀
        val process = FakeProcess(survivesDestroy = true)
        val managed = ManagedWorkspaceProcess(
            inputStream = TrackingInputStream(),
            outputStream = TrackingOutputStream(),
            errorStream = TrackingInputStream(),
            process = process,
        )

        managed.close()

        assertEquals(1, process.destroyCount)
        assertEquals(1, process.destroyForciblyCount)
        assertFalse(process.isAlive)
    }

    // ---- P1-1: startManagedProcess 与 deleteWorkspace 互斥 ----

    @Test
    fun `deleteWorkspace cannot orphan a process started concurrently`() {
        val baseDir = Files.createTempDirectory("ws-race").toFile()
        val shell = BlockingShellRunner()
        val manager = WorkspaceManager(baseDir, shellRunner = shell)
        val root = "race-root"
        manager.ensureWorkspace(root)
        // 伪造 rootfs 标记 (hasRootfs 检查 linux/bin/sh 是否存在)
        Files.createDirectories(manager.linuxDir(root).toPath().resolve("bin"))
        manager.linuxDir(root).resolve("bin/sh").writeText("#!/bin/sh\n")

        val returned = AtomicReference<ManagedWorkspaceProcess?>()
        val startThread = Thread {
            returned.set(manager.startManagedProcess(root, "mcp-server"))
        }
        startThread.start()
        assertTrue("startStructured should be entered", shell.awaitStart(5))

        val deleteDone = AtomicBoolean(false)
        val deleteThread = Thread {
            manager.deleteWorkspace(root)
            deleteDone.set(true)
        }
        deleteThread.start()
        // 给删除线程时间抵达锁; 若 startManagedProcess 未持有同一把生命周期锁, 删除会
        // 在启动线程仍阻塞于 startStructured 时完成 —— 正是审查报告的竞态窗口
        Thread.sleep(300)
        assertFalse(
            "deleteWorkspace must wait while startManagedProcess holds the lifecycle lock",
            deleteDone.get(),
        )

        shell.releaseStart()
        startThread.join(5_000)
        deleteThread.join(5_000)

        assertFalse("startManagedProcess should have returned", startThread.isAlive)
        assertTrue(deleteDone.get())
        val managed = returned.get()
        assertNotNull("startManagedProcess must return the managed process", managed)
        assertTrue(
            "process started concurrently with deleteWorkspace must be closed (no orphan)",
            managed!!.isClosed,
        )
    }

    // ---- helpers ----

    /** startStructured 受 latch 控制, 用于制造"启动中"窗口。 */
    private class BlockingShellRunner : WorkspaceShellRunner {
        private val started = CountDownLatch(1)
        private val release = CountDownLatch(1)

        fun awaitStart(timeoutSeconds: Long): Boolean = started.await(timeoutSeconds, TimeUnit.SECONDS)
        fun releaseStart() = release.countDown()

        override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult = error("not used here")
        override fun start(context: WorkspaceShellContext): Process = error("not used here")

        override fun startStructured(
            context: WorkspaceShellContext,
            args: List<String>,
            extraEnv: Map<String, String>,
        ): Process {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            return FakeProcess()
        }
    }

    private class FakeProcess(
        private val survivesDestroy: Boolean = false,
    ) : Process() {
        var destroyCount = 0
            private set
        var destroyForciblyCount = 0
            private set

        @Volatile
        private var alive = true

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            alive = false
            return 0
        }

        // 测试中不真实等待: 立即返回当前存活状态 (由 survivesDestroy 决定 destroy 后是否存活)
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !isAlive()

        override fun exitValue(): Int = 0

        override fun destroy() {
            destroyCount++
            if (!survivesDestroy) alive = false
        }

        override fun destroyForcibly(): Process {
            destroyForciblyCount++
            alive = false
            return this
        }

        override fun isAlive(): Boolean = alive
    }

    private class TrackingInputStream : ByteArrayInputStream(ByteArray(0)) {
        var closeCount = 0
            private set

        override fun close() {
            closeCount++
            super.close()
        }
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closeCount = 0
            private set

        override fun close() {
            closeCount++
            super.close()
        }
    }
}
