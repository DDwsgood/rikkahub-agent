package me.rerere.workspace

import java.io.File

data class WorkspaceBindMount(
    val source: File,
    val target: String,
) {
    init {
        require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }
    }
}

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!context.linuxDir.hasUsableRootfs()) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Rootfs is not installed",
            )
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot executable not found: ${proot.absolutePath}",
            )
        }
        if (!loader.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot loader not found: ${loader.absolutePath}",
            )
        }

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        val process = newProcessBuilder(context, proot, loader).start()

        return process.readResult(context.timeoutMillis, context.stdin)
    }

    override fun start(context: WorkspaceShellContext): Process {
        if (!context.linuxDir.hasUsableRootfs()) {
            throw IllegalStateException("Rootfs is not installed")
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            throw IllegalStateException("proot executable not found: ${proot.absolutePath}")
        }
        if (!loader.isFile) {
            throw IllegalStateException("proot loader not found: ${loader.absolutePath}")
        }

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        return newProcessBuilder(context, proot, loader).start()
    }

    override fun startStructured(
        context: WorkspaceShellContext,
        args: List<String>,
        extraEnv: Map<String, String>,
    ): Process {
        if (!context.linuxDir.hasUsableRootfs()) {
            throw IllegalStateException("Rootfs is not installed")
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            throw IllegalStateException("proot executable not found: ${proot.absolutePath}")
        }
        if (!loader.isFile) {
            throw IllegalStateException("proot loader not found: ${loader.absolutePath}")
        }

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        return newStructuredProcessBuilder(context, proot, loader, args, extraEnv).start()
    }

    private fun newProcessBuilder(
        context: WorkspaceShellContext,
        proot: File,
        loader: File,
    ): ProcessBuilder =
        ProcessBuilder(buildCommand(context, proot))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .applyProotEnv(context, loader)

    /** Structured argv launch (no shell eval): proot + env -i <extraEnv> command args... */
    private fun newStructuredProcessBuilder(
        context: WorkspaceShellContext,
        proot: File,
        loader: File,
        args: List<String>,
        extraEnv: Map<String, String>,
    ): ProcessBuilder =
        ProcessBuilder(buildStructuredCommand(context, proot, args, extraEnv))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .applyProotEnv(context, loader)

    private fun ProcessBuilder.applyProotEnv(
        context: WorkspaceShellContext,
        loader: File,
    ): ProcessBuilder = apply {
        environment()["PROOT_LOADER"] = loader.absolutePath
        environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
        environment()["TMPDIR"] = context.tempDir.absolutePath
    }

    private fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
    ): List<String> {
        val command = prootPrefix(context, proot)
        command += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "/bin/bash",
            "-l",
            "-c",
            // 命令通过位置参数传入, 避免任何转义; eval "$2" 对命令文本只求值一次, 等价于 bash -c "$cmd"
            "cd -- \"\$1\" && eval \"\$2\"",
            "rikkahub",
            context.prootCwd(),
            context.command,
        )
        return command
    }

    /** Structured argv: proot flags + `/usr/bin/env -i` base vars + user env, then the command itself. */
    private fun buildStructuredCommand(
        context: WorkspaceShellContext,
        proot: File,
        args: List<String>,
        extraEnv: Map<String, String>,
    ): List<String> {
        val command = prootPrefix(context, proot)
        command += "/usr/bin/env"
        command += "-i"
        command += "HOME=/root"
        command += "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        command += "TERM=xterm-256color"
        command += "LANG=C.UTF-8"
        command += "LC_ALL=C.UTF-8"
        // 用户 env 追加在基础变量之后, env -i 按顺序赋值, 后者覆盖前者
        extraEnv.forEach { (k, v) -> command += "$k=$v" }
        command += context.command
        command += args
        return command
    }

    /** Shared proot prefix used by both the shell-eval path and the structured argv path. */
    private fun prootPrefix(
        context: WorkspaceShellContext,
        proot: File,
    ): MutableList<String> {
        val command = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            context.linuxDir.absolutePath,
            "-w",
            context.prootCwd(),
            "-b",
            "${context.filesDir.absolutePath}:$WORKSPACE_DIR",
        )

        context.bindMounts.forEach { mount ->
            if (mount.source.exists()) {
                command += "-b"
                command += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }

        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                command += "-b"
                command += path
            }
        }
        return command
    }

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WORKSPACE_DIR
        } else {
            "$WORKSPACE_DIR/$normalized"
        }
    }

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR
    }
}
