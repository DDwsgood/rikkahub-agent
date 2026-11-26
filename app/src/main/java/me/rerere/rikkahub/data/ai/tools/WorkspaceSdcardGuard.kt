package me.rerere.rikkahub.data.ai.tools

import me.rerere.workspace.WorkspaceSdcard
import me.rerere.workspace.WorkspaceSdcardMode

/**
 * Path-aware sdcard guard for the proot workspace shell tools
 * (`workspace_shell` / `workspace_run_background`).
 *
 * Enforcement model (the proot bind is NOT a kernel read-only boundary — the
 * vendored proot has no ro-bind support, so a READ_ONLY mount is writable at
 * the syscall level; these checks are the enforcement, not decoration):
 *  - READ_ONLY: every write/delete/metadata primitive with a /sdcard target is
 *    denied, before it can reach the (kernel-writable) bind.
 *  - READ_WRITE: public-directory-level annihilation is denied — forced
 *    recursive deletes, `mv`, `rsync --delete`, `find -delete`/`-exec rm`
 *    affecting `/sdcard`, first-level children (incl. globs), or critical
 *    second-level user folders (`/sdcard/DCIM/Camera`, etc.); `shred` at any
 *    depth; glob redirect truncation; symlink indirection combined with delete;
 *    and destructive interpreter/heredoc payloads mentioning `/sdcard`.
 *    Single-file writes/deletes pass through to the normal approval chain.
 *  - NONE: no mount, nothing to guard.
 */
object WorkspaceSdcardGuard {

    private const val MAX_WRAPPER_DEPTH = 4

    /** workspace_shell's default cwd inside the rootfs (relative paths resolve from here). */
    private const val DEFAULT_BASE = "/workspace"

    private val IGNORE = setOf(RegexOption.IGNORE_CASE)

    private val DELETE_LEAVES = setOf("rm", "rmdir", "unlink", "shred", "srm", "wipe")
    private val INERT_REDIRECTS = setOf("/dev/null", "/dev/stdout", "/dev/stderr", "/dev/tty")

    private val XARGS_DELETE_WORDS = listOf("rm", "rmdir", "unlink", "shred", "srm", "wipe")
    private val XARGS_DELETE_CONSUMER =
        Regex("""\bxargs\b[^|;&]*\b(?:${XARGS_DELETE_WORDS.joinToString("|")})\b""", IGNORE)

    /** Critical depth-2 user folders under /sdcard that must never be wiped in bulk. */
    private val PROTECTED_DEPTH2_PATHS = setOf(
        "/sdcard/dcim/camera",
        "/sdcard/dcim/screenshots",
        "/sdcard/pictures/screenshots",
        "/sdcard/android/media",
        "/sdcard/android/data",
        "/sdcard/android/obb",
    )

    private val DESTRUCTIVE_CODE_PATTERN = Regex(
        """\b(?:rmtree|rmSync|rmdirSync|unlink|unlinkSync|os\.remove|os\.rmdir|fs\.rm|fs\.unlink|shred)\b""" +
            """|\.(?:rm|rmdir|unlink)\s*\(""" +
            """|\brm\s+(?:-[^\s]*r|--recursive)\b""" +
            """|\bfind\b[^\n]*-delete\b""",
        IGNORE,
    )

    private val HEREDOC_START_PATTERN = Regex("""<<-?\s*['"]?([A-Za-z_][A-Za-z0-9_]*)['"]?""")
    private val ASSIGNMENT_ONLY_PATTERN = Regex("""^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.+)$""")
    private val LEADING_ASSIGNMENT_PATTERN = Regex("""^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=("[^"]*"|'[^']*'|[^\s]+)\s+""")

    // ---------- public API -----------------------------------------------------

