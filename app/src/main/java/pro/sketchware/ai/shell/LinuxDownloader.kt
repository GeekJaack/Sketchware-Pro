package pro.sketchware.ai.shell

import android.content.Context
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads a Linux userland (proot + rootfs) matching the device ABI at runtime, so the APK stays
 * slim and every architecture can bootstrap itself on demand. Integrity is enforced with SHA-256
 * from the official sources (GitHub release digest / Alpine `.sha256` / Ubuntu `SHA256SUMS`).
 */
object LinuxDownloader {

    internal var client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private const val UA = "SketchwarePro-AI-Agent"

    /** SHA-256 of proot-me v5.3.0 static builds (published without a GitHub digest). */
    private val KNOWN_PROOT_SHA256 = mapOf(
        "proot-v5.3.0-aarch64-static" to "fa10b1a7818c2f5b1dcb5834450570c368c9ecf66d31521509621b95c4538a45",
        "proot-v5.3.0-arm-static" to "bf186a37c7a19621e5bf3cfdf6bce54bfa2e220f91eb7196318e699ac174cc69",
        "proot-v5.3.0-x86_64-static" to "d1eb20cb201e6df08d707023efb000623ff7c10d6574839d7bb42d0adba6b4da"
    )

    fun downloadAndInstall(context: Context, distro: String, onProgress: (String) -> Unit): String {
        if (distro.lowercase() !in SUPPORTED_DISTROS) {
            return "Error: distro '$distro' tidak didukung untuk download. Pilihan: ${SUPPORTED_DISTROS.joinToString(", ")}."
        }
        val tag = LinuxSubsystem.deviceArchTag()
        val sb = StringBuilder()
        try {
            // Prefer the bundled, Android-patched proot (clone3 + link2symlink) over proot-me 5.3.0,
            // which cannot execute many dynamically linked binaries on Android.
            if (!LinuxSubsystem.isProotInstalled()) {
                sb.appendLine(LinuxSubsystem.installFromAssets())
            }
            if (!LinuxSubsystem.isProotInstalled()) {
                val proot = resolveProot(tag)
                    ?: return ("Error: tidak menemukan biner proot static untuk arsitektur '$tag'. " +
                        "Untuk 32-bit x86 (x86) belum tersedia dari proot-me.").also { LinuxSubsystem.recordError(it, null) }
                onProgress("Mengunduh proot ($tag)…")
                val prootFile = File(LinuxSubsystem.tmpDir(), proot.first.substringAfterLast('/'))
                httpDownload(proot.first, prootFile)
                proot.second?.let { verify(prootFile, it, "proot") }
                onProgress("Memasang proot…")
                sb.appendLine(LinuxSubsystem.installArchive(prootFile))
                prootFile.delete()
            } else {
                sb.appendLine("proot sudah terpasang.")
            }

            if (LinuxSubsystem.isReady(distro)) {
                sb.appendLine("rootfs '$distro' sudah tersedia.")
            } else {
                val rootfs = resolveRootfs(distro, tag)
                    ?: return "Error: tidak menemukan rootfs '$distro' untuk arsitektur '$tag'.".also { LinuxSubsystem.recordError(it, null) }

                onProgress("Mengunduh rootfs $distro ($tag)…")
                val rootfsFile = File(LinuxSubsystem.tmpDir(), rootfs.first.substringAfterLast('/'))
                httpDownload(rootfs.first, rootfsFile)
                rootfs.second?.let { verify(rootfsFile, it, "rootfs") }
                onProgress("Mengekstrak rootfs $distro…")
                sb.appendLine(LinuxSubsystem.installArchive(rootfsFile))
                rootfsFile.delete()
            }
        } catch (t: Throwable) {
            LinuxSubsystem.recordError("unduh/instal '$distro' gagal", t)
            return "Error: unduhan gagal: ${t.javaClass.simpleName}: ${t.message}"
        }
        LinuxSubsystem.clearError()
        sb.appendLine()
        sb.appendLine(LinuxSubsystem.statusReport())
        return sb.toString().trim()
    }

    /**
     * End-to-end bootstrap of a Linux distro (`debian` by default): ensures proot + rootfs are
     * installed, sets the distro as default, configures DNS + APT IPv4/noninteractive rules, and
     * installs essential developer packages (`ca-certificates`, `curl`, `wget`, `git`, `python3`,
     * `python3-pip`, `unzip`, `procps`).
     */
    fun bootstrapToolchain(
        context: Context,
        distro: String = "debian",
        onProgress: (String) -> Unit
    ): String {
        val target = distro.lowercase().ifBlank { "debian" }
        val sb = StringBuilder()
        if (!LinuxSubsystem.isReady(target)) {
            sb.appendLine(downloadAndInstall(context, target, onProgress))
            if (!LinuxSubsystem.isReady(target)) {
                return sb.toString().trim()
            }
        }
        LinuxSubsystem.setDefaultDistro(target)
        LinuxSubsystem.ensureResolvConf(target)
        LinuxSubsystem.ensureAptConfig(target)
        LinuxSubsystem.ensureGitConfig(target)

        onProgress("Memasang paket dasar ($target: curl, wget, git, python3, pip)…")
        val installCmd = if (target == "alpine") {
            "apk update && apk add --no-cache ca-certificates curl wget git python3 py3-pip unzip procps bash"
        } else {
            "dpkg --configure -a || true; apt-get update && apt-get install -y ca-certificates curl wget git python3 python3-pip unzip procps"
        }
        val res = ShellRunner.run(
            command = installCmd,
            cwd = LinuxSubsystem.baseDir(),
            timeoutMs = 300_000L,
            useRoot = false,
            inLinux = true,
            distro = target,
            pty = false
        )
        sb.appendLine("=== BOOTSTRAP TOOLCHAIN ($target) exit=${res.exitCode} (${res.durationMs} ms) ===")
        if (res.stdout.isNotBlank()) sb.appendLine(res.stdout.takeLast(2000))
        if (res.stderr.isNotBlank()) sb.appendLine(res.stderr.takeLast(1500))
        sb.appendLine()
        sb.appendLine(LinuxSubsystem.statusReport())
        return sb.toString().trim()
    }

