package pro.sketchware.ai.tools

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.w3c.dom.Document
import org.w3c.dom.Element
import pro.sketchware.utility.FilePathUtil
import java.io.File

/**
 * Per-entry editing of `files/resource/values*` XML (strings, colors, dimens, styles, themes,
 * arrays...). Unlike overwriting a whole file, only the requested entry changes; every other
 * entry and comment is preserved.
 */
class ResValuesStore(private val resourceDir: File) {

    data class Spec(
        val name: String,
        val type: String? = null,
        val value: String? = null,
        val parent: String? = null,
        val listItems: List<String>? = null,
        val styleItems: Map<String, String>? = null,
        val translatable: Boolean? = null
    )

    private class Loaded(val doc: Document, val hadDeclaration: Boolean, val exists: Boolean)

    fun fileFor(kind: String, variant: String): File {
        require(KIND.matches(kind)) { "file '$kind' tidak valid (huruf kecil/angka/underscore, mis. strings, colors, styles, themes)." }
        return File(File(resourceDir, valuesFolder(variant)), "$kind.xml")
    }

    private fun load(file: File): Loaded {
        if (!file.isFile) return Loaded(XmlLite.parse("<resources></resources>"), hadDeclaration = true, exists = false)
        val text = file.readText()
        val doc = try {
            XmlLite.parse(text)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("${file.name} yang ada bukan XML valid (${e.message}). Perbaiki manual dengan manage_sketchware_files.")
        }
        require(doc.documentElement.tagName == "resources") { "Root ${file.name} bukan <resources>." }
        return Loaded(doc, text.trimStart().startsWith("<?xml"), exists = true)
    }

    private fun save(file: File, loaded: Loaded) {
        FileIo.atomicWrite(file, XmlLite.serializeDocument(loaded.doc, loaded.hadDeclaration))
    }

    // ---------- read ----------

    fun listFiles(): String {
        val dirs = resourceDir.listFiles { f -> f.isDirectory && f.name.startsWith("values") }?.sortedBy { it.name }.orEmpty()
        if (dirs.isEmpty()) return "Belum ada folder values* di ${resourceDir.path}."
        return buildString {
            appendLine("=== RESOURCE VALUES FILES ===")
            for (d in dirs) {
                val files = d.listFiles { f -> f.isFile && f.name.endsWith(".xml") }?.sortedBy { it.name }.orEmpty()
                appendLine("${d.name}/: " + files.joinToString(", ") { "${it.name} (${it.length()} B)" }.ifEmpty { "(kosong)" })
            }
        }.trim()
    }

    fun list(kind: String, variant: String): String {
        val file = fileFor(kind, variant)
        if (!file.isFile) return "${relative(file)} belum ada."
        val root = load(file).doc.documentElement
        val els = XmlLite.childElements(root)
        return buildString {
            appendLine("=== ${relative(file)} (${els.size} entri) ===")
            for (el in els) appendLine(summary(el))
        }.trim()
    }

    fun get(kind: String, variant: String, name: String): String {
        val file = fileFor(kind, variant)
        if (!file.isFile) return "${relative(file)} belum ada."
        val matches = XmlLite.childElements(load(file).doc.documentElement).filter { it.getAttribute("name") == name }
        if (matches.isEmpty()) return "Entri '$name' tidak ditemukan di ${relative(file)}."
        return matches.joinToString("\n") { XmlLite.serializeElement(it) }
    }

    // ---------- write ----------

    fun set(kind: String, variant: String, spec: Spec): String {
        validateName(spec.name)
        val type = (spec.type?.trim()?.ifEmpty { null } ?: DEFAULT_TYPE[kind])
            ?: throw IllegalArgumentException("type wajib diisi untuk file '$kind' (mis. string, color, dimen, bool, integer, style, string-array).")
        require(type in TYPES) { "type '$type' tidak didukung. Pilihan: ${TYPES.joinToString(", ")}." }
        val file = fileFor(kind, variant)
        val loaded = load(file)
        val doc = loaded.doc
        val root = doc.documentElement
        val existing = XmlLite.childElements(root).firstOrNull { it.tagName == type && it.getAttribute("name") == spec.name }

        val el: Element = if (type == "style") buildStyle(doc, spec, existing) else buildSimple(doc, spec, type, existing)
        val created = existing == null
        if (!created && el !== existing) root.replaceChild(el, existing!!)
        if (created) root.appendChild(el)
        save(file, loaded)
        return (if (created) "DITAMBAHKAN" else "DIPERBARUI") + " di ${relative(file)}:\n" + XmlLite.serializeElement(el)
    }

