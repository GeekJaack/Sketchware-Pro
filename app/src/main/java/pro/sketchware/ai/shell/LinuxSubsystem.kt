package pro.sketchware.ai.shell

import android.content.Context
import android.os.Build
import android.os.Environment
import pro.sketchware.SketchApplication
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Manages the optional Linux userland (proot + one or more rootfs distros) that lets the agent use
 * a real shell with `apt`/`apk`/`pip`/`npm`/`npx`. Everything lives in the app's private files
 * directory so binaries stay executable (the app targets SDK 28) and out of reach of other apps.
 *
 * Multiple distros are supported, each under `files/linux/rootfs/<distro>`; [defaultDistro] selects
 * the one used by `run_shell` unless the caller overrides it. The native `proot` binary and rootfs
 * bootstraps are provided via `assets/linux/` or any path through [installArchive].
 */
object LinuxSubsystem {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var legacyChecked = false

    @Volatile
    private var autoInstallAttempted = false

    @Volatile
    private var autoInstallFailures = 0

    @Volatile
    private var lastAutoInstallAt = 0L

    @Volatile
    private var lastErrorMessage: String? = null

    private const val MAX_AUTO_INSTALL_FAILURES = 3
    private const val AUTO_INSTALL_RETRY_DELAY_MS = 30_000L

    /** Most recent setup/bootstrap failure (shown by [statusReport]), or null. */
    fun lastError(): String? = lastErrorMessage

    fun recordError(what: String, t: Throwable?, detail: String? = null) {
        val cause = t?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        val extra = detail?.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""
        lastErrorMessage = what + cause + extra
    }

    fun clearError() {
        lastErrorMessage = null
    }

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun ctx(): Context? = appContext ?: SketchApplication.getContext()

    /**
     * Automatically extracts the bundled `proot` + `alpine` userland from APK assets on first use
     * so fresh installs on any user's phone work out-of-the-box with zero manual setup.
     */
    @Synchronized
    fun ensureAssetsExtractedIfNeeded() {
        if (autoInstallAttempted) return
        migrateLegacyFlatRootfs()
        if (isProotInstalled() && installedDistros().isNotEmpty()) {
            autoInstallAttempted = true
            return
        }
        if (ctx() == null) return
        if (autoInstallFailures >= MAX_AUTO_INSTALL_FAILURES) return
        val now = System.currentTimeMillis()
        if (autoInstallFailures > 0 && now - lastAutoInstallAt < AUTO_INSTALL_RETRY_DELAY_MS) return
        lastAutoInstallAt = now
        try {
            val report = installFromAssets()
            if (isProotInstalled() && installedDistros().isNotEmpty()) {
                autoInstallAttempted = true
                clearError()
            } else {
                autoInstallFailures++
                recordError("auto-install dari assets tidak menghasilkan userland siap", null, report.take(300))
            }
        } catch (t: Throwable) {
            autoInstallFailures++
            recordError("auto-install dari assets gagal", t)
        }
    }

    /**
     * Seeds `/root/.gitconfig` with `safe.directory = *`: project folders are bind-mounted from
     * Android storage, whose ownership never matches the proot user, so git would otherwise refuse
     * with "dubious ownership".
     */
    fun ensureGitConfig(distro: String) {
        val root = rootfsDir(distro)
        if (!root.isDirectory) return
        try {
            val home = File(root, "root").apply { mkdirs() }
            val cfg = File(home, ".gitconfig")
            val current = if (cfg.isFile) cfg.readText() else ""
            if (Regex("""directory\s*=\s*\*""").containsMatchIn(current)) return
            val prefix = if (current.isNotEmpty() && !current.endsWith("\n")) "\n" else ""
            cfg.appendText(prefix + "[safe]\n\tdirectory = *\n")
        } catch (_: Throwable) {
        }
    }

    fun baseDir(): File = File(ctx()?.filesDir ?: fallbackRoot(), "linux").apply { mkdirs() }
    fun rootfsBase(): File = File(baseDir(), "rootfs").apply { mkdirs() }
    fun rootfsDir(distro: String): File = File(rootfsBase(), distro)
    fun prootBinary(): File = File(baseDir(), "proot")
    fun loaderBinary(): File {
        val primary = File(baseDir(), "libproot-loader.so")
        if (primary.isFile && primary.length() > 0L) return primary
        val fallback = File(baseDir(), "proot-loader")
        return if (fallback.isFile && fallback.length() > 0L) fallback else primary
    }
    fun stagingDir(): File = File(baseDir(), "staging")

