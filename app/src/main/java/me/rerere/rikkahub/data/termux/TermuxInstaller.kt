package me.rerere.rikkahub.data.termux

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.preferences.TermuxPreferences
import me.rerere.rikkahub.data.preferences.TermuxRuntime
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

internal enum class TermuxInstallAction {
    NONE,
    REPAIR,
    INSTALL,
}

internal fun selectTermuxInstallAction(isInstalled: Boolean, hasBootstrapCore: Boolean): TermuxInstallAction =
    when {
        isInstalled -> TermuxInstallAction.NONE
        hasBootstrapCore -> TermuxInstallAction.REPAIR
        else -> TermuxInstallAction.INSTALL
    }

internal fun recoverInterruptedTermuxPrefix(prefix: File, backup: File, marker: File) {
    if (!backup.exists()) return
    if (marker.isFile) {
        backup.deleteRecursively()
        return
    }
    prefix.deleteRecursively()
    if (!backup.renameTo(prefix)) {
        throw IOException("Failed to recover previous Termux PREFIX after interrupted install")
    }
}

/**
 * 从 Termux GitHub Release 下载 bootstrap 并解压安装。
 * 参考 Termux 的 TermuxInstaller.java 流程。
 *
 * 安装流程：
 * 1. 检测设备 ABI -> 确定下载哪个 bootstrap zip
 * 2. 检查磁盘空间（至少 500MB）
 * 3. 下载 bootstrap zip
 * 4. SHA-256 校验
 * 5. 解压到 staging 目录
 * 6. 处理 SYMLINKS.txt（创建符号链接）
 * 7. 设置 bin/、libexec/、lib/apt/ 下文件为可执行（chmod 0700）
 * 8. 重命名 staging -> $PREFIX
 * 9. 写入 etc/termux/termux.env
 * 10. 创建 $HOME 目录
 * 11. 创建 $HOME/storage/ symlinks -> /sdcard 各目录
 */
