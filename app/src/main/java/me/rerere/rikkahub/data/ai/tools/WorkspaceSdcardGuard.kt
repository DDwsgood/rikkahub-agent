package me.rerere.rikkahub.data.ai.tools

import me.rerere.workspace.WorkspaceSdcard
import me.rerere.workspace.WorkspaceSdcardMode

/**
 * Path-aware sdcard guard for the proot workspace shell tools
 * (`workspace_shell` / `workspace_run_background`).
 *
 * This is the "thin layer" from the opencode-v2-security migration study: a
 * quote-aware segment splitter + lexical path reduction (with literal `cd`
 * tracking) + a small command vocabulary — deliberately NOT a full port of the
 * plugin's 10k-line classifier. The unconditional destructive floor
 * (rm -rf /, fork bombs, …) stays in [HardlineCommandGuard]; this guard only
 * reasons about targets that resolve into the /sdcard mount, and only when the
 * workspace's sdcard mode mounts it.
 *
 * Enforcement model (the proot bind is NOT a kernel read-only boundary — the
 * vendored proot has no ro-bind support, so a READ_ONLY mount is writable at
 * the syscall level; these checks are the enforcement, not decoration):
 *  - READ_ONLY: every write/delete/metadata primitive with a /sdcard target is
 *    denied, before it can reach the (kernel-writable) bind.
 *  - READ_WRITE: "public-directory-level annihilation" is denied — forced
 *    recursive deletes of /sdcard itself or its first-level children (incl.
 *    globs), find -delete / find -exec rm rooted there, xargs→rm pipelines
 *    sourcing /sdcard, shred (destroys content irrecoverably even for one
 *    file), and glob redirect truncation. Single-file writes/deletes pass
 *    through to the normal approval chain.
 *  - NONE: no mount, nothing to guard.
 *
 * Known residual bypass surface (documented, accepted for the thin layer):
 *  - no heredoc body scanning (`bash <<EOF … EOF` payloads are invisible);
 *  - dynamic `cd` targets (`cd $(...)`) make later relative paths unjudgeable
 *    (they fall through to the approval chain, never to an allow);
 *  - symlink indirection inside the rootfs (`ln -s /sdcard /x && rm -rf /x/`)
 *    is not resolved;
 *  - interpreters writing via their own APIs are only caught when passed as a
 *    single-quoted `-c` payload containing a /sdcard literal;
 *  - parameter expansion / variable indirection is not substituted.
 * The tool description and the workspace prompt layer state the mount
 * contract; the per-tool approval prompt (and the user) are the final backstop.
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

    // ---------- public API -----------------------------------------------------

    /**
     * Classify [command] against the workspace's sdcard [mode].
     * Returns a model-readable block reason, or null when the command may run.
     */
    fun check(command: String, mode: WorkspaceSdcardMode): String? {
        if (mode == WorkspaceSdcardMode.NONE || command.isBlank()) return null

        // Pipeline rule first: `find /sdcard … | xargs rm` spans `|`, so judge it on the
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

        // Per-command walk with literal `cd` tracking so relative targets resolve against
        // the actual (tracked) working directory, not a stale default.
        var base: String = DEFAULT_BASE
        for (raw in splitTopLevel(command, setOf(';', '&', '|', '\n'))) {
            val segment = stripWrappers(raw.trim()) ?: continue
            if (segment.isEmpty()) continue
            val cd = parseCd(segment)
            if (cd != null) {
                base = cd
                continue
            }
            checkCommand(segment, mode, base, depth = 0)?.let { return it }
        }
        return null
    }

    // ---------- per-command analysis ------------------------------------------

    private fun checkCommand(
        segment: String,
        mode: WorkspaceSdcardMode,
        base: String,
        depth: Int,
    ): String? {
        if (depth > MAX_WRAPPER_DEPTH) return null

        // Quoted interpreter payloads (`bash -c '…'`, `python -c '…'`, `eval '…'`) are code,
        // not data — re-scan them so a wrapped write is caught. Inner segments resolve
        // against the SAME tracked base (subshells inherit cwd).
        for (payload in extractQuotedPayloads(segment)) {
            when (payload.kind) {
                PayloadKind.SHELL -> {
                    var innerBase = base
                    for (raw in splitTopLevel(payload.text, setOf(';', '&', '|', '\n'))) {
                        val inner = stripWrappers(raw.trim()) ?: continue
                        val cd = parseCd(inner)
                        if (cd != null) {
                            innerBase = cd
                            continue
                        }
                        checkCommand(inner, mode, innerBase, depth + 1)?.let { return it }
                    }
                }
                // Arbitrary-code interpreters: the payload grammar is opaque, so a literal
                // /sdcard mention under READ_ONLY is judged as an unprovable write (conservative
                // — reading shared storage from an interpreter is served by the file API).
                PayloadKind.CODE -> if (mode == WorkspaceSdcardMode.READ_ONLY && mentionsSdcard(payload.text)) {
                    return "blocked: the /sdcard mount is configured read-only for this workspace; " +
                        "interpreter code touching shared storage cannot be proven read-only"
                }
            }
        }

        firstSdcardRedirectTarget(segment, mode, base)?.let { return it }

        val tokens = tokenize(segment)
        val leaf = commandLeaf(tokens) ?: return null
        val args = tokens.drop(1)

        return when (leaf) {
            in DELETE_LEAVES -> {
                val (flags, positionals) = splitFlagsAndPositionals(args)
                val recursive = flags.any { flag ->
                    !flag.startsWith("--") && flag.length > 1 && flag.drop(1).any { it in "rR" }
                }
                positionals.firstNotNullOfOrNull { judgeDelete(it, recursive, leaf, mode, base) }
            }
            "find" -> judgeFind(args, mode, base)
            "dd" -> args.mapNotNull { arg ->
                if (arg.startsWith("of=", ignoreCase = true)) judgeWrite(arg.substring(3), mode, base) else null
            }.firstOrNull()
            "tee", "truncate", "touch", "mkdir" ->
                args.filter { !it.startsWith("-") }.firstNotNullOfOrNull { judgeWrite(it, mode, base) }
            "chmod", "chown", "chgrp", "chattr", "setfattr" ->
                args.filter { !it.startsWith("-") }.firstNotNullOfOrNull { judgeWrite(it, mode, base) }
            "mv" -> {
                val (_, positionals) = splitFlagsAndPositionals(args)
                // moving OUT of /sdcard deletes from shared storage; moving INTO it writes
                positionals.getOrNull(0)?.let { judgeWrite(it, mode, base, deleteSemantics = true) }
                    ?: positionals.lastOrNull()?.let { judgeWrite(it, mode, base) }
            }
            "cp", "install", "ln" -> {
                val (flags, positionals) = splitFlagsAndPositionals(args)
                // `cp -t DIR src…` / `--target-directory=DIR`: DIR is the destination
                val flagTarget = flags.firstNotNullOfOrNull { flag ->
                    when {
                        flag.startsWith("-t=") -> flag.substring(3)
                        flag.startsWith("--target-directory=") -> flag.substringAfter('=')
                        else -> null
                    }
                }
                (flagTarget ?: positionals.lastOrNull())?.let { judgeWrite(it, mode, base) }
            }
            "sed" -> {
                val hasInPlace = args.any { it == "-i" || it.startsWith("-i") || it.startsWith("--in-place") }
                if (hasInPlace) {
                    args.filter { !it.startsWith("-") }.lastOrNull()?.let { judgeWrite(it, mode, base) }
                } else null
            }
            "unzip", "7z", "7za", "7zr" -> {
                val dir = args.firstOrNull { it == "-d" || it == "--directory" }
                    ?.let { d -> args.getOrNull(args.indexOf(d) + 1) }
                dir?.let { judgeWrite(it, mode, base) }
            }
            "tar" -> {
                val extracting = args.any { it.startsWith("-") && it.contains(Regex("x")) } ||
                    args.any { it == "--extract" }
                if (extracting) {
                    val cDir = args.firstOrNull { it == "-C" }?.let { c -> args.getOrNull(args.indexOf(c) + 1) }
                        ?: args.firstOrNull { it.startsWith("--directory=") }?.substringAfter('=')
                    cDir?.let { judgeWrite(it, mode, base) }
                } else null
            }
            "gzip", "bzip2", "xz", "gunzip", "bunzip2", "unxz" ->
                args.filter { !it.startsWith("-") }.firstNotNullOfOrNull { judgeWrite(it, mode, base) }
            else -> null
        }
    }

    // ---------- judgements ------------------------------------------------------

    /**
     * Write-shaped verdict. READ_ONLY: every in-zone write (incl. metadata
     * changes and mv sources) is denied. READ_WRITE: single-file writes pass
     * through to the approval chain — glob-carrying targets are still denied
     * (breadth-destructive).
     */
    private fun judgeWrite(
        rawTarget: String,
        mode: WorkspaceSdcardMode,
        base: String,
        deleteSemantics: Boolean = false,
    ): String? {
        val path = normalizeTarget(rawTarget, base) ?: return null
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

    /**
     * Delete verdict. READ_ONLY: every in-zone delete is denied. READ_WRITE:
     * recursive/glob deletes are denied at "public-directory level" (the mount
     * root or a first-level child); shred is denied at ANY depth (irrecoverable
     * content destruction); single-file non-shred deletes stay on the approval
     * chain.
     */
    private fun judgeDelete(rawTarget: String, recursive: Boolean, leaf: String, mode: WorkspaceSdcardMode, base: String): String? {
        val path = normalizeTarget(rawTarget, base) ?: return null
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
        val depth = path.removePrefix(WorkspaceSdcard.MOUNT_TARGET).count { it == '/' }
        val publicLevel = depth <= 1
        if (!(recursive || carriesGlob) || !publicLevel) return null
        return "blocked: refusing to recursively wipe user shared storage at the " +
            "public-directory level ($path). Operate on specific subdirectories or files " +
            "instead of wiping an entire public directory"
    }

    /**
     * find … -delete / -exec rm|shred … {} — judged on the root. A literal root
     * before any flag is parsed; a flag-leading find that merely mentions
     * /sdcard is treated as rooted there (conservative — flag-value grammar is
     * not fully parsed).
     */
    private fun judgeFind(args: List<String>, mode: WorkspaceSdcardMode, base: String): String? {
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

        val root = literalRoot?.let { normalizeTarget(it, base) }
            ?: if (mentionsSdcard(args.joinToString(" "))) WorkspaceSdcard.MOUNT_TARGET else null
            ?: return null
        if (!WorkspaceSdcard.isWithin(root)) return null

        if (mode == WorkspaceSdcardMode.READ_ONLY) {
            return "blocked: the /sdcard mount is configured read-only for this workspace; " +
                "find actions cannot modify shared storage"
        }
        val depth = root.removePrefix(WorkspaceSdcard.MOUNT_TARGET).count { it == '/' }
        return if (depth <= 1) {
            "blocked: find with delete/exec actions rooted at $root would wipe user shared " +
                "storage at the public-directory level"
        } else {
            null
        }
    }

    /** Redirect targets (`>f`, `>>f`, `&>f`, `2>f`) — always a write, for any command. */
    private fun firstSdcardRedirectTarget(segment: String, mode: WorkspaceSdcardMode, base: String): String? {
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
                        // A quoted target reads up to the matching close quote (quote-stripped);
                        // a bare word reads until the next shell delimiter.
                        val target: String
                        val openQuote = segment[j]
                        if (openQuote == '\'' || openQuote == '"') {
                            val close = segment.indexOf(openQuote, j + 1)
                            if (close == -1) {
                                i = n
                                break // unterminated quote: rest of the segment is opaque
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
                            val path = normalizeTarget(target, base)
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
                                return null // single-file RW redirect: approval chain
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

    /**
     * Lexical `.`/`..` reduction. Relative inputs resolve against [base]
     * (the tracked cwd), so `../../sdcard` from /workspace lands in-zone.
     * Dynamic content (`$`, backtick) is unprovable → null (falls to approval).
     */
    private fun normalizeTarget(raw: String, base: String): String? {
        var value = raw.trim()
        if (value.length >= 2) {
            val first = value.first()
            if ((first == '\'' || first == '"') && value.last() == first) {
                value = value.substring(1, value.length - 1)
            }
        }
        if (value.isEmpty() || value.contains('$') || value.contains('`')) return null
        value = value.replace(Regex("//+"), "/")
        if (value.startsWith("~")) return null // $HOME expands to /root inside proot: never in-zone

        val absolute = value.startsWith("/")
        val start = if (absolute) value.substring(1) else "$base/$value"
        val reduced = mutableListOf<String>()
        for (part in start.split('/')) {
            when (part) {
                "", "." -> {}
                // `..` at the root is a no-op ("/.." === "/"); a relative path escaping its
                // base keeps popping into the root — exactly what the rootfs does too
                // (from /workspace, `../../sdcard` IS /sdcard), so it must resolve in-zone.
                ".." -> if (reduced.isNotEmpty()) reduced.removeAt(reduced.lastIndex)
                else -> reduced.add(part)
            }
        }
        return "/" + reduced.joinToString("/")
    }

    /** Literal `cd <dir>` / `pushd <dir>`: returns the new tracked base, or null when not a cd. */
    private fun parseCd(segment: String): String? {
        val tokens = tokenize(segment)
        val leaf = commandLeaf(tokens) ?: return null
        if (leaf !in setOf("cd", "pushd")) return null
        if (tokens.size < 2) return "/" // bare `cd` goes to HOME (/root) — outside any known base
        val target = tokens[1]
        return when {
            target == "-" -> null // previous dir is unknown
            else -> normalizeTarget(target, DEFAULT_BASE) ?: null
        }
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
                    if (i + 1 < text.length && text[i + 1] == ch) i++ // &&, ||, ;;
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
    private fun stripWrappers(segment: String): String? {
        var value = segment.trim()
        while (true) {
            val assignment = Regex("""^[A-Za-z_][A-Za-z0-9_]*=\S*\s+""").find(value)
            if (assignment != null) {
                value = value.removePrefix(assignment.value)
                continue
            }
            val first = value.split(Regex("\\s+")).firstOrNull() ?: break
            val leaf = first.trim('(', ')').substringAfterLast('/').lowercase()
            if (leaf !in WRAPPER_LEAVES) break
            value = value.removePrefix(first).trim()
            // `timeout 10 …` / `env VAR=x …`: drop one value word after these wrappers
            if (leaf == "timeout" || leaf == "env") {
                value = value.replace(Regex("^\\S+\\s+"), "")
            }
        }
        return value.ifBlank { null }
    }

    private val WRAPPER_LEAVES = setOf("sudo", "nohup", "setsid", "exec", "time", "nice", "stdbuf", "timeout", "env")

    /** Quoted payloads after a shell/interpreter `-c` (or a bare `eval '…'`). */
    private enum class PayloadKind { SHELL, CODE }

    private data class QuotedPayload(val text: String, val kind: PayloadKind)

    private val shellPayloadRegex = Regex(
        """\b(?:bash|sh|dash|zsh|ash)\s+(?:-[a-zA-Z]+\s+)*-c\s+(['"])([\s\S]{1,4000}?)\1""",
        IGNORE,
    )

    private val codePayloadRegex = Regex(
        """\b(?:python3?|node)\s+(?:-[a-zA-Z]+\s+)*-c\s+(['"])([\s\S]{1,4000}?)\1""",
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

    /** Cheap textual mention of the mount path — used for pipeline-source and find-root heuristics. */
    private fun mentionsSdcard(text: String): Boolean =
        Regex("""(?:^|[\s"'=(])/(?:sdcard)\b""").containsMatchIn(text)
}