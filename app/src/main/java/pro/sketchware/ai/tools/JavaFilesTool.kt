package pro.sketchware.ai.tools

import a.a.a.lC
import a.a.a.yB
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import pro.sketchware.utility.FilePathUtil
import java.io.File
import java.util.Locale

/** The three component lists the Java manager keeps per project (`data/<id>/java|service|broadcast`). */
class ManifestLists(private val javaFile: File, private val serviceFile: File, private val broadcastFile: File) {

    enum class Kind(val label: String) { ACTIVITY("Activity"), SERVICE("Service"), BROADCAST("BroadcastReceiver") }

    private fun fileOf(kind: Kind) = when (kind) {
        Kind.ACTIVITY -> javaFile
        Kind.SERVICE -> serviceFile
        Kind.BROADCAST -> broadcastFile
    }

    fun read(kind: Kind): List<String> {
        val f = fileOf(kind)
        if (!f.isFile) return emptyList()
        val text = f.readText().trim()
        if (text.isEmpty()) return emptyList()
        return try {
            JsonParser.parseString(text).asJsonArray.filter { it.isJsonPrimitive }.map { it.asString }
        } catch (e: Exception) {
            throw IllegalStateException("Daftar ${kind.label} (${f.name}) bukan JSON array valid: ${e.message?.take(80)}")
        }
    }

    private fun write(kind: Kind, list: List<String>) = FileIo.atomicWrite(fileOf(kind), Gson().toJson(list))

    fun contains(kind: Kind, fullName: String) = read(kind).contains(fullName)

    fun add(kind: Kind, fullName: String): Boolean {
        val list = read(kind).toMutableList()
        if (list.contains(fullName)) return false
        list.add(fullName)
        write(kind, list)
        return true
    }

    fun remove(kind: Kind, fullName: String): Boolean {
        val list = read(kind).toMutableList()
        if (!list.remove(fullName)) return false
        write(kind, list)
        return true
    }

    fun removeEverywhere(fullName: String): List<Kind> = Kind.values().filter { remove(it, fullName) }

    fun replaceEverywhere(oldName: String, newName: String): List<Kind> {
        val changed = ArrayList<Kind>()
        for (k in Kind.values()) {
            val list = read(k).toMutableList()
            val i = list.indexOf(oldName)
            if (i >= 0) {
                list[i] = newName
                write(k, list)
                changed.add(k)
            }
        }
        return changed
    }
}

/**
 * Java/Kotlin source manager: files live under `files/java/<sub/packages>/`; the package of a file
 * is `<project package>.<relative folder>` — the same rule the app's Java manager uses.
 */
class JavaFilesStore(private val javaRoot: File, private val projectPackage: String, private val lists: ManifestLists) {

    private val root: File get() = javaRoot.canonicalFile

    fun resolve(rel: String): File {
        val clean = rel.trim().trimStart('/', '\\')
        require(clean.isNotEmpty()) { "path wajib diisi (relatif terhadap files/java, mis. 'utils/Helper.java')." }
        val f = File(javaRoot, clean).canonicalFile
        require(f.path.startsWith(root.path + File.separator)) { "path berada di luar folder java proyek." }
        return f
    }

    fun expectedPackage(dir: File): String {
        val rel = dir.canonicalFile.relativeTo(root).path.replace(File.separatorChar, '.').trim('.')
        return if (rel.isEmpty()) projectPackage else "$projectPackage.$rel"
    }

    fun declaredPackage(text: String): String? = PACKAGE_DECL.find(text)?.groupValues?.get(1)

    fun fullName(file: File): String {
        val pkg = declaredPackage(file.readText()) ?: expectedPackage(file.parentFile)
        return "$pkg.${file.nameWithoutExtension}"
    }

    private fun sources(): List<File> =
        if (!javaRoot.isDirectory) emptyList()
        else javaRoot.walkTopDown().filter { it.isFile && (it.extension == "java" || it.extension == "kt") }.sortedBy { it.path }.toList()

