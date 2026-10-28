package pro.sketchware.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JavaFilesStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pkg = "com.my.app"
    private fun javaRoot() = File(tmp.root, "files/java")

    private fun lists() = ManifestLists(File(tmp.root, "java"), File(tmp.root, "service"), File(tmp.root, "broadcast"))

    private fun store(): JavaFilesStore {
        javaRoot().mkdirs()
        return JavaFilesStore(javaRoot(), pkg, lists())
    }

    private inline fun expectIae(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `create derives the package from the folder`() {
        val s = store()
        s.create("utils.net", "Http", "class", "java", null, null, false)
        val f = File(javaRoot(), "utils/net/Http.java")
        assertTrue(f.isFile)
        assertTrue(f.readText().startsWith("package com.my.app.utils.net;"))
        assertTrue(f.readText().contains("public class Http"))
    }

    @Test
    fun `create at the root uses the project package and kotlin has no semicolon`() {
        val s = store()
        s.create("", "Main2", "activity", "kotlin", null, null, false)
        val f = File(javaRoot(), "Main2.kt")
        assertTrue(f.readText().startsWith("package com.my.app\n"))
        assertTrue(f.readText().contains("class Main2 : Activity()"))
    }

    @Test
    fun `create can register services receivers and activities`() {
        val s = store()
        s.create("", "SyncService", "service", "java", null, "service", false)
        s.create("rx", "BootReceiver", "receiver", "java", null, "broadcast", false)
        s.create("ui", "Detail", "activity", "java", null, "activity", false)
        assertEquals(listOf("com.my.app.SyncService"), lists().read(ManifestLists.Kind.SERVICE))
        assertEquals(listOf("com.my.app.rx.BootReceiver"), lists().read(ManifestLists.Kind.BROADCAST))
        assertEquals(listOf("com.my.app.ui.Detail"), lists().read(ManifestLists.Kind.ACTIVITY))
        assertTrue(s.list().contains("manifest: Service"))
    }

    @Test
    fun `an activity cannot also be a service`() {
        val s = store()
        s.create("", "Both", "class", "java", null, "service", false)
        expectIae { s.register("Both.java", "activity") }
        assertTrue(s.register("Both.java", "broadcast").contains("BroadcastReceiver"))
        assertTrue(s.register("Both.java", "service").contains("sudah terdaftar"))
    }

    @Test
    fun `create does not overwrite unless asked`() {
        val s = store()
        s.create("", "A", "class", "java", null, null, false)
        expectIae { s.create("", "A", "class", "java", null, null, false) }
        expectIae { s.create("", "A", "class", "kotlin", null, null, false) }
        s.create("", "A", "class", "java", "package x;\npublic class A { int v; }", null, true)
        assertTrue(File(javaRoot(), "A.java").readText().contains("int v"))
    }

    @Test
    fun `provided content gets its package line fixed`() {
        val warnings = ArrayList<String>()
        val missing = JavaFilesStore.ensurePackage("public class A {}", pkg, false, warnings)
        assertTrue(missing.startsWith("package com.my.app;\n"))
        val wrong = JavaFilesStore.ensurePackage("package other.pkg;\n\npublic class A {}", pkg, false, warnings)
        assertTrue(wrong.startsWith("package com.my.app;"))
        val ok = JavaFilesStore.ensurePackage("package com.my.app;\npublic class A {}", pkg, false, warnings)
        assertEquals("package com.my.app;\npublic class A {}", ok)
        assertEquals(2, warnings.size)
    }

    @Test
    fun `rename updates the class and the manifest lists and warns about other references`() {
        val s = store()
        s.create("ui", "Foo", "activity", "java", null, "activity", false)
        s.create("", "User", "class", "java", "package com.my.app;\npublic class User { Object o = new com.my.app.ui.Foo(); }", null, false)
        val msg = s.rename("ui/Foo.java", "Bar")
        assertFalse(File(javaRoot(), "ui/Foo.java").exists())
        val renamed = File(javaRoot(), "ui/Bar.java").readText()
        assertTrue(renamed.contains("public class Bar"))
        assertFalse(renamed.contains("Foo"))
        assertEquals(listOf("com.my.app.ui.Bar"), lists().read(ManifestLists.Kind.ACTIVITY))
        assertTrue(msg, msg.contains("User.java"))
        expectIae { s.rename("ui/Bar.java", "bad name") }
    }

    @Test
    fun `delete unregisters and refuses folders and path traversal`() {
        val s = store()
        s.create("", "Gone", "service", "java", null, "service", false)
        assertTrue(s.delete("Gone.java").contains("Service"))
        assertTrue(lists().read(ManifestLists.Kind.SERVICE).isEmpty())
        File(javaRoot(), "dir").mkdirs()
        expectIae { s.delete("dir") }
        expectIae { s.delete("../../etc/passwd") }
        expectIae { s.resolve("/../../x") }
    }

    @Test
    fun `validate finds package class and brace problems`() {
        val s = store()
        File(javaRoot(), "ui").mkdirs()
        File(javaRoot(), "ui/X.java").writeText("package wrong;\npublic class X {}\n")
        File(javaRoot(), "W.java").writeText("package com.my.app;\npublic class Z {}\n")
        File(javaRoot(), "Y.java").writeText("package com.my.app;\npublic class Y {\n")
        File(javaRoot(), "Good.java").writeText("package com.my.app;\npublic class Good { String s = \"}\"; // {\n}\n")
        lists().add(ManifestLists.Kind.SERVICE, "com.my.app.Ghost")
        val report = s.validate(null)
        assertTrue(report, report.contains("tidak sama dengan lokasi folder"))
        assertTrue(report, report.contains("harus sama dengan nama berkas"))
        assertTrue(report, report.contains("tidak seimbang"))
        assertTrue(report, report.contains("Ghost"))
        assertFalse(report, report.contains("Good.java"))
    }

    @Test
    fun `validate reports ok for a clean file`() {
        val s = store()
        s.create("", "Clean", "class", "java", null, null, false)
        assertTrue(s.validate("Clean.java").startsWith("OK"))
    }

    @Test
    fun `unregister removes from one or all kinds`() {
        val s = store()
        s.create("", "C", "class", "java", null, "broadcast", false)
        assertTrue(s.unregister("C.java", "service").contains("tidak terdaftar"))
        assertTrue(s.unregister("C.java", null).contains("BroadcastReceiver"))
        assertTrue(s.listManifest().contains("BroadcastReceiver (0)"))
    }

    @Test
    fun `invalid names are rejected`() {
        val s = store()
        expectIae { s.create("", "bad name", "class", "java", null, null, false) }
        expectIae { s.create("a b", "Ok", "class", "java", null, null, false) }
        expectIae { s.create("", "Ok", "weird", "java", null, null, false) }
        expectIae { s.register("Missing.java", "activity") }
        expectIae { s.create("", "Ok", "class", "java", null, "nope", false) }
    }

    @Test
    fun `stripNoise ignores braces in strings and comments`() {
        val clean = JavaFilesStore.stripNoise("class A { String s = \"}{\"; char c = '{'; /* } */ // {\n }")
        assertEquals(clean.count { it == '{' }, clean.count { it == '}' })
    }
}
