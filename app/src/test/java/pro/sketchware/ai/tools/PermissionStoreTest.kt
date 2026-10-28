package pro.sketchware.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PermissionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, "data/permission")
    private fun store() = PermissionStore(file(), File(tmp.root, "data/Injection/androidmanifest/attributes.json"))

    @Test
    fun `short names are expanded and duplicates are skipped`() {
        val s = store()
        val msg = s.add(listOf("internet", "CAMERA", "com.android.vending.BILLING", "INTERNET"))
        assertEquals(
            listOf("android.permission.INTERNET", "android.permission.CAMERA", "com.android.vending.BILLING"),
            s.read()
        )
        assertTrue(msg, msg.contains("Sudah ada (1)"))
        assertTrue(file().readText().startsWith("[\"android.permission.INTERNET\""))
    }

    @Test
    fun `remove accepts short or full names and reports missing ones`() {
        val s = store()
        s.add(listOf("INTERNET", "CAMERA"))
        val msg = s.remove(listOf("camera", "VIBRATE"))
        assertEquals(listOf("android.permission.INTERNET"), s.read())
        assertTrue(msg.contains("Tidak ditemukan (1)"))
    }

    @Test
    fun `set replaces the whole list`() {
        val s = store()
        s.add(listOf("INTERNET"))
        s.set(listOf("CAMERA", "camera"))
        assertEquals(listOf("android.permission.CAMERA"), s.read())
    }

    @Test
    fun `invalid names are rejected and an empty list is an error`() {
        try {
            store().add(listOf("bad name"))
            fail()
        } catch (_: IllegalArgumentException) {
        }
        try {
            store().add(emptyList())
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `describe also shows permissions injected through the manifest`() {
        val s = store()
        s.add(listOf("INTERNET"))
        val attrs = File(tmp.root, "data/Injection/androidmanifest/attributes.json")
        attrs.parentFile.mkdirs()
        attrs.writeText("[{\"name\":\"_application_permissions\",\"value\":\"android:name=\\\"android.permission.WAKE_LOCK\\\"\"}]")
        val d = s.describe()
        assertTrue(d.contains("android.permission.INTERNET"))
        assertTrue(d.contains("WAKE_LOCK"))
    }
}
