package pro.sketchware.ai.shell

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A long-lived interactive shell process (host, Linux userland via proot, or root) with an output
 * ring buffer. Backed either by a native PTY (preferred) or pipes with an optional `script` PTY.
 * Shared by the agent tools (`terminal_*`) and the on-screen terminal.
 */
class TerminalSession internal constructor(
    val id: String,
    val engine: String,
    private val process: Process?,
    private val stdin: OutputStream?,
    private val masterPfd: ParcelFileDescriptor? = null,
    val childPid: Int = -1
) {
    private val buffer = StringBuilder()
    private var cursor = 0

    @Volatile
    private var closed = false

    @Volatile
    var exitCode: Int? = null
        private set

    @Volatile
    var lastActivity: Long = System.currentTimeMillis()
        private set

    val startedAt: Long = System.currentTimeMillis()

    val isAlive: Boolean
        get() = !closed && (process?.isAlive ?: (exitCode == null))

    internal fun append(text: String) {
        if (text.isEmpty()) return
        synchronized(buffer) {
            buffer.append(text)
            if (buffer.length > MAX_BUFFER) {
                val dropped = buffer.length - MAX_BUFFER
                buffer.delete(0, dropped)
                cursor = (cursor - dropped).coerceAtLeast(0)
            }
        }
        lastActivity = System.currentTimeMillis()
    }

    fun write(input: String) {
        try {
            stdin?.write(input.toByteArray(Charsets.UTF_8))
            stdin?.flush()
            lastActivity = System.currentTimeMillis()
        } catch (_: Throwable) {
        }
    }

    /**
     * Returns output produced since the previous call (using an internal cursor), with ANSI escapes
     * and control characters removed. A trailing, still-incomplete escape sequence is held back
     * until the rest arrives (or the session ends).
     */
    fun readNew(): String {
        synchronized(buffer) {
            if (cursor > buffer.length) cursor = 0
            var end = buffer.length
            if (isAlive) {
                end = cursor + AnsiStripper.incompleteTailStart(buffer.substring(cursor, end))
            }
            val out = buffer.substring(cursor, end)
            cursor = end
            return AnsiStripper.strip(out)
        }
    }

    /** Cleaned, not-yet-read output without advancing the read cursor. */
    fun peekNew(): String = synchronized(buffer) {
        if (cursor > buffer.length) cursor = 0
        AnsiStripper.strip(buffer.substring(cursor))
    }

    /**
     * Blocks until [pattern] matches the unread output, the output has been idle for [idleMs]
     * (when > 0), the process exits, or [maxWaitMs] elapses; then returns [readNew].
     */
    fun readUntil(pattern: Regex?, maxWaitMs: Long, idleMs: Long): String {
        val deadline = System.currentTimeMillis() + maxWaitMs.coerceAtLeast(0L)
        while (true) {
            if (!isAlive) break
            val now = System.currentTimeMillis()
            val pending = peekNew()
            if (pattern != null && pending.isNotEmpty() && pattern.containsMatchIn(pending)) break
            if (idleMs > 0 && pending.isNotEmpty() && now - lastActivity >= idleMs) break
            if (now >= deadline) break
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                break
            }
        }
        return readNew()
    }

    /** Resizes the PTY window (native PTY sessions only). Returns true when applied. */
    fun resize(rows: Int, cols: Int): Boolean {
        val pfd = masterPfd ?: return false
        return try {
            Pty.setWindowSize(pfd.fd, rows.coerceIn(5, 500), cols.coerceIn(20, 500))
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun snapshot(): String = synchronized(buffer) { buffer.toString() }

    internal fun markClosed(code: Int?) {
        closed = true
        exitCode = code
    }

    fun close() {
        closed = true
        if (masterPfd != null) {
            val pid = childPid
            if (pid > 1) {
                Thread { Pty.terminateTree(pid) }.apply {
                    isDaemon = true
                    name = "TerminalKill-$id"
                    start()
                }
            }
            try {
                masterPfd.close()
            } catch (_: Throwable) {
            }
        } else {
            try {
                stdin?.close()
            } catch (_: Throwable) {
            }
            try {
                process?.destroyForcibly()
            } catch (_: Throwable) {
            }
        }
    }

    fun describe(): String {
        val age = (System.currentTimeMillis() - startedAt) / 1000L
        return "$id | engine=$engine | alive=$isAlive | exit=${exitCode ?: "-"} | ${age}s | buffered=${buffer.length}"
    }

    companion object {
        const val MAX_BUFFER = 200_000
    }
}

object TerminalSessionManager {

    const val DEFAULT_ROWS = 24
    const val DEFAULT_COLS = 80

    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    fun list(): List<TerminalSession> = sessions.values.sortedBy { it.startedAt }

    fun get(id: String): TerminalSession? = sessions[id]

    fun stop(id: String): Boolean {
        val session = sessions.remove(id) ?: return false
        session.close()
        return true
    }

    fun stopAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    /**
     * Starts a persistent session.
     * @param engine one of "host", "root", "linux"
     * @param distro required when engine="linux"
     * @param pty when true, prefer a real PTY (native lib, else util-linux `script`)
     */
    fun start(
        context: Context,
        engine: String,
        distro: String?,
        cwd: File?,
        pty: Boolean,
        rows: Int = DEFAULT_ROWS,
        cols: Int = DEFAULT_COLS
    ): TerminalSession {
        val workDir = cwd?.takeIf { it.isDirectory } ?: LinuxSubsystem.baseDir()

        val engineKind = engine.lowercase()
        val resolvedDistro = if (engineKind == "linux") {
            distro?.takeIf { it.isNotBlank() } ?: LinuxSubsystem.defaultDistro()
        } else {
            null
        }
        if (engineKind == "linux" && (resolvedDistro == null || !LinuxSubsystem.isReady(resolvedDistro))) {
            throw IllegalStateException(
                "Linux userland belum siap (distro='${resolvedDistro ?: "?"}'). Jalankan manage_linux_env(action=\"install\")."
            )
        }

        val baseLabel = when (engineKind) {
            "linux" -> "linux:$resolvedDistro"
            "root" -> "root"
            else -> "host"
        }

        // 1) Preferred: native PTY.
        if (pty && Pty.isAvailable) {
            val commandLine = buildCommandLine(engineKind, resolvedDistro, workDir)
            try {
                return startNativePty(commandLine, workDir, "$baseLabel:pty", rows.coerceIn(5, 500), cols.coerceIn(20, 500))
            } catch (_: Throwable) {
                // fall through to pipe/script path
            }
        }

        // 2) Pipe path (optionally wrapped by `script` for a PTY on Linux).
        return startPiped(engineKind, resolvedDistro, workDir, pty, baseLabel)
    }

    private fun buildCommandLine(engineKind: String, distro: String?, workDir: File?): String {
        return when (engineKind) {
            "linux" -> LinuxSubsystem.prootEnvPrefix() + "exec " + LinuxSubsystem.buildProotCommand(
                distro!!, workDir,
                listOf("/bin/sh", "-c", LinuxSubsystem.GUEST_ENV_PREFIX + "exec /bin/sh -i")
            ).joinToString(" ") { shellQuote(it) }
            "root" -> "su"
            else -> "/system/bin/sh -i"
        }
    }

    private fun startNativePty(commandLine: String, workDir: File, engineLabel: String, rows: Int, cols: Int): TerminalSession {
        val pidOut = IntArray(1)
        val fd = Pty.createSubprocess(commandLine, workDir.absolutePath, pidOut, rows, cols)
        if (fd < 0 || pidOut[0] <= 0) {
            throw IllegalStateException("native pty gagal (fd=$fd pid=${pidOut[0]})")
        }
        val pfd = ParcelFileDescriptor.adoptFd(fd)
        val id = "term_" + UUID.randomUUID().toString().take(8)
        val stdin = FileOutputStream(pfd.fileDescriptor)
        val session = TerminalSession(id, engineLabel, null, stdin, pfd, pidOut[0])
        sessions[id] = session

        val reader = Thread {
            try {
                FileInputStream(pfd.fileDescriptor).use { input -> pump(input, session) }
            } catch (_: Throwable) {
            }
        }
        reader.isDaemon = true
        reader.name = "TerminalPty-$id"
        reader.start()

        val waiter = Thread {
            val code = try {
                Pty.waitFor(pidOut[0])
            } catch (_: Throwable) {
                null
            }
            session.markClosed(code)
        }
        waiter.isDaemon = true
        waiter.name = "TerminalWait-$id"
        waiter.start()

        return session
    }

    private fun startPiped(
        engineKind: String,
        resolvedDistro: String?,
        workDir: File,
        pty: Boolean,
        baseLabel: String
    ): TerminalSession {
        val builder: ProcessBuilder
        val engineLabel: String
        when (engineKind) {
            "linux" -> {
                val useScript = pty && LinuxSubsystem.hasScript(resolvedDistro!!)
                val guest = if (useScript) {
                    listOf("/bin/sh", "-c", LinuxSubsystem.GUEST_ENV_PREFIX + "exec /usr/bin/script -q -f /dev/null")
                } else {
                    listOf("/bin/sh", "-c", LinuxSubsystem.GUEST_ENV_PREFIX + "exec /bin/sh -i")
                }
                // Bind + enter the project dir, matching run_shell and the native-PTY path.
                val prootCmd = LinuxSubsystem.buildProotCommand(resolvedDistro!!, workDir, guest)
                    .joinToString(" ") { shellQuote(it) }
                builder = ProcessBuilder(
                    "/system/bin/sh", "-c", LinuxSubsystem.prootEnvPrefix() + "exec " + prootCmd
                )
                builder.directory(LinuxSubsystem.baseDir())
                engineLabel = "$baseLabel" + (if (useScript) ":pty-script" else "")
            }
            "root" -> {
                val su = findSu() ?: throw IllegalStateException("Biner 'su' tidak ditemukan; akses root tidak tersedia.")
                builder = ProcessBuilder(su)
                builder.directory(workDir)
                builder.environment().apply { put("TERM", "xterm-256color"); put("HOME", "/") }
                engineLabel = baseLabel
            }
            else -> {
                builder = ProcessBuilder("/system/bin/sh", "-i")
                builder.directory(workDir)
                builder.environment().apply {
                    put("TERM", "xterm-256color")
                    put("HOME", LinuxSubsystem.baseDir().absolutePath)
                    put("TMPDIR", LinuxSubsystem.tmpDir().absolutePath)
                    put("PS1", "$ ")
                }
                engineLabel = baseLabel
            }
        }

        builder.redirectErrorStream(true)
        val process = builder.start()
        val id = "term_" + UUID.randomUUID().toString().take(8)
        val session = TerminalSession(id, engineLabel, process, process.outputStream)
        sessions[id] = session

        val reader = Thread {
            try {
                process.inputStream.use { input -> pump(input, session) }
            } catch (_: Throwable) {
            } finally {
                val code = try {
                    process.waitFor()
                } catch (_: Throwable) {
                    null
                }
                session.markClosed(code)
            }
        }
        reader.isDaemon = true
        reader.name = "TerminalSession-$id"
        reader.start()

        return session
    }

    private fun pump(input: InputStream, session: TerminalSession) {
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            session.append(String(buffer, 0, read, Charsets.UTF_8))
        }
    }

    private fun findSu(): String? {
        val candidates = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/su/bin/su", "/debug_ramdisk/su", "/data/adb/magisk/su"
        )
        return candidates.firstOrNull { File(it).exists() }
    }

    private fun shellQuote(value: String): String {
        if (value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "/._-+=:@," }) return value
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
