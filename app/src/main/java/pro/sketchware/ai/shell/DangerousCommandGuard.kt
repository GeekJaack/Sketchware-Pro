package pro.sketchware.ai.shell

/**
 * Last line of defence against agent commands that can wipe data outside the project workspace.
 *
 * The Linux userland binds `/storage/emulated/0` read-write, so a stray `rm -rf` there deletes the
 * user's photos and downloads. Commands are NOT sandboxed in general; only a short list of clearly
 * destructive patterns needs an explicit `confirm_destructive=true` override.
 */
object DangerousCommandGuard {

    data class Verdict(val blocked: Boolean, val reason: String = "")

    private val STORAGE_ROOTS = listOf("/storage/emulated/0", "/sdcard", "/storage/self/primary", "/mnt/sdcard")
    private val SAFE_STORAGE_SUBDIR = ".sketchware"

    private val CRITICAL_PATHS = setOf(
        "/", "/*", "/storage", "/storage/*", "/storage/emulated", "/storage/emulated/*",
        "/data", "/data/*", "/system", "/system/*", "/proc", "/dev", "/sys",
        "/etc", "/usr", "/bin", "/sbin", "/lib", "/lib64", "/var", "/home", "/root", "~", "~/", "~/*",
        "\$HOME", "\$HOME/", "\$HOME/*", "\${HOME}", "\${HOME}/", "\${HOME}/*"
    )

    private val SEGMENT_SPLIT = Regex("""[;&|\n]+""")
    private val TOKEN = Regex("\"[^\"]*\"|'[^']*'|\\S+")
    private val WRAPPERS = setOf("sudo", "nohup", "exec", "command", "env", "busybox", "toybox", "time", "nice")

    private val PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("""\bmkfs(\.\w+)?\b""") to "memformat filesystem (mkfs)",
        Regex("""\bdd\b[^\n]*\bof=/dev/""") to "menulis langsung ke perangkat blok (dd of=/dev/...)",
        Regex(""">\s*/dev/(sd[a-z]|mmcblk|block/)""") to "menimpa perangkat blok",
        Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:""") to "fork bomb",
        Regex("""\bchmod\s+(-[A-Za-z]+\s+)*[0-7]{3,4}\s+/(\s|$)""") to "mengubah izin root filesystem",
        Regex("""\bchown\s+-[A-Za-z]*R[A-Za-z]*\s+\S+\s+/(\s|$)""") to "chown rekursif pada root filesystem"
    )

    fun inspect(command: String): Verdict {
        if (command.isBlank()) return Verdict(false)
        for ((regex, why) in PATTERNS) {
            if (regex.containsMatchIn(command)) return Verdict(true, why)
        }
        for (segment in SEGMENT_SPLIT.split(command)) {
            val tokens = TOKEN.findAll(segment).map { unquote(it.value) }.toMutableList()
            while (tokens.isNotEmpty() && (tokens[0] in WRAPPERS || '=' in tokens[0] && !tokens[0].startsWith("-"))) {
                tokens.removeAt(0)
            }
            if (tokens.isEmpty()) continue
            val cmd = tokens[0].substringAfterLast('/')
            val args = tokens.drop(1)
            if (cmd == "rm") checkRm(args)?.let { return Verdict(true, it) }
            if (cmd == "find") checkFind(args)?.let { return Verdict(true, it) }
        }
        return Verdict(false)
    }

    private fun checkRm(args: List<String>): String? {
        val recursive = args.any { it == "--recursive" || (it.startsWith("-") && !it.startsWith("--") && (it.contains('r') || it.contains('R'))) }
        if (!recursive) return null
        for (arg in args) {
            if (arg.startsWith("-") && arg != "-") continue
            dangerousPath(arg)?.let { return "rm rekursif pada '$arg': $it" }
        }
        return null
    }

    private fun checkFind(args: List<String>): String? {
        if (args.none { it == "-delete" } && !(args.contains("-exec") && args.any { it == "rm" })) return null
        val root = args.firstOrNull { !it.startsWith("-") } ?: return null
        return dangerousPath(root)?.let { "find ... -delete/-exec rm pada '$root': $it" }
    }

    /** Reason [rawPath] is too dangerous to delete recursively, or null when it is acceptable. */
    private fun dangerousPath(rawPath: String): String? {
        val path = normalize(rawPath)
        if (path.isEmpty()) return null
        if (path in CRITICAL_PATHS) return "target kritis sistem/penyimpanan"
        for (root in STORAGE_ROOTS) {
            if (path == root || path == "$root/*") return "seluruh penyimpanan pengguna"
            if (path.startsWith("$root/")) {
                val segments = path.removePrefix("$root/").split('/').filter { it.isNotEmpty() }
                if (segments.firstOrNull() != SAFE_STORAGE_SUBDIR) {
                    return "di luar folder .sketchware pada penyimpanan pengguna"
                }
                if (segments.size <= 2) return "folder induk data Sketchware (menghapus semua proyek/library)"
            }
        }
        return null
    }

    private fun normalize(path: String): String {
        var p = path.trim()
        while (p.contains("//")) p = p.replace("//", "/")
        if (p.length > 1 && p.endsWith("/") && p != "~/" && p != "\$HOME/" && p != "\${HOME}/") p = p.trimEnd('/')
        return p
    }

    private fun unquote(token: String): String =
        if (token.length >= 2 && (token.first() == '"' && token.last() == '"' || token.first() == '\'' && token.last() == '\''))
            token.substring(1, token.length - 1) else token
}