    fun tmpDir(): File = File(baseDir(), "tmp").apply { mkdirs() }

    private fun configFile(): File = File(baseDir(), "default_distro")
    private fun fallbackRoot(): File = File(Environment.getExternalStorageDirectory(), ".sketchware")

    private fun supportedAbis(): Array<String> = (Build.SUPPORTED_ABIS as Array<String>?) ?: emptyArray()

    fun abi(): String = supportedAbis().firstOrNull() ?: "unknown"

    fun isProotInstalled(): Boolean = prootBinary().let { it.isFile && it.length() > 0L }

    fun isReady(): Boolean {
        ensureAssetsExtractedIfNeeded()
        return isProotInstalled() && installedDistros().isNotEmpty()
    }

    fun isReady(distro: String): Boolean {
        ensureAssetsExtractedIfNeeded()
        return isProotInstalled() && looksLikeRootfs(rootfsDir(distro))
    }

    /**
     * Ensures the distro has `/etc/resolv.conf` so package managers (apk/apt) and any network tool
     * can resolve hostnames. proot does not provide one, and Android has no `/etc/resolv.conf`,
     * so we seed it with the device's DNS (when readable) or well-known public resolvers.
     */
    fun ensureResolvConf(distro: String) {
        val root = rootfsDir(distro)
        if (!looksLikeRootfs(root)) return
        val etc = File(root, "etc").apply { mkdirs() }
        val resolv = File(etc, "resolv.conf")
        if (resolv.exists() && resolv.length() > 0L) return
        val servers = mutableListOf<String>()
        for (prop in listOf("net.dns1", "net.dns2", "net.dns3", "ro.net.dns1")) {
            val value = readSystemProperty(prop)
            if (!value.isNullOrBlank() && value.count { it == '.' } == 3) servers.add(value)
        }
        if (servers.isEmpty()) servers.addAll(listOf("8.8.8.8", "8.8.4.4", "1.1.1.1"))
        try {
            resolv.writeText(servers.distinct().joinToString("\n") { "nameserver $it" } + "\n")
        } catch (_: Throwable) {
        }
    }

    /**
     * Ensures Debian/Ubuntu rootfs has `/etc/apt/apt.conf.d/99sketchware` configured for proot on
     * Android: forces IPv4 (avoids 30s IPv6 timeouts on mobile/Wi-Fi networks), runs apt sandbox
     * as root (avoids setgroups/setuid errors inside proot), and enables non-interactive dpkg defaults.
     */
    fun ensureAptConfig(distro: String) {
        val root = rootfsDir(distro)
        val aptDir = File(root, "etc/apt")
        if (!aptDir.isDirectory) return
        val confDir = File(aptDir, "apt.conf.d").apply { mkdirs() }
        val confFile = File(confDir, "99sketchware")
        if (confFile.exists() && confFile.length() > 0L) return
        try {
            confFile.writeText(
                """
                Acquire::ForceIPv4 "true";
                APT::Sandbox::User "root";
                Acquire::Retries "3";
                DPkg::Options { "--force-confdef"; "--force-confold"; };
                APT::Install-Recommends "false";
                """.trimIndent() + "\n"
            )
        } catch (_: Throwable) {
        }
    }

    private fun readSystemProperty(name: String): String? = try {
        val process = ProcessBuilder("getprop", name).start()
        process.inputStream.bufferedReader().readText().trim().ifEmpty { null }
    } catch (_: Throwable) {
        null
    }

    /** True when the distro ships util-linux `script`, which lets commands run under a real PTY. */
    fun hasScript(distro: String): Boolean {
        val root = rootfsDir(distro)
        return File(root, "usr/bin/script").exists() || File(root, "bin/script").exists()
    }

