package pro.sketchware.ai.tools

import com.google.gson.JsonObject
import pro.sketchware.utility.FilePathUtil
import java.io.File

/** Minimal ELF header reader used to verify that a `.so` really targets the ABI folder it is put in. */
object ElfInfo {

    data class Header(val is64: Boolean, val machine: Int)

    fun parse(bytes: ByteArray): Header? {
        if (bytes.size < 20) return null
        if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() || bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()) return null
        val is64 = bytes[4].toInt() == 2
        val machine = (bytes[18].toInt() and 0xFF) or ((bytes[19].toInt() and 0xFF) shl 8)
        return Header(is64, machine)
    }

    /** ABI folder name -> (ELF class is64, e_machine). */
    val ABIS: Map<String, Pair<Boolean, Int>> = linkedMapOf(
        "armeabi" to (false to 40),
        "armeabi-v7a" to (false to 40),
        "arm64-v8a" to (true to 183),
        "x86" to (false to 3),
        "x86_64" to (true to 62)
    )

    fun matches(abi: String, h: Header): Boolean = ABIS[abi]?.let { it.first == h.is64 && it.second == h.machine } ?: false

    fun describe(h: Header): String {
        val arch = when (h.machine) {
            40 -> "ARM"
            183 -> "AArch64"
            3 -> "x86"
            62 -> "x86-64"
            else -> "machine=${h.machine}"
        }
        return "$arch ${if (h.is64) "64-bit" else "32-bit"}"
    }
}

class NativeLibsStore(private val root: File) {

    private fun abiDir(abi: String): File {
        require(abi in ElfInfo.ABIS) { "abi '$abi' tidak dikenal. Pilihan: ${ElfInfo.ABIS.keys.joinToString(", ")}." }
        return File(root, abi)
    }

    fun list(): String {
        val abis = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
        val withFiles = abis.map { it to (it.listFiles { f -> f.isFile }?.sortedBy { f -> f.name }.orEmpty()) }.filter { it.second.isNotEmpty() }
        if (withFiles.isEmpty()) return "Belum ada native library di ${root.path}."
        return buildString {
            appendLine("=== NATIVE LIBS ===")
            for ((dir, files) in withFiles) {
                appendLine("${dir.name}/: " + files.joinToString(", ") { "${it.name} (${it.length()} B)" })
            }
        }.trim()
    }

    fun add(abi: String, source: File, fileName: String?): String {
        val dir = abiDir(abi)
        require(source.isFile) { "source_path '${source.path}' tidak ditemukan." }
        val name = fileName?.takeIf { it.isNotBlank() } ?: source.name
        require(name.endsWith(".so") && !name.contains('/') && !name.contains('\\')) { "Nama berkas harus berakhiran .so dan tanpa path." }
        val head = source.inputStream().use { s -> ByteArray(20).also { s.read(it) } }
        val h = ElfInfo.parse(head) ?: throw IllegalArgumentException("'${source.name}' bukan berkas ELF (.so) yang valid.")
        require(ElfInfo.matches(abi, h)) { "'${source.name}' adalah ${ElfInfo.describe(h)}, tidak cocok untuk folder ABI '$abi'." }
        dir.mkdirs()
        val dest = File(dir, name)
        val replaced = dest.exists()
        source.copyTo(dest, overwrite = true)
        return (if (replaced) "Diganti" else "Ditambahkan") + " $abi/$name (${dest.length()} B, ${ElfInfo.describe(h)})."
    }

    fun remove(abi: String, name: String): String {
        val dir = abiDir(abi)
        require(name.isNotBlank() && !name.contains('/') && !name.contains('\\')) { "name wajib berupa nama berkas tanpa path." }
        val f = File(dir, name)
        if (!f.isFile) return "$abi/$name tidak ditemukan."
        f.delete()
        return "$abi/$name dihapus."
    }
}

/** Tool entry point for `manage_native_libs`. */
object NativeLibsTool {

    fun execute(scId: String, args: JsonObject): String {
        ProjectPaths.requireScId(scId)
        val store = NativeLibsStore(File(FilePathUtil().getPathNativelibs(scId)))
        return try {
            when (val action = args.argStr("action").ifEmpty { "list" }) {
                "list" -> store.list()
                "add" -> store.add(args.argStr("abi"), File(args.argStr("source_path")), args.argStr("file_name").ifEmpty { null })
                "remove" -> store.remove(args.argStr("abi"), args.argStr("name"))
                else -> "Error: action '$action' tidak dikenal pada manage_native_libs."
            }
        } catch (e: IllegalArgumentException) {
            "Error: ${e.message}"
        } catch (e: java.io.IOException) {
            "Error: gagal I/O: ${e.message}"
        }
    }
}
