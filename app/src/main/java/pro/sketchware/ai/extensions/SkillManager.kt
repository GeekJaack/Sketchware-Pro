package pro.sketchware.ai.extensions

import android.os.Environment
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import pro.sketchware.ai.tools.WebTools
import java.io.File

data class AgentSkill(
    val id: String,
    val name: String,
    val description: String,
    val triggers: List<String>,
    val dirPath: String,
    val skillFilePath: String,
    val source: String, // "user", "builtin", or "plugin:<id>"
    val enabled: Boolean,
    val scripts: List<String> = emptyList(),
    val references: List<String> = emptyList()
)

/**
 * Manages `SKILL.md`-based skills (compatible with Claude Code / Google Antigravity skill folders).
 *
 * Directory layout:
 * - `.sketchware/ai/skills/<skill-id>/SKILL.md`
 * - `.sketchware/ai/skills/<skill-id>/scripts/...`
 * - `.sketchware/ai/skills/<skill-id>/references/...`
 * - `.sketchware/ai/plugins/<plugin-id>/skills/<skill-id>/SKILL.md`
 */
object SkillManager {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private var seeded = false

    fun aiRootDir(): File {
        val ext = try {
            Environment.getExternalStorageDirectory()?.let { File(it, ".sketchware/ai") }
        } catch (_: Throwable) {
            null
        }
        if (ext != null && (ext.isDirectory || ext.mkdirs())) {
            return ext
        }
        return File(pro.sketchware.ai.shell.LinuxSubsystem.baseDir(), "ai_extensions").apply { mkdirs() }
    }

    fun skillsRootDir(): File = File(aiRootDir(), "skills").apply { mkdirs() }

    private fun stateFile(): File = File(aiRootDir(), "skills_state.json")

    @Synchronized
    fun ensureSeeded() {
        if (seeded) return
        seeded = true
        try {
            seedBuiltInSkills()
        } catch (_: Throwable) {
        }
    }

    private fun loadDisabledSet(): MutableSet<String> {
        val f = stateFile()
        if (!f.isFile) return mutableSetOf()
        return try {
            val obj = JsonParser.parseString(f.readText()).asJsonObject
            val arr = obj.getAsJsonArray("disabled") ?: return mutableSetOf()
            arr.mapNotNull { if (it.isJsonPrimitive) it.asString else null }.toMutableSet()
        } catch (_: Throwable) {
            mutableSetOf()
        }
    }

    private fun saveDisabledSet(disabled: Set<String>) {
        try {
            val obj = JsonObject().apply {
                add("disabled", JsonArray().apply { disabled.sorted().forEach { add(it) } })
            }
            stateFile().writeText(gson.toJson(obj))
        } catch (_: Throwable) {
        }
    }

    fun setSkillEnabled(skillId: String, enabled: Boolean): Boolean {
        ensureSeeded()
        val cleanId = sanitizeId(skillId)
        val all = listAllSkills()
        if (all.none { it.id.equals(cleanId, ignoreCase = true) }) return false
        val disabled = loadDisabledSet()
        if (enabled) disabled.remove(cleanId) else disabled.add(cleanId)
        saveDisabledSet(disabled)
        return true
    }

    fun listAllSkills(): List<AgentSkill> {
        ensureSeeded()
        val disabled = loadDisabledSet()
        val result = mutableListOf<AgentSkill>()
        val seenIds = mutableSetOf<String>()

        // 1. Standalone skills in .sketchware/ai/skills/
        val root = skillsRootDir()
        root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            val md = File(dir, "SKILL.md")
            if (md.isFile) {
                val skill = parseSkillDir(dir, md, source = "standalone", disabled = disabled)
                if (seenIds.add(skill.id)) {
                    result.add(skill)
                }
            }
        }

