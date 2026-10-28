package pro.sketchware.ai.tools

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import mod.hilal.saif.android_manifest.AndroidManifestInjector
import org.w3c.dom.Element
import java.io.File

/**
 * Reads and edits Sketchware Pro's AndroidManifest injection files
 * (`data/<id>/Injection/androidmanifest/`), exactly as the app's Manifest Manager does:
 *
 * - `attributes.json`            : `[{name, value}]`, name = `_application_attrs`, `_apply_for_all_activities`
 *                                  or an Activity class name; value = one raw `prefix:attr="value"`.
 * - `activities_components.json` : `[{name, value}]`, value = XML placed inside that `<activity>`.
 * - `app_components.txt`         : raw XML (service/receiver/provider/meta-data...) placed inside `<application>`.
 * - `activity_launcher.txt`      : screen name used as launcher.
 */
class ManifestInjectionStore(private val dir: File) {

    data class Entry(val name: String, val value: String)

    private fun file(name: String) = File(dir, name)

    private fun parseExisting(text: String, label: String) = try {
        XmlLite.parseFragment(text)
    } catch (e: IllegalArgumentException) {
        throw IllegalStateException("$label yang sudah ada bukan XML valid (${e.message}). Perbaiki manual dengan manage_sketchware_files.")
    }

    // ---------- raw access ----------

    fun readAttributes(): List<Entry> = readEntries(ATTRIBUTES)

    fun readActivityComponents(): List<Entry> = readEntries(ACTIVITY_COMPONENTS)

    fun readAppComponents(): String = file(APP_COMPONENTS).takeIf { it.isFile }?.readText() ?: ""

    fun readLauncher(): String? = file(LAUNCHER).takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