class TermuxInstaller(
    private val context: Context,
    private val environment: TermuxEnvironment,
    private val preferences: TermuxPreferences,
) {
    private val installMutex = Mutex()

    /**
     * Return an existing healthy installation, repair an incomplete runtime layout without
     * touching installed packages, or perform a fresh bootstrap install. Concurrent startup
     * and tool calls share this lock so commands never observe a half-installed prefix.
     */
    suspend fun ensureInstalled(): Result<Unit> {
        val result = installMutex.withLock {
            recoverInterruptedPrefixSwap()
            when (selectTermuxInstallAction(environment.isInstalled(), environment.hasBootstrapCore())) {
                TermuxInstallAction.NONE -> Result.success(Unit)
                TermuxInstallAction.REPAIR -> repairExistingInstallation()
                TermuxInstallAction.INSTALL -> installLocked()
            }
        }
        val ready = result.isSuccess && environment.isInstalled()
        TermuxRuntime.embeddedTermuxInstalled = ready
        preferences.setEmbeddedTermuxInstalled(ready)
        return result
    }

    /**
     * 完整的安装流程。在 IO dispatcher 上执行。
     *
     * @return Result<Unit> 成功返回 Result.success(Unit)，失败返回 Result.failure
     */
    suspend fun install(): Result<Unit> = ensureInstalled()

    private suspend fun installLocked(): Result<Unit> = withContext(Dispatchers.IO) {
        var activatedFreshPrefix = false
        runCatching {
            val arch = detectArch()
            Log.i(TAG, "Installing bootstrap for arch=$arch")

            val stagingDir = environment.stagingDir
            val prefixDir = environment.prefix

            // 1. 清理可能残留的 staging 目录，并先创建持久化 HOME。HOME 位于 PREFIX
            // 外部，重新安装 bootstrap 时不会删除用户脚本、dotfiles 或项目。
            cleanupOnFailure()
            stagingDir.mkdirs()
            ensureDirectory(environment.homeDir, "Termux home")
            ensureDirectory(File(environment.homeDir, ".termux"), "Termux config directory")
            runCatching { Os.chmod(environment.homeDir.absolutePath, 0b111_000_000) }

            // 2. 检查磁盘空间
            val availableBytes = getAvailableSpace(context.filesDir)
            if (availableBytes < MIN_DISK_SPACE_BYTES) {
                throw IOException(
                    "Insufficient disk space: ${availableBytes / MB}MB available, " +
                        "${MIN_DISK_SPACE_BYTES / MB}MB required"
                )
            }

            // 3. Copy a bootstrap bundled by the build pipeline, or download one when
            // the APK does not contain an asset.
            val expectedSha256 = expectedSha256(arch)
            val zipFile = File(context.cacheDir, "bootstrap-$arch.zip")
            val assetPath = "termux/bootstrap-$arch.zip"
            val bundledBootstrap = runCatching { context.assets.open(assetPath) }.getOrNull()
            val bootstrapSource = if (bundledBootstrap != null) {
                Log.i(TAG, "Installing bundled bootstrap asset $assetPath")
                zipFile.parentFile?.mkdirs()
                bundledBootstrap.use { input ->
                    zipFile.outputStream().use(input::copyTo)
                }
                "asset://$assetPath"
            } else {
                val downloadUrl = buildBootstrapUrl(arch)
                Log.i(TAG, "Downloading bootstrap from $downloadUrl")
                downloadWithRetry(downloadUrl, zipFile, maxRetries = 3)
                downloadUrl
            }

            // 4. SHA-256 校验
            if (!verifySha256(zipFile, expectedSha256)) {
                zipFile.delete()
                throw IOException("SHA-256 verification failed for $bootstrapSource")
            }
            Log.i(TAG, "SHA-256 verification passed")

            // 5-7. 解压 + 符号链接 + 权限
            extractBootstrap(zipFile, stagingDir)
            ensureDirectory(File(stagingDir, "tmp"), "Termux temporary directory")

            // 8. 重命名 staging -> prefix
            val backupDir = environment.prefixBackupDir
            if (backupDir.exists()) {
                backupDir.deleteRecursively()
            }
            if (prefixDir.exists() && !prefixDir.renameTo(backupDir)) {
                throw IOException("Failed to preserve existing PREFIX before replacement")
            }
            prefixDir.parentFile?.mkdirs()
            if (!stagingDir.renameTo(prefixDir)) {
                if (backupDir.exists()) backupDir.renameTo(prefixDir)
                throw IOException("Failed to move staging to prefix: ${stagingDir.absolutePath}")
            }
            activatedFreshPrefix = true
            Log.i(TAG, "Bootstrap extracted to ${prefixDir.absolutePath}")

            // 9. 写入 termux.env
            writeEnvFile()

            // 11. 创建 storage symlinks
            setupStorageSymlinks()

            configureAndVerifyBootstrap()

            if (!environment.isInstalled()) {
                throw IOException("Bootstrap installation completed without a ready runtime layout")
            }

            // 清理下载的 zip
            zipFile.delete()

            Log.i(TAG, "Bootstrap installation complete")
            backupDir.deleteRecursively()
            Unit
        }.onFailure {
            Log.e(TAG, "Bootstrap installation failed", it)
            cleanupOnFailure()
            if (activatedFreshPrefix && !environment.installationMarker.isFile) {
                runCatching {
                    environment.prefix.deleteRecursively()
                    if (environment.prefixBackupDir.exists() &&
                        !environment.prefixBackupDir.renameTo(environment.prefix)
                    ) {
                        throw IOException("Failed to restore previous PREFIX")
                    }
                }
                    .onFailure { cleanupError ->
                        Log.w(TAG, "Failed to roll back incomplete fresh PREFIX", cleanupError)
                    }
            }
        }
    }

    /** Repair directories/config around an intact PREFIX without replacing installed packages. */
    private suspend fun repairExistingInstallation(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureDirectory(environment.homeDir, "Termux home")
            ensureDirectory(File(environment.homeDir, ".termux"), "Termux config directory")
            ensureDirectory(environment.tmpDir, "Termux temporary directory")
            runCatching { Os.chmod(environment.homeDir.absolutePath, 0b111_000_000) }
            runCatching { Os.chmod(environment.tmpDir.absolutePath, 0b111_000_000) }
            // This file is app-managed. Rewriting it migrates stale release/debug paths and
            // old termux-exec variants while leaving PREFIX packages and HOME untouched.
            writeEnvFile()
            if (!File(environment.homeDir, "storage").isDirectory) {
                setupStorageSymlinks()
            }
            if (!environment.installationMarker.isFile) {
                configureAndVerifyBootstrap()
            }
            if (!environment.isInstalled()) {
                throw IOException("Embedded Termux repair did not produce a ready runtime layout")
            }
            Log.i(TAG, "Repaired embedded Termux runtime without replacing PREFIX or HOME")
            Unit
        }.onFailure {
            Log.e(TAG, "Embedded Termux runtime repair failed", it)
        }
    }

    private fun ensureDirectory(directory: File, label: String) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Failed to create $label: ${directory.absolutePath}")
        }
    }

    /** Recover an app/process death between PREFIX backup and verified activation. */
    private fun recoverInterruptedPrefixSwap() {
        recoverInterruptedTermuxPrefix(
            prefix = environment.prefix,
            backup = environment.prefixBackupDir,
            marker = environment.installationMarker,
        )
    }

    /**
     * Run the bootstrap's mandatory package post-install stage once, then prove that the shell,
     * termux-exec child launching, and dpkg database are usable before declaring readiness.
     */
    private fun configureAndVerifyBootstrap() {
        if (!environment.secondStageScript.isFile) {
            throw IOException("Missing Termux bootstrap second-stage script")
        }
        runBootstrapProcess(
            arguments = listOf(environment.bashPath.absolutePath, environment.secondStageScript.absolutePath),
            label = "bootstrap second stage",
            timeoutSeconds = BOOTSTRAP_CONFIG_TIMEOUT_SECONDS,
        )
        val smokeOutput = runBootstrapProcess(
            arguments = listOf(
                environment.bashPath.absolutePath,
                "--noprofile",
                "--norc",
                "-c",
                "command -v pkg >/dev/null && command -v apt >/dev/null && " +
                    "audit=\$(dpkg --audit) && test -z \"\$audit\" || " +
                    "{ printf '%s\\n' \"\$audit\"; exit 1; }; " +
                    "printf 'rikkahub-termux-ok'",
            ),
            label = "runtime smoke test",
            timeoutSeconds = RUNTIME_SMOKE_TIMEOUT_SECONDS,
        )
        if (!smokeOutput.contains("rikkahub-termux-ok")) {
            throw IOException("Termux runtime smoke test did not produce its success marker")
        }
        environment.installationMarker.writeText("ready\n")
    }

    private fun runBootstrapProcess(
        arguments: List<String>,
        label: String,
        timeoutSeconds: Long,
    ): String {
        val launcher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val linker = findExecutableSystemLinker(EmbeddedTermuxRunner.SYSTEM_LINKER_64_CANDIDATES)
                ?: throw IOException(
                    "No executable 64-bit Android linker found at " +
                        EmbeddedTermuxRunner.SYSTEM_LINKER_64_CANDIDATES.joinToString()
                )
            listOf(linker.absolutePath) + arguments
        } else {
            arguments
        }
        val outputFile = File(context.cacheDir, "termux-${label.replace(' ', '-')}.log")
        val process = ProcessBuilder(launcher).apply {
            directory(resolveTermuxWorkingDirectory(null, environment.homeDir))
            environment().putAll(environment.buildProcessEnv())
            redirectErrorStream(true)
            redirectOutput(outputFile)
        }.start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            terminateProcessTree(process, PROCESS_TREE_SHUTDOWN_SECONDS)
            throw IOException("Termux $label timed out after ${timeoutSeconds}s")
        }
        val output = runCatching { outputFile.readText().takeLast(MAX_BOOTSTRAP_LOG_CHARS) }
            .getOrDefault("")
        outputFile.delete()
        if (process.exitValue() != 0) {
            throw IOException(
                "Termux $label failed with exit ${process.exitValue()}: ${output.ifBlank { "no output" }}"
            )
        }
        return output
    }

    /**
     * 检测设备 ABI，返回对应的 bootstrap 架构标识。
     * 只支持 arm64-v8a 和 x86_64。
     */
    fun detectArch(): String {
        val abis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS
        } else {
            @Suppress("DEPRECATION")
            arrayOf(Build.CPU_ABI)
        }
        return when {
            abis.any { it.equals("arm64-v8a", ignoreCase = true) } -> "aarch64"
            abis.any { it.equals("x86_64", ignoreCase = true) } -> "x86_64"
            else -> throw IOException("Unsupported ABI: ${abis.joinToString()}")
        }
    }

    /**
     * 构建 bootstrap 下载 URL。
     */
    fun buildBootstrapUrl(arch: String): String {
        val template = BuildConfig.TERMUX_BOOTSTRAP_URL_TEMPLATE.trim()
        if (template.isEmpty()) {
            throw IOException(
                "Embedded Termux bootstrap is not configured. Official com.termux " +
                    "bootstraps are not relocatable; provide a bootstrap compiled for " +
                    "${context.packageName} and ${environment.prefix.absolutePath}."
            )
        }
        return template
            .replace("{package}", context.packageName)
            .replace("{arch}", arch)
    }

    private fun expectedSha256(arch: String): String {
        val value = when (arch) {
            "aarch64" -> BuildConfig.TERMUX_BOOTSTRAP_AARCH64_SHA256
            "x86_64" -> BuildConfig.TERMUX_BOOTSTRAP_X86_64_SHA256
            else -> ""
        }.trim()
        if (!value.matches(Regex("[0-9a-fA-F]{64}"))) {
            throw IOException("Missing or invalid SHA-256 for embedded Termux arch=$arch")
        }
        return value
    }

    /**
     * 带重试的下载。每次失败后等待递增的延迟再重试。
     */
    fun downloadWithRetry(url: String, file: File, maxRetries: Int = 3): File {
        var lastError: Throwable? = null
        for (attempt in 1..maxRetries) {
            try {
                download(url, file)
                return file
            } catch (e: Throwable) {
                lastError = e
                Log.w(TAG, "Download attempt $attempt/$maxRetries failed: ${e.message}")
                if (attempt < maxRetries) {
                    val delayMs = RETRY_BASE_DELAY_MS * attempt
                    Thread.sleep(delayMs)
                }
            }
        }
        throw IOException("Download failed after $maxRetries attempts: ${lastError?.message}", lastError)
    }

    /**
     * 单次下载。跟随重定向，支持大文件流式写入。
     */
    private fun download(url: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "RikkaHub/${context.packageName}")
        }
        try {
            val code = connection.responseCode
            require(code in 200..299) { "HTTP $code downloading $url" }
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedException("Download cancelled")
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * SHA-256 校验。
     */
    fun verifySha256(file: File, expected: String): Boolean {
        val actual = sha256(file)
        val match = actual.equals(expected, ignoreCase = true)
        if (!match) {
            Log.e(TAG, "SHA-256 mismatch:\n  expected: $expected\n  actual:   $actual")
        }
        return match
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 解压 bootstrap zip 到 staging 目录，处理 SYMLINKS.txt，
     * 并设置可执行权限。
     *
     * SYMLINKS.txt 格式：每行 `target←linkpath`，其中 target 是符号链接指向的路径，
     * linkpath 是相对于 $PREFIX 的链接路径。
     */
    fun extractBootstrap(zipFile: File, stagingDir: File) {
        stagingDir.mkdirs()
        val symlinks = mutableListOf<Pair<String, File>>()
        val buffer = ByteArray(BUFFER_SIZE)

        BufferedInputStream(zipFile.inputStream()).use { bis ->
            ZipInputStream(bis).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (Thread.currentThread().isInterrupted) {
                        throw InterruptedException("Extraction cancelled")
                    }
                    val entryName = entry.name
                    if (entryName == "SYMLINKS.txt") {
                        // Read all symlink definitions. Do NOT close the reader - it would
                        // close the underlying ZipInputStream and abort entry iteration.
                        // The outer zis.use{} will handle cleanup. This mirrors the
                        // official TermuxInstaller.java which creates the BufferedReader
                        // without a try-with-resources.
                        val reader = BufferedReader(InputStreamReader(zis))
                        var line = reader.readLine()
                        while (line != null) {
                            val parts = line.split("←")
                            if (parts.size != 2) {
                                throw IOException("Malformed symlink line: $line")
                            }
                            val target = parts[0].trim()
                            val linkPath = parts[1].trim()
                            val linkFile = File(stagingDir, linkPath)
                            linkFile.parentFile?.mkdirs()
                            symlinks.add(target to linkFile)
                            line = reader.readLine()
                        }
                    } else {
                        val targetFile = File(stagingDir, entryName)
                        if (entry.isDirectory) {
                            targetFile.mkdirs()
                        } else {
                            targetFile.parentFile?.mkdirs()
                            targetFile.outputStream().use { output ->
                                var read: Int
                                while (zis.read(buffer).also { read = it } != -1) {
                                    output.write(buffer, 0, read)
                                }
                            }
                            // 设置可执行权限：bin/、libexec/、lib/apt/ 下的文件
                            if (entryName.startsWith("bin/") ||
                                entryName.startsWith("libexec/") ||
                                entryName.startsWith("lib/apt/apt-helper") ||
                                entryName.startsWith("lib/apt/methods")
                            ) {
                                runCatching {
                                    Os.chmod(targetFile.absolutePath, 0b111_000_000)
                                }.onFailure { e ->
                                    Log.w(TAG, "chmod failed for $entryName: ${e.message}")
                                }
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }

        if (symlinks.isEmpty()) {
            throw IOException("No SYMLINKS.txt found in bootstrap zip")
        }

        // 创建所有符号链接
        for ((target, linkFile) in symlinks) {
            runCatching {
                if (linkFile.exists()) linkFile.delete()
                Os.symlink(target, linkFile.absolutePath)
            }.onFailure { e ->
                Log.w(TAG, "symlink failed: $target -> ${linkFile.absolutePath}: ${e.message}")
            }
        }
        Log.i(TAG, "Extracted ${symlinks.size} symlinks")
    }

    /**
     * 写入 etc/termux/termux.env 环境变量文件。
     */
    private fun writeEnvFile() {
        val envDir = File(environment.prefix, "etc/termux")
        envDir.mkdirs()
        val env = environment.defaultEnvironment()
        val content = buildString {
            env.forEach { (k, v) ->
                append(k).append('=').append(v).append('\n')
            }
        }
        environment.envFile.writeText(content)
        Log.i(TAG, "Wrote env file to ${environment.envFile.absolutePath}")
    }

    /**
     * 创建 $HOME/storage/ 目录下的符号链接，指向 /sdcard 各公共目录。
     * 参考 Termux 的 setupStorageSymlinks。
     */
    private fun setupStorageSymlinks() {
        val storageDir = File(environment.homeDir, "storage")
        storageDir.mkdirs()
        // 清理可能存在的旧链接
        storageDir.listFiles()?.forEach { it.delete() }

        val links = buildList {
            add("shared" to Environment.getExternalStorageDirectory().absolutePath)
            add("documents" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath)
            add("downloads" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
            add("dcim" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath)
            add("pictures" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath)
            add("music" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath)
            add("movies" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath)
            add("podcasts" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS).absolutePath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add("audiobooks" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS).absolutePath)
            }
        }

        for ((name, target) in links) {
            val linkFile = File(storageDir, name)
            runCatching {
                Os.symlink(target, linkFile.absolutePath)
            }.onFailure { e ->
                Log.w(TAG, "storage symlink failed: $name -> $target: ${e.message}")
            }
        }

        // Android/data/{packageName} symlinks
        context.getExternalFilesDirs(null)?.forEachIndexed { i, dir ->
            if (dir == null) return@forEachIndexed
            val linkFile = File(storageDir, "external-$i")
            runCatching { Os.symlink(dir.absolutePath, linkFile.absolutePath) }
                .onFailure { e ->
                    Log.w(TAG, "external-$i symlink failed: ${e.message}")
                }
        }

        // Android/media/{packageName} symlinks
        context.getExternalMediaDirs()?.forEachIndexed { i, dir ->
            if (dir == null) return@forEachIndexed
            val linkFile = File(storageDir, "media-$i")
            runCatching { Os.symlink(dir.absolutePath, linkFile.absolutePath) }
                .onFailure { e ->
                    Log.w(TAG, "media-$i symlink failed: ${e.message}")
                }
        }

        Log.i(TAG, "Storage symlinks created at ${storageDir.absolutePath}")
    }

    /**
     * 安装失败时清理 staging 目录。
     */
    fun cleanupOnFailure() {
        runCatching {
            if (environment.stagingDir.exists()) {
                environment.stagingDir.deleteRecursively()
            }
        }.onFailure {
            Log.w(TAG, "cleanupOnFailure failed", it)
        }
    }

    companion object {
        private const val TAG = "TermuxInstaller"

        private const val BUFFER_SIZE = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val RETRY_BASE_DELAY_MS = 2_000L
        private const val MIN_DISK_SPACE_BYTES = 500L * 1024 * 1024 // 500MB
        private const val MB = 1024 * 1024
        private const val BOOTSTRAP_CONFIG_TIMEOUT_SECONDS = 300L
        private const val RUNTIME_SMOKE_TIMEOUT_SECONDS = 30L
        private const val PROCESS_TREE_SHUTDOWN_SECONDS = 5L
        private const val MAX_BOOTSTRAP_LOG_CHARS = 16 * 1024

        /**
         * 获取指定路径所在分区的可用空间（字节）。
         */
        private fun getAvailableSpace(path: File): Long {
            return runCatching {
                val stat = StatFs(path.absolutePath)
                stat.availableBlocksLong * stat.blockSizeLong
            }.getOrElse {
                Log.w(TAG, "getAvailableSpace failed, assuming sufficient", it)
                Long.MAX_VALUE
            }
        }
    }
}
