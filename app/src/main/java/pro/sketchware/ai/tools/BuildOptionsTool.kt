package pro.sketchware.ai.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.Locale

/**
 * Project build options exactly as the app persists them, all in `data/<id>/`:
 *  - `proguard`       {enabled, debug, r8}
 *  - `stringfog`      {enabled}
 *  - `project_config` {min_sdk, target_sdk, app_class, enable_viewbinding, xml_command, ...}
 *  - `proguard-rules.pro` custom ProGuard/R8 rules
 * Values are stored as strings ("true"/"false"/"28"), matching ProjectSettings/ProguardHandler.
 */
class BuildOptionsStore(private val dataDir: File) {

    private enum class Type { BOOL, INT, CLASS }

    private data class Option(val file: String, val key: String, val type: Type, val help: String, val min: Int = 0, val max: Int = 0)

    private val options: Map<String, Option> = linkedMapOf(
        "proguard" to Option("proguard", "enabled", Type.BOOL, "Aktifkan shrinking/obfuscation (ProGuard/R8) saat build"),
        "r8" to Option("proguard", "r8", Type.BOOL, "Pakai R8 alih-alih ProGuard"),
        "proguard_debug" to Option("proguard", "debug", Type.BOOL, "Simpan berkas debug ProGuard"),
        "stringfog" to Option("stringfog", "enabled", Type.BOOL, "Enkripsi string konstanta (StringFog)"),
        "min_sdk" to Option("project_config", "min_sdk", Type.INT, "minSdkVersion (default aplikasi: 21)", 16, 36),
        "target_sdk" to Option("project_config", "target_sdk", Type.INT, "targetSdkVersion (default aplikasi: 28)", 21, 36),
        "app_class" to Option("project_config", "app_class", Type.CLASS, "Kelas Application kustom untuk manifest (mis. .MyApp); kosong = default"),
        "viewbinding" to Option("project_config", "enable_viewbinding", Type.BOOL, "Aktifkan ViewBinding"),
        "xml_command" to Option("project_config", "xml_command", Type.BOOL, "Aktifkan XML Command baru"),
        "disable_old_methods" to Option("project_config", "disable_old_methods", Type.BOOL, "Nonaktifkan method lama (Android API lawas)"),
        "bridgeless_themes" to Option("project_config", "enable_bridgeless_themes", Type.BOOL, "Aktifkan tema bridgeless")
    )

    fun optionNames(): Set<String> = options.keys

    private fun read(file: String): MutableMap<String, String> {
        val f = File(dataDir, file)
        if (!f.isFile) return LinkedHashMap()
        val text = f.readText().trim()
        if (text.isEmpty()) return LinkedHashMap()
        return try {
            val out = LinkedHashMap<String, String>()
            for ((k, v) in JsonParser.parseString(text).asJsonObject.entrySet()) {
                out[k] = if (v.isJsonPrimitive) v.asString else v.toString()
            }
            out
        } catch (e: Exception) {
            throw IllegalStateException("Berkas $file bukan JSON object valid: ${e.message?.take(80)}")
        }
    }

    private fun write(file: String, map: Map<String, String>) = FileIo.atomicWrite(File(dataDir, file), Gson().toJson(map))

    fun describe(): String = buildString {
        appendLine("=== OPSI BUILD PROYEK ===")
        for ((name, o) in options) {
            val v = read(o.file)[o.key]
            appendLine("- $name = ${v?.takeIf { it.isNotEmpty() } ?: "(default)"}  — ${o.help}")
        }
        val rules = File(dataDir, "proguard-rules.pro")
        appendLine("- aturan ProGuard kustom: " + if (rules.isFile) "${rules.length()} B (proguard-rules.pro)" else "belum ada")
    }.trim()