    private fun readEntries(fileName: String): List<Entry> {
        val f = file(fileName)
        if (!f.isFile) return emptyList()
        val text = f.readText().trim()
        if (text.isEmpty()) return emptyList()
        val arr = try {
            JsonParser.parseString(text).asJsonArray
        } catch (e: Exception) {
            throw IllegalStateException("$fileName bukan JSON array yang valid (${e.message?.take(80)}). Periksa dengan manage_sketchware_files sebelum mengedit.")
        }
        val out = ArrayList<Entry>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val n = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val v = o.get("value")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            out.add(Entry(n, v))
        }
        return out
    }

    private fun writeEntries(fileName: String, entries: List<Entry>) {
        val arr = JsonArray()
        for (e in entries) {
            arr.add(JsonObject().apply {
                addProperty("name", e.name)
                addProperty("value", e.value)
            })
        }
        FileIo.atomicWrite(file(fileName), GsonBuilder().disableHtmlEscaping().create().toJson(arr))
    }

    // ---------- attributes ----------

    fun setAttribute(target: String, attribute: String): String {
        val tgt = normalizeTarget(target)
        val (key, value) = parseAttribute(attribute)
        val line = "$key=\"$value\""
        val entries = readAttributes().toMutableList()
        val idx = entries.indexOfFirst { it.name == tgt && attributeKey(it.value) == key }
        return if (idx >= 0) {
            entries[idx] = Entry(tgt, line)
            writeEntries(ATTRIBUTES, entries)
            "Atribut '$key' pada $tgt DIPERBARUI: $line"
        } else {
            entries.add(Entry(tgt, line))
            writeEntries(ATTRIBUTES, entries)
            "Atribut ditambahkan pada $tgt: $line"
        }
    }

    fun removeAttribute(target: String, attributeName: String): String {
        val tgt = normalizeTarget(target)
        val key = attributeName.trim()
        require(key.isNotEmpty()) { "attribute_name wajib diisi." }
        val entries = readAttributes().toMutableList()
        val before = entries.size
        entries.removeAll { it.name == tgt && attributeKey(it.value) == key }
        val removed = before - entries.size
        if (removed == 0) return "Tidak ada atribut '$key' pada $tgt."
        writeEntries(ATTRIBUTES, entries)
        return "$removed atribut '$key' dihapus dari $tgt."
    }

    // ---------- application components ----------

    fun addAppComponent(xml: String): String {
        val newEls = XmlLite.childElements(XmlLite.parseFragment(xml).documentElement)
        require(newEls.isNotEmpty()) { "xml tidak berisi elemen." }
        val warnings = ArrayList<String>()
        for (el in newEls) {
            require(el.tagName in APP_COMPONENT_TAGS) {
                "Tag <${el.tagName}> tidak diizinkan di app_components. Pakai salah satu: ${APP_COMPONENT_TAGS.joinToString(", ")}."
            }
            exportedWarning(el)?.let { warnings.add(it) }
        }
        val doc = parseExisting(readAppComponents(), "app_components.txt")
        val root = doc.documentElement
        var added = 0
        var replaced = 0
        for (el in newEls) {
            val imported = doc.importNode(el, true) as Element
            val key = imported.getAttribute("android:name")
            val dup = if (key.isNotEmpty()) {
                XmlLite.childElements(root).firstOrNull { it.tagName == imported.tagName && it.getAttribute("android:name") == key }
            } else {
                null
            }
            if (dup != null) {
                root.replaceChild(imported, dup)
                replaced++
            } else {
                root.appendChild(imported)
                added++
            }
        }
        FileIo.atomicWrite(file(APP_COMPONENTS), XmlLite.serializeFragment(root))
        return buildString {
            append("app_components.txt: $added ditambahkan, $replaced diganti.")
            warnings.forEach { append("\nPeringatan: ").append(it) }
        }
    }

    fun removeAppComponent(componentName: String, tag: String?): String {
        val name = componentName.trim()
        require(name.isNotEmpty()) { "component_name wajib diisi." }
        val doc = parseExisting(readAppComponents(), "app_components.txt")
        val root = doc.documentElement
        val victims = XmlLite.childElements(root).filter { el ->
            (tag.isNullOrBlank() || el.tagName == tag) && namesMatch(el.getAttribute("android:name"), name)
        }
        if (victims.isEmpty()) return "Tidak ada komponen dengan android:name '$name' di app_components."
        victims.forEach { root.removeChild(it) }
        FileIo.atomicWrite(file(APP_COMPONENTS), XmlLite.serializeFragment(root))
        return "${victims.size} komponen '$name' dihapus dari app_components."
    }

    // ---------- per-activity components ----------

    fun addActivityComponent(activity: String, xml: String): String {
        val act = normalizeActivity(activity)
        val newEls = XmlLite.childElements(XmlLite.parseFragment(xml).documentElement)
        require(newEls.isNotEmpty()) { "xml tidak berisi elemen." }
        newEls.forEach {
            require(it.tagName in ACTIVITY_CHILD_TAGS) {
                "Tag <${it.tagName}> tidak diizinkan di dalam <activity>. Pakai: ${ACTIVITY_CHILD_TAGS.joinToString(", ")}."
            }
        }
        val entries = readActivityComponents().toMutableList()
        val idx = entries.indexOfFirst { it.name == act }
        val doc = parseExisting(if (idx >= 0) entries[idx].value else "", "Komponen activity $act")
        val root = doc.documentElement
        val existing = XmlLite.childElements(root).map { XmlLite.serializeElement(it) }.toMutableSet()
        var added = 0
        for (el in newEls) {
            val imported = doc.importNode(el, true) as Element
            if (existing.add(XmlLite.serializeElement(imported))) {
                root.appendChild(imported)
                added++
            }
        }
        val value = XmlLite.serializeFragment(root)
        if (idx >= 0) entries[idx] = Entry(act, value) else entries.add(Entry(act, value))
        writeEntries(ACTIVITY_COMPONENTS, entries)
        return "Komponen activity $act: $added elemen ditambahkan (duplikat identik dilewati)."
    }

    fun removeActivityComponent(activity: String, contains: String?): String {
        val act = normalizeActivity(activity)
        val entries = readActivityComponents().toMutableList()
        val idx = entries.indexOfFirst { it.name == act }
        if (idx < 0) return "Tidak ada komponen khusus untuk $act."
        if (contains.isNullOrBlank()) {
            entries.removeAt(idx)
            writeEntries(ACTIVITY_COMPONENTS, entries)
            return "Semua komponen khusus untuk $act dihapus."
        }
        val doc = parseExisting(entries[idx].value, "Komponen activity $act")
        val root = doc.documentElement
        val victims = XmlLite.childElements(root).filter { XmlLite.serializeElement(it).contains(contains) }
        if (victims.isEmpty()) return "Tidak ada elemen yang memuat '$contains' pada $act."
        victims.forEach { root.removeChild(it) }
        val remaining = XmlLite.serializeFragment(root)
        if (remaining.isBlank()) entries.removeAt(idx) else entries[idx] = Entry(act, remaining)
        writeEntries(ACTIVITY_COMPONENTS, entries)
        return "${victims.size} elemen dihapus dari $act."
    }

    // ---------- launcher ----------

    fun setLauncher(screen: String): String {
        val s = screen.trim()
        require(LAUNCHER_NAME.matches(s)) { "Nama layar launcher hanya boleh huruf/angka/underscore (tanpa spasi atau titik), mis. 'main'." }
        FileIo.atomicWrite(file(LAUNCHER), s)
        return "Layar launcher diatur ke '$s'."
    }

    // ---------- report ----------

    fun describe(): String = buildString {
        appendLine("=== MANIFEST INJECTION ===")
        appendLine("Launcher: ${readLauncher() ?: "(default: main)"}")
        val attrs = readAttributes()
        appendLine("Atribut (${attrs.size}):")
        if (attrs.isEmpty()) appendLine("  (kosong)")
        attrs.forEach { appendLine("  - [${it.name}] ${it.value}") }
        val comps = readActivityComponents()
        appendLine("Komponen per-Activity (${comps.size}):")
        if (comps.isEmpty()) appendLine("  (kosong)")
        comps.forEach { appendLine("  - [${it.name}]\n${it.value.prependIndent("      ")}") }
        val app = readAppComponents().trim()
        appendLine("app_components.txt:")
        appendLine(if (app.isEmpty()) "  (kosong)" else app.prependIndent("  "))
    }.trim()

    companion object {
        const val ATTRIBUTES = "attributes.json"
        const val ACTIVITY_COMPONENTS = "activities_components.json"
        const val APP_COMPONENTS = "app_components.txt"
        const val LAUNCHER = "activity_launcher.txt"

        const val TARGET_APPLICATION = "_application_attrs"
        const val TARGET_ALL_ACTIVITIES = "_apply_for_all_activities"

        private val APP_COMPONENT_TAGS = setOf(
            "activity", "activity-alias", "service", "receiver", "provider",
            "meta-data", "uses-library", "uses-native-library", "property"
        )
        private val ACTIVITY_CHILD_TAGS = setOf("intent-filter", "meta-data", "layout", "property")
        private val LAUNCHER_NAME = Regex("[A-Za-z0-9_]+")
        private val ATTRIBUTE = Regex("""^\s*((?:[A-Za-z_][\w.\-]*:)?[A-Za-z_][\w.\-]*)\s*=\s*"([^"<]*)"\s*$""")

        fun parseAttribute(attribute: String): Pair<String, String> {
            val m = ATTRIBUTE.matchEntire(attribute)
                ?: throw IllegalArgumentException(
                    "attribute harus tepat satu atribut berformat prefix:nama=\"nilai\", mis. android:usesCleartextTraffic=\"true\" (diterima: '${attribute.take(80)}')."
                )
            return m.groupValues[1] to m.groupValues[2]
        }

        fun attributeKey(value: String): String = value.substringBefore('=').trim()

        /** `_application`/`application` -> application target, `all` -> every Activity, else an Activity class name. */
        fun normalizeTarget(target: String): String {
            val t = target.trim()
            require(t.isNotEmpty()) { "target/activity wajib diisi." }
            return when (t.lowercase()) {
                "application", "app", TARGET_APPLICATION -> TARGET_APPLICATION
                "all", "all_activities", TARGET_ALL_ACTIVITIES -> TARGET_ALL_ACTIVITIES
                else -> normalizeActivity(t)
            }
        }

        /** Screen name (`main`, `my_screen`) or class name -> Activity class name (`MainActivity`, `MyScreenActivity`). */
        fun normalizeActivity(activity: String): String {
            val a = activity.trim().removeSuffix(".java")
            require(a.isNotEmpty()) { "activity wajib diisi." }
            require(Regex("[A-Za-z0-9_]+").matches(a)) { "Nama activity '$a' tidak valid." }
            if (a.endsWith("Activity")) return a
            val camel = a.split('_').filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }
            return camel + "Activity"
        }

        fun namesMatch(actual: String, wanted: String): Boolean {
            if (actual.isEmpty()) return false
            if (actual == wanted) return true
            val simpleActual = actual.substringAfterLast('.')
            return !wanted.contains('.') && simpleActual == wanted ||
                wanted.startsWith(".") && simpleActual == wanted.removePrefix(".")
        }

        private fun exportedWarning(el: Element): String? {
            val needs = el.tagName in setOf("activity", "service", "receiver") &&
                XmlLite.childElements(el).any { it.tagName == "intent-filter" } ||
                el.tagName == "provider"
            if (!needs || el.hasAttribute("android:exported")) return null
            return "<${el.tagName} ${el.getAttribute("android:name")}> tidak punya android:exported; " +
                "Android 12+ (targetSdk 31+) mewajibkannya jika ada intent-filter."
        }
    }
}