    private fun rel(f: File) = f.canonicalFile.relativeTo(root).path.replace(File.separatorChar, '/')

    private fun registrations(full: String): String {
        val tags = ManifestLists.Kind.values().filter { lists.contains(it, full) }.map { it.label }
        return if (tags.isEmpty()) "-" else tags.joinToString("+")
    }

    // ---------- read ----------

    fun list(): String {
        val files = sources()
        if (files.isEmpty()) return "Belum ada berkas Java/Kotlin di ${javaRoot.path}. Paket proyek: $projectPackage"
        return buildString {
            appendLine("=== JAVA/KOTLIN (${files.size} berkas) — paket proyek: $projectPackage ===")
            for (f in files) {
                val full = fullName(f)
                appendLine("- ${rel(f)} | $full | manifest: ${registrations(full)} | ${f.length()} B")
            }
        }.trim()
    }

    fun listManifest(): String = buildString {
        appendLine("=== DAFTAR MANIFEST (Java manager) ===")
        for (k in ManifestLists.Kind.values()) {
            val items = lists.read(k)
            appendLine("${k.label} (${items.size}): " + items.joinToString(", ").ifEmpty { "-" })
        }
    }.trim()

    // ---------- write ----------

    fun create(
        subPackage: String,
        className: String,
        templateKind: String,
        language: String,
        content: String?,
        registerAs: String?,
        overwrite: Boolean
    ): String {
        require(IDENT.matches(className)) { "class_name '$className' bukan identifier yang valid." }
        val kotlin = language.equals("kotlin", true) || language.equals("kt", true)
        val ext = if (kotlin) "kt" else "java"
        val segments = subPackage.trim().replace('\\', '/').replace('.', '/').split('/').filter { it.isNotEmpty() }
        segments.forEach { require(IDENT.matches(it)) { "Segmen package '$it' tidak valid." } }
        val dir = segments.fold(javaRoot) { acc, s -> File(acc, s) }
        require(dir.canonicalFile.path.startsWith(root.path)) { "package berada di luar folder java proyek." }
        val target = File(dir, "$className.$ext")
        val sibling = File(dir, "$className.${if (kotlin) "java" else "kt"}")
        require(!sibling.exists()) { "Sudah ada ${sibling.name} dengan nama kelas yang sama." }
        require(overwrite || !target.exists()) { "${rel(target)} sudah ada. Pakai overwrite=true untuk menimpa." }
        dir.mkdirs()
        val pkg = expectedPackage(dir)
        val warnings = ArrayList<String>()
        val text = if (content.isNullOrBlank()) {
            template(templateKind, pkg, className, kotlin)
        } else {
            ensurePackage(content, pkg, kotlin, warnings)
        }
        FileIo.atomicWrite(target, text)
        val full = "$pkg.$className"
        val reg = registerAs?.takeIf { it.isNotBlank() }?.let { registerKind(it, full) }
        return buildString {
            append("Dibuat ${rel(target)} (package $pkg)")
            if (reg != null) append("\n").append(reg)
            warnings.forEach { append("\nPeringatan: ").append(it) }
        }
    }

    fun rename(relPath: String, newName: String): String {
        val src = resolve(relPath)
        require(src.isFile) { "${relPath.trim()} tidak ditemukan." }
        require(IDENT.matches(newName)) { "new_name '$newName' bukan identifier yang valid." }
        val oldName = src.nameWithoutExtension
        val dest = File(src.parentFile, "$newName.${src.extension}")
        require(!dest.exists()) { "${rel(dest)} sudah ada." }
        val oldFull = fullName(src)
        val wordOld = Regex("\\b${Regex.escape(oldName)}\\b")
        val updated = src.readText().replace(wordOld, newName)
        FileIo.atomicWrite(dest, updated)
        src.delete()
        val newFull = "${oldFull.substringBeforeLast('.', "")}.$newName".trimStart('.')
        val moved = lists.replaceEverywhere(oldFull, newFull)
        val referencing = sources().filter { it.canonicalPath != dest.canonicalPath && wordOld.containsMatchIn(it.readText()) }
        return buildString {
            append("Diganti nama ${rel(src)} -> ${rel(dest)}; nama kelas di dalam berkas diperbarui.")
            if (moved.isNotEmpty()) append("\nDaftar manifest diperbarui: ${moved.joinToString { it.label }} ($oldFull -> $newFull).")
            if (referencing.isNotEmpty()) {
                append("\nPeringatan: berkas lain masih menyebut '$oldName' dan TIDAK diubah otomatis: ")
                append(referencing.joinToString(", ") { rel(it) })
            }
        }
    }