    fun set(option: String, value: String): String {
        val name = option.trim().lowercase(Locale.ROOT)
        val o = options[name] ?: throw IllegalArgumentException("option '$option' tidak dikenal. Pilihan: ${options.keys.joinToString(", ")}.")
        val v = value.trim()
        val normalized = when (o.type) {
            Type.BOOL -> {
                require(v.equals("true", true) || v.equals("false", true)) { "value untuk '$name' harus true atau false." }
                v.lowercase(Locale.ROOT)
            }
            Type.INT -> {
                val n = v.toIntOrNull() ?: throw IllegalArgumentException("value untuk '$name' harus bilangan bulat.")
                require(n in o.min..o.max) { "value '$name' harus di antara ${o.min} dan ${o.max}." }
                checkSdkOrder(name, n)
                n.toString()
            }
            Type.CLASS -> {
                require(v.isEmpty() || CLASS_NAME.matches(v)) { "app_class '$v' tidak valid (huruf/angka/underscore/titik, mis. .MyApp atau com.x.MyApp)." }
                v
            }
        }
        val map = read(o.file)
        if (o.file == "proguard") {
            map.putIfAbsent("enabled", "false")
            map.putIfAbsent("debug", "false")
        }
        if (o.file == "stringfog") map.putIfAbsent("enabled", "false")
        val before = map[o.key]
        map[o.key] = normalized
        write(o.file, map)
        return "$name: ${before ?: "(default)"} -> ${normalized.ifEmpty { "(default)" }}"
    }

    private fun checkSdkOrder(name: String, value: Int) {
        val cfg = read("project_config")
        val min = if (name == "min_sdk") value else cfg["min_sdk"]?.toIntOrNull()
        val target = if (name == "target_sdk") value else cfg["target_sdk"]?.toIntOrNull()
        if (min != null && target != null) require(min <= target) { "min_sdk ($min) tidak boleh lebih besar dari target_sdk ($target)." }
    }

    // ---------- custom ProGuard rules ----------

    private fun rulesFile() = File(dataDir, "proguard-rules.pro")

    fun getRules(): String = rulesFile().takeIf { it.isFile }?.readText()?.ifBlank { null } ?: "(aturan kustom kosong)"

    fun setRules(content: String): String {
        FileIo.atomicWrite(rulesFile(), content.trimEnd() + "\n")
        return "proguard-rules.pro ditulis (${content.length} karakter)."
    }

    fun appendRules(content: String): String {
        require(content.isNotBlank()) { "content kosong." }
        val existing = rulesFile().takeIf { it.isFile }?.readText().orEmpty()
        val lines = existing.lines().map { it.trim() }.toSet()
        val toAdd = content.lines().filter { it.isNotBlank() && it.trim() !in lines }
        if (toAdd.isEmpty()) return "Semua aturan sudah ada; tidak ada yang ditambahkan."
        FileIo.atomicWrite(rulesFile(), existing.trimEnd() + (if (existing.isBlank()) "" else "\n") + toAdd.joinToString("\n") + "\n")
        return "${toAdd.size} baris aturan ditambahkan ke proguard-rules.pro."
    }

    companion object {
        private val CLASS_NAME = Regex("\\.?[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
    }
}

/** Tool entry point for `manage_build_options`. */
object BuildOptionsTool {

    fun execute(scId: String, args: JsonObject): String {
        val store = BuildOptionsStore(ProjectPaths.dataDir(scId))
        return try {
            when (val action = args.argStr("action").ifEmpty { "list" }) {
                "list" -> store.describe()
                "set" -> store.set(args.argStr("option"), args.argStr("value"))
                "get_rules" -> store.getRules()
                "set_rules" -> store.setRules(args.get("content")?.takeIf { !it.isJsonNull }?.asString ?: "")
                "append_rules" -> store.appendRules(args.get("content")?.takeIf { !it.isJsonNull }?.asString ?: "")
                else -> "Error: action '$action' tidak dikenal pada manage_build_options."
            }
        } catch (e: IllegalArgumentException) {
            "Error: ${e.message}"
        } catch (e: IllegalStateException) {
            "Error: ${e.message}"
        }
    }
}
