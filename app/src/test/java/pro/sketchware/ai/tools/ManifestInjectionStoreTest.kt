package pro.sketchware.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ManifestInjectionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir() = File(tmp.root, "Injection/androidmanifest")
    private fun store() = ManifestInjectionStore(dir())

    private inline fun expectIae(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `setAttribute adds then updates the same attribute key`() {
        val s = store()
        s.setAttribute("application", "android:usesCleartextTraffic=\"true\"")
        s.setAttribute("application", "android:usesCleartextTraffic=\"false\"")
        s.setAttribute("application", "android:largeHeap=\"true\"")
        val attrs = s.readAttributes()
        assertEquals(2, attrs.size)
        assertEquals("_application_attrs", attrs[0].name)
        assertEquals("android:usesCleartextTraffic=\"false\"", attrs[0].value)
    }

    @Test
    fun `activity targets are normalized from screen names`() {
        assertEquals("MainActivity", ManifestInjectionStore.normalizeActivity("main"))
        assertEquals("MyScreenActivity", ManifestInjectionStore.normalizeActivity("my_screen"))
        assertEquals("MainActivity", ManifestInjectionStore.normalizeActivity("MainActivity"))
        assertEquals("_apply_for_all_activities", ManifestInjectionStore.normalizeTarget("all"))
        assertEquals("_application_attrs", ManifestInjectionStore.normalizeTarget("application"))
    }

    @Test
    fun `setAttribute on an activity uses the class name and removeAttribute deletes it`() {
        val s = store()
        s.setAttribute("main", "android:screenOrientation=\"portrait\"")
        assertEquals("MainActivity", s.readAttributes().single().name)
        assertTrue(s.removeAttribute("main", "android:screenOrientation").contains("dihapus"))
        assertTrue(s.readAttributes().isEmpty())
        assertTrue(s.removeAttribute("main", "android:screenOrientation").startsWith("Tidak ada"))
    }

    @Test
    fun `invalid attributes are rejected`() {
        val s = store()
        expectIae { s.setAttribute("application", "android:name") }
        expectIae { s.setAttribute("application", "android:a=\"1\" android:b=\"2\"") }
        expectIae { s.setAttribute("application", "android:a=\"<x>\"") }
    }

    @Test
    fun `addAppComponent adds replaces by android name and removes by simple name`() {
        val s = store()
        val first = s.addAppComponent("<service android:name=\".SyncService\" android:exported=\"false\"/>")
        assertTrue(first, first.contains("1 ditambahkan"))
        val second = s.addAppComponent("<service android:name=\".SyncService\" android:exported=\"true\"/>")
        assertTrue(second, second.contains("1 diganti"))
        val xml = s.readAppComponents()
        assertTrue(xml.contains("android:exported=\"true\""))
        assertEquals(1, Regex("SyncService").findAll(xml).count())
        assertTrue(s.removeAppComponent("SyncService", null).contains("dihapus"))
        assertTrue(s.readAppComponents().isBlank())
    }

    @Test
    fun `addAppComponent warns about missing exported on intent filter components`() {
        val s = store()
        val msg = s.addAppComponent(
            "<receiver android:name=\".Boot\"><intent-filter><action android:name=\"android.intent.action.BOOT_COMPLETED\"/></intent-filter></receiver>"
        )
        assertTrue(msg, msg.contains("android:exported"))
        assertTrue(s.readAppComponents().contains("    <intent-filter>"))
    }

    @Test
    fun `addAppComponent rejects tags that do not belong inside application`() {
        expectIae { store().addAppComponent("<uses-permission android:name=\"x\"/>") }
        expectIae { store().addAppComponent("<service android:name=\".A\"") }
    }

    @Test
    fun `existing invalid app_components is reported instead of overwritten`() {
        dir().mkdirs()
        File(dir(), "app_components.txt").writeText("<service")
        try {
            store().addAppComponent("<service android:name=\".A\"/>")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("bukan XML valid"))
        }
        assertEquals("<service", File(dir(), "app_components.txt").readText())
    }

    @Test
    fun `activity components are deduplicated and removable by content`() {
        val s = store()
        val xml = "<intent-filter><action android:name=\"android.intent.action.VIEW\"/></intent-filter>"
        assertTrue(s.addActivityComponent("main", xml).contains("1 elemen"))
        assertTrue(s.addActivityComponent("main", xml).contains("0 elemen"))
        assertEquals(1, s.readActivityComponents().size)
        assertTrue(s.removeActivityComponent("MainActivity", "VIEW").contains("1 elemen dihapus"))
        assertTrue(s.readActivityComponents().isEmpty())
        expectIae { s.addActivityComponent("main", "<service android:name=\".X\"/>") }
    }

    @Test
    fun `launcher name is validated`() {
        val s = store()
        expectIae { s.setLauncher("my screen") }
        expectIae { s.setLauncher("a.b") }
        s.setLauncher("main")
        assertEquals("main", s.readLauncher())
    }

    @Test
    fun `describe lists everything`() {
        val s = store()
        s.setAttribute("application", "android:largeHeap=\"true\"")
        s.addAppComponent("<service android:name=\".S\" android:exported=\"false\"/>")
        val d = s.describe()
        assertTrue(d.contains("largeHeap"))
        assertTrue(d.contains(".S"))
    }
}
