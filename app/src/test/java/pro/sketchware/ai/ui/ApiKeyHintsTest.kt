package pro.sketchware.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.sketchware.ai.config.AgentLanguage
import pro.sketchware.ai.config.AiProviderType

class ApiKeyHintsTest {

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
        assertEquals("Ya", id.t("Ya", "Yes"))
        assertEquals("Yes", en.t("Ya", "Yes"))
        for (h in KeyHint.values()) {
            assertTrue(id.keyHint(h).isNotBlank())
            assertNotEquals(id.keyHint(h), en.keyHint(h))
        }
    }
}