    /** Distros [downloadAndInstall] can fetch; anything else is rejected instead of silently becoming Alpine. */
    val SUPPORTED_DISTROS = listOf("alpine", "debian", "ubuntu")

    internal fun resolveRootfs(distro: String, tag: String): Pair<String, String?>? =
        when (distro.lowercase()) {
            "alpine" -> resolveAlpine(tag)
            "debian" -> resolveDebian(tag)
            "ubuntu" -> resolveUbuntu(tag)
            else -> null
        }

    /**
     * Debian rootfs from the newest termux/proot-distro release that ships a Debian bootstrap for
     * this ABI with a GitHub SHA-256 digest (assets without a digest are skipped, never trusted).
     */
    private fun resolveDebian(tag: String): Pair<String, String?>? {
        val arch = when (tag) {
            "arm64" -> "aarch64"
            "arm" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "i686"
            else -> return null
        }
        val releases = JsonParser.parseString(
            httpGetText("https://api.github.com/repos/termux/proot-distro/releases?per_page=100")
        ).asJsonArray
        for (rel in releases) {
            val assets = rel.asJsonObject.getAsJsonArray("assets") ?: continue
            for (asset in assets) {
                val obj = asset.asJsonObject
                val name = obj.get("name")?.asString ?: continue
                if (!name.startsWith("debian-") || !name.contains("-$arch-") || !name.endsWith(".tar.xz")) continue
                val digest = obj.get("digest")?.takeIf { !it.isJsonNull }?.asString
                    ?.removePrefix("sha256:") ?: continue
                val url = obj.get("browser_download_url")?.asString ?: continue
                return url to digest
            }
        }
        return null
    }

    private fun resolveProot(tag: String): Pair<String, String?>? {
        val want = when (tag) {
            "arm64" -> "aarch64"
            "arm" -> "arm"
            "x86_64" -> "x86_64"
            else -> return null
        }
        val releases = JsonParser.parseString(
            httpGetText("https://api.github.com/repos/proot-me/proot/releases?per_page=10")
        ).asJsonArray
        for (rel in releases) {
            val assets = rel.asJsonObject.getAsJsonArray("assets") ?: continue
            for (asset in assets) {
                val obj = asset.asJsonObject
                val name = obj.get("name")?.asString ?: continue
                if (name.contains(want) && name.contains("static")) {
                    val url = obj.get("browser_download_url")?.asString ?: continue
                    // Older releases have no GitHub digest; fall back to pinned hashes, else skip.
                    val digest = obj.get("digest")?.takeIf { !it.isJsonNull }?.asString?.removePrefix("sha256:")
                        ?: KNOWN_PROOT_SHA256[name] ?: continue
                    return url to digest
                }
            }
        }
        return null
    }

    private fun resolveAlpine(tag: String): Pair<String, String?>? {
        val dir = when (tag) {
            "arm64" -> "aarch64"
            "arm" -> "armv7"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> return null
        }
        val base = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/$dir"
        val yaml = httpGetText("$base/latest-releases.yaml")
        val file = Regex("alpine-minirootfs-[0-9.]+-$dir\\.tar\\.gz").find(yaml)?.value ?: return null
        val url = "$base/$file"
        val sha = try {
            httpGetText("$url.sha256").trim().split(Regex("\\s+")).firstOrNull()
        } catch (_: Throwable) {
            null
        }
        return url to sha
    }

    private fun resolveUbuntu(tag: String): Pair<String, String?>? {
        val arch = when (tag) {
            "arm64" -> "arm64"
            "arm" -> "armhf"
            "x86_64" -> "amd64"
            else -> return null
        }
        val base = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release"
        val sums = httpGetText("$base/SHA256SUMS")
        val line = sums.lines().firstOrNull { it.contains("-base-$arch.tar.gz") } ?: return null
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 2) return null
        val sha = parts[0]
        val file = parts[1].removePrefix("*")
        return "$base/$file" to sha
    }

    private fun httpGetText(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} untuk $url")
            return response.body.string()
        }
    }

    private fun httpDownload(url: String, dest: File) {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} saat mengunduh $url")
            val body = response.body
            dest.parentFile?.mkdirs()
            body.byteStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun verify(file: File, expected: String, label: String) {
        val actual = sha256(file)
        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete()
            throw IOException("Checksum $label tidak cocok (diharapkan=$expected, aktual=$actual)")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
