package pro.sketchware.ai.provider

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises the real OkHttp request path against a local server that mimics OpenAI's behaviour. */
class OpenAiCompatibleRequestTest {

    private class Recorded(val path: String, val body: String, val auth: String?)

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Recorded>()
    private var responder: (Int) -> Pair<Int, String> = { 200 to OK_BODY }

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val index = requests.size
            requests.add(Recorded(ex.requestURI.path, body, ex.requestHeaders.getFirst("Authorization")))
            val (status, resp) = responder(index)
            val bytes = resp.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
    }

    private fun base() = "http://127.0.0.1:${server.address.port}"

    private fun call(model: String, baseUrl: String = base() + "/v1", tools: List<ToolDefinition> = emptyList()) =
        LlmClient().callOpenAiCompatible(
            baseUrl, "sk-test", model, "sys",
            listOf(LlmTurnMessage(role = "user", content = "hi")), tools
        )

    private fun hasField(req: Recorded, name: String) = JsonParser.parseString(req.body).asJsonObject.has(name)

    @Test
    fun `gpt-5 and o-series requests omit temperature`() {
        for (model in listOf("gpt-5", "gpt-5-mini", "o3-mini", "o4-mini", "openai/gpt-5")) {
            requests.clear()
            assertEquals("ok", call(model).text)
            assertFalse("$model must not send temperature", hasField(requests.single(), "temperature"))
        }
    }

    @Test
    fun `classic models keep temperature`() {
        assertEquals("ok", call("gpt-4.1").text)
        assertTrue(hasField(requests.single(), "temperature"))
    }

    @Test
    fun `a temperature rejection is retried once without the parameter`() {
        responder = { index ->
            if (index == 0) {
                400 to """{"error":{"message":"Unsupported value: 'temperature' does not support 0.2 with this model. Only the default (1) value is supported.","type":"invalid_request_error","param":"temperature"}}"""
            } else {
                200 to OK_BODY
            }
        }
        assertEquals("ok", call("some-new-model").text)
        assertEquals(2, requests.size)
        assertTrue(hasField(requests[0], "temperature"))
        assertFalse(hasField(requests[1], "temperature"))
    }

    @Test
    fun `a bare custom host is left as typed and the bearer token is sent`() {
        assertEquals("ok", call("gpt-4.1", baseUrl = base()).text)
        assertEquals("/chat/completions", requests.single().path)
        assertEquals("Bearer sk-test", requests.single().auth)
    }

    @Test
    fun `an explicit v1 base url is left untouched`() {
        call("gpt-4.1", baseUrl = base() + "/v1/")
        assertEquals("/v1/chat/completions", requests.single().path)
    }

    @Test
    fun `a 404 explains which endpoint was called`() {
        responder = { 404 to """{"error":{"message":"Not Found"}}""" }
        try {
            call("gpt-4.1", baseUrl = base() + "/wrong")
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("API Error (404)"))
            assertTrue(e.message, e.message!!.contains("/wrong/chat/completions"))
        }
    }

    @Test
    fun `tool schema rejection still falls back to prompt based tools`() {
        responder = { index ->
            if (index == 0) 400 to """{"error":{"message":"tools are not supported by this model"}}""" else 200 to OK_BODY
        }
        val tool = ToolDefinition("ping", "Ping", emptyList())
        assertEquals("ok", call("custom-model", tools = listOf(tool)).text)
        assertEquals(2, requests.size)
        assertTrue(hasField(requests[0], "tools"))
        assertFalse(hasField(requests[1], "tools"))
    }

    @Test
    fun `helper functions classify models and urls`() {
        val c = LlmClient()
        assertTrue(c.modelRejectsTemperature("gpt-5.1"))
        assertTrue(c.modelRejectsTemperature("o1-preview"))
        assertFalse(c.modelRejectsTemperature("gpt-4o"))
        assertFalse(c.modelRejectsTemperature("deepseek-chat"))
        assertTrue(c.isTemperatureRejection(400, "Unsupported value: 'temperature'"))
        assertFalse(c.isTemperatureRejection(500, "temperature"))
        assertEquals("https://api.openai.com/v1", c.normalizeOpenAiBaseUrl("https://api.openai.com"))
        assertEquals("https://api.openai.com/v1", c.normalizeOpenAiBaseUrl("https://api.openai.com/v1/"))
        assertEquals("https://openrouter.ai/api/v1", c.normalizeOpenAiBaseUrl("https://openrouter.ai"))
        assertEquals("https://x.com", c.normalizeOpenAiBaseUrl("https://x.com"))
        assertEquals("http://localhost:11434", c.normalizeOpenAiBaseUrl("http://localhost:11434"))
        assertEquals("https://x.com/v1/chat/completions", c.normalizeOpenAiBaseUrl("https://x.com/v1/chat/completions"))
        assertEquals("not a url", c.normalizeOpenAiBaseUrl("not a url"))
    }

    companion object {
        private const val OK_BODY =
            """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"total_tokens":5}}"""
    }
}