    fun delete(kind: String, variant: String, name: String, type: String?): String {
        validateName(name)
        val file = fileFor(kind, variant)
        if (!file.isFile) return "${relative(file)} belum ada."
        if (kind == "strings" && name == "app_name" && valuesFolder(variant) == "values") {
            return "Error: 'app_name' di values/strings.xml dilindungi (dipakai manifest dan Resource Editor). Ubah nilainya dengan action 'set'."
        }
        val wantedType = type?.trim()?.ifEmpty { null } ?: DEFAULT_TYPE[kind]
        val loaded = load(file)
        val root = loaded.doc.documentElement
        val victims = XmlLite.childElements(root).filter {
            it.getAttribute("name") == name && (wantedType == null || it.tagName == wantedType)
        }
        if (victims.isEmpty()) return "Entri '$name' tidak ditemukan di ${relative(file)}."
        victims.forEach { root.removeChild(it) }
        save(file, loaded)
        return "${victims.size} entri '$name' dihapus dari ${relative(file)}."
    }

    fun deleteStyleItem(kind: String, variant: String, styleName: String, itemName: String): String {
        validateName(styleName)
        require(itemName.isNotBlank()) { "item_name wajib diisi." }
        val file = fileFor(kind, variant)
        if (!file.isFile) return "${relative(file)} belum ada."
        val loaded = load(file)
        val style = XmlLite.childElements(loaded.doc.documentElement).firstOrNull { it.tagName == "style" && it.getAttribute("name") == styleName }
            ?: return "Style '$styleName' tidak ditemukan di ${relative(file)}."
        val items = XmlLite.childElements(style).filter { it.tagName == "item" && it.getAttribute("name") == itemName }
        if (items.isEmpty()) return "Item '$itemName' tidak ada pada style '$styleName'."
        items.forEach { style.removeChild(it) }
        save(file, loaded)
        return "Item '$itemName' dihapus dari style '$styleName'."
    }

    // ---------- element builders ----------

    private fun buildSimple(doc: Document, spec: Spec, type: String, existing: Element?): Element {
        val el = doc.createElement(type)
        el.setAttribute("name", spec.name)
        when (type) {
            "string" -> {
                val v = spec.value ?: throw IllegalArgumentException("value wajib diisi untuk type 'string'.")
                el.appendChild(doc.createTextNode(androidEscape(v)))
                val tr = spec.translatable?.toString() ?: existing?.getAttribute("translatable")?.ifEmpty { null }
                if (tr != null && tr != "true") el.setAttribute("translatable", tr)
            }
            "color" -> el.appendChild(doc.createTextNode(requireMatch(spec.value, COLOR, "color", "#RGB, #ARGB, #RRGGBB, #AARRGGBB, atau @color/nama")))
            "dimen" -> el.appendChild(doc.createTextNode(requireMatch(spec.value, DIMEN, "dimen", "angka+satuan seperti 16dp, 14sp, atau @dimen/nama")))
            "bool" -> el.appendChild(doc.createTextNode(requireMatch(spec.value, BOOL, "bool", "true, false, atau @bool/nama")))
            "integer" -> el.appendChild(doc.createTextNode(requireMatch(spec.value, INTEGER, "integer", "bilangan bulat atau @integer/nama")))
            "string-array", "integer-array", "array" -> {
                val items = spec.listItems ?: throw IllegalArgumentException("items (daftar) wajib diisi untuk type '$type'.")
                for (item in items) {
                    val child = doc.createElement("item")
                    val text = if (type == "string-array") androidEscape(item) else item
                    child.appendChild(doc.createTextNode(text))
                    el.appendChild(child)
                }
            }
            else -> throw IllegalArgumentException("type '$type' tidak didukung.")
        }
        return el
    }

    /** Styles are merged: given items are added/updated, the rest are kept. */
    private fun buildStyle(doc: Document, spec: Spec, existing: Element?): Element {
        val style = existing ?: doc.createElement("style").also { it.setAttribute("name", spec.name) }
        spec.parent?.let { p ->
            if (p.isBlank()) style.removeAttribute("parent") else style.setAttribute("parent", p)
        }
        spec.styleItems?.forEach { (itemName, itemValue) ->
            require(itemName.isNotBlank()) { "nama item style tidak boleh kosong." }
            val found = XmlLite.childElements(style).firstOrNull { it.tagName == "item" && it.getAttribute("name") == itemName }
            val item = found ?: doc.createElement("item").also {
                it.setAttribute("name", itemName)
                style.appendChild(it)
            }
            while (item.firstChild != null) item.removeChild(item.firstChild)
            item.appendChild(doc.createTextNode(itemValue))
        }
        return style
    }

    // ---------- helpers ----------

    private fun summary(el: Element): String {
        val name = el.getAttribute("name")
        return when (el.tagName) {
            "style" -> "style ${name}" + (el.getAttribute("parent").takeIf { it.isNotEmpty() }?.let { " (parent=$it)" } ?: "") +
                " : ${XmlLite.childElements(el).size} item"
            "string-array", "integer-array", "array", "plurals" -> "${el.tagName} $name : ${XmlLite.childElements(el).size} item"
            else -> "${el.tagName} $name = ${el.textContent.trim().replace("\n", " ").take(100)}"
        }
    }

    private fun relative(file: File): String = "${file.parentFile?.name}/${file.name}"

