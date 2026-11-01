package pro.sketchware.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.sketchware.ai.config.AgentLanguage
import pro.sketchware.ai.config.AiProviderType

class CapabilityLogicTest {

    @Test
    fun `skill sources map to kinds`() {
        assertEquals(SourceKind.PLUGIN, CapabilityLogic.skillKind("plugin:acme", "anything"))
        assertEquals(SourceKind.BUILTIN, CapabilityLogic.skillKind("standalone", "material-ui-architect"))
        assertEquals(SourceKind.CUSTOM, CapabilityLogic.skillKind("standalone", "my-own-skill"))
        assertEquals("acme", CapabilityLogic.pluginIdOf("plugin:acme"))
        assertNull(CapabilityLogic.pluginIdOf("standalone"))
    }

    @Test
    fun `mcp sources map to kinds`() {
        assertEquals(SourceKind.BUILTIN, CapabilityLogic.mcpKind("builtin"))
        assertEquals(SourceKind.PLUGIN, CapabilityLogic.mcpKind("plugin:acme"))
        assertEquals(SourceKind.CUSTOM, CapabilityLogic.mcpKind("user"))
    }

    @Test
    fun `mcp state is derived from enabled flag and last status`() {
        assertEquals(McpState.DISABLED, CapabilityLogic.mcpState(false, "OK (3 tools)", 3))
        assertEquals(McpState.CONNECTED, CapabilityLogic.mcpState(true, "OK (3 tools)", 3))
        assertEquals(McpState.ERROR, CapabilityLogic.mcpState(true, "Error: connection refused", 0))
        assertEquals(McpState.ERROR, CapabilityLogic.mcpState(true, "Gagal sinkron: timeout", 5))
        assertEquals(McpState.IDLE, CapabilityLogic.mcpState(true, "Belum disinkronkan", 0))
        assertEquals(McpState.IDLE, CapabilityLogic.mcpState(true, "", 0))
    }

    @Test
    fun `search requires every token in some field`() {
        assertTrue(CapabilityLogic.matches("", "anything"))
        assertTrue(CapabilityLogic.matches("rest api", "REST API Networking", "helper"))
        assertTrue(CapabilityLogic.matches("api helper", "REST API", "helper"))
        assertTrue(!CapabilityLogic.matches("api firebase", "REST API", "helper"))
        assertTrue(CapabilityLogic.matches("x", null, "x"))
    }

    @Test
    fun `tone is stable and within range`() {
        val t = CapabilityLogic.tone("Material UI")
        assertEquals(t, CapabilityLogic.tone("Material UI"))
        for (n in listOf("a", "bb", "ccc", "dddd", "")) assertTrue(CapabilityLogic.tone(n) in 0..3)
    }

    @Test
    fun `initials come from the first two words`() {
        assertEquals("MU", CapabilityLogic.initials("Material UI Architect"))
        assertEquals("RE", CapabilityLogic.initials("rest"))
        assertEquals("AB", CapabilityLogic.initials("alpha-beta"))
        assertEquals("?", CapabilityLogic.initials("   "))
    }

    @Test
    fun `preview truncates with a counter`() {
        assertEquals(listOf("a", "b"), CapabilityLogic.preview(listOf("a", "b"), 3))
        assertEquals(listOf("a", "b", "+2"), CapabilityLogic.preview(listOf("a", "b", "c", "d"), 2))
    }

    @Test
    fun `api key hints are provider aware`() {
        assertNull(ApiKeyHints.check(AiProviderType.OPENAI, ""))
        assertNull(ApiKeyHints.check(AiProviderType.OPENAI, "sk-abc123"))
        assertEquals(KeyHint.LOOKS_ANTHROPIC, ApiKeyHints.check(AiProviderType.OPENAI, "sk-ant-abc"))
        assertEquals(KeyHint.LOOKS_GOOGLE, ApiKeyHints.check(AiProviderType.OPENAI, "AIzaXyz"))
        assertEquals(KeyHint.LOOKS_OPENROUTER, ApiKeyHints.check(AiProviderType.OPENAI, "sk-or-v1-abc"))
        assertEquals(KeyHint.EXPECT_SK, ApiKeyHints.check(AiProviderType.OPENAI, "abcdef"))
        assertNull(ApiKeyHints.check(AiProviderType.CLAUDE, "sk-ant-api03-xyz"))
        assertEquals(KeyHint.EXPECT_SK_ANT, ApiKeyHints.check(AiProviderType.CLAUDE, "sk-proj-xyz"))
        assertNull(ApiKeyHints.check(AiProviderType.GEMINI, "AIzaSyABC"))
        assertEquals(KeyHint.EXPECT_AIZA, ApiKeyHints.check(AiProviderType.GEMINI, "sk-abc"))
        assertNull(ApiKeyHints.check(AiProviderType.OPENROUTER, "sk-or-v1-abc"))
        assertEquals(KeyHint.EXPECT_SK_OR, ApiKeyHints.check(AiProviderType.OPENROUTER, "sk-abc"))
        assertNull(ApiKeyHints.check(AiProviderType.CUSTOM, "whatever-token"))
    }

    @Test
    fun `whitespace and urls are flagged for every provider`() {
        for (p in AiProviderType.values()) {
            assertEquals(KeyHint.WHITESPACE, ApiKeyHints.check(p, "sk-abc def"))
            assertEquals(KeyHint.LOOKS_URL, ApiKeyHints.check(p, "https://api.openai.com/v1"))
        }
        assertNull(ApiKeyHints.check(AiProviderType.OPENAI, "  sk-abc  "))
    }

    @Test
    fun `texts follow the agent language`() {
        val id = ExtText(AgentLanguage.ID)
        val en = ExtText(AgentLanguage.EN)
        assertEquals("2 dari 5 aktif", id.enabledOfTotal(2, 5))
        assertEquals("2 of 5 enabled", en.enabledOfTotal(2, 5))
        for (h in KeyHint.values()) {
            assertTrue(id.keyHint(h).isNotBlank())
            assertNotEquals(id.keyHint(h), en.keyHint(h))
        }
    }
}
