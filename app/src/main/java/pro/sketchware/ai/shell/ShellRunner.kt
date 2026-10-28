package pro.sketchware.ai.shell

import com.topjohnwu.superuser.Shell
import java.io.File
import java.util.concurrent.TimeUnit

data class ShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean,
    val durationMs: Long,
    val engine: String
)

/**
 * Executes shell commands for the agent across three engines:
 * - host system shell (`/system/bin/sh`, toybox utilities)
 * - Linux userland via `proot` (apt/pip/npm/npx) when a bootstrap is installed
 * - root shell via libsu when explicitly requested
 *
 * No command allowlist is applied by design; execution is gated only by the agent autonomy mode.
 */
object ShellRunner {

    private const val DEFAULT_TIMEOUT_MS = 120_000L
    private const val HARD_TIMEOUT_MS = 900_000L
    private val APT_USAGE = Regex("""(^|[\s;&|(])apt(-get)?\s""")

    /** Runs [command] and returns its result with ANSI escapes / control characters removed. */
    fun run(
        command: String,
        cwd: File?,
        timeoutMs: Long,
        useRoot: Boolean,
        inLinux: Boolean,
        distro: String?,
        pty: Boolean = false
    ): ShellResult {
        val raw = runRaw(command, cwd, timeoutMs, useRoot, inLinux, distro, pty)
        return raw.copy(stdout = AnsiStripper.strip(raw.stdout), stderr = AnsiStripper.strip(raw.stderr))
    }

    private fun runRaw(
        command: String,
        cwd: File?,
        timeoutMs: Long,
        useRoot: Boolean,
        inLinux: Boolean,
        distro: String?,
        pty: Boolean
    ): ShellResult {
        val start = System.currentTimeMillis()
        if (inLinux) {
            LinuxSubsystem.ensureAssetsExtractedIfNeeded()
            val requested = distro?.takeIf { it.isNotBlank() }
            if (requested != null && !LinuxSubsystem.isReady(requested)) {
                val ctx = pro.sketchware.SketchApplication.getContext()
                if (ctx != null && requested.lowercase() in setOf("debian", "ubuntu", "alpine")) {
                    try {
                        LinuxDownloader.bootstrapToolchain(ctx, requested.lowercase()) { }
                    } catch (t: Throwable) {
                        LinuxSubsystem.recordError("bootstrap distro '$requested' gagal", t)
                    }
                }
            }
            var resolved = requested ?: LinuxSubsystem.defaultDistro()
            var note = ""
            if (requested != null && !LinuxSubsystem.isReady(requested)) {
                val fallback = LinuxSubsystem.defaultDistro()
                if (fallback != null && !fallback.equals(requested, ignoreCase = true) && LinuxSubsystem.isReady(fallback)) {
                    note = "[fallback] distro '$requested' belum tersedia" +
                        (LinuxSubsystem.lastError()?.let { " ($it)" } ?: " (unduhan gagal atau perangkat offline)") +
                        "; perintah dijalankan di '$fallback'. Gunakan 'apk' untuk alpine, 'apt-get' untuk debian/ubuntu.\n"
                    resolved = fallback
                }
            }
            if (resolved != null && resolved.equals("alpine", ignoreCase = true) && APT_USAGE.containsMatchIn(command)) {
                note += "[hint] distro aktif 'alpine' tidak punya apt; pakai 'apk add <paket>' atau pasang Debian via " +
                    "manage_linux_env(action=\"bootstrap_debian\") saat online.\n"
            }
            if (resolved == null || !LinuxSubsystem.isReady(resolved)) {
                val installed = LinuxSubsystem.installedDistros()
                return ShellResult(
                    stdout = "",
                    stderr = "Linux userland belum siap untuk distro='${resolved ?: "(belum ada)"}'. " +
                        "Terpasang: ${installed.joinToString(", ").ifEmpty { "(kosong)" }}. " +
                        "Jalankan manage_linux_env(action=\"bootstrap_debian\") untuk mengunduh Debian, " +
                        "atau pakai in_linux=false untuk system shell.",
                    exitCode = 127,
                    timedOut = false,
                    durationMs = 0,
                    engine = "linux-unavailable"
                )
            }
            val result = runLinux(command, cwd, timeoutMs, useRoot, start, resolved, pty)
            return if (note.isEmpty()) result else result.copy(stdout = note + result.stdout)
        }
        return if (useRoot) runRoot(command, start) else runHost(command, cwd, timeoutMs, start)
    }

    private fun runHost(command: String, cwd: File?, timeoutMs: Long, start: Long): ShellResult {
        val pb = ProcessBuilder("/system/bin/sh", "-c", command)
        pb.directory(cwd?.takeIf { it.isDirectory } ?: LinuxSubsystem.baseDir())
        pb.environment().apply {
            put("TMPDIR", LinuxSubsystem.tmpDir().absolutePath)
            put("HOME", LinuxSubsystem.baseDir().absolutePath)
        }
        return execProcess(pb, normalizeTimeout(timeoutMs), start, "host-shell")
    }