    private fun requireMatch(value: String?, regex: Regex, type: String, hint: String): String {
        val v = value?.trim() ?: throw IllegalArgumentException("value wajib diisi untuk type '$type'.")
        require(regex.matches(v)) { "value '$v' bukan $type yang valid ($hint)." }
        return v
    }

    companion object {
        private val KIND = Regex("[a-z][a-z0-9_]*")
        private val NAME = Regex("[A-Za-z_][A-Za-z0-9_.]*")
        private val VARIANT = Regex("[A-Za-z0-9+\\-]+")
        private val VALUES_DIR = Regex("values(-[A-Za-z0-9+]+)*")
        private val COLOR = Regex("#([0-9A-Fa-f]{3,4}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})|@(android:)?color/\\w+|\\?(android:)?(attr/)?\\w+")
        private val DIMEN = Regex("-?\\d+(\\.\\d+)?(dp|dip|sp|px|pt|mm|in)|@(android:)?dimen/\\w+")
        private val BOOL = Regex("true|false|@(android:)?bool/\\w+")
        private val INTEGER = Regex("-?\\d+|@(android:)?integer/\\w+")

        val TYPES = setOf("string", "color", "dimen", "bool", "integer", "style", "string-array", "integer-array", "array")

        private val DEFAULT_TYPE = mapOf(
            "strings" to "string", "colors" to "color", "dimens" to "dimen", "bools" to "bool",
            "integers" to "integer", "styles" to "style", "themes" to "style", "arrays" to "string-array"
        )

        fun valuesFolder(variant: String): String {
            val v = variant.trim()
            if (v.isEmpty() || v == "default" || v == "base") return "values"
            if (v.startsWith("values")) {
                require(VALUES_DIR.matches(v)) { "variant '$v' tidak valid." }
                return v
            }
            require(VARIANT.matches(v)) { "variant '$v' tidak valid (mis. 'night', 'v21', 'id')." }
            return "values-$v"
        }

        fun validateName(name: String) {
            require(NAME.matches(name)) { "name '$name' tidak valid (huruf/angka/underscore/titik, tidak diawali angka)." }
        }

        /** Escapes apostrophes and quotes the way Android string resources require; other text is kept literal. */
        fun androidEscape(raw: String): String =
            raw.replace(Regex("(?<!\\\\)'")) { "\\'" }.replace(Regex("(?<!\\\\)\"")) { "\\\"" }
    }
}

/** Tool entry point for `manage_res_values`. */
object ResValuesTool {

    fun execute(scId: String, args: JsonObject): String {
        ProjectPaths.requireScId(scId)
        val store = ResValuesStore(File(FilePathUtil().getPathResource(scId)))
        val kind = args.argStr("file").ifEmpty { "strings" }.removeSuffix(".xml")
        val variant = args.argStr("variant")
        return try {
            when (val action = args.argStr("action").ifEmpty { "list" }) {
                "list_files" -> store.listFiles()
                "list" -> store.list(kind, variant)
                "get" -> store.get(kind, variant, args.argStr("name"))
                "set" -> {
                    val items = args.get("items")
                    store.set(
                        kind, variant,
                        ResValuesStore.Spec(
                            name = args.argStr("name"),
                            type = args.argStr("type").ifEmpty { null },
                            value = args.get("value")?.takeIf { !it.isJsonNull }?.let { args.argStr("value") },
                            parent = args.get("parent")?.takeIf { !it.isJsonNull }?.asString?.trim(),
                            listItems = items?.let { listItems(it) },
                            styleItems = items?.let { styleItems(it) },
                            translatable = args.get("translatable")?.takeIf { !it.isJsonNull }?.let { args.argBool("translatable", true) }
                        )
                    )
                }
                "delete" -> store.delete(kind, variant, args.argStr("name"), args.argStr("type").ifEmpty { null })
                "delete_style_item" -> store.deleteStyleItem(kind, variant, args.argStr("name"), args.argStr("item_name"))
                else -> "Error: action '$action' tidak dikenal pada manage_res_values."
            }
        } catch (e: IllegalArgumentException) {
            "Error: ${e.message}"
        } catch (e: IllegalStateException) {
            "Error: ${e.message}"
        }
    }

    private fun parsed(el: JsonElement): JsonElement = if (el.isJsonPrimitive && el.asString.trim().let { it.startsWith("{") || it.startsWith("[") }) {
        try {
            JsonParser.parseString(el.asString)
        } catch (_: Exception) {
            el
        }
    } else {
        el
    }

    private fun listItems(el: JsonElement): List<String>? {
        val p = parsed(el)
        return if (p.isJsonArray) p.asJsonArray.filter { it.isJsonPrimitive }.map { it.asString } else null
    }

    private fun styleItems(el: JsonElement): Map<String, String>? {
        val p = parsed(el)
        if (!p.isJsonObject) return null
        val out = LinkedHashMap<String, String>()
        for ((k, v) in p.asJsonObject.entrySet()) if (v.isJsonPrimitive) out[k] = v.asString
        return out
    }
}
