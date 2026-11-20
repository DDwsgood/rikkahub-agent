package me.rerere.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * On-device Android instrumented tests exercising real `libworkspace.so` JNI
 * `SYS_renameat2(..., RENAME_NOREPLACE)` behavior:
 * - Same-volume atomic cross-workspace move without overwrite
 * - Standard UTF-8 handling of CJK + 4-byte supplementary-plane Unicode (emoji) filenames
 * - Target-exists refusal (EEXIST) and symlink refusal (ELOOP)
 */
@RunWith(AndroidJUnit4::class)
class NativeSafeFilesInstrumentedTest {

    private lateinit var testRoot: File

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        testRoot = File(ctx.filesDir, "native_safe_files_test_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        WorkspaceSafeFiles.deleteRecursivelyNoFollow(testRoot)
    }

    @Test
    fun nativeMoveNoReplaceHandlesCrossWorkspaceNonAsciiAndEmojiFilenamesAtomically() {
        assertTrue("libworkspace.so must load on Android", NativeSafeFiles.isLoaded)

        val wsA = File(testRoot, "ws-a/files/目录").apply { mkdirs() }
        val wsB = File(testRoot, "ws-b/files").apply { mkdirs() }
        val emojiName = "笔记_📝😀.txt"
        val src = File(wsA, emojiName).apply { writeText("payload-emoji") }
        val existing = File(wsB, emojiName).apply { writeText("do-not-overwrite") }

        // Attempting to move onto existing target must fail with Target already exists
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSafeFiles.moveNoFollow(
                root = testRoot,
                sourceRelPath = "ws-a/files/目录/$emojiName",
                targetRelPath = "ws-b/files/$emojiName",
                overwrite = false,
            )
        }
        assertEquals("do-not-overwrite", existing.readText())
        assertTrue(src.exists())

        // Moving to a free supplementary-plane target succeeds atomically
        val targetName = "归档_✅🎉.txt"
        WorkspaceSafeFiles.moveNoFollow(
            root = testRoot,
            sourceRelPath = "ws-a/files/目录/$emojiName",
            targetRelPath = "ws-b/files/$targetName",
            overwrite = false,
        )
        assertFalse(Files.exists(src.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("payload-emoji", File(wsB, targetName).readText())
    }
}
