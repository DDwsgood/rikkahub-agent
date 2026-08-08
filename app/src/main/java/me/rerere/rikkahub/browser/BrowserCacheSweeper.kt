package me.rerere.rikkahub.browser

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Best-effort cleanup of browser cache directories. Runs on every browser bind
 * (foreground / headless) so any leftovers from a prior session — including any
 * from a force-stop or process-kill — are gone before the new session starts.
 *
 * Strategy: keep the most-recent [keepLast] files per subdirectory, sorted by
 * `lastModified()` descending. Failures are swallowed with a single warn-log; missing
 * directories or unreadable files never bubble up to the caller.
 */
internal object BrowserCacheSweeper {

    private const val TAG = "BrowserCacheSweeper"
    private val CACHE_SUBDIRS = emptyList<String>()

    /**
     * Trim the browser-related cache subdirs to [keepLast] entries each (newest first).
     * Idempotent. Safe to call repeatedly. Errors logged at WARN, never thrown.
     */
    fun sweep(context: Context, keepLast: Int = 20) {
        val cacheDir = context.cacheDir ?: return
        sweep(cacheDir, keepLast)
    }

    /**
     * File-based overload. Used by [sweep] (production) and unit tests (which can pass a
     * temp dir directly without mocking a Context).
     *
     * Returns the number of files deleted across both subdirs — useful for tests and for
     * potential future logging at the call site (where Android's Log facility is
     * available without the JVM-unit-test mock-required overhead).
     */
    internal fun sweep(cacheDir: File, keepLast: Int = 20): Int {
        var totalDeleted = 0
        for (sub in CACHE_SUBDIRS) {
            runCatching {
                val dir = File(cacheDir, sub)
                if (!dir.exists() || !dir.isDirectory) return@runCatching
                val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                    ?: return@runCatching
                if (files.size <= keepLast) return@runCatching
                val excess = files.drop(keepLast)
                for (f in excess) {
                    if (runCatching { f.delete() }.getOrDefault(false)) totalDeleted++
                }
            }.onFailure {
                Log.w(TAG, "sweep failed for $sub", it)
            }
        }
        return totalDeleted
    }
}