    fun delete(relPath: String): String {
        val f = resolve(relPath)
        require(f.exists()) { "${relPath.trim()} tidak ditemukan." }
        require(f.isFile) { "Tool ini hanya menghapus berkas. Untuk folder gunakan manage_sketchware_files." }
        val full = fullName(f)
        val unregistered = lists.removeEverywhere(full)
        f.delete()
        return "Dihapus ${rel(f)}." + if (unregistered.isNotEmpty()) " Dihapus juga dari daftar manifest: ${unregistered.joinToString { it.label }}." else ""
    }

    fun register(relPath: String, kind: String): String {
        val f = resolve(relPath)
        require(f.isFile) { "${relPath.trim()} tidak ditemukan." }
        return registerKind(kind, fullName(f))
    }

    fun unregister(relPath: String, kind: String?): String {
        val f = resolve(relPath)
        require(f.isFile) { "${relPath.trim()} tidak ditemukan." }
        val full = fullName(f)
        val removed = if (kind.isNullOrBlank()) lists.removeEverywhere(full) else parseKind(kind).let { k ->
            if (lists.remove(k, full)) listOf(k) else emptyList()
        }
        return if (removed.isEmpty()) "$full tidak terdaftar di manifest." else "$full dihapus dari: ${removed.joinToString { it.label }}."
    }

    private fun registerKind(kind: String, full: String): String {
        val k = parseKind(kind)
        // The app treats Activity and Service as mutually exclusive for one class.
        if (k == ManifestLists.Kind.ACTIVITY && lists.contains(ManifestLists.Kind.SERVICE, full)) {
            throw IllegalArgumentException("$full sudah terdaftar sebagai Service; unregister dulu sebelum menjadikannya Activity.")
        }
        if (k == ManifestLists.Kind.SERVICE && lists.contains(ManifestLists.Kind.ACTIVITY, full)) {
            throw IllegalArgumentException("$full sudah terdaftar sebagai Activity; unregister dulu sebelum menjadikannya Service.")
        }
        return if (lists.add(k, full)) "$full didaftarkan sebagai ${k.label} di manifest."
        else "$full sudah terdaftar sebagai ${k.label}."
    }

    // ---------- validation ----------

    fun validate(relPath: String?): String {
        val files = if (relPath.isNullOrBlank()) sources() else listOf(resolve(relPath).also { require(it.isFile) { "$relPath tidak ditemukan." } })
        val issues = ArrayList<String>()
        for (f in files) {
            val text = f.readText()
            val expected = expectedPackage(f.parentFile)
            val declared = declaredPackage(text)
            if (declared == null) issues.add("${rel(f)}: tidak ada deklarasi package (seharusnya '$expected').")
            else if (declared != expected) issues.add("${rel(f)}: package '$declared' tidak sama dengan lokasi folder ('$expected').")
            val clean = stripNoise(text)
            if (f.extension == "java") {
                val pub = PUBLIC_TYPE.find(clean)?.groupValues?.get(2)
                if (pub != null && pub != f.nameWithoutExtension) issues.add("${rel(f)}: tipe public '$pub' harus sama dengan nama berkas.")
            }
            val open = clean.count { it == '{' }
            val close = clean.count { it == '}' }
            if (open != close) issues.add("${rel(f)}: jumlah '{' ($open) dan '}' ($close) tidak seimbang (kemungkinan sintaks rusak).")
        }
        if (relPath.isNullOrBlank()) {
            for (k in ManifestLists.Kind.values()) {
                for (full in lists.read(k)) {
                    if (!sourceExistsFor(full)) issues.add("${k.label} '$full' terdaftar di manifest tetapi berkasnya tidak ada.")
                }
            }
        }
        return if (issues.isEmpty()) "OK: ${files.size} berkas diperiksa, tidak ada masalah (ini bukan pengganti kompilasi — jalankan save_and_verify_compile)."
        else "Ditemukan ${issues.size} masalah pada ${files.size} berkas:\n" + issues.joinToString("\n") { "- $it" }
    }

    private fun sourceExistsFor(full: String): Boolean {
        val cls = full.substringAfterLast('.')
        val pkg = full.substringBeforeLast('.', "")
        val relPkg = when {
            pkg == projectPackage -> ""
            pkg.startsWith("$projectPackage.") -> pkg.removePrefix("$projectPackage.").replace('.', '/')
            else -> pkg.replace('.', '/')
        }
        val dir = if (relPkg.isEmpty()) javaRoot else File(javaRoot, relPkg)
        return File(dir, "$cls.java").isFile || File(dir, "$cls.kt").isFile
    }

    private fun parseKind(kind: String): ManifestLists.Kind = when (kind.trim().lowercase(Locale.ROOT)) {
        "activity" -> ManifestLists.Kind.ACTIVITY
        "service" -> ManifestLists.Kind.SERVICE
        "broadcast", "receiver", "broadcastreceiver" -> ManifestLists.Kind.BROADCAST
        else -> throw IllegalArgumentException("kind '$kind' tidak dikenal. Pilihan: activity, service, broadcast.")
    }

    companion object {
        private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val PACKAGE_DECL = Regex("(?m)^\\s*package\\s+([\\w.]+)\\s*;?")
        private val PUBLIC_TYPE = Regex("\\bpublic\\s+(?:abstract\\s+|final\\s+|static\\s+)*(class|interface|enum)\\s+(\\w+)")
        private val NOISE = Regex("//[^\\n]*|/\\*.*?\\*/|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'", RegexOption.DOT_MATCHES_ALL)

        fun stripNoise(src: String): String = NOISE.replace(src, "")

        fun ensurePackage(content: String, pkg: String, kotlin: Boolean, warnings: MutableList<String>): String {
            val decl = if (kotlin) "package $pkg" else "package $pkg;"
            val m = PACKAGE_DECL.find(content)
            if (m == null) {
                warnings.add("konten tidak punya deklarasi package; ditambahkan '$decl'.")
                return "$decl\n\n${content.trimStart()}"
            }
            if (m.groupValues[1] != pkg) {
                warnings.add("package '${m.groupValues[1]}' disesuaikan menjadi '$pkg' mengikuti lokasi folder.")
                return content.replaceRange(m.range, m.value.replace(m.groupValues[1], pkg))
            }
            return content
        }

        fun template(kind: String, pkg: String, name: String, kotlin: Boolean): String {
            val t = when (kind.trim().lowercase(Locale.ROOT).ifEmpty { "class" }) {
                "activity" -> if (kotlin) KT_ACTIVITY else JAVA_ACTIVITY
                "service" -> if (kotlin) KT_SERVICE else JAVA_SERVICE
                "receiver", "broadcast", "broadcastreceiver" -> if (kotlin) KT_RECEIVER else JAVA_RECEIVER
                "class" -> if (kotlin) KT_CLASS else JAVA_CLASS
                else -> throw IllegalArgumentException("template '$kind' tidak dikenal. Pilihan: activity, service, receiver, class.")
            }
            return String.format(Locale.US, t, pkg, name)
        }

        private const val JAVA_CLASS = "package %s;\n\npublic class %s {\n\n}\n"
        private const val JAVA_ACTIVITY = "package %s;\n\nimport android.app.Activity;\nimport android.os.Bundle;\n\npublic class %s extends Activity {\n\n    @Override\n    protected void onCreate(Bundle savedInstanceState) {\n        super.onCreate(savedInstanceState);\n    }\n}\n"
        private const val JAVA_SERVICE = "package %s;\n\nimport android.app.Service;\nimport android.content.Intent;\nimport android.os.IBinder;\n\npublic class %s extends Service {\n\n    @Override\n    public IBinder onBind(Intent intent) {\n        return null;\n    }\n}\n"
        private const val JAVA_RECEIVER = "package %s;\n\nimport android.content.BroadcastReceiver;\nimport android.content.Context;\nimport android.content.Intent;\n\npublic class %s extends BroadcastReceiver {\n\n    @Override\n    public void onReceive(Context context, Intent intent) {\n    }\n}\n"
        private const val KT_CLASS = "package %s\n\nclass %s {\n\n}\n"
        private const val KT_ACTIVITY = "package %s\n\nimport android.app.Activity\nimport android.os.Bundle\n\nclass %s : Activity() {\n\n    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n    }\n}\n"
        private const val KT_SERVICE = "package %s\n\nimport android.app.Service\nimport android.content.Intent\nimport android.os.IBinder\n\nclass %s : Service() {\n\n    override fun onBind(intent: Intent?): IBinder? = null\n}\n"
        private const val KT_RECEIVER = "package %s\n\nimport android.content.BroadcastReceiver\nimport android.content.Context\nimport android.content.Intent\n\nclass %s : BroadcastReceiver() {\n\n    override fun onReceive(context: Context?, intent: Intent?) {\n    }\n}\n"
    }
}

