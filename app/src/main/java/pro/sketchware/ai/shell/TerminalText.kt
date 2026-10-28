package pro.sketchware.ai.shell

/**
 * Cleans raw PTY output so the agent receives plain text: ANSI/CSI/OSC escapes, charset
 * switches, backspaces, other control characters and carriage-return progress redraws are
 * removed. Keeps `\n` and `\t`.
 */
object AnsiStripper {

    private val CSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    private val OSC = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
    private val CHARSET = Regex("\u001B[()*+][0-9A-Za-z]")
    private val SIMPLE_ESC = Regex("\u001B[@-Z\\\\^_=>78cDEHMNOZ]")
    private val CONTROL = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]")

    fun strip(text: String): String {
        if (text.isEmpty()) return text
        var t = text.replace("\r\n", "\n")
        t = OSC.replace(t, "")
        t = CSI.replace(t, "")
        t = CHARSET.replace(t, "")
        t = SIMPLE_ESC.replace(t, "")
        // A lone '\r' redraws the current line (progress bars): keep only the final redraw.
        if (t.indexOf('\r') >= 0) {
            t = t.split('\n').joinToString("\n") { line -> line.substringAfterLast('\r') }
        }
        t = t.replace("\u001B", "")
        return CONTROL.replace(t, "")
    }

    /**
     * Index at which an incomplete trailing escape sequence starts, or `text.length` when the
     * text ends cleanly. Lets incremental readers hold the fragment back until it is complete.
     */
    fun incompleteTailStart(text: String): Int {
        val esc = text.lastIndexOf('\u001B')
        if (esc < 0 || text.length - esc > 64) return text.length
        val tail = text.substring(esc)
        if (tail.length == 1) return esc
        // The tail starts at the last ESC, so a complete sequence can only begin at index 0.
        val complete = listOf(CSI, OSC, CHARSET, SIMPLE_ESC).any { it.find(tail)?.range?.first == 0 }
        return if (complete) text.length else esc
    }
}

/** Heuristic detection of an interactive prompt waiting for the agent's input. */
object PromptDetector {

    private val PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("""\[[Yy]/[Nn]\]\s*:?\s*$""") to "menunggu konfirmasi y/n; kirim jawaban lewat terminal_send",
        Regex("""\([Yy](es)?/[Nn](o)?\)\s*\??\s*:?\s*$""") to "menunggu konfirmasi y/n; kirim jawaban lewat terminal_send",
        Regex("""Do you want to continue\?[^\n]*$""") to "menunggu konfirmasi y/n; kirim jawaban lewat terminal_send",
        Regex("""[Pp]assword[^\n]*:\s*$""") to "menunggu password",
        Regex("""[Pp]assphrase[^\n]*:\s*$""") to "menunggu passphrase",
        Regex("""(^|\n)>>> ?$""") to "REPL Python menunggu input",
        Regex("""(^|\n)\.\.\. ?$""") to "REPL menunggu lanjutan baris",
        Regex("""(^|\n)[^\n]{0,80}[#$%>] $""") to "shell kembali ke prompt (perintah sebelumnya kemungkinan sudah selesai)"
    )

    /** A short human-readable hint when [output] ends at a prompt, otherwise null. */
    fun detect(output: String): String? {
        val tail = output.trimEnd('\n', '\r').takeLast(300)
        if (tail.isBlank()) return null
        for ((regex, hint) in PATTERNS) {
            if (regex.containsMatchIn(tail)) return hint
        }
        return null
    }
}