    /**
     * Builds the full `proot` argv for [distro], binding device/storage and the optional [cwd],
     * then exec'ing [guestArgs] inside a clean guest environment (HOME/PATH/TERM).
     */
    fun buildProotCommand(distro: String, cwd: File?, guestArgs: List<String>): List<String> {
        ensureResolvConf(distro)
        ensureAptConfig(distro)
        ensureGitConfig(distro)
        val root = rootfsDir(distro)
        val guestTmp = File(root, "tmp").apply { mkdirs() }
        val isAlpine = distro.equals("alpine", ignoreCase = true)

        val args = mutableListOf<String>()
        args.add(prootBinary().absolutePath)
        args.add("--link2symlink")
        args.add("-r"); args.add(root.absolutePath)
        // Alpine's apk fails to finalize its database under proot's fake-id0 extension, while
        // Debian/Ubuntu dpkg explicitly requires superuser. Use fake root only where it is needed.
        if (!isAlpine) {
            args.add("-0")
        }
        args.add("-w"); args.add("/root")
        // /dev is always bound for /dev/null, /dev/urandom, and /dev/pts.
        // For glibc distros (Debian/Ubuntu), /proc and /sys MUST be bound so glibc's
        // posix_spawn(..., POSIX_SPAWN_CLOSEFROM) can read /proc/self/fd on Android kernels < 5.9
        // where close_range() returns ENOSYS (needed by Python subprocess/pip, apt postinst, git, node).
        val binds = if (isAlpine) listOf("/dev") else listOf("/dev", "/proc", "/sys")
        for (bind in binds) {
            if (File(bind).exists()) {
                args.add("-b"); args.add(bind)
            }
        }
        if (!isAlpine) {
            args.add("-b"); args.add("${guestTmp.absolutePath}:/dev/shm")
        }
        // Bind both Android app data path aliases so proot --link2symlink (.l2s.*) targets resolve
        // regardless of whether /data/user/0/... or /data/data/... was used when creating links.
        for (aliasPath in listOf("/data/user/0/pro.sketchware", "/data/data/pro.sketchware")) {
            if (File(aliasPath).exists()) {
                args.add("-b"); args.add(aliasPath)
            }
        }
        val storage = File("/storage/emulated/0")
        if (storage.exists()) { args.add("-b"); args.add(storage.absolutePath) }
        val boundCwd = cwd?.takeIf { it.isDirectory }
        if (boundCwd != null) {
            args.add("-b"); args.add(boundCwd.absolutePath)
            args.add("-w"); args.add(boundCwd.absolutePath)
        }
        args.addAll(guestArgs)
        return args
    }

    /**
     * Exported inside the guest shell. We set the environment with `export` rather than
     * `/usr/bin/env -i`, because busybox `env -i` was observed to fail under proot on-device.
     */
    const val GUEST_ENV_PREFIX =
        // Only directories that exist in a typical rootfs: a missing PATH entry makes busybox hit a
        // blocked syscall (SIGSYS) during command lookup, so /usr/local/sbin is intentionally omitted.
        "export HOME=/root; export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin; " +
            "export TERM=xterm-256color; export LANG=C.UTF-8; export SHELL=/bin/sh; " +
            "export DEBIAN_FRONTEND=noninteractive; export PYTHONIOENCODING=utf-8; " +
            "export PIP_BREAK_SYSTEM_PACKAGES=1; "

    /**
     * Shell prefix that exports the environment proot needs (temp dir for its loader, seccomp off).
     * Applied inside a `sh -c "…; exec proot …"` wrapper so it works for direct ProcessBuilder
     * runs, native-PTY forks, and root shells alike — `ProcessBuilder.environment()` alone was
     * not propagated reliably on-device.
     */
    fun prootEnvPrefix(): String {
        val sb = StringBuilder()
        sb.append("export PROOT_TMP_DIR='").append(freshProotTmpDir().absolutePath).append("'; ")
        sb.append("export PROOT_NO_SECCOMP=1; ")
        // Only export a loader that really exists and is executable. A stale or zero-byte path makes
        // proot fail every execve with "loader not found", so it must not be exported in that case.
        if (loaderBinary().isFile && loaderBinary().length() > 0L && loaderBinary().canExecute()) {
            sb.append("export PROOT_LOADER='").append(loaderBinary().absolutePath).append("'; ")
        }
        return sb.toString()
    }

    /**
     * proot requires PROOT_TMP_DIR to exist and be free of stale artifacts (its "glue rootfs");
     * reusing a populated dir made invocations fail intermittently on-device. Each run gets a fresh
     * directory, and older ones are pruned symlink-safely.
     */
    private fun freshProotTmpDir(): File {
        val root = File(baseDir(), "proot-tmp").apply { mkdirs() }
        try {
            root.listFiles()?.sortedBy { it.name }?.dropLast(8)?.forEach { safeDelete(it) }
        } catch (_: Throwable) {
        }
        val dir = File(root, "run-${System.currentTimeMillis()}-${(Math.random() * 1_000_000).toInt()}")
        dir.mkdirs()
        return dir
    }

