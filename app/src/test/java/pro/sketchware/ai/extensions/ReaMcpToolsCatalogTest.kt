package pro.sketchware.ai.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaMcpToolsCatalogTest {

    @Test
    fun `catalog contains 205 tools and zero ADB tools`() {
        val all = ReaMcpToolsCatalog.buildDefaultReaTools("rea_mcp")
        assertEquals(205, ReaMcpToolsCatalog.TOTAL_TOOLS_COUNT)
        assertEquals(205, all.size)

        val adbTools = all.filter {
            it.name.lowercase().contains("_adb_") ||
                it.name.lowercase().startsWith("adb_") ||
                it.description.lowercase().contains("[adb")
        }
        assertTrue("Expected zero ADB tools on Android, found: ${adbTools.map { it.name }}", adbTools.isEmpty())
    }

    @Test
    fun `searchTools finds relevant tools by keyword and category`() {
        val apkTools = ReaMcpToolsCatalog.searchTools(query = "apk", maxResults = 50)
        assertTrue(apkTools.isNotEmpty())
        assertTrue(apkTools.any { it.name.contains("apk") || it.description.lowercase().contains("apk") })

        val dexTools = ReaMcpToolsCatalog.searchTools(query = "android", maxResults = 50)
        assertTrue(dexTools.any { it.name == "inspect_android_package" || it.name == "search_android_classes" })

        val androidCategoryTools = ReaMcpToolsCatalog.searchTools(query = "", category = "Android Tools", maxResults = 50)
        assertTrue(androidCategoryTools.isNotEmpty())
        assertTrue(androidCategoryTools.all { it.description.contains("[Android Tools]", ignoreCase = true) })

        val adbSearch = ReaMcpToolsCatalog.searchTools(query = "inspect_adb_client", maxResults = 10)
        assertTrue(adbSearch.isEmpty())
    }

    @Test
    fun `categories list excludes ADB and sums to 205`() {
        val cats = ReaMcpToolsCatalog.listCategories()
        assertTrue(cats.isNotEmpty())
        assertFalse(cats.any { it.first.lowercase().contains("adb") })
        assertEquals(205, cats.sumOf { it.second })
    }
}