/** Tool entry point for `manage_manifest`. */
object ManifestTool {

    fun execute(scId: String, args: JsonObject): String {
        val dir = File(
            AndroidManifestInjector.getPathAndroidManifestAttributeInjection(ProjectPaths.requireScId(scId)).parentFile!!.path
        )
        val store = ManifestInjectionStore(dir)
        return try {
            when (val action = args.argStr("action").ifEmpty { "list" }) {
                "list" -> store.describe()
                "set_application_attr" -> store.setAttribute(ManifestInjectionStore.TARGET_APPLICATION, args.argStr("attribute"))
                "remove_application_attr" -> store.removeAttribute(ManifestInjectionStore.TARGET_APPLICATION, args.argStr("attribute_name"))
                "set_activity_attr" -> store.setAttribute(args.argStr("activity").ifEmpty { "all" }, args.argStr("attribute"))
                "remove_activity_attr" -> store.removeAttribute(args.argStr("activity").ifEmpty { "all" }, args.argStr("attribute_name"))
                "add_app_component" -> store.addAppComponent(args.argStr("xml"))
                "remove_app_component" -> store.removeAppComponent(args.argStr("component_name"), args.argStr("tag").ifEmpty { null })
                "add_activity_component" -> store.addActivityComponent(args.argStr("activity"), args.argStr("xml"))
                "remove_activity_component" -> store.removeActivityComponent(args.argStr("activity"), args.argStr("contains").ifEmpty { null })
                "set_launcher" -> store.setLauncher(args.argStr("screen"))
                else -> "Error: action '$action' tidak dikenal pada manage_manifest."
            }
        } catch (e: IllegalArgumentException) {
            "Error: ${e.message}"
        } catch (e: IllegalStateException) {
            "Error: ${e.message}"
        }
    }
}
