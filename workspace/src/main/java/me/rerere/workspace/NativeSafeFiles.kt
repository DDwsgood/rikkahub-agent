package me.rerere.workspace

import androidx.annotation.Keep
import java.io.File
import java.io.IOException

/**
 * JNI bridge into `libworkspace.so` for kernel-level FD-relative `renameat2(RENAME_NOREPLACE)`.
 *
 * - Passes standard UTF-8 `ByteArray`s across JNI (never JNI Modified UTF-8 `jstring`) so
 *   non-ASCII and 4-byte supplementary-plane Unicode filenames (e.g. emoji) match Linux
 *   filesystem bytes identically.
 * - Strictly fails closed (`-ENOSYS` -> [IOException]) if the kernel or filesystem does not
 *   support atomic `RENAME_NOREPLACE`, never falling back to racy `linkat+unlinkat` or
 *   copy+delete.
 * - On Android (`Dalvik` / `ART`), `libworkspace.so` is bundled with the APK and non-overwrite
 *   moves fail closed if native loading fails.
 */
@Keep
internal object NativeSafeFiles {

    internal const val ERR_ENOENT = -2
    internal const val ERR_EEXIST = -17
    internal const val ERR_EXDEV = -18
    internal const val ERR_ENOTDIR = -20
    internal const val ERR_EINVAL = -22
    internal const val ERR_ENOSYS = -38
    internal const val ERR_ELOOP = -40

    val isLoaded: Boolean = runCatching {
        System.loadLibrary("workspace")
        true
    }.getOrDefault(false)

    fun isAndroidRuntime(): Boolean {
        val vm = System.getProperty("java.vm.name").orEmpty()
        return vm.contains("Dalvik", ignoreCase = true) || vm.contains("ART", ignoreCase = true)
    }

    internal fun encodeUtf8PathBytes(value: String): ByteArray {
        require(!value.contains('\u0000')) { "Path contains invalid NUL character" }
        return value.toByteArray(Charsets.UTF_8)
    }

    @Keep
    @JvmStatic
    private external fun nativeMoveNoReplace(
        rootPathUtf8: ByteArray,
        srcDirSegsUtf8: Array<ByteArray>,
        srcLeafUtf8: ByteArray,
        dstDirSegsUtf8: Array<ByteArray>,
        dstLeafUtf8: ByteArray,
        createMissingDstDirs: Boolean,
    ): Int

    fun moveNoReplace(
        root: File,
        srcDirSegments: List<String>,
        srcLeaf: String,
        dstDirSegments: List<String>,
        dstLeaf: String,
        createMissingDstDirs: Boolean,
        sourceDisplay: String,
        targetDisplay: String,
    ): File {
        val rc = nativeMoveNoReplace(
            rootPathUtf8 = encodeUtf8PathBytes(root.path),
            srcDirSegsUtf8 = srcDirSegments.map(::encodeUtf8PathBytes).toTypedArray(),
            srcLeafUtf8 = encodeUtf8PathBytes(srcLeaf),
            dstDirSegsUtf8 = dstDirSegments.map(::encodeUtf8PathBytes).toTypedArray(),
            dstLeafUtf8 = encodeUtf8PathBytes(dstLeaf),
            createMissingDstDirs = createMissingDstDirs,
        )
        return mapNativeMoveResult(
            rc = rc,
            root = root,
            dstDirSegments = dstDirSegments,
            dstLeaf = dstLeaf,
            sourceDisplay = sourceDisplay,
            targetDisplay = targetDisplay,
        )
    }

    internal fun mapNativeMoveResult(
        rc: Int,
        root: File,
        dstDirSegments: List<String>,
        dstLeaf: String,
        sourceDisplay: String,
        targetDisplay: String,
    ): File {
        if (rc == 0) {
            var cur = root.toPath()
            for (seg in dstDirSegments) {
                cur = cur.resolve(seg)
            }
            return cur.resolve(dstLeaf).toFile()
        }
        when (rc) {
            ERR_EEXIST -> throw IllegalArgumentException("Target already exists: $targetDisplay")
            ERR_ENOENT -> throw IllegalArgumentException("Source does not exist: $sourceDisplay")
            ERR_ELOOP -> throw IllegalArgumentException(
                "Refusing to traverse or move symbolic link: $sourceDisplay -> $targetDisplay"
            )
            ERR_ENOTDIR -> throw IllegalArgumentException("Ancestor is not a directory: $sourceDisplay -> $targetDisplay")
            ERR_EINVAL -> throw IllegalArgumentException("Cannot move a directory into itself")
            ERR_EXDEV -> throw IOException("Cannot atomically move across filesystems: $sourceDisplay -> $targetDisplay")
            ERR_ENOSYS -> throw IOException(
                "Atomic non-overwrite move (RENAME_NOREPLACE) is not supported by this filesystem"
            )
            else -> throw IOException("Atomic non-overwrite move failed (errno=${-rc}): $sourceDisplay -> $targetDisplay")
        }
    }
}
