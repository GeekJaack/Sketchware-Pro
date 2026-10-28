package pro.sketchware.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BuildOptionsAndNativeLibsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private inline fun expectIae(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---------- build options ----------

    private fun options() = BuildOptionsStore(File(tmp.root, "data").apply { mkdirs() })

    @Test
    fun `enabling proguard seeds the defaults the app expects`() {
        val s = options()
        s.set("proguard", "true")
        s.set("r8", "TRUE")
        val json = File(tmp.root, "data/proguard").readText()
        assertTrue(json, json.contains("\"enabled\":\"true\""))
        assertTrue(json.contains("\"debug\":\"false\""))
        assertTrue(json.contains("\"r8\":\"true\""))
        assertTrue(s.describe().contains("proguard = true"))
    }

    @Test
    fun `sdk values are range checked and ordered`() {
        val s = options()
        s.set("target_sdk", "28")
        expectIae { s.set("min_sdk", "30") }
        expectIae { s.set("target_sdk", "10") }
        expectIae { s.set("min_sdk", "abc") }
        s.set("min_sdk", "24")
        val cfg = File(tmp.root, "data/project_config").readText()
        assertTrue(cfg.contains("\"min_sdk\":\"24\"") && cfg.contains("\"target_sdk\":\"28\""))
    }

    @Test
    fun `other keys in an existing config are preserved`() {
        File(tmp.root, "data").mkdirs()
        File(tmp.root, "data/project_config").writeText("{\"custom_key\":\"keep\"}")
        options().set("viewbinding", "true")
        val cfg = File(tmp.root, "data/project_config").readText()
        assertTrue(cfg.contains("custom_key") && cfg.contains("enable_viewbinding"))
    }

    @Test
    fun `invalid options and values are rejected`() {
        val s = options()
        expectIae { s.set("unknown", "true") }
        expectIae { s.set("stringfog", "maybe") }
        expectIae { s.set("app_class", "My App") }
        s.set("app_class", ".MyApp")
        s.set("stringfog", "true")
        assertTrue(File(tmp.root, "data/stringfog").readText().contains("\"enabled\":\"true\""))
    }

    @Test
    fun `appending rules skips lines that already exist`() {
        val s = options()
        assertEquals("(aturan kustom kosong)", s.getRules())
        assertTrue(s.appendRules("-keep class a.B { *; }\n-dontwarn x.**").contains("2 baris"))
        assertTrue(s.appendRules("-keep class a.B { *; }\n-keep class c.D").contains("1 baris"))
        assertTrue(s.appendRules("-dontwarn x.**").contains("sudah ada"))
        assertEquals(3, s.getRules().lines().count { it.isNotBlank() })
    }

    // ---------- native libs ----------

    private fun elf(is64: Boolean, machine: Int): ByteArray {
        val b = ByteArray(64)
        b[0] = 0x7F
        b[1] = 'E'.code.toByte()
        b[2] = 'L'.code.toByte()
        b[3] = 'F'.code.toByte()
        b[4] = if (is64) 2 else 1
        b[18] = (machine and 0xFF).toByte()
        b[19] = ((machine shr 8) and 0xFF).toByte()
        return b
    }

    @Test
    fun `ELF header parsing identifies architecture`() {
        val h = ElfInfo.parse(elf(true, 183))!!
        assertTrue(ElfInfo.matches("arm64-v8a", h))
        assertFalse(ElfInfo.matches("armeabi-v7a", h))
        assertTrue(ElfInfo.matches("armeabi-v7a", ElfInfo.parse(elf(false, 40))!!))
        assertTrue(ElfInfo.matches("x86_64", ElfInfo.parse(elf(true, 62))!!))
        assertTrue(ElfInfo.matches("x86", ElfInfo.parse(elf(false, 3))!!))
        assertNull(ElfInfo.parse(ByteArray(64)))
        assertNull(ElfInfo.parse(ByteArray(4)))
        assertNotNull(ElfInfo.describe(h))
    }

    @Test
    fun `add verifies the abi list and remove work`() {
        val root = File(tmp.root, "native_libs")
        val s = NativeLibsStore(root)
        val src = tmp.newFile("libfoo.so").apply { writeBytes(elf(true, 183)) }
        assertTrue(s.add("arm64-v8a", src, null).startsWith("Ditambahkan"))
        assertTrue(File(root, "arm64-v8a/libfoo.so").isFile)
        assertTrue(s.add("arm64-v8a", src, null).startsWith("Diganti"))
        assertTrue(s.list().contains("arm64-v8a/: libfoo.so"))
        expectIae { s.add("armeabi-v7a", src, null) }
        assertTrue(s.remove("arm64-v8a", "libfoo.so").contains("dihapus"))
        assertTrue(s.remove("arm64-v8a", "libfoo.so").contains("tidak ditemukan"))
    }

    @Test
    fun `native lib input is validated`() {
        val s = NativeLibsStore(File(tmp.root, "native_libs"))
        val notElf = tmp.newFile("fake.so").apply { writeBytes(ByteArray(64)) }
        val good = tmp.newFile("good.so").apply { writeBytes(elf(false, 40)) }
        expectIae { s.add("arm64-v8a", notElf, null) }
        expectIae { s.add("mips", good, null) }
        expectIae { s.add("armeabi-v7a", good, "evil/../x.so") }
        expectIae { s.add("armeabi-v7a", good, "noext") }
        expectIae { s.add("armeabi-v7a", File(tmp.root, "missing.so"), null) }
        expectIae { s.remove("armeabi-v7a", "../x") }
    }
}
