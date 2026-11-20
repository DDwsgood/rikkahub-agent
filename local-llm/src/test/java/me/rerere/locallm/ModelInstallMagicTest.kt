package me.rerere.locallm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class ModelInstallMagicTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Returns at most 1 byte per read to test multi-chunk header sniffing (e.g. SAF Google Drive). */
    private class OneByteChunkStream(private val data: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            b[off] = data[pos++]
            return 1
        }
    }

    @Test
    fun accepts8ByteLiteRtMagicAcrossOneByteChunks() = runBlocking {
        // LITERTLM 8-byte ASCII magic + payload
        val bytes = "LITERTLM".toByteArray(Charsets.US_ASCII) + ByteArray(64) { 0x42 }
        val target = File(tempFolder.root, "model.litertlm")
        val events = ModelInstall.copyFromStream(
            input = OneByteChunkStream(bytes),
            target = target,
            totalBytes = bytes.size.toLong(),
            expectedExtension = "litertlm",
        ).toList()

        assertTrue("Expected Done for valid 8-byte LITERTLM header", events.last() is ModelInstall.Progress.Done)
        assertTrue(target.exists())
    }

    @Test
    fun accepts4ByteGgufMagicAcrossOneByteChunks() = runBlocking {
        val bytes = "GGUF".toByteArray(Charsets.US_ASCII) + ByteArray(32) { 0x03 }
        val target = File(tempFolder.root, "model.gguf")
        val events = ModelInstall.copyFromStream(
            input = OneByteChunkStream(bytes),
            target = target,
            totalBytes = bytes.size.toLong(),
            expectedExtension = "gguf",
        ).toList()

        assertTrue("Expected Done for valid GGUF header", events.last() is ModelInstall.Progress.Done)
        assertTrue(target.exists())
    }

    @Test
    fun rejectsTruncatedAndCorruptedHeadersAndCleansPartial() = runBlocking {
        val truncatedTarget = File(tempFolder.root, "short.litertlm")
        val truncatedEvents = ModelInstall.copyFromStream(
            input = ByteArrayInputStream("LITE".toByteArray(Charsets.US_ASCII)),
            target = truncatedTarget,
            totalBytes = 4L,
            expectedExtension = "litertlm",
        ).toList()

        assertTrue("Truncated 4-byte .litertlm must fail", truncatedEvents.last() is ModelInstall.Progress.Failed)
        assertFalse(truncatedTarget.exists())
        assertFalse(File(truncatedTarget.absolutePath + ".partial").exists())

        val htmlTarget = File(tempFolder.root, "bad.gguf")
        val htmlEvents = ModelInstall.copyFromStream(
            input = ByteArrayInputStream("<!DOCTYPE html><html>err</html>".toByteArray()),
            target = htmlTarget,
            totalBytes = null,
            expectedExtension = "gguf",
        ).toList()
        assertTrue("HTML error page disguised as .gguf must fail", htmlEvents.last() is ModelInstall.Progress.Failed)
        assertFalse(htmlTarget.exists())
    }
}