/** Tool entry point for `manage_java_files`. */
object JavaFilesTool {

    fun execute(scId: String, args: JsonObject): String {
        ProjectPaths.requireScId(scId)
        val fpu = FilePathUtil()
        val pkg = projectPackage(scId)
        if (pkg.isEmpty()) return "Error: paket proyek untuk sc_id=$scId tidak ditemukan."
        val store = JavaFilesStore(
            File(fpu.getPathJava(scId)), pkg,
            ManifestLists(File(fpu.getManifestJava(scId)), File(fpu.getManifestService(scId)), File(fpu.getManifestBroadcast(scId)))
        )
        return try {
            when (val action = args.argStr("action").ifEmpty { "list" }) {
                "list" -> store.list()
                "list_manifest" -> store.listManifest()
                "create" -> store.create(
                    subPackage = args.argStr("package_subpath"),
                    className = args.argStr("class_name"),
                    templateKind = args.argStr("template"),
                    language = args.argStr("language"),
                    content = args.get("content")?.takeIf { !it.isJsonNull }?.asString,
                    registerAs = args.argStr("register_as").ifEmpty { null },
                    overwrite = args.argBool("overwrite", false)
                )
                "rename" -> store.rename(args.argStr("path"), args.argStr("new_name"))
                "delete" -> store.delete(args.argStr("path"))
                "validate" -> store.validate(args.argStr("path").ifEmpty { null })
                "register_manifest" -> store.register(args.argStr("path"), args.argStr("kind"))
                "unregister_manifest" -> store.unregister(args.argStr("path"), args.argStr("kind").ifEmpty { null })
                else -> "Error: action '$action' tidak dikenal pada manage_java_files."
            }
        } catch (e: IllegalArgumentException) {
            "Error: ${e.message}"
        } catch (e: IllegalStateException) {
            "Error: ${e.message}"
        }
    }

    private fun projectPackage(scId: String): String {
        val list = lC.a() ?: return ""
        val map = list.firstOrNull { yB.c(it, "sc_id") == scId } ?: return ""
        return yB.c(map, "my_sc_pkg_name")
    }
}