    private fun runLinux(
        command: String,
        cwd: File?,
        timeoutMs: Long,
        useRoot: Boolean,
        start: Long,
        distro: String,
        pty: Boolean
    ): ShellResult {
        val usePty = pty && LinuxSubsystem.hasScript(distro)
        val script = LinuxSubsystem.GUEST_ENV_PREFIX + command
        val guest = if (usePty) {
            listOf("/usr/bin/script", "-q", "-f", "-c", script, "/dev/null")
        } else {
            listOf("/bin/sh", "-c", script)
        }
        val args = LinuxSubsystem.buildProotCommand(distro, cwd, guest)
        val engine = "linux-proot:$distro" + (if (usePty) ":pty" else "")
        val wrapper = LinuxSubsystem.prootEnvPrefix() + "exec " + args.joinToString(" ") { shellQuote(it) }

        if (useRoot) {
            return runRoot(wrapper, start)
        }
        // Prefer the native PTY (no `script` needed; works on distros without util-linux).
        if (Pty.isAvailable) {
            runNativePty(wrapper, normalizeTimeout(timeoutMs), start, engine + ":native")?.let { return it }
        }
        val pb = ProcessBuilder("/system/bin/sh", "-c", wrapper)
        pb.directory(LinuxSubsystem.baseDir())
        return execProcess(pb, normalizeTimeout(timeoutMs), start, engine)
    }

    /**
     * Runs [wrapper] inside a native PTY and collects its output until exit or timeout.
     * Returns null when the PTY could not be allocated so the caller can fall back to pipes.
     */
    private fun runNativePty(wrapper: String, timeoutMs: Long, start: Long, engine: String): ShellResult? {
        val pidOut = IntArray(1)
        val fd = try {
            Pty.createSubprocess(wrapper, LinuxSubsystem.baseDir().absolutePath, pidOut, 24, 200)
        } catch (_: Throwable) {
            return null
        }
        if (fd < 0 || pidOut[0] <= 0) return null

        val out = StringBuilder()
        val pfd = android.os.ParcelFileDescriptor.adoptFd(fd)
        val reading = Thread {
            try {
                java.io.InputStreamReader(java.io.FileInputStream(pfd.fileDescriptor), Charsets.UTF_8).use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(out) { out.append(buf, 0, n) }
                    }
                }
            } catch (_: Throwable) {
            }
        }.apply { isDaemon = true; start() }

        var code: Int? = null
        val waiter = Thread { code = try { Pty.waitFor(pidOut[0]) } catch (_: Throwable) { null } }
            .apply { isDaemon = true; start() }
        waiter.join(timeoutMs)
        val timedOut = waiter.isAlive
        if (timedOut) {
            // Terminate the whole process group so proot tracees, apt, npm... do not linger as orphans.
            Pty.terminateTree(pidOut[0])
        }
        waiter.join(2_000)
        reading.join(1_000)
        try { pfd.close() } catch (_: Throwable) {}

        val text = synchronized(out) { out.toString() }
            .replace("\r\n", "\n").replace("\r", "")
        return ShellResult(
            stdout = text,
            stderr = if (timedOut) "\n[timeout setelah ${timeoutMs}ms — proses dihentikan]" else "",
            exitCode = if (timedOut) -1 else (code ?: -1),
            timedOut = timedOut,
            durationMs = System.currentTimeMillis() - start,
            engine = engine
        )
    }

    private fun runRoot(command: String, start: Long): ShellResult {
        return try {
            val out = mutableListOf<String>()
            val err = mutableListOf<String>()
            val result = Shell.cmd(command).to(out, err).exec()
            ShellResult(
                stdout = out.joinToString("\n"),
                stderr = err.joinToString("\n"),
                exitCode = result.code,
                timedOut = false,
                durationMs = System.currentTimeMillis() - start,
                engine = "root-shell"
            )
        } catch (t: Throwable) {
            ShellResult(
                stdout = "",
                stderr = "Root shell gagal: ${t.javaClass.simpleName}: ${t.message}",
                exitCode = 127,
                timedOut = false,
                durationMs = System.currentTimeMillis() - start,
                engine = "root-shell"
            )
        }
    }

    private fun execProcess(pb: ProcessBuilder, timeoutMs: Long, start: Long, engine: String): ShellResult {
        return try {
            val process = pb.start()
            val outBuf = StringBuilder()
            val errBuf = StringBuilder()
            val tOut = Thread { drain(process.inputStream, outBuf) }.apply { isDaemon = true; start() }
            val tErr = Thread { drain(process.errorStream, errBuf) }.apply { isDaemon = true; start() }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                tOut.join(500)
                tErr.join(500)
                ShellResult(
                    stdout = outBuf.toString(),
                    stderr = errBuf.toString() + "\n[timeout setelah ${timeoutMs}ms — proses dihentikan]",
                    exitCode = -1,
                    timedOut = true,
                    durationMs = System.currentTimeMillis() - start,
                    engine = engine
                )
            } else {
                tOut.join(1000)
                tErr.join(1000)
                ShellResult(
                    stdout = outBuf.toString(),
                    stderr = errBuf.toString(),
                    exitCode = process.exitValue(),
                    timedOut = false,
                    durationMs = System.currentTimeMillis() - start,
                    engine = engine
                )
            }
        } catch (t: Throwable) {
            ShellResult(
                stdout = "",
                stderr = "Gagal menjalankan proses: ${t.javaClass.simpleName}: ${t.message}",
                exitCode = 127,
                timedOut = false,
                durationMs = System.currentTimeMillis() - start,
                engine = engine
            )
        }
    }

    private fun drain(input: java.io.InputStream, sink: StringBuilder) {
        try {
            input.bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    synchronized(sink) { sink.append(buffer, 0, read) }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun normalizeTimeout(timeoutMs: Long): Long {
        val value = if (timeoutMs <= 0L) DEFAULT_TIMEOUT_MS else timeoutMs
        return value.coerceIn(1_000L, HARD_TIMEOUT_MS)
    }

    private fun shellQuote(value: String): String {
        if (value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "/._-+=:@," }) return value
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