    fun installedDistros(): List<String> {
        migrateLegacyFlatRootfs()
        return rootfsBase().listFiles()
            ?.filter { it.isDirectory && looksLikeRootfs(it) }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    fun defaultDistro(): String? {
        ensureAssetsExtractedIfNeeded()
        val names = installedDistros()
        if (names.isEmpty()) return null
        val configured = try {
            configFile().takeIf { it.isFile }?.readText()?.trim()
        } catch (_: Throwable) {
            null
        }
        if (!configured.isNullOrEmpty() && names.contains(configured)) return configured
        if (names.contains("debian")) return "debian"
        if (names.contains("ubuntu")) return "ubuntu"
        if (names.contains("alpine")) return "alpine"
        return names.first()
    }

    fun setDefaultDistro(distro: String): Boolean {
        if (!isReady(distro)) return false
        return try {
            configFile().writeText(distro)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun statusReport(): String {
        ensureAssetsExtractedIfNeeded()
        val sb = StringBuilder()
        sb.appendLine("=== LINUX SUBSYSTEM ===")
        sb.appendLine("ABI          : ${abi()}")
        sb.appendLine("Base dir     : ${baseDir().absolutePath}")
        sb.appendLine("proot        : ${if (isProotInstalled()) "TERPASANG" else "BELUM"}")
        sb.appendLine("loader       : ${if (loaderBinary().exists()) "ada" else "tidak ada (opsional)"}")
        lastErrorMessage?.let { sb.appendLine("Error terakhir: $it") }

        val distros = installedDistros()
        if (distros.isEmpty()) {
            sb.appendLine("distros      : BELUM ADA")
        } else {
            sb.appendLine("distros      : ${distros.joinToString(", ")}")
            sb.appendLine("default      : ${defaultDistro()}")
            for (d in distros) {
                val tools = detectToolchains(rootfsDir(d))
                sb.appendLine("  - $d: ${if (tools.isEmpty()) "(belum ada paket toolchain)" else tools.joinToString(", ")}")
            }
        }

        sb.appendLine("Status       : ${if (isReady()) "READY" else "PERLU SETUP"}")
        if (!isReady() || distros.isEmpty()) {
            sb.appendLine()
            sb.appendLine("Untuk mengaktifkan:")
            sb.appendLine("  1) Letakkan berkas proot + bootstrap rootfs ke assets/linux/ (atau path mana pun).")
            sb.appendLine("  2) Jalankan manage_linux_env(action=\"install\"), atau sertakan source_path=\"<file>\".")
            sb.appendLine("  Catatan: system shell (toybox) sudah berfungsi tanpa langkah ini.")
        } else {
            sb.appendLine()
            sb.appendLine("Pakai run_shell(command=\"...\", in_linux=true, linux_distro=\"${defaultDistro()}\").")
            sb.appendLine("Alpine → 'apk add ...'   |   Ubuntu → 'apt-get update && apt-get install -y ...'")
        }
        return sb.toString().trim()
    }

    private fun detectToolchains(root: File): List<String> {
        val dirs = listOf(File(root, "usr/bin"), File(root, "usr/local/bin"), File(root, "bin"), File(root, "sbin"))
        val wanted = listOf(
            "apk", "apt", "apt-get", "dpkg", "pkg", "pip", "pip3", "python", "python3",
            "node", "npm", "npx", "git", "yarn", "pnpm", "busybox", "curl", "wget"
        )
        val found = mutableListOf<String>()
        for (d in dirs) {
            if (!d.isDirectory) continue
            for (w in wanted) {
                if (File(d, w).exists() && !found.contains(w)) found.add(w)
            }
        }
        return found
    }

    fun listUserlandBinaries(distro: String?): String {
        val target = distro ?: defaultDistro()
            ?: return "Belum ada distro terpasang. Jalankan manage_linux_env(action=\"status\")."
        if (!looksLikeRootfs(rootfsDir(target))) {
            return "Distro '$target' tidak ditemukan. Terpasang: ${installedDistros().joinToString(", ").ifEmpty { "(kosong)" }}"
        }
        val dirs = listOf(File(rootfsDir(target), "usr/bin"), File(rootfsDir(target), "usr/local/bin"), File(rootfsDir(target), "bin"))
        val sb = StringBuilder("=== BINER USERLAND: $target ===\n")
        for (d in dirs) {
            if (!d.isDirectory) continue
            val names = d.list()?.sorted() ?: emptyList()
            sb.appendLine("${d.absolutePath} (${names.size} berkas): ${names.take(200).joinToString(", ")}")
        }
        return sb.toString().trim()
    }

    fun installFromAssets(): String {
        val context = ctx() ?: return "Error: ApplicationContext tidak tersedia."
        val listing = try {
            context.assets.list("linux")?.toList().orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
        if (listing.isEmpty()) {
            return "Tidak ada berkas di assets/linux/. Letakkan berkas proot & bootstrap rootfs di sana lebih dulu."
        }
        val sb = StringBuilder()
        for (name in listing.sorted()) {
            val lower = name.lowercase()
            if (!assetMatchesDevice(lower)) {
                sb.appendLine("Dilewati (ABI lain, perangkat=${deviceArchTag()}): $name")
                continue
            }
            try {
                when {
                    isArchiveName(lower) -> {
                        val tmp = File(tmpDir(), name)
                        context.assets.open("linux/$name").use { input ->
                            tmp.outputStream().use { input.copyTo(it) }
                        }
                        sb.appendLine("--- $name ---")
                        sb.appendLine(installArchive(tmp))
                        tmp.delete()
                    }
                    lower.startsWith("proot") -> {
                        context.assets.open("linux/$name").use { input ->
                            prootBinary().outputStream().use { input.copyTo(it) }
                        }
                        prootBinary().setExecutable(true, false)
                        sb.appendLine("proot dipasang dari assets: $name")
                    }
                    lower.startsWith("libproot-loader") -> {
                        context.assets.open("linux/$name").use { input ->
                            loaderBinary().outputStream().use { input.copyTo(it) }
                        }
                        sb.appendLine("proot loader dipasang dari assets: $name")
                    }
                    else -> sb.appendLine("Dilewati (bukan arsip/proot): $name")
                }
            } catch (t: Throwable) {
                sb.appendLine("Gagal memasang $name: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        sb.appendLine()
        sb.appendLine(statusReport())
        return sb.toString().trim()
    }

    private fun isArchiveName(lower: String): Boolean =
        lower.endsWith(".zip") || lower.endsWith(".tar") ||
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ||
            lower.endsWith(".tar.xz") || lower.endsWith(".txz")

    /** True for a bare ELF executable (e.g. the static `proot` published by proot-me). */
    private fun isElfBinary(file: File): Boolean = try {
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && magic[0] == 0x7f.toByte() &&
                magic[1] == 'E'.code.toByte() && magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
        }
    } catch (_: Throwable) {
        false
    }

    private fun installRawProot(binary: File): String {
        binary.copyTo(prootBinary(), overwrite = true)
        if (!prootBinary().setExecutable(true, false)) {
            return "Error: proot disalin tetapi gagal diberi izin eksekusi: ${prootBinary().absolutePath}"
        }
        return "proot dipasang: ${prootBinary().absolutePath}\n\n" + statusReport()
    }

    /** Skips assets whose name declares a different architecture than the device. */
    private fun assetMatchesDevice(name: String): Boolean {
        val arch = archTagOf(name) ?: return true
        return arch == deviceArchTag()
    }

    fun deviceArchTag(): String {
        val abi = supportedAbis().firstOrNull()?.lowercase() ?: "arm64-v8a"
        return when {
            abi.contains("arm64") -> "arm64"
            abi.contains("armeabi") || abi.contains("armv7") -> "arm"
            abi.contains("x86_64") -> "x86_64"
            abi.contains("x86") -> "x86"
            else -> "arm64"
        }
    }

    private fun archTagOf(name: String): String? = when {
        Regex("aarch64|arm64").containsMatchIn(name) -> "arm64"
        Regex("armv7|armhf|armeabi").containsMatchIn(name) -> "arm"
        Regex("x86_64|x86-64|amd64").containsMatchIn(name) -> "x86_64"
        Regex("x86|i686|i386").containsMatchIn(name) -> "x86"
        else -> null
    }

    /**
     * Installs a bootstrap archive. A `proot` binary inside is installed as the launcher; each rootfs
     * tree found is installed as its own distro (name from `/etc/os-release`, bundle folder, or file).
     */
    fun installArchive(archive: File): String {
        if (!archive.exists() || !archive.isFile) {
            return "Error: arsip tidak ditemukan: ${archive.absolutePath}"
        }
        // Official proot releases are bare static binaries; install them directly as the launcher.
        if (!isArchiveName(archive.name.lowercase()) && isElfBinary(archive)) {
            return installRawProot(archive)
        }
        val staging = stagingDir()
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            ArchiveExtractor.extract(archive, staging)
        } catch (t: Throwable) {
            return "Error: gagal mengekstrak ${archive.name}: ${t.javaClass.simpleName}: ${t.message}"
        }

        val report = StringBuilder()
        try {
            val stagedProot = File(staging, "proot").takeIf { it.isFile }
                ?: File(staging, "proot/proot").takeIf { it.isFile }
            if (stagedProot != null) {
                stagedProot.copyTo(prootBinary(), overwrite = true)
                prootBinary().setExecutable(true, false)
                report.appendLine("proot dipasang: ${prootBinary().absolutePath}")
            }

            val stagedLoader = File(staging, "libproot-loader.so").takeIf { it.isFile }
                ?: File(staging, "proot/libproot-loader.so").takeIf { it.isFile }
            if (stagedLoader != null) {
                stagedLoader.copyTo(loaderBinary(), overwrite = true)
                report.appendLine("proot loader dipasang: ${loaderBinary().absolutePath}")
            }

            val distroRoots = resolveDistroRoots(staging, archive.name)
            for (dr in distroRoots) {
                val target = rootfsDir(dr.name).apply { mkdirs() }
                // Merge instead of replacing the whole rootfs, so packages installed by the
                // user/agent (node, npm, curl, etc.) survive later install/upgrade operations.
                dr.dir.listFiles()?.forEach { src -> safeCopy(src, File(target, src.name)) }
                applySymlinkManifest(staging, target)
                chmodExecutable(target)
                ensureResolvConf(dr.name)
                ensureAptConfig(dr.name)
                report.appendLine("distro '${dr.name}' dipasang ke: ${target.absolutePath}")
            }

            if (distroRoots.isEmpty() && stagedProot == null && stagedLoader == null) {
                report.appendLine("Peringatan: arsip tidak memuat proot maupun rootfs yang dikenali.")
            }

            val preferredInstalled = distroRoots.firstOrNull { it.name in listOf("debian", "ubuntu") }
            if (preferredInstalled != null) {
                setDefaultDistro(preferredInstalled.name)
            } else if (defaultDistro() == null && distroRoots.isNotEmpty()) {
                setDefaultDistro(distroRoots.first().name)
            }
        } catch (t: Throwable) {
            report.appendLine("Error saat memasang: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            safeDelete(staging)
        }

        report.appendLine()
        report.appendLine(statusReport())
        return report.toString().trim()
    }

    private data class DistroRoot(val dir: File, val name: String)

    private fun resolveDistroRoots(staging: File, archiveName: String): List<DistroRoot> {
        if (looksLikeRootfs(staging)) {
            return listOf(DistroRoot(staging, detectDistroId(staging) ?: distroNameFromFile(archiveName)))
        }
        // proot-distro bootstraps wrap the rootfs in a single folder, e.g. `debian-trixie-aarch64/`.
        val children = staging.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val wrapped = children.singleOrNull()
        if (wrapped != null && looksLikeRootfs(wrapped) && detectDistroId(wrapped) != null) {
            return listOf(DistroRoot(wrapped, detectDistroId(wrapped)!!))
        }
        val roots = mutableListOf<DistroRoot>()
        for (containerName in listOf("rootfs", "termux-bootstrap")) {
            val container = File(staging, containerName)
            if (!container.isDirectory) continue
            if (looksLikeRootfs(container)) {
                roots.add(DistroRoot(container, detectDistroId(container) ?: container.name))
            }
            container.listFiles()?.filter { it.isDirectory && looksLikeRootfs(it) }?.forEach {
                roots.add(DistroRoot(it, detectDistroId(it) ?: it.name))
            }
        }
        return roots
    }

    private fun detectDistroId(root: File): String? {
        val candidates = listOf(File(root, "etc/os-release"), File(root, "usr/lib/os-release"))
        val text = candidates.firstOrNull { it.isFile }?.let {
            try {
                it.readText()
            } catch (_: Throwable) {
                null
            }
        } ?: return null
        for (line in text.lines()) {
            if (line.startsWith("ID=")) {
                val id = line.substringAfter('=').trim().trim('"').substringBefore(' ')
                if (id.isNotEmpty()) return id.lowercase()
            }
        }
        return null
    }

    private fun distroNameFromFile(fileName: String): String {
        val base = fileName.lowercase()
            .removeSuffix(".tar.gz").removeSuffix(".tgz").removeSuffix(".tar.xz").removeSuffix(".txz")
            .removeSuffix(".tar").removeSuffix(".zip")
        return when {
            base.contains("alpine") -> "alpine"
            base.contains("ubuntu") -> "ubuntu"
            base.contains("debian") -> "debian"
            base.isBlank() -> "rootfs"
            else -> base.replace(Regex("[^a-z0-9._-]"), "_").take(32)
        }
    }

    private fun looksLikeRootfs(dir: File): Boolean {
        if (!dir.isDirectory) return false
        return File(dir, "bin").isDirectory ||
            File(dir, "usr").isDirectory ||
            File(dir, "etc").isDirectory ||
            File(dir, "lib").isDirectory
    }

    private fun applySymlinkManifest(staging: File, root: File) {
        val manifest = File(staging, "termux-bootstrap/SYMLINKS.txt").takeIf { it.isFile }
            ?: File(staging, "SYMLINKS.txt").takeIf { it.isFile }
            ?: return
        try {
            manifest.readLines().forEach { line ->
                val idx = line.indexOf('\u2190')
                if (idx <= 0) return@forEach
                val linkPath = line.substring(0, idx).trim()
                val target = line.substring(idx + 1).trim()
                if (linkPath.isEmpty() || target.isEmpty()) return@forEach
                val linkFile = File(root, linkPath.removePrefix("/"))
                try {
                    linkFile.parentFile?.mkdirs()
                    if (linkFile.exists()) linkFile.delete()
                    java.nio.file.Files.createSymbolicLink(linkFile.toPath(), java.nio.file.Paths.get(target))
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun migrateLegacyFlatRootfs() {
        if (legacyChecked) return
        legacyChecked = true
        try {
            val base = rootfsBase()
            if (!looksLikeRootfs(base)) return
            val distro = detectDistroId(base) ?: "rootfs"
            val target = File(base, distro)
            if (target.exists()) return
            target.mkdirs()
            base.listFiles()?.forEach { src ->
                if (src.name == distro) return@forEach
                try {
                    moveEntry(src, File(target, src.name))
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Symlink-safe delete: NIO walk does not follow symlinks, so links pointing at absolute host
     * paths (e.g. `/bin/busybox`) are removed as links instead of traversing into real system dirs.
     */
    private fun safeDelete(root: File) {
        if (!root.exists()) return
        try {
            Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: Throwable) {
            try {
                root.delete()
            } catch (_: Throwable) {
            }
        }
    }

    /** Moves an entry without following symlinks; falls back to a symlink-safe copy. */
    private fun moveEntry(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        if (dst.exists() || Files.isSymbolicLink(dst.toPath())) safeDelete(dst)
        if (src.renameTo(dst)) return
        safeCopy(src, dst)
    }

    private fun safeCopy(src: File, dst: File) {
        val srcPath = src.toPath()
        when {
            Files.isSymbolicLink(srcPath) -> {
                val linkTarget = Files.readSymbolicLink(srcPath)
                if (dst.exists() || Files.isSymbolicLink(dst.toPath())) dst.delete()
                Files.createSymbolicLink(dst.toPath(), linkTarget)
            }
            src.isDirectory -> {
                dst.mkdirs()
                src.listFiles()?.forEach { safeCopy(it, File(dst, it.name)) }
            }
            else -> {
                dst.parentFile?.mkdirs()
                src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private fun chmodExecutable(root: File) {
        val execDirNames = setOf("bin", "sbin", "libexec")
        try {
            Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isSymbolicLink) {
                        val name = file.fileName?.toString().orEmpty()
                        val parent = file.parent?.fileName?.toString().orEmpty()
                        if (parent in execDirNames || name == "proot" || name.startsWith("libproot-loader")) {
                            file.toFile().setExecutable(true, false)
                        }
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: Throwable) {
        }
    }
}
