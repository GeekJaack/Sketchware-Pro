package pro.sketchware.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ResValuesStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun resDir() = File(tmp.root, "resource")
    private fun store() = ResValuesStore(resDir())
    private fun read(path: String) = File(resDir(), path).readText()

    private inline fun expectIae(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `new files get a declaration and strings are escaped for Android`() {
        val s = store()
        s.set("strings", "", ResValuesStore.Spec(name = "app_name", value = "My App"))
        s.set("strings", "", ResValuesStore.Spec(name = "greeting", value = "It's me & \"you\""))
        val xml = read("values/strings.xml")
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"))
        assertTrue(xml, xml.contains("<string name=\"greeting\">It\\'s me &amp; \\\"you\\\"</string>"))
        assertTrue(xml.contains("<string name=\"app_name\">My App</string>"))
    }

    @Test
    fun `updating one entry keeps every other entry and comment`() {
        File(resDir(), "values").mkdirs()
        File(resDir(), "values/strings.xml").writeText(
            "<resources>\n    <!-- keep me -->\n    <string name=\"a\">A</string>\n    <string name=\"b\">B</string>\n</resources>\n"
        )
        val s = store()
        val msg = s.set("strings", "", ResValuesStore.Spec(name = "b", value = "Changed"))
        assertTrue(msg, msg.startsWith("DIPERBARUI"))
        val xml = read("values/strings.xml")
        assertTrue(xml.contains("<!-- keep me -->"))
        assertTrue(xml.contains("<string name=\"a\">A</string>"))
        assertTrue(xml.contains("<string name=\"b\">Changed</string>"))
        assertEquals(1, Regex("name=\"b\"").findAll(xml).count())
        assertFalse(xml.startsWith("<?xml"))
    }

    @Test
    fun `app_name is protected from deletion`() {
        val s = store()
        s.set("strings", "", ResValuesStore.Spec(name = "app_name", value = "X"))
        assertTrue(s.delete("strings", "", "app_name", null).startsWith("Error"))
        assertTrue(read("values/strings.xml").contains("app_name"))
        s.set("strings", "", ResValuesStore.Spec(name = "other", value = "Y"))
        assertTrue(s.delete("strings", "", "other", null).contains("dihapus"))
        assertFalse(read("values/strings.xml").contains("other"))
    }

    @Test
    fun `colors are validated and night variant uses values-night`() {
        val s = store()
        s.set("colors", "night", ResValuesStore.Spec(name = "colorPrimary", value = "#FF112233"))
        assertTrue(read("values-night/colors.xml").contains("<color name=\"colorPrimary\">#FF112233</color>"))
        expectIae { s.set("colors", "", ResValuesStore.Spec(name = "bad", value = "red")) }
        s.set("colors", "", ResValuesStore.Spec(name = "ref", value = "@color/colorPrimary"))
    }

    @Test
    fun `dimen bool and integer values are validated`() {
        val s = store()
        s.set("dimens", "", ResValuesStore.Spec(name = "pad", value = "16dp"))
        s.set("bools", "", ResValuesStore.Spec(name = "flag", value = "true"))
        s.set("integers", "", ResValuesStore.Spec(name = "count", value = "5"))
        expectIae { s.set("dimens", "", ResValuesStore.Spec(name = "bad", value = "16")) }
        expectIae { s.set("bools", "", ResValuesStore.Spec(name = "bad", value = "yes")) }
        expectIae { s.set("integers", "", ResValuesStore.Spec(name = "bad", value = "1.5")) }
    }

    @Test
    fun `styles are merged item by item and items can be deleted`() {
        val s = store()
        s.set("styles", "", ResValuesStore.Spec(name = "AppTheme", parent = "Theme.Material3.Light", styleItems = mapOf("colorPrimary" to "@color/p")))
        s.set("styles", "", ResValuesStore.Spec(name = "AppTheme", styleItems = mapOf("android:windowBackground" to "@color/bg", "colorPrimary" to "@color/p2")))
        var xml = read("values/styles.xml")
        assertTrue(xml, xml.contains("<style name=\"AppTheme\" parent=\"Theme.Material3.Light\">"))
        assertTrue(xml.contains("<item name=\"colorPrimary\">@color/p2</item>"))
        assertTrue(xml.contains("<item name=\"android:windowBackground\">@color/bg</item>"))
        assertEquals(1, Regex("name=\"colorPrimary\"").findAll(xml).count())
        assertTrue(s.deleteStyleItem("styles", "", "AppTheme", "colorPrimary").contains("dihapus"))
        xml = read("values/styles.xml")
        assertFalse(xml.contains("colorPrimary"))
        assertTrue(xml.contains("windowBackground"))
    }

    @Test
    fun `arrays are written as items`() {
        val s = store()
        s.set("arrays", "", ResValuesStore.Spec(name = "planets", listItems = listOf("Mars", "Venus")))
        val xml = read("values/arrays.xml")
        assertTrue(xml, xml.contains("<string-array name=\"planets\">"))
        assertTrue(xml.contains("    <item>Mars</item>") || xml.contains("<item>Mars</item>"))
    }

    @Test
    fun `same name with different types can coexist`() {
        File(resDir(), "values").mkdirs()
        val s = store()
        s.set("strings", "", ResValuesStore.Spec(name = "x", value = "text"))
        s.set("strings", "", ResValuesStore.Spec(name = "x", type = "color", value = "#FFFFFF"))
        val xml = read("values/strings.xml")
        assertTrue(xml.contains("<string name=\"x\">text</string>"))
        assertTrue(xml.contains("<color name=\"x\">#FFFFFF</color>"))
    }

    @Test
    fun `list get and list_files report content`() {
        val s = store()
        s.set("strings", "", ResValuesStore.Spec(name = "hello", value = "Hi"))
        assertTrue(s.list("strings", "").contains("string hello = Hi"))
        assertTrue(s.get("strings", "", "hello").contains("<string name=\"hello\">Hi</string>"))
        assertTrue(s.get("strings", "", "missing").contains("tidak ditemukan"))
        assertTrue(s.listFiles().contains("strings.xml"))
    }

    @Test
    fun `invalid input is rejected`() {
        val s = store()
        expectIae { s.set("strings", "", ResValuesStore.Spec(name = "1bad", value = "x")) }
        expectIae { s.set("strings", "", ResValuesStore.Spec(name = "ok")) }
        expectIae { s.set("../etc", "", ResValuesStore.Spec(name = "ok", value = "x")) }
        expectIae { ResValuesStore.valuesFolder("../x") }
        expectIae { s.set("custom", "", ResValuesStore.Spec(name = "ok", value = "x")) }
    }

    @Test
    fun `variants map to values folders`() {
        assertEquals("values", ResValuesStore.valuesFolder(""))
        assertEquals("values-night", ResValuesStore.valuesFolder("night"))
        assertEquals("values-v21", ResValuesStore.valuesFolder("v21"))
        assertEquals("values-sw600dp", ResValuesStore.valuesFolder("values-sw600dp"))
    }

    @Test
    fun `androidEscape is idempotent`() {
        assertEquals("It\\'s", ResValuesStore.androidEscape("It's"))
        assertEquals("It\\'s", ResValuesStore.androidEscape("It\\'s"))
        assertEquals("say \\\"hi\\\"", ResValuesStore.androidEscape("say \"hi\""))
    }

    @Test
    fun `a corrupt existing file is reported and left untouched`() {
        File(resDir(), "values").mkdirs()
        File(resDir(), "values/strings.xml").writeText("<resources><string")
        try {
            store().set("strings", "", ResValuesStore.Spec(name = "a", value = "b"))
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("bukan XML valid"))
        }
        assertEquals("<resources><string", read("values/strings.xml"))
    }
}
