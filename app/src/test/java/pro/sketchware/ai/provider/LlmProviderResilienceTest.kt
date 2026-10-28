package pro.sketchware.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmProviderResilienceTest {

    private val client = LlmClient()

    @Test
    fun `parseOpenAiResponse handles concatenated JSON objects on line 1 without MalformedJsonException`() {
        val chunk1 = """{"id":"chatcmpl-1","choices":[{"index":0,"delta":{"role":"assistant","content":"Halo! "},"finish_reason":null}]}"""
        val chunk2 = """{"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"Ada yang bisa saya bantu di project Sketchware Pro Anda?"},"finish_reason":"stop"}],"usage":{"total_tokens":42}}"""
        val concatenated = chunk1 + chunk2

        val response = client.parseOpenAiResponse(concatenated)
        assertEquals("Halo! Ada yang bisa saya bantu di project Sketchware Pro Anda?", response.text)
        assertEquals("stop", response.finishReason)
        assertEquals(42, response.totalTokens)
    }

    @Test
    fun `parseOpenAiResponse handles trailing garbage after root JSON object at path dollar`() {
        val body = """{"id":"chatcmpl-2","choices":[{"index":0,"message":{"role":"assistant","content":"Halo dari GLM-5.3 Flash!"}}]}[DONE] trailing_server_metadata"""
        val response = client.parseOpenAiResponse(body)
        assertEquals("Halo dari GLM-5.3 Flash!", response.text)
    }

    @Test
    fun `parseOpenAiResponse handles SSE data lines stream`() {
        val sseBody = """
            data: {"id":"1","choices":[{"index":0,"delta":{"content":"Halo "}}]}

            data: {"id":"1","choices":[{"index":0,"delta":{"content":"Dunia!"}}]}

            data: [DONE]
        """.trimIndent()

        val response = client.parseOpenAiResponse(sseBody)
        assertEquals("Halo Dunia!", response.text)
    }

    @Test
    fun `parseOpenAiResponse extracts inline tool_call XML tags when provider uses prompt fallback`() {
        val body = """
            {"choices":[{"message":{"role":"assistant","content":"Saya akan memeriksa struktur project.\n<tool_call>\n{\"name\":\"get_project_overview\",\"arguments\":{}}\n</tool_call>"}}]}
        """.trimIndent()

        val response = client.parseOpenAiResponse(body)
        assertEquals("Saya akan memeriksa struktur project.", response.text)
        assertEquals(1, response.toolCalls.size)
        assertEquals("get_project_overview", response.toolCalls.first().name)
    }

    @Test
    fun `parseOpenAiResponse accumulates streamed tool_calls chunks`() {
        val sseToolStream = """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_abc","function":{"name":"read_project_","arguments":""}}]}}]}
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"file","arguments":"{\"category\":\"java\","}}]}}]}
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"name\":\"MainActivity.java\"}"}}]}}]}
            data: [DONE]
        """.trimIndent()

        val response = client.parseOpenAiResponse(sseToolStream)
        assertTrue(response.toolCalls.isNotEmpty())
        val call = response.toolCalls.first()
        assertEquals("call_abc", call.id)
        assertEquals("read_project_file", call.name)
        assertEquals("java", call.arguments.get("category").asString)
        assertEquals("MainActivity.java", call.arguments.get("name").asString)
    }

    @Test
    fun `all 17 AgentLanguage entries provide complete UI strings and systemPromptDirective`() {
        for (lang in pro.sketchware.ai.config.AgentLanguage.entries) {
            val ui = pro.sketchware.ai.config.AiStrings.forLanguage(lang)
            assertTrue(ui.emptyTitle.isNotBlank())
            assertTrue(ui.composerHint.isNotBlank())
            assertTrue(ui.settingsDialogTitle.isNotBlank())
            assertTrue(lang.systemPromptDirective().contains("LANGUAGE RULE"))
        }
    }

    @Test
    fun `normalizeOpenAiBaseUrl appends default path only for bare known hosts`() {
        assertEquals("https://api.openai.com/v1", client.normalizeOpenAiBaseUrl("https://api.openai.com/"))
        assertEquals("https://api.openai.com/v1", client.normalizeOpenAiBaseUrl("https://api.openai.com/v1"))
        assertEquals("https://openrouter.ai/api/v1", client.normalizeOpenAiBaseUrl("https://openrouter.ai"))
        assertEquals("http://192.168.1.5:11434/v1", client.normalizeOpenAiBaseUrl("http://192.168.1.5:11434/v1/"))
        assertEquals("http://192.168.1.5:11434", client.normalizeOpenAiBaseUrl("http://192.168.1.5:11434"))
    }
}