    /**
     * Classify [command] against the workspace's sdcard [mode].
     * Returns a model-readable block reason, or null when the command may run.
     */
    fun check(command: String, mode: WorkspaceSdcardMode): String? {
        if (mode == WorkspaceSdcardMode.NONE || command.isBlank()) return null

        // Scan heredoc bodies upfront before line splitting flattens delimiters.
        for (body in extractHeredocBodies(command)) {
            if (mentionsSdcard(body)) {
                if (mode == WorkspaceSdcardMode.READ_ONLY) {
                    return "blocked: the /sdcard mount is configured read-only for this workspace; " +
                        "heredoc scripts touching shared storage cannot be proven read-only"
                }
                if (DESTRUCTIVE_CODE_PATTERN.containsMatchIn(body)) {
                    return "blocked: heredoc script performs destructive delete operations on /sdcard; " +
                        "operate on explicit individual files instead"
                }
            }
        }

        // Symlink indirection inside a command chain: `ln -s /sdcard ... && rm ...`
        if (hasSymlinkToProtectedSdcard(command) && hasAnyDeleteVerb(command)) {
            return "blocked: creating a symlink to /sdcard in a command chain that also deletes files"
        }

        // Pipeline rule: `find /sdcard … | xargs rm` spans `|`, so judge it on the
        // pipe-joined stages before per-command analysis splits them apart.
        splitTopLevel(command, setOf('|')).let { stages ->
            if (stages.size >= 2) {
                val joined = stages.joinToString(" ")
                val sourcesSdcard = stages.dropLast(1).any { mentionsSdcard(it) }
                if (sourcesSdcard && XARGS_DELETE_CONSUMER.containsMatchIn(joined)) {
                    return "blocked: piping /sdcard content into a deletion consumer " +
                        "(xargs ${XARGS_DELETE_WORDS.joinToString("/")}) can destroy user files in shared storage"
                }
            }
        }

        // Per-command walk with literal `cd` + simple variable assignment tracking.
        var base: String = DEFAULT_BASE
        val vars = mutableMapOf<String, String>()
        for (raw in splitTopLevel(command, setOf(';', '&', '|', '\n'))) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            val standaloneVar = parseStandaloneAssignment(trimmed, vars)
            if (standaloneVar != null) {
                vars[standaloneVar.first] = standaloneVar.second
                continue
            }
            val segment = stripWrappers(trimmed, vars) ?: continue
            if (segment.isEmpty()) continue
            val cd = parseCd(segment, base, vars)
            if (cd.handled) {
                base = cd.newBase
                continue
            }
            checkCommand(segment, mode, base, vars, depth = 0)?.let { return it }
        }
        return null
    }

    // ---------- per-command analysis ------------------------------------------

    private fun checkCommand(
        segment: String,
        mode: WorkspaceSdcardMode,
        base: String,
        vars: Map<String, String>,
        depth: Int,
    ): String? {
        if (depth > MAX_WRAPPER_DEPTH) return null

        for (payload in extractQuotedPayloads(segment)) {
            when (payload.kind) {
                PayloadKind.SHELL -> {
                    var innerBase = base
                    val innerVars = vars.toMutableMap()
                    for (raw in splitTopLevel(payload.text, setOf(';', '&', '|', '\n'))) {
                        val trimmed = raw.trim()
                        if (trimmed.isEmpty()) continue
                        val standaloneVar = parseStandaloneAssignment(trimmed, innerVars)
                        if (standaloneVar != null) {
                            innerVars[standaloneVar.first] = standaloneVar.second
                            continue
                        }
                        val inner = stripWrappers(trimmed, innerVars) ?: continue
                        val cd = parseCd(inner, innerBase, innerVars)
                        if (cd.handled) {
                            innerBase = cd.newBase
                            continue
                        }
                        checkCommand(inner, mode, innerBase, innerVars, depth + 1)?.let { return it }
                    }
                }
                PayloadKind.CODE -> {
                    val expandedPayload = substituteVariables(payload.text, vars)
                    if (mentionsSdcard(expandedPayload)) {
                        if (mode == WorkspaceSdcardMode.READ_ONLY) {
                            return "blocked: the /sdcard mount is configured read-only for this workspace; " +
                                "interpreter code touching shared storage cannot be proven read-only"
                        }
                        if (DESTRUCTIVE_CODE_PATTERN.containsMatchIn(expandedPayload)) {
                            return "blocked: interpreter code performs destructive delete operations on /sdcard; " +
                                "operate on explicit individual files instead"
                        }
                    }
                }
            }
        }

        firstSdcardRedirectTarget(segment, mode, base, vars)?.let { return it }

        val tokens = tokenize(segment)
        val leaf = commandLeaf(tokens) ?: return null
        val args = tokens.drop(1)

        return when (leaf) {
            in DELETE_LEAVES -> {
                val (flags, positionals) = splitFlagsAndPositionals(args)
                val recursive = flags.any { flag ->
                    flag.equals("--recursive", ignoreCase = true) ||
                        (!flag.startsWith("--") && flag.length > 1 && flag.drop(1).any { it in "rR" })
                }
                positionals.firstNotNullOfOrNull { judgeDelete(it, recursive, leaf, mode, base, vars) }
            }
            "find" -> judgeFind(args, mode, base, vars)
            "rsync" -> judgeRsync(args, mode, base, vars)
            "dd" -> args.mapNotNull { arg ->
                if (arg.startsWith("of=", ignoreCase = true)) judgeWrite(arg.substring(3), mode, base, vars) else null
            }.firstOrNull()
            "tee", "truncate", "touch", "mkdir" ->
                args.filter { !it.startsWith("-") }.firstNotNullOfOrNull { judgeWrite(it, mode, base, vars) }
            "chmod", "chown", "chgrp", "chattr", "setfattr" ->
                args.filter { !it.startsWith("-") }.firstNotNullOfOrNull { judgeWrite(it, mode, base, vars) }
            "mv" -> {
                val (_, positionals) = splitFlagsAndPositionals(args)
                val src = positionals.getOrNull(0)
                val dst = positionals.lastOrNull()
                if (src != null) {
                    judgeMoveSource(src, mode, base, vars)?.let { return it }
                }
                if (dst != null && positionals.size >= 2) {
                    judgeWrite(dst, mode, base, vars)?.let { return it }
                }
                null
            }
            "cp", "install", "ln" -> {
                val (flags, positionals) = splitFlagsAndPositionals(args)
                val flagTarget = flags.firstNotNullOfOrNull { flag ->
                    when {
                        flag.startsWith("-t=") -> flag.substring(3)
                        flag.startsWith("--target-directory=") -> flag.substringAfter('=')
                        else -> null
                    }
                }
                (flagTarget ?: positionals.lastOrNull())?.let { judgeWrite(it, mode, base, vars) }
            }
            "sed" -> {
                val hasInPlace = args.any { it == "-i" || it.startsWith("-i") || it.startsWith("--in-place") }
                if (hasInPlace) {
                    args.filter { !it.startsWith("-") }.lastOrNull()?.let { judgeWrite(it, mode, base, vars) }
                } else null
            }
            "unzip", "7z", "7za", "7zr" -> {
                val dir = args.firstOrNull { it == "-d" || it == "--directory" }
                    ?.let { d -> args.getOrNull(args.indexOf(d) + 1) }
                dir?.let { judgeWrite(it, mode, base, vars) }
            }
            "tar" -> {
                val extracting = args.any { it.startsWith("-") && it.contains(Regex("x")) } ||
                    args.any { it == "--extract" }
                if (extracting) {
                    val cDir = args.firstOrNull { it == "-C" }?.let { c -> args.getOrNull(args.indexOf(c) + 1) }
                        ?: args.firstOrNull { it.startsWith("--directory=") }?.substringAfter('=')
                    cDir?.let { judgeWrite(it, mode, base, vars) }
                } else null
            }
            "gzip", "bzip2", "xz", "gunzip", "bunzip2", "unxz" ->
                args.filter { !it.startsWith("-") }.firstNotNullOfOrNull { judgeWrite(it, mode, base, vars) }
            else -> null
        }
    }

    // ---------- judgements ------------------------------------------------------

    private fun isProtectedSdcardDirectory(normalizedPath: String): Boolean {
        if (!WorkspaceSdcard.isWithin(normalizedPath)) return false
        val rel = normalizedPath.removePrefix(WorkspaceSdcard.MOUNT_TARGET).trim('/')
        if (rel.isEmpty()) return true // /sdcard itself
        val depth = rel.count { it == '/' } + 1
        if (depth <= 1) return true
        return normalizedPath.lowercase() in PROTECTED_DEPTH2_PATHS
    }

    private fun judgeWrite(
        rawTarget: String,
        mode: WorkspaceSdcardMode,
        base: String,
        vars: Map<String, String>,
        deleteSemantics: Boolean = false,
    ): String? {
        val path = normalizeTarget(rawTarget, base, vars) ?: return null
        if (!WorkspaceSdcard.isWithin(path)) return null
        if (mode == WorkspaceSdcardMode.READ_WRITE) {
            if (rawTarget.contains('*') || rawTarget.contains('?')) {
                return "blocked: glob target $rawTarget can overwrite or delete user files in " +
                    "shared storage in breadth; name specific files instead"
            }
            return null
        }
        val action = if (deleteSemantics) "delete from" else "write to"
        return "blocked: the /sdcard mount is configured read-only for this workspace; " +
            "the workspace cannot $action shared storage"
    }

    private fun judgeMoveSource(
        rawSource: String,
        mode: WorkspaceSdcardMode,
        base: String,
        vars: Map<String, String>,
    ): String? {
        val path = normalizeTarget(rawSource, base, vars) ?: return null
        if (!WorkspaceSdcard.isWithin(path)) return null
        if (mode == WorkspaceSdcardMode.READ_ONLY) {
            return "blocked: the /sdcard mount is configured read-only for this workspace; " +
                "the workspace cannot delete from shared storage"
        }
        if (rawSource.contains('*') || rawSource.contains('?')) {
            return "blocked: glob target $rawSource can move/remove user files in shared storage in breadth"
        }
        val rel = path.removePrefix(WorkspaceSdcard.MOUNT_TARGET).trim('/')
        val isSingleRootFileMove = rel.isNotEmpty() &&
            !rel.contains('/') &&
            !rel.startsWith('.') &&
            '.' in rel &&
            !rawSource.trimEnd('\'', '"').endsWith('/')
        if (!isSingleRootFileMove && isProtectedSdcardDirectory(path)) {
            return "blocked: refusing to move protected user shared-storage directory ($path)"
        }
        return null
    }

    private fun judgeDelete(
        rawTarget: String,
        recursive: Boolean,
        leaf: String,
        mode: WorkspaceSdcardMode,
        base: String,
        vars: Map<String, String>,
    ): String? {
        val path = normalizeTarget(rawTarget, base, vars) ?: return null
        if (!WorkspaceSdcard.isWithin(path)) return null
        if (mode == WorkspaceSdcardMode.READ_ONLY) {
            return "blocked: the /sdcard mount is configured read-only for this workspace; " +
                "the workspace cannot delete from shared storage"
        }
        if (leaf == "shred") {
            return "blocked: shred irrecoverably destroys shared-storage content; it is not " +
                "available for /sdcard paths"
        }
        val carriesGlob = rawTarget.contains('*') || rawTarget.contains('?')
        val publicLevel = isProtectedSdcardDirectory(path) ||
            (carriesGlob && isProtectedSdcardDirectory(path.substringBeforeLast('/').ifEmpty { "/" }))
        if (!(recursive || carriesGlob) || !publicLevel) return null
        return "blocked: refusing to recursively wipe user shared storage at the " +
            "public-directory level ($path). Operate on specific subdirectories or files " +
            "instead of wiping an entire public directory"
    }

    private fun judgeRsync(
        args: List<String>,
        mode: WorkspaceSdcardMode,
        base: String,
        vars: Map<String, String>,
    ): String? {
        val (flags, positionals) = splitFlagsAndPositionals(args)
        val hasDeleteFlag = flags.any { f ->
            val lower = f.lowercase()
            lower == "--delete" ||
                lower.startsWith("--delete-") ||
                lower == "--remove-source-files"
        }
        val dst = positionals.lastOrNull()
        val srcs = if (positionals.size >= 2) positionals.dropLast(1) else emptyList()

        if (mode == WorkspaceSdcardMode.READ_ONLY) {
            dst?.let { judgeWrite(it, mode, base, vars)?.let { reason -> return reason } }
            if (flags.any { it.equals("--remove-source-files", ignoreCase = true) }) {
                srcs.firstNotNullOfOrNull { judgeWrite(it, mode, base, vars, deleteSemantics = true) }
                    ?.let { return it }
            }
            return null
        }

        if (!hasDeleteFlag) {
            return dst?.let { judgeWrite(it, mode, base, vars) }
        }

        val checkPath: (String) -> String? = { raw ->
            val path = normalizeTarget(raw, base, vars)
            if (path != null && WorkspaceSdcard.isWithin(path) &&
                (isProtectedSdcardDirectory(path) || raw.contains('*') || raw.contains('?'))
            ) {
                "blocked: rsync with delete/remove flags cannot target protected /sdcard path ($path)"
            } else null
        }
        dst?.let { checkPath(it)?.let { reason -> return reason } }
        if (flags.any { it.equals("--remove-source-files", ignoreCase = true) }) {
            srcs.firstNotNullOfOrNull { checkPath(it) }?.let { return it }
        }
        return null
    }

    private fun judgeFind(
        args: List<String>,
        mode: WorkspaceSdcardMode,
        base: String,
        vars: Map<String, String>,
    ): String? {
        var hasDelete = false
        var hasExecDelete = false
        var literalRoot: String? = null
        var sawFlag = false
        for ((index, arg) in args.withIndex()) {
            if (arg == "-delete") hasDelete = true
            if (arg in setOf("-exec", "-execdir", "-ok", "-okdir")) {
                if (args.getOrNull(index + 1)?.lowercase() in DELETE_LEAVES) hasExecDelete = true
            }
            if (literalRoot == null) {
                if (arg.startsWith("-")) {
                    sawFlag = true
                } else if (!sawFlag) {
                    literalRoot = arg
                }
            }
        }
        if (!hasDelete && !hasExecDelete) return null

        val root = literalRoot?.let { normalizeTarget(it, base, vars) }
            ?: if (mentionsSdcard(substituteVariables(args.joinToString(" "), vars))) {
                WorkspaceSdcard.MOUNT_TARGET
            } else null
            ?: return null
        if (!WorkspaceSdcard.isWithin(root)) return null

        if (mode == WorkspaceSdcardMode.READ_ONLY) {
            return "blocked: the /sdcard mount is configured read-only for this workspace; " +
                "find actions cannot modify shared storage"
        }
        return if (isProtectedSdcardDirectory(root)) {
            "blocked: find with delete/exec actions rooted at $root would wipe user shared " +
                "storage at the public-directory level"
        } else {
            null
        }
    }

    /** Redirect targets (`>f`, `>>f`, `&>f`, `2>f`) — always a write, for any command. */
    private fun firstSdcardRedirectTarget(
        segment: String,
        mode: WorkspaceSdcardMode,
        base: String,
        vars: Map<String, String>,
    ): String? {
        var i = 0
        val n = segment.length
        var quote: Char? = null
        while (i < n) {
            val ch = segment[i]
            when {
                quote != null && ch == quote -> quote = null
                quote != null -> {}
                ch == '\'' || ch == '"' -> quote = ch
                ch == '\\' && quote != '\'' -> i++ // skip escaped char
                ch == '>' -> {
                    var j = i + 1
                    if (j < n && (segment[j] == '>' || segment[j] == '&')) j++
                    while (j < n && segment[j] == ' ') j++
                    if (j < n && segment[j] != '&') { // >&2 / >&- are fd merges, not files
                        val target: String
                        val openQuote = segment[j]
                        if (openQuote == '\'' || openQuote == '"') {
                            val close = segment.indexOf(openQuote, j + 1)
                            if (close == -1) {
                                i = n
                                break
                            }
                            target = segment.substring(j + 1, close)
                            j = close + 1
                        } else {
                            var k = j
                            while (k < n && !";<>|& \t\n'\"".contains(segment[k])) k++
                            target = segment.substring(j, k)
                            j = k
                        }
                        val isFdMerge = target.matches(Regex("&?[\\d-]+"))
                        if (target.isNotBlank() && target !in INERT_REDIRECTS && !isFdMerge) {
                            val path = normalizeTarget(target, base, vars)
                            if (path != null && WorkspaceSdcard.isWithin(path)) {
                                if (mode == WorkspaceSdcardMode.READ_ONLY) {
                                    return "blocked: the /sdcard mount is configured read-only " +
                                        "for this workspace; redirecting output into shared " +
                                        "storage is a write"
                                }
                                if (target.contains('*') || target.contains('?')) {
                                    return "blocked: glob redirect target $target can truncate " +
                                        "user files in shared storage in breadth"
                                }
                                return null
                            }
                        }
                        i = j
                        continue
                    }
                }
            }
            i++
        }
        return null
    }

    // ---------- lexical helpers ---------------------------------------------------

    private fun extractHeredocBodies(command: String): List<String> {
        val lines = command.lines()
        if (lines.size < 2) return emptyList()
        val bodies = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val match = HEREDOC_START_PATTERN.find(lines[i])
            if (match != null) {
                val delim = match.groupValues[1]
                val sb = StringBuilder()
                i++
                while (i < lines.size && lines[i].trim() != delim) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(lines[i])
                    i++
                }
                if (sb.isNotEmpty()) bodies.add(sb.toString())
            }
            i++
        }
        return bodies
    }

    private fun hasSymlinkToProtectedSdcard(command: String): Boolean {
        val lnRegex = Regex("""\bln\s+[^\n;|&]*-s[^\n;|&]*""", IGNORE)
        for (match in lnRegex.findAll(command)) {
            if (mentionsSdcard(match.value)) return true
        }
        return false
    }

    private fun hasAnyDeleteVerb(command: String): Boolean =
        Regex("""\b(?:rm|rmdir|unlink|shred|srm|wipe|find\b[^\n]*-delete)\b""", IGNORE)
            .containsMatchIn(command)

    private fun parseStandaloneAssignment(
        segment: String,
        vars: Map<String, String>,
    ): Pair<String, String>? {
        val match = ASSIGNMENT_ONLY_PATTERN.matchEntire(segment) ?: return null
        val name = match.groupValues[1]
        val rawVal = stripQuotes(match.groupValues[2].trim())
        if (rawVal.contains(' ') && !match.groupValues[2].trim().let {
                (it.startsWith('"') && it.endsWith('"')) || (it.startsWith('\'') && it.endsWith('\''))
            }
        ) {
            return null
        }
        val resolved = substituteVariables(rawVal, vars)
        return name to resolved
    }

    private fun substituteVariables(text: String, vars: Map<String, String>): String {
        if (vars.isEmpty() || !text.contains('$')) return text
        var out = text
        for ((k, v) in vars) {
            out = out.replace("\${$k}", v)
            out = out.replace(Regex("""\$$k(?![A-Za-z0-9_])"""), Regex.escapeReplacement(v))
        }
        return out
    }

    private fun stripQuotes(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length >= 2) {
            val first = trimmed.first()
            if ((first == '\'' || first == '"') && trimmed.last() == first) {
                return trimmed.substring(1, trimmed.length - 1)
            }
        }
        return trimmed
    }

    /**
     * Lexical `.`/`..` reduction + host storage alias normalization (`/storage/emulated/0` -> `/sdcard`).
     */
    private fun normalizeTarget(
        raw: String,
        base: String,
        vars: Map<String, String> = emptyMap(),
    ): String? {
        var value = substituteVariables(stripQuotes(raw), vars)
        if (value.isEmpty() || value.contains('$') || value.contains('`')) return null
        value = value.replace(Regex("//+"), "/")
        if (value.startsWith("~")) return null // $HOME expands to /root inside proot: never in-zone

        // Normalize Android host storage aliases to /sdcard
        val lower = value.lowercase()
        value = when {
            lower == "/storage/emulated/0" -> "/sdcard"
            lower.startsWith("/storage/emulated/0/") -> "/sdcard" + value.substring("/storage/emulated/0".length)
            lower == "/storage/self/primary" -> "/sdcard"
            lower.startsWith("/storage/self/primary/") -> "/sdcard" + value.substring("/storage/self/primary".length)
            else -> value
        }

        val absolute = value.startsWith("/")
        val start = if (absolute) value.substring(1) else {
            if (base.isEmpty()) return null
            "$base/$value"
        }
        val reduced = mutableListOf<String>()
        for (part in start.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (reduced.isNotEmpty()) reduced.removeAt(reduced.lastIndex)
                else -> reduced.add(part)
            }
        }
        return "/" + reduced.joinToString("/")
    }

    private data class CdResult(val handled: Boolean, val newBase: String)

    /** Literal `cd <dir>` / `pushd <dir>`: resolves relative to [currentBase]. */
    private fun parseCd(
        segment: String,
        currentBase: String,
        vars: Map<String, String>,
    ): CdResult {
        val tokens = tokenize(segment)
        val leaf = commandLeaf(tokens) ?: return CdResult(false, currentBase)
        if (leaf !in setOf("cd", "pushd")) return CdResult(false, currentBase)
        if (tokens.size < 2) return CdResult(true, "/root")
        val target = tokens[1]
        if (target == "-") return CdResult(true, "")
        val resolved = normalizeTarget(target, currentBase, vars) ?: ""
        return CdResult(true, resolved)
    }

    /** Splits [text] on any of [separators] occurring outside quotes/escapes. Doubled separators collapse. */
    private fun splitTopLevel(text: String, separators: Set<Char>): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                escaped -> {
                    current.append(ch)
                    escaped = false
                }
                ch == '\\' && quote != '\'' -> {
                    current.append(ch)
                    escaped = true
                }
                quote != null -> {
                    current.append(ch)
                    if (ch == quote) quote = null
                }
                ch == '\'' || ch == '"' -> {
                    quote = ch
                    current.append(ch)
                }
                ch in separators -> {
                    if (i + 1 < text.length && text[i + 1] == ch) i++
                    segments.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
            i++
        }
        segments.add(current.toString())
        return segments
    }

    /** Drops leading `VAR=value` assignments and wrapper words (sudo/env/nohup/setsid/time/exec/nice/timeout). */
    private fun stripWrappers(segment: String, vars: MutableMap<String, String>? = null): String? {
        var value = segment.trim()
        while (true) {
            val assignment = LEADING_ASSIGNMENT_PATTERN.find(value)
            if (assignment != null) {
                val varName = assignment.groupValues[1]
                val varVal = stripQuotes(assignment.groupValues[2])
                if (vars != null) vars[varName] = substituteVariables(varVal, vars)
                value = value.removePrefix(assignment.value)
                continue
            }
            val first = value.split(Regex("\\s+")).firstOrNull() ?: break
            val leaf = first.trim('(', ')').substringAfterLast('/').lowercase()
            if (leaf !in WRAPPER_LEAVES) break
            value = value.removePrefix(first).trim()
            if (leaf == "timeout" || leaf == "env") {
                value = value.replace(Regex("^\\S+\\s+"), "")
            }
        }
        return value.ifBlank { null }
    }

    private val WRAPPER_LEAVES = setOf("sudo", "nohup", "setsid", "exec", "time", "nice", "stdbuf", "timeout", "env")

    private enum class PayloadKind { SHELL, CODE }

    private data class QuotedPayload(val text: String, val kind: PayloadKind)

    private val shellPayloadRegex = Regex(
        """\b(?:busybox\s+)?(?:bash|sh|dash|zsh|ash)\s+(?:-[a-zA-Z]+\s+)*-c\s+(['"])([\s\S]{1,4000}?)\1""",
        IGNORE,
    )

    private val codePayloadRegex = Regex(
        """\b(?:python3?|node|perl|ruby|php)\s+(?:-[a-zA-Z]+\s+)*(?:-c|-e|-r|--eval)\s+(['"])([\s\S]{1,4000}?)\1""",
        IGNORE,
    )

    private val evalPayloadRegex = Regex("""\beval\s+(['"])([\s\S]{1,4000}?)\1""", IGNORE)

    private fun extractQuotedPayloads(segment: String): List<QuotedPayload> {
        val payloads = mutableListOf<QuotedPayload>()
        for (match in shellPayloadRegex.findAll(segment)) {
            match.groupValues[2].takeIf { it.isNotBlank() }?.let { payloads.add(QuotedPayload(it, PayloadKind.SHELL)) }
        }
        for (match in evalPayloadRegex.findAll(segment)) {
            match.groupValues[2].takeIf { it.isNotBlank() }?.let { payloads.add(QuotedPayload(it, PayloadKind.SHELL)) }
        }
        for (match in codePayloadRegex.findAll(segment)) {
            match.groupValues[2].takeIf { it.isNotBlank() }?.let { payloads.add(QuotedPayload(it, PayloadKind.CODE)) }
        }
        return payloads
    }

    /** Whitespace tokenizer that keeps quoted words intact. */
    private fun tokenize(segment: String): List<String> =
        Regex(""""(?:[^"]*)"|'[^']*'|[^ \t\n]+""").findAll(segment).map { it.value }.toList()

    private fun commandLeaf(tokens: List<String>): String? =
        tokens.firstOrNull()
            ?.trim('(', ')')
            ?.replace(Regex("""^["']|["']$"""), "")
            ?.substringAfterLast('/')
            ?.lowercase()

    private fun splitFlagsAndPositionals(args: List<String>): Pair<List<String>, List<String>> {
        val flags = mutableListOf<String>()
        val positionals = mutableListOf<String>()
        var optionsEnded = false
        for (arg in args) {
            when {
                !optionsEnded && arg == "--" -> optionsEnded = true
                !optionsEnded && arg.startsWith("-") && arg != "-" -> flags.add(arg)
                else -> positionals.add(arg)
            }
        }
        return flags to positionals
    }

    /** Textual mention of /sdcard or its Android host storage aliases. */
    private fun mentionsSdcard(text: String): Boolean =
        Regex("""(?:^|[\s"'=(])/(?:sdcard|storage/emulated/0|storage/self/primary)\b""", IGNORE)
            .containsMatchIn(text)
}