        // 2. Skills bundled inside enabled plugins
        for (plugin in PluginManager.listPlugins()) {
            if (!plugin.enabled) continue
            val pluginSkillsDir = File(plugin.dirPath, "skills")
            if (!pluginSkillsDir.isDirectory) continue
            pluginSkillsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
                val md = File(dir, "SKILL.md")
                if (md.isFile) {
                    val skill = parseSkillDir(dir, md, source = "plugin:${plugin.id}", disabled = disabled)
                    if (seenIds.add(skill.id)) {
                        result.add(skill)
                    }
                }
            }
        }

        return result
    }

    fun listEnabledSkills(): List<AgentSkill> = listAllSkills().filter { it.enabled }

    fun readSkillContent(skillId: String): String {
        ensureSeeded()
        val cleanId = sanitizeId(skillId)
        val skill = listAllSkills().firstOrNull {
            it.id.equals(cleanId, ignoreCase = true) || it.name.equals(skillId.trim(), ignoreCase = true)
        } ?: return "Error: Skill '$skillId' tidak ditemukan. Gunakan use_skill(action=\"list\") untuk melihat daftar skill."

        val mdFile = File(skill.skillFilePath)
        if (!mdFile.isFile) return "Error: File SKILL.md tidak ditemukan di ${skill.skillFilePath}"

        return buildString {
            appendLine("=== SKILL: ${skill.name} (${skill.id}) ===")
            appendLine("Source : ${skill.source}")
            appendLine("Folder : ${skill.dirPath}")
            if (skill.scripts.isNotEmpty()) {
                appendLine("Scripts: ${skill.scripts.joinToString(", ")}")
            }
            if (skill.references.isNotEmpty()) {
                appendLine("Refs   : ${skill.references.joinToString(", ")}")
            }
            appendLine()
            append(mdFile.readText())
        }.trim()
    }

    fun createOrUpdateSkill(
        id: String,
        name: String,
        description: String,
        bodyMarkdown: String,
        triggers: List<String> = emptyList()
    ): AgentSkill {
        ensureSeeded()
        val cleanId = sanitizeId(id.ifBlank { name })
        if (cleanId.isBlank()) throw IllegalArgumentException("ID atau nama skill tidak boleh kosong.")
        val dir = File(skillsRootDir(), cleanId).apply { mkdirs() }
        val mdFile = File(dir, "SKILL.md")

        val fullContent = if (bodyMarkdown.trimStart().startsWith("---")) {
            bodyMarkdown
        } else {
            buildString {
                appendLine("---")
                appendLine("name: ${name.ifBlank { cleanId }}")
                appendLine("description: ${description.replace("\n", " ").trim()}")
                if (triggers.isNotEmpty()) {
                    appendLine("triggers: ${triggers.joinToString(", ")}")
                }
                appendLine("---")
                appendLine()
                appendLine(bodyMarkdown.trim())
            }
        }
        mdFile.writeText(fullContent)
        val disabled = loadDisabledSet()
        disabled.remove(cleanId)
        saveDisabledSet(disabled)
        return parseSkillDir(dir, mdFile, "standalone", disabled)
    }

    fun installSkillFromUrl(url: String, customId: String? = null): String {
        ensureSeeded()
        val res = WebTools.request(url.trim())
        if (res.code >= 400) {
            return "Error: Gagal mengunduh skill dari $url (HTTP ${res.code})"
        }
        val text = res.body
        if (text.isBlank()) {
            return "Error: Konten dari $url kosong."
        }
        val parsedMeta = parseFrontmatter(text)
        val rawId = customId?.takeIf { it.isNotBlank() }
            ?: parsedMeta.first["name"]
            ?: url.substringBefore('?').trimEnd('/').substringAfterLast('/').removeSuffix(".md").removeSuffix("SKILL")
                .ifBlank { "custom-skill-${System.currentTimeMillis() % 10000}" }
        val cleanId = sanitizeId(rawId)
        val name = parsedMeta.first["name"] ?: cleanId
        val desc = parsedMeta.first["description"] ?: "Skill diimpor dari $url"
        val created = createOrUpdateSkill(cleanId, name, desc, text)
        return "Skill '${created.name}' (${created.id}) berhasil dipasang ke ${created.skillFilePath}"
    }

    fun deleteSkill(skillId: String): Boolean {
        val cleanId = sanitizeId(skillId)
        val dir = File(skillsRootDir(), cleanId)
        if (!dir.isDirectory) return false
        return dir.deleteRecursively()
    }

    fun buildSystemPromptSection(): String {
        val enabled = listEnabledSkills()
        if (enabled.isEmpty()) return ""
        return buildString {
            appendLine("=== SKILLS TERSEDIA (SKILL.md) ===")
            appendLine("Jika tugas pengguna relevan dengan salah satu skill di bawah, panggil `use_skill(action=\"read\", skill_id=\"<id>\")` terlebih dahulu untuk membaca instruksi lengkapnya:")
            for (s in enabled) {
                appendLine("- `${s.id}` (${s.name}): ${s.description} [path: ${s.skillFilePath}]")
            }
        }.trim()
    }

    private fun parseSkillDir(
        dir: File,
        mdFile: File,
        source: String,
        disabled: Set<String>
    ): AgentSkill {
        val raw = try {
            mdFile.readText()
        } catch (_: Throwable) {
            ""
        }
        val (meta, body) = parseFrontmatter(raw)
        val id = sanitizeId(dir.name)
        val name = meta["name"]?.takeIf { it.isNotBlank() } ?: id
        val desc = meta["description"]?.takeIf { it.isNotBlank() }
            ?: body.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?.take(180)
            ?: "Skill panduan khusus untuk $name"
        val triggers = meta["triggers"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val scripts = File(dir, "scripts").takeIf { it.isDirectory }
            ?.listFiles()?.map { it.name }?.sorted() ?: emptyList()
        val references = File(dir, "references").takeIf { it.isDirectory }
            ?.listFiles()?.map { it.name }?.sorted() ?: emptyList()

        return AgentSkill(
            id = id,
            name = name,
            description = desc,
            triggers = triggers,
            dirPath = dir.absolutePath,
            skillFilePath = mdFile.absolutePath,
            source = source,
            enabled = id !in disabled,
            scripts = scripts,
            references = references
        )
    }

    internal fun parseFrontmatter(markdown: String): Pair<Map<String, String>, String> {
        val trimmed = markdown.trimStart()
        if (!trimmed.startsWith("---")) return emptyMap<String, String>() to markdown
        val lines = trimmed.lines()
        var endIdx = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") {
                endIdx = i
                break
            }
        }
        if (endIdx == -1) return emptyMap<String, String>() to markdown
        val map = mutableMapOf<String, String>()
        var currentKey: String? = null
        val currentVal = StringBuilder()

        fun flushKey() {
            val k = currentKey ?: return
            map[k] = currentVal.toString().trim().removeSurrounding("\"").removeSurrounding("'")
            currentKey = null
            currentVal.setLength(0)
        }

        for (i in 1 until endIdx) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon > 0 && !line.startsWith(" ") && !line.startsWith("\t")) {
                flushKey()
                currentKey = line.substring(0, colon).trim().lowercase()
                val rest = line.substring(colon + 1).trim()
                if (rest != "|" && rest != ">") {
                    currentVal.append(rest)
                }
            } else if (currentKey != null) {
                if (currentVal.isNotEmpty()) currentVal.append(' ')
                currentVal.append(line.trim())
            }
        }
        flushKey()
        val body = lines.drop(endIdx + 1).joinToString("\n").trim()
        return map to body
    }

    fun sanitizeId(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-').take(64)

    private fun seedBuiltInSkills() {
        val marker = File(skillsRootDir(), ".seeded_v2")
        if (marker.isFile) return

        val builtIns = listOf(
            Triple(
                "patent-principal-software-engineer",
                "Patent Skill: Principal Software Engineer & Android App Architect",
                "Doktrin paten rekayasa perangkat lunak end-to-end: arsitektur aplikasi Android skala produksi, penguasaan penuh ViewBean/BlockBean/ComponentBean/Custom Java, defensive programming, dan otonomi penuh hingga BUILD SUKSES."
            ) to """
                # Patent Skill: Principal Software Engineer & Android App Architect

                Skill paten ini menetapkan standar tertinggi saat AI Agent merancang, membangun, merefaktor, atau menyelesaikan proyek aplikasi Android di Sketchware Pro:

                ## 1. Deconstruct & Plan (Zero Ambiguity)
                - Sebelum menyentuh kode pada tugas kompleks, selalu buat/perbarui **Long-Horizon Plan** (`update_long_horizon_plan`) yang memecah pekerjaan ke dalam milestone terukur:
                  1. **Architecture & Assets/Libraries Setup** (`manage_project_resources`, `manage_local_libraries`, `configure_project_metadata`)
                  2. **Multi-Screen UI & CustomView XML Construction** (`create_screen`, `write_screen_xml`)
                  3. **Data Layer, Models & Custom Java Classes** (`write_custom_java_file`, `manage_variable`, `manage_list`, `add_component`)
                  4. **Interactive Event Logic & Business Flows** (`write_event_logic`, `create_more_block`)
                  5. **End-to-End Compile Verification & Self-Healing** (`save_and_verify_compile`)

                ## 2. Production-Grade Android Architecture inside Sketchware Pro
                - **Separation of Concerns**:
                  - Untuk logika bisnis yang kompleks (seperti database helper SQLite, repositori API, enkripsi, parser, atau state manager), buat kelas Java modular menggunakan `write_custom_java_file(file_name="...", source_code="...")` di paket proyek, lalu panggil dari blok Activity!
                  - Untuk daftar dinamis (`ListView`), selalu buat `CustomView` (`create_screen(screen_name="item_...", is_custom_view=true)`) dan hubungkan dengan `ListMap` + event `onBindCustomView`.
                - **Defensive & Crash-Proof Engineering**:
                  - Bungkus operasi I/O, parsing JSON (`Gson`/`JSONObject`), konversi angka (`Double.parseDouble`), dan akses indeks list di dalam `try { ... } catch (Exception _e) { ... }`.
                  - Pastikan setiap pembaruan UI dari thread latar belakang dijalankan di dalam `runOnUiThread(new Runnable() { @Override public void run() { ... } });`.
                  - Sediakan state kosong (*empty state*), indikator pemuatan (*loading state*), dan umpan balik pengguna yang jelas.

                ## 3. Unlimited Long-Horizon Autonomy (Jangan Berhenti Sebelum Tuntas)
                - Anda berjalan dengan mode **Unlimited Steps (`∞`)**. Jangan pernah menyerahkan kode setengah jadi atau meminta pengguna melanjutkan sendiri.
                - Teruslah bekerja melintasi seluruh layar, event, dan resource sampai semua task di `plan.json` berstatus `COMPLETED` dan `save_and_verify_compile` menghasilkan **`BUILD SUKSES!`**.
            """.trimIndent(),

            Triple(
                "patent-terminal-linux-commander",
                "Patent Skill: Full Terminal & Debian Linux Workspace Commander",
                "Penguasaan penuh atas terminal Android & Debian Linux (PRoot), otomasi Python3/Bash, manipulasi filesystem proyek, pembuatan aset/database, dan manajemen sesi PTY."
            ) to """
                # Patent Skill: Full Terminal & Debian Linux Workspace Commander

                Anda memegang kendali penuh atas terminal di AI Agent Studio (`run_shell`, `terminal_start`, `terminal_send`, `terminal_read`, `terminal_list`, `terminal_stop`, `terminal_resize`, `manage_linux_env`, serta MCP `debian_toolkit`).

                ## 1. Peta Direktori Penting di Terminal
                - **Data Proyek Aktif (`@project/`)**: `/storage/emulated/0/.sketchware/data/<sc_id>/`
                  - `files/java/` : Kelas Java kustom proyek
                  - `files/resource/` : Resource Android kustom (`drawable/`, `values/`, `xml/`, `images/`, `fonts/`, `sounds/`)
                  - `files/assets/` : File aset bundel APK (`assets/`)
                  - `files/rea_workspace/` : Hasil ekstraksi & dekompilasi REA APK
                  - `ai_artifacts/` : Deliverable di panel **📦 Artifacts**
                - **Output Build & Source Proyek (`@mysc/`)**: `/storage/emulated/0/.sketchware/mysc/<sc_id>/`
                - **Local Libraries (`@libs/`)**: `/storage/emulated/0/.sketchware/libs/local_libs/`

                ## 1b. Cheat Sheet Jalur Fitur Sketchware (`@project/` = `.sketchware/data/<sc_id>/`)
                - `@project/Injection/androidmanifest/` : `attributes.json`, `activity_launcher.txt`, `activities_components.json`, `app_components.txt` (manifest injection)
                - `@project/permission`, `@project/java`, `@project/service`, `@project/broadcast` : daftar JSON permission, Activity, Service, dan BroadcastReceiver yang terdaftar di manifest
                - `@project/files/java/`, `@project/files/service/`, `@project/files/broadcast/` : sumber Java/Kotlin
                - `@project/files/native_libs/<abi>/` : file `.so`; `@project/files/kt_plugins/` : plugin Kotlin compiler
                - `@project/files/resource/values*/` : `strings.xml`, `colors.xml`, `styles.xml`, `themes.xml`
                - `@project/proguard-rules.pro` : aturan ProGuard/R8
                - Pakai tool khusus (`manage_manifest`, `manage_permissions`, `manage_res_values`, `manage_java_files`) bila tersedia; menulis berkas mentah hanya jika tidak ada tool yang sesuai.

                ## 2. Kapan & Bagaimana Menguasai Terminal Secara Leluasa
                - **Otomasi Skrip Python 3 (`python3 -c "..."` atau `mcp__debian_toolkit__python_eval`)**:
                  - Gunakan Python 3 untuk men-generate dataset JSON/SQLite awal di `files/assets/`, memproses ratusan baris XML/Java secara batch, menghasilkan gambar/ikon SVG/PNG, atau memvalidasi sintaks JSON/XML.
                - **Inspeksi & Pemrosesan Binari/Arsip (`unzip`, `javap`, `strings`, `sqlite3`, `curl`, `git`)**:
                  - Gunakan `unzip -l` / `unzip -p` untuk memeriksa isi `.apk`, `.aar`, atau `.jar`.
                  - Gunakan `curl -L` atau `git clone` untuk mengambil resource open-source, template web untuk `WebView`, atau library `.jar` langsung ke dalam proyek.
                - **Sesi PTY Interaktif & Background Daemon (`terminal_start`)**:
                  - Gunakan `terminal_start(engine="linux", distro="debian", pty=true)` ketika membutuhkan shell persisten yang mempertahankan state direktori/environment variables atau menjalankan proses background.
            """.trimIndent(),

            Triple(
                "patent-rea-apk-cloner",
                "Patent Skill: REA Reverse Engineering & Full APK Project Cloner",
                "Metodologi paten Reverse Engineer Anything (REA) untuk membedah file APK yang dilampirkan pengguna dan meng-clone menjadi proyek Sketchware Pro utuh hingga terkompilasi."
            ) to """
                # Patent Skill: REA Reverse Engineering & Full APK Project Cloner

                Gunakan skill paten ini setiap kali pengguna melampirkan file `.apk` (melalui `+ Add Context -> Tambahkan File APK`) atau meminta untuk melakukan reverse engineering / full clone APK ke proyek Sketchware Pro:

                ## Tahap 1: Eksekusi Otomatis `reconstruct_apk_project(action="full_clone")`
                1. Panggil `reconstruct_apk_project(action="full_clone", apk_path="<path_apk>")`.
                2. Tool ini secara otomatis akan:
                   - Membuat snapshot checkpoint proyek sebelum modifikasi.
                   - Mendekode binary `AndroidManifest.xml` dan menyinkronkan seluruh `<uses-permission>`.
                   - Mengekstrak & mendaftarkan seluruh `drawable` (`res/drawable*`, `res/mipmap*`), `assets/*`, dan `fonts/*` ke dalam proyek Sketchware.
                   - Mendekompilasi seluruh layout binary `res/layout/*.xml`, mengadaptasi tag ke format `ViewBean` Sketchware, dan membuat layar Activity serta CustomView secara otomatis.
                   - Menghasilkan dokumen **`rea_clone_blueprint.md`** di panel **📦 Artifacts**.

                ## Tahap 2: Bedah Mendalam DEX Bytecode & Layout Asli
                1. Baca laporan kelas DEX atau panggil `reconstruct_apk_project(action="analyze_dex", apk_path="<path_apk>", query="<NamaActivityAtauFitur>")` (atau `mcp__rea_mcp__rea_analyze_dex`).
                2. Bila ingin memeriksa detail atribut asli dari layout tertentu sebelum diadaptasi, panggil `reconstruct_apk_project(action="decode_xml", apk_path="<path_apk>", xml_entry="res/layout/<nama>.xml")` atau baca dari `.sketchware/data/<sc_id>/files/rea_workspace/layouts/`.
                3. Identifikasi untuk setiap Activity:
                   - Daftar field (`SharedPreferences`, `RequestNetwork`, `ArrayList<HashMap>`, variabel state, adapter).
                   - Daftar method & algoritma bisnis, endpoint URL API, atau skema tabel SQLite.

                ## Tahap 3: Rekonstruksi Logika Penuh (`BlockBean` + `Custom Java`)
                1. Untuk setiap Activity yang telah di-clone, daftarkan variabel (`manage_variable`), list (`manage_list`), dan komponen (`add_component`: `Intent`, `SharedPreferences`, `RequestNetwork`, `Dialog`, `Timer`, dll.).
                2. Tulis blok logika di `onCreate_initializeLogic` (termasuk styling `GradientDrawable` agar tampilan identik dengan APK asli) serta seluruh event klik tombol/list menggunakan `write_event_logic`.
                3. Jika APK asli memiliki class model/helper/adapter kustom di DEX, buat ulang class Java tersebut menggunakan `write_custom_java_file`.

                ## Tahap 4: Verifikasi Kompilasi & Self-Healing (`save_and_verify_compile`)
                1. Jalankan `save_and_verify_compile(full_apk_build=true)`.
                2. Jika ada simbol/resource/variabel yang belum cocok, perbaiki secara otonom hingga proyek hasil clone menghasilkan **`BUILD SUKSES!`**.
            """.trimIndent(),

            Triple(
                "material-ui-architect",
                "Material UI & Styling Architect",
                "Panduan mendesain layout XML Sketchware Pro yang modern, proporsional, dengan kartu rounded, bayangan, palet warna Material 3, dan styling GradientDrawable."
            ) to """
                # Material UI & Styling Architect (Sketchware Pro)

                Gunakan skill ini saat merancang layar baru atau mempercantik tampilan XML di Sketchware Pro:

                ## 1. Struktur Layout XML (`write_screen_xml`)
                - Selalu gunakan root `<LinearLayout>` vertikal dengan `android:background="#F8FAFC"` (mode terang) atau `#0F172A` (mode gelap).
                - Buat **Header / AppBar Kustom** dengan `LinearLayout` horizontal (`padding="16dp"`, `gravity="center_vertical"`).
                - Bungkus konten utama di dalam `<ScrollView android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:fillViewport="true">`.
                - Setiap View wajib memiliki `android:id="@+id/..."` yang deskriptif (contoh: `linear_card_hero`, `tv_saldo_utama`, `btn_aksi_simpan`).

                ## 2. Styling Sudut Membulat (Rounded Card) & Ripple di `initializeLogic`
                Karena atribut XML standar Sketchware terbatas, tambahkan blok `addSourceDirectly` di `onCreate` (`initializeLogic`) untuk mempercantik kartu dan tombol:
                ```java
                android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
                cardBg.setColor(0xFFFFFFFF);
                cardBg.setCornerRadius((int) getDip(16));
                cardBg.setStroke((int) getDip(1), 0xFFE2E8F0);
                linear_card_hero.setBackground(cardBg);
                linear_card_hero.setElevation(getDip(3));
                ```
            """.trimIndent(),

            Triple(
                "rest-api-networking",
                "REST API & JSON Integration",
                "Pola lengkap integrasi HTTP/HTTPS REST API, RequestNetwork component, dan parsing JSON (Gson / org.json) di proyek Sketchware Pro."
            ) to """
                # REST API & JSON Integration (Sketchware Pro)

                ## 1. Menggunakan Komponen `RequestNetwork`
                1. Panggil `add_component(screen_name="main", component_type="RequestNetwork", component_id="reqNet")`
                2. Tambahkan blok pada tombol/onCreate:
                   - `requestNetworkSetHeaders` (opsional)
                   - `requestNetworkStartRequestNetwork("reqNet", "GET", "https://api.example.com/data", "tag_load")`
                3. Isi event `target_id="reqNet"`, `event_name="onResponse"` dan `event_name="onErrorResponse"`.

                ## 2. Parsing JSON yang Aman
                Gunakan `com.google.gson.JsonParser` atau `org.json.JSONObject` di dalam `addSourceDirectly` dengan blok `try { ... } catch (Exception e) { ... }` agar aplikasi tidak pernah mengalami Force Close jika respons API berubah.
            """.trimIndent(),

            Triple(
                "linux-jar-dex-builder",
                "Linux Debian JAR/DEX Library Builder",
                "Cara mengunduh library .jar dari Maven Central menggunakan terminal Debian (curl/python3) dan memasangnya ke local_libs Sketchware Pro."
            ) to """
                # Linux Debian JAR/DEX Library Builder

                Saat proyek membutuhkan library pihak ketiga yang belum ada di `.sketchware/libs/local_libs/`:
                1. Cari koordinat Maven dengan `web_search(query="<nama-library> maven central jar")` atau `web_fetch`.
                2. Buat folder library di `/storage/emulated/0/.sketchware/libs/local_libs/<nama_lib>/`.
                3. Gunakan `run_shell` (Debian) dengan `curl -L -o /storage/emulated/0/.sketchware/libs/local_libs/<nama_lib>/classes.jar <url-maven-jar>`.
                4. Aktifkan library ke proyek menggunakan `manage_local_libraries(action="enable", library_names="<nama_lib>")`, lalu verifikasi dengan `save_and_verify_compile`.
            """.trimIndent(),

            Triple(
                "autonomous-bug-healer",
                "Autonomous Compile & Runtime Bug Healer",
                "Prosedur diagnosis sistematis untuk memperbaiki error kompilasi ECJ/AAPT2, variabel/komponen yang belum terdaftar, dan runtime Force Close."
            ) to """
                # Autonomous Compile & Runtime Bug Healer

                1. Jalankan `save_and_verify_compile(full_apk_build=false)` untuk melihat error log kompilasi.
                2. Jika error `cannot find symbol variable <nama>`:
                   - Periksa apakah ID tersebut adalah View di XML (`read_screen_xml`), variabel (`manage_variable`), atau Component (`add_component`).
                3. Jika error di dalam kode Java yang di-generate, panggil `preview_generated_source(file_name="MainActivity.java")` untuk melihat baris persisnya.
                4. Jika aplikasi mengalami Force Close saat dibuka, panggil `inspect_runtime_logs(clear_crash=false)` untuk membaca stacktrace lengkap dan perbaiki penyebabnya.
            """.trimIndent()
        )

        for ((info, body) in builtIns) {
            val (id, name, desc) = info
            val dir = File(skillsRootDir(), id)
            val md = File(dir, "SKILL.md")
            if (!md.exists()) {
                createOrUpdateSkill(id, name, desc, body)
            }
        }
        try {
            marker.writeText("ok")
        } catch (_: Throwable) {
        }
    }
}
