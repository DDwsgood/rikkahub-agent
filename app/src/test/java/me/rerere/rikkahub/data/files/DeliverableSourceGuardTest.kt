package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Security tests for [DeliverableSourceGuard]:
 * 1. Trusted-root FD-pinned resolution & `O_NOFOLLOW` reading (blocks symlinks & traversal).
 * 2. Strict SAF persisted-grant URI verification (rejects raw prefix spoofing and unverified tree URIs).
 */
class DeliverableSourceGuardTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun trustedRootOpenAllowsPlainFileAndBlocksSymlinkAndEscape() {
        val trustedRoot = tempFolder.newFolder("trusted_root")
        val outside = tempFolder.newFolder("outside")
        val secret = File(outside, "secret.txt").apply { writeText("do-not-leak") }

        File(trustedRoot, "sub").mkdirs()
        File(trustedRoot, "sub/ok.txt").writeText("safe-content")
        Files.createSymbolicLink(File(trustedRoot, "sub/link.txt").toPath(), secret.toPath())
        Files.createSymbolicLink(File(trustedRoot, "escape_dir").toPath(), outside.toPath())

        // Plain file resolves and opens cleanly
        val okResolved = DeliverableSourceGuard.resolveUnderTrustedRoot(
            trustedRoot = trustedRoot,
            rawPath = File(trustedRoot, "sub/ok.txt").absolutePath,
        )
        assertNotNull(okResolved)
        val (root, rel) = okResolved!!
        assertEquals("sub/ok.txt", rel)
        assertEquals("safe-content", DeliverableSourceGuard.openTrustedFileNoFollow(root, rel).bufferedReader().readText())

        // Path escaping trusted root is rejected at resolution
        assertNull(
            DeliverableSourceGuard.resolveUnderTrustedRoot(
                trustedRoot = trustedRoot,
                rawPath = "${trustedRoot.absolutePath}/../outside/secret.txt",
            )
        )

        // Leaf symlink inside trusted root is refused on open
        val symLeaf = DeliverableSourceGuard.resolveUnderTrustedRoot(
            trustedRoot = trustedRoot,
            rawPath = File(trustedRoot, "sub/link.txt").absolutePath,
        )!!
        assertThrows(IllegalArgumentException::class.java) {
            DeliverableSourceGuard.openTrustedFileNoFollow(symLeaf.first, symLeaf.second).close()
        }

        // Ancestor symlink inside trusted root is refused on open
        val symDir = DeliverableSourceGuard.resolveUnderTrustedRoot(
            trustedRoot = trustedRoot,
            rawPath = File(trustedRoot, "escape_dir/secret.txt").absolutePath,
        )!!
        assertThrows(IllegalArgumentException::class.java) {
            DeliverableSourceGuard.openTrustedFileNoFollow(symDir.first, symDir.second).close()
        }
    }

    @Test
    fun publicStorageAliasMapsOntoRealRootAndEnforcesNoFollow() {
        val fakeStorage = tempFolder.newFolder("emulated_0")
        val outside = tempFolder.newFolder("private_db")
        val dbFile = File(outside, "app.db").apply { writeText("sqlite-secret") }

        File(fakeStorage, "Download").mkdirs()
        File(fakeStorage, "Download/report.txt").writeText("ok")
        Files.createSymbolicLink(File(fakeStorage, "Download/steal.db").toPath(), dbFile.toPath())

        val resolved = DeliverableSourceGuard.resolvePublicStorageSource(
            rawPath = "/sdcard/Download/report.txt",
            primaryRootOverride = fakeStorage,
        )
        assertNotNull(resolved)
        assertEquals("Download/report.txt", resolved!!.second)
        assertEquals(
            "ok",
            DeliverableSourceGuard.openTrustedFileNoFollow(resolved.first, resolved.second).bufferedReader().readText(),
        )

        val symResolved = DeliverableSourceGuard.resolvePublicStorageSource(
            rawPath = "/sdcard/Download/steal.db",
            primaryRootOverride = fakeStorage,
        )!!
        assertThrows(IllegalArgumentException::class.java) {
            DeliverableSourceGuard.openTrustedFileNoFollow(symResolved.first, symResolved.second).close()
        }
    }

    @Test
    fun singleDocumentSafGrantRequiresExactMatchAndRejectsPrefixSpoofing() {
        val granted = "content://com.android.externalstorage.documents/document/primary%3ADocuments%2Fdir1"
        val exact = granted
        val spoofedSibling = "content://com.android.externalstorage.documents/document/primary%3ADocuments%2Fdir10%2Fsecret.txt"
        val spoofedChild = "content://com.android.externalstorage.documents/document/primary%3ADocuments%2Fdir1%2Fchild.txt"

        assertTrue(
            DeliverableSourceGuard.isSafUriCoveredByGrant(exact, granted) { _, _ -> false },
        )
        assertFalse(
            "Single-document grant must NEVER accept prefix-matching sibling dir10",
            DeliverableSourceGuard.isSafUriCoveredByGrant(spoofedSibling, granted) { _, _ -> true },
        )
        assertFalse(
            "Single-document grant must NEVER accept arbitrary child path",
            DeliverableSourceGuard.isSafUriCoveredByGrant(spoofedChild, granted) { _, _ -> true },
        )
    }

    @Test
    fun treeSafGrantRequiresMatchingTreeIdAndVerifiedIsChildDocument() {
        val treeGrant = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FProject"
        val childInTree = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FProject/document/primary%3ADocuments%2FProject%2Fnotes.txt"
        val forgedSiblingTree = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FProject2/document/primary%3ADocuments%2FProject2%2Fsecret.txt"
        val forgedDocInsideSameTreeHeader = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FProject/document/primary%3ADocuments%2FPrivate%2Fsecret.txt"
        val differentAuthority = "content://other.provider/tree/primary%3ADocuments%2FProject/document/primary%3ADocuments%2FProject%2Fnotes.txt"

        // Valid child confirmed by provider
        assertTrue(
            DeliverableSourceGuard.isSafUriCoveredByGrant(childInTree, treeGrant) { parent, target ->
                assertEquals(
                    "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FProject/document/primary%3ADocuments%2FProject",
                    parent,
                )
                target == childInTree
            },
        )
        // Sibling tree prefix spoof rejected before even reaching provider
        assertFalse(
            DeliverableSourceGuard.isSafUriCoveredByGrant(forgedSiblingTree, treeGrant) { _, _ -> true },
        )
        // Different authority rejected
        assertFalse(
            DeliverableSourceGuard.isSafUriCoveredByGrant(differentAuthority, treeGrant) { _, _ -> true },
        )
        // Forged document ID outside tree rejected when provider returns false
        assertFalse(
            DeliverableSourceGuard.isSafUriCoveredByGrant(forgedDocInsideSameTreeHeader, treeGrant) { _, _ -> false },
        )
        // Unverifiable provider (throws) must fail closed
        assertFalse(
            DeliverableSourceGuard.isSafUriCoveredByGrant(childInTree, treeGrant) { _, _ ->
                throw SecurityException("provider cannot verify")
            },
        )
        // Bare tree root URI itself cannot be opened as a document
        assertFalse(
            DeliverableSourceGuard.isSafUriCoveredByGrant(treeGrant, treeGrant) { _, _ -> true },
        )
    }
}
