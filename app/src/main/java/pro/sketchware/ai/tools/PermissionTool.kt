package pro.sketchware.ai.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import pro.sketchware.utility.FilePathUtil
import java.io.File

/**
 * Project permissions as the Permission Manager stores them: `data/<id>/permission` is a JSON
 * array of permission names. Permissions injected through the manifest (`_application_permissions`
 * entries in attributes.json) are listed read-only.
 *
 * Sketchware's manifest generator has no `uses-feature` support, so that is intentionally absent.
 */
class PermissionStore(private val permissionFile: File, private val attributesFile: File?) {

    fun read(): List<String> {
        if (!permissionFile.isFile) return emptyList()
        val text = permissionFile.readText().trim()
        if (text.isEmpty()) return emptyList()
        return try {
            JsonParser.parseString(text).asJsonArray.filter { it.isJsonPrimitive }.map { it.asString }
        } catch (e: Exception) {
            throw IllegalStateException("File permission bukan JSON array valid: ${e.message?.take(80)}")
        }
    }

    private fun write(list: List<String>) {
        FileIo.atomicWrite(permissionFile, Gson().toJson(list))
    }

    fun add(names: List<String>): String {
        require(names.isNotEmpty()) { "permissions wajib diisi (daftar nama, mis. [\"INTERNET\",\"CAMERA\"])." }
        val current = read().toMutableList()
        val added = ArrayList<String>()
        val already = ArrayList<String>()
        for (raw in names) {
            val n = normalize(raw)
            if (current.contains(n)) already.add(n) else {
                current.add(n)
                added.add(n)
            }
        }
        if (added.isNotEmpty()) write(current)
        return buildString {
            append("Ditambahkan (${added.size}): ${added.joinToString(", ").ifEmpty { "-" }}")
            if (already.isNotEmpty()) append("\nSudah ada (${already.size}): ${already.joinToString(", ")}")
            append("\nTotal permission: ${current.size}")
        }
    }

    fun remove(names: List<String>): String {
        require(names.isNotEmpty()) { "permissions wajib diisi." }
        val current = read().toMutableList()
        val removed = ArrayList<String>()
        val missing = ArrayList<String>()
        for (raw in names) {
            val n = normalize(raw)
            if (current.remove(n)) removed.add(n) else missing.add(n)
        }
        if (removed.isNotEmpty()) write(current)
        return buildString {
            append("Dihapus (${removed.size}): ${removed.joinToString(", ").ifEmpty { "-" }}")
            if (missing.isNotEmpty()) append("\nTidak ditemukan (${missing.size}): ${missing.joinToString(", ")}")
            append("\nTotal permission: ${current.size}")
        }
    }

    /** Replaces the whole list. */
    fun set(names: List<String>): String {
        val list = names.map { normalize(it) }.distinct()
        write(list)
        return "Daftar permission diganti: ${list.size} item."
    }

    fun describe(): String = buildString {
        val list = read()
        appendLine("=== PERMISSIONS (${list.size}) ===")
        if (list.isEmpty()) appendLine("(kosong)") else list.forEach { appendLine("- $it") }
        val injected = injected()
        if (injected.isNotEmpty()) {
            appendLine("Disuntik via manifest (_application_permissions) — ubah lewat manage_manifest/berkas attributes.json:")
            injected.forEach { appendLine("- $it") }
        }
    }.trim()

    private fun injected(): List<String> {
        val f = attributesFile ?: return emptyList()
        if (!f.isFile) return emptyList()
        return try {
            JsonParser.parseString(f.readText()).asJsonArray
                .filter { it.isJsonObject && it.asJsonObject.get("name")?.asString == "_application_permissions" }
                .mapNotNull { it.asJsonObject.get("value")?.asString }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private val VALID = Regex("[A-Za-z][A-Za-z0-9_.]*")

        /** `camera` / `CAMERA` -> `android.permission.CAMERA`; names containing a dot are kept as-is. */
        fun normalize(raw: String): String {
            val n = raw.trim().removePrefix("uses-permission:").trim()
            require(VALID.matches(n)) { "Nama permission '$raw' tidak valid." }
            return if (n.contains('.')) n else "android.permission.${n.uppercase()}"
        }
    }
}

/** Tool entry point for `manage_permissions`. */
object PermissionTool {

    fun execute(scId: String, args: JsonObject): String {
        ProjectPaths.requireScId(scId)
        val permissionFile = File(FilePathUtil().getPathPermission(scId))
        val attributesFile = File(ProjectPaths.dataDir(scId), "Injection/androidmanifest/attributes.json")
        val store = PermissionStore(permissionFile, attributesFile)
        return try {
            when (val action = args.argStr("action").ifEmpty { "list" }) {
                "list" -> store.describe()
                "add" -> store.add(args.argStringList("permissions"))
                "remove" -> store.remove(args.argStringList("permissions"))
                "set" -> store.set(args.argStringList("permissions"))
                else -> "Error: action '$action' tidak dikenal pada manage_permissions."
            }
        } catch (e: IllegalArgumentException) {
            "Error: ${e.message}"
        } catch (e: IllegalStateException) {
            "Error: ${e.message}"
        }
    }
}
