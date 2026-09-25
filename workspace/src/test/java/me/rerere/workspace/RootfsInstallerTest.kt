package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

class RootfsInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `extract skips OTHER entry data exactly once`() {
        // OTHER 条目 (如 GNU sparse) 带 size>0 数据区, 双重 skip 会让后续 header 错位
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("a.txt", '0', "hello".toByteArray())
            out.writeTarEntry("sparse.bin", 'S', ByteArray(700) { 1 })
            out.writeTarEntry("b.txt", '0', "world".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        assertEquals("hello", File(target, "a.txt").readText())
        assertEquals("world", File(target, "b.txt").readText())
        assertFalse(File(target, "sparse.bin").exists())
    }

    @Test
    fun `extract handles directories and zero size entries`() {
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("dir/", '5', ByteArray(0))
            out.writeTarEntry("dir/file.txt", '0', "content".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        assertEquals(true, File(target, "dir").isDirectory)
        assertEquals("content", File(target, "dir/file.txt").readText())
    }

    @Test
    fun `rootfs without bin slash sh is not usable`() {
        val linuxDir = tmp.newFolder("linux")
        assertFalse(hasUsableRootfsDir(linuxDir))
        File(linuxDir, "bin").mkdirs()
        assertFalse(hasUsableRootfsDir(linuxDir))
    }

    @Test
    fun `rootfs with bin slash sh file is usable`() {
        val linuxDir = tmp.newFolder("linux")
        File(linuxDir, "bin").mkdirs()
        File(linuxDir, "bin/sh").writeText("#!fake\n")
        assertTrue(hasUsableRootfsDir(linuxDir))
    }

    @Test
    fun `rootfs with bin slash sh symlink is usable`() {
        val linuxDir = tmp.newFolder("linux")
        File(linuxDir, "bin").mkdirs()
        File(linuxDir, "bin/bash").writeText("#!fake\n")
        java.nio.file.Files.createSymbolicLink(
            File(linuxDir, "bin/sh").toPath(),
            java.io.File("bash").toPath(),
        )
        assertTrue(hasUsableRootfsDir(linuxDir))
    }

    @Test
    fun `extract rejects oversized LONG_NAME meta entry`() {
        // 恶意归档可给 LONG_NAME/PAX 声明超大 size; readExactly 有 1MB 上限,
        // 必须在分配缓冲前拒绝而不是 OOM。
        val archive = tmp.newFile("evil.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("payload.txt", '0', "x".toByteArray())
            val header = ByteArray(TAR_BLOCK)
            "long.bin".toByteArray(Charsets.UTF_8).copyInto(header, 0)
            "0000644".toByteArray().copyInto(header, 100)
            (2L * 1024 * 1024).toOctalField().copyInto(header, 124)
            header[156] = 'L'.code.toByte()
            out.write(header)
        }

        val target = tmp.newFolder("out")
        assertThrows(IllegalArgumentException::class.java) {
            createInstaller().extractTar(archive, target) {}
        }
        // 已解压的正常条目保留, 但从巨型元数据条目起不再继续
        assertEquals("x", File(target, "payload.txt").readText())
    }

    private fun createInstaller() = RootfsInstaller(WorkspaceManager(tmp.newFolder()))

    private fun OutputStream.writeTarEntry(name: String, type: Char, data: ByteArray) {
        val header = ByteArray(TAR_BLOCK)
        name.toByteArray(Charsets.UTF_8).copyInto(header, 0)
        "0000755".toByteArray().copyInto(header, 100)
        data.size.toLong().toOctalField().copyInto(header, 124)
        header[156] = type.code.toByte()
        write(header)
        write(data)
        val padding = (TAR_BLOCK - data.size % TAR_BLOCK) % TAR_BLOCK
        write(ByteArray(padding))
    }

    private fun Long.toOctalField(): ByteArray =
        toString(8).padStart(11, '0').toByteArray(Charsets.UTF_8)

    companion object {
        private const val TAR_BLOCK = 512
    }
}
