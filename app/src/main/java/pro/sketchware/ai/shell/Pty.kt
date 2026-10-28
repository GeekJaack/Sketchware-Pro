package pro.sketchware.ai.shell

/**
 * JNI bridge to a hand-rolled pseudo-terminal allocator (`/dev/ptmx` + fork + TIOCSCTTY),
 * since Android's bionic does not provide `openpty`/`forkpty`. When the native library is
 * unavailable (e.g. non-arm64 ABI), callers fall back to the `script`-based PTY or plain pipes.
 */
object Pty {

    @Volatile
    private var loaded = false

    init {
        try {
            System.loadLibrary("pty")
            loaded = true
        } catch (_: Throwable) {
            loaded = false
        }
    }

    val isAvailable: Boolean
        get() = loaded

    /**
     * Allocates a PTY and runs [command] via `/system/bin/sh -c` in it.
     * @return the master fd (>= 0) or -1 on failure; the child pid is written to pidOut[0].
     */
    external fun createSubprocess(command: String, cwd: String?, pidOut: IntArray, rows: Int, cols: Int): Int

    external fun setWindowSize(fd: Int, rows: Int, cols: Int)

    external fun waitFor(pid: Int): Int

    external fun closeFd(fd: Int)

    /** Sends [signal] to the whole process group led by [pid] (see pty.c). Returns 0 on success. */
    external fun killProcessGroup(pid: Int, signal: Int): Int

    /**
     * Graceful-then-forceful termination of a PTY child and all of its descendants:
     * SIGTERM to the group, a short grace period, then SIGKILL to anything still alive.
     * Never throws; falls back to [android.os.Process.sendSignal] when the native call is missing.
     */
    fun terminateTree(pid: Int, graceMs: Long = 1_500L) {
        if (pid <= 1) return
        signalTree(pid, 15)
        try {
            Thread.sleep(graceMs)
        } catch (_: InterruptedException) {
        }
        signalTree(pid, 9)
    }

    private fun signalTree(pid: Int, signal: Int) {
        try {
            killProcessGroup(pid, signal)
        } catch (_: Throwable) {
            try {
                android.os.Process.sendSignal(pid, signal)
            } catch (_: Throwable) {
            }
        }
    }
}
