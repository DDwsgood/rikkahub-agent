package me.rerere.workspace

/**
 * Per-workspace shared-storage (sdcard) mount mode.
 *
 * The proot bind (`-b /storage/emulated/0:/sdcard`) is a *path translation*, not a
 * kernel security boundary: the vendored proot has no read-only bind support, so
 * "READ_ONLY" is enforced by layers around the shell (file-tool API checks, the
 * command guard in the app module, and the tool description/prompt), never by the
 * kernel. Treat [READ_ONLY] as "the agent should not write", not "the agent cannot
 * write".
 */
enum class WorkspaceSdcardMode {
    /** No /sdcard bind at all — shared storage is invisible inside the rootfs. */
    NONE,

    /** /sdcard is mounted for reads only; write attempts are blocked by the app-side guard. */
    READ_ONLY,

    /** /sdcard is mounted read-write (as the app uid, subject to the app's storage grant). */
    READ_WRITE;

    companion object {
        /** Tolerant parse used by the Room column: unknown/null falls back to [NONE]. */
        fun fromName(raw: String?): WorkspaceSdcardMode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: NONE
    }
}

/** Rootfs-internal mount point shared by the bind and every layer that reasons about it. */
object WorkspaceSdcard {
    const val MOUNT_TARGET = "/sdcard"

    /**
     * The bind added on top of the static bind-mount table when [mode] mounts shared
     * storage. [source] is the host-side shared-storage root (passed in from the app
     * layer; typically `/storage/emulated/0`).
     */
    fun bindMount(source: java.io.File, mode: WorkspaceSdcardMode): WorkspaceBindMount? =
        when (mode) {
            WorkspaceSdcardMode.NONE -> null
            else -> WorkspaceBindMount(source = source, target = MOUNT_TARGET)
        }

    /** Whether a rootfs-absolute [path] (already `..`-reduced) lives under the mount. */
    fun isWithin(path: String): Boolean {
        val trimmed = path.trimEnd('/')
        return trimmed == MOUNT_TARGET || trimmed.startsWith("$MOUNT_TARGET/")
    }
}