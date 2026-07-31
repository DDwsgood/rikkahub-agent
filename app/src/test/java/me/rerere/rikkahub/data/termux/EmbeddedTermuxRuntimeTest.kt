package me.rerere.rikkahub.data.termux

import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.preferences.TermuxDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EmbeddedTermuxRuntimeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `default working directory follows the application build variant`() {
        assertEquals(
            "/data/data/${BuildConfig.APPLICATION_ID}/files/termux/home",
            TermuxDefaults.DEFAULT_WORKING_DIR,
        )
        assertTrue(TermuxDefaults.DEFAULT_WORKING_DIR.contains(BuildConfig.APPLICATION_ID))
    }

    @Test
    fun `missing working directory falls back to persistent home`() {
        val home = temporaryFolder.newFolder("termux-root").resolve("home")
        val missingReleaseHome = temporaryFolder.root.resolve("other-package-home")

        val selected = resolveTermuxWorkingDirectory(missingReleaseHome.absolutePath, home)

        assertEquals(home.canonicalFile, selected.canonicalFile)
        assertTrue(home.isDirectory)
        assertFalse(missingReleaseHome.exists())
    }

    @Test
    fun `existing custom working directory is retained`() {
        val home = temporaryFolder.newFolder("home")
        val project = temporaryFolder.newFolder("project")

        assertEquals(
            project.canonicalFile,
            resolveTermuxWorkingDirectory(project.absolutePath, home).canonicalFile,
        )
    }

    @Test
    fun `linker selection uses the first executable candidate`() {
        val missing = temporaryFolder.root.resolve("missing-linker")
        val executable = temporaryFolder.newFile("linker64").apply { setExecutable(true) }

        assertEquals(
            executable.canonicalFile,
            findExecutableSystemLinker(listOf(missing.absolutePath, executable.absolutePath))?.canonicalFile,
        )
    }

    @Test
    fun `incomplete layout is repaired without reinstalling prefix`() {
        assertEquals(
            TermuxInstallAction.REPAIR,
            selectTermuxInstallAction(isInstalled = false, hasBootstrapCore = true),
        )
        assertEquals(
            TermuxInstallAction.NONE,
            selectTermuxInstallAction(isInstalled = true, hasBootstrapCore = true),
        )
        assertEquals(
            TermuxInstallAction.INSTALL,
            selectTermuxInstallAction(isInstalled = false, hasBootstrapCore = false),
        )
    }

    @Test
    fun `stale build variant environment is rejected`() {
        val expected = mapOf(
            "PREFIX" to "/data/data/excp.rikkahub.debug/files/termux/usr",
            "HOME" to "/data/data/excp.rikkahub.debug/files/termux/home",
            "LD_PRELOAD" to "/data/data/excp.rikkahub.debug/files/termux/usr/lib/linker.so",
            "TERMUX_APP__PACKAGE_NAME" to "excp.rikkahub.debug",
        )
        val stale = expected.toMutableMap().apply {
            this["HOME"] = "/data/data/excp.rikkahub/files/termux/home"
        }

        assertTrue(matchesRequiredTermuxEnvironment(expected, expected))
        assertFalse(matchesRequiredTermuxEnvironment(stale, expected))
    }

    @Test
    fun `interrupted prefix replacement restores installed packages`() {
        val root = temporaryFolder.newFolder("rollback")
        val prefix = root.resolve("usr").apply { mkdirs() }
        prefix.resolve("incomplete").writeText("new")
        val backup = root.resolve("usr-backup").apply { mkdirs() }
        backup.resolve("installed-package").writeText("preserve")
        val marker = prefix.resolve(".rikkahub-bootstrap-complete")

        recoverInterruptedTermuxPrefix(prefix, backup, marker)

        assertEquals("preserve", prefix.resolve("installed-package").readText())
        assertFalse(prefix.resolve("incomplete").exists())
        assertFalse(backup.exists())
    }
}
