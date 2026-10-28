package pro.sketchware.ai.provider

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pro.sketchware.ai.config.AiConfigManager
import pro.sketchware.ai.config.AiProviderType
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.io.StringReader
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class TransientLlmException(
    message: String,
    val statusCode: Int = 0,
    cause: Throwable? = null
) : IOException(message, cause)

data class ToolParameterProperty(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = false,
    val enumValues: List<String>? = null
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameterProperty>,
    val isWriteTool: Boolean = false
)

data class LlmToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
    val thoughtSignature: String? = null
)

data class LlmTurnMessage(
    val role: String, // "user", "assistant", "tool"
    val content: String = "",
    val toolCalls: List<LlmToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null
)

data class LlmResponse(
    val text: String,
    val toolCalls: List<LlmToolCall>,
    val finishReason: String? = null,
    val totalTokens: Int = 0
)

class LlmClient(private val configManager: AiConfigManager? = null) {

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(330, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Throws(IOException::class)
    fun generateTurn(
        systemPrompt: String,
        messages: List<LlmTurnMessage>,
        tools: List<ToolDefinition>,
        shouldCancel: () -> Boolean = { false },
        transformMessagesOnRetry: ((attempt: Int, original: List<LlmTurnMessage>) -> List<LlmTurnMessage>)? = null,
        onRetry: ((attempt: Int, maxAttempts: Int, reason: String) -> Unit)? = null
    ): LlmResponse {
        val cfg = requireNotNull(configManager) { "AiConfigManager is required for generateTurn" }
        val provider = cfg.providerType
        val apiKey = cfg.getApiKey(provider)
        val baseUrl = cfg.getBaseUrl(provider).trimEnd('/')
        val modelId = cfg.getModelId(provider)

        if (apiKey.isEmpty() && provider != AiProviderType.CUSTOM) {
            throw IOException("API Key untuk ${provider.displayName} belum diisi. Silakan buka Pengaturan AI Agent (ikon roda gigi) dan masukkan API Key Anda.")
        }

        val maxAttempts = 4
        var lastError: IOException? = null

        for (attempt in 1..maxAttempts) {
            if (shouldCancel()) {
                throw IOException("Eksekusi dibatalkan oleh pengguna.")
            }

            val effectiveMessages = if (attempt > 1 && transformMessagesOnRetry != null) {
                transformMessagesOnRetry(attempt, messages)
            } else {
                messages
            }

            try {
                val useOpenAiCompatForGeminiTab = provider == AiProviderType.GEMINI &&
                    !baseUrl.contains("googleapis.com", ignoreCase = true) &&
                    !modelId.startsWith("gemini", ignoreCase = true)

                return when {
                    useOpenAiCompatForGeminiTab -> callOpenAiCompatible(baseUrl, apiKey, modelId, systemPrompt, effectiveMessages, tools)
                    provider == AiProviderType.GEMINI -> callGemini(baseUrl, apiKey, modelId, systemPrompt, effectiveMessages, tools)
                    provider == AiProviderType.CLAUDE -> callClaude(baseUrl, apiKey, modelId, systemPrompt, effectiveMessages, tools)
                    else -> callOpenAiCompatible(baseUrl, apiKey, modelId, systemPrompt, effectiveMessages, tools)
                }
            } catch (e: IOException) {
                lastError = e
                val isRetryable = isTransientError(e)
                if (!isRetryable || attempt >= maxAttempts || shouldCancel()) {
                    throw e
                }

                val reason = when (e) {
                    is SocketTimeoutException -> "SocketTimeoutException (koneksi lambat/server sibuk)"
                    is TransientLlmException -> "HTTP ${e.statusCode}"
                    else -> e.javaClass.simpleName
                }
                onRetry?.invoke(attempt + 1, maxAttempts, reason)

                // Exponential backoff: 2.5s, 5s, 9s (checked in 250ms slices so cancel works immediately)
                val backoffMs = when (attempt) {
                    1 -> 2500L
                    2 -> 5000L
                    else -> 9000L
                }
                var waited = 0L
                while (waited < backoffMs && !shouldCancel()) {
                    try {
                        Thread.sleep(250L)
                    } catch (_: InterruptedException) {
                        break
                    }
                    waited += 250L
                }
            }
        }

        throw lastError ?: IOException("Gagal menghubungi layanan AI setelah $maxAttempts percobaan.")
    }

    private fun isTransientError(e: IOException): Boolean {
        if (e is TransientLlmException) return true
        if (e is SocketTimeoutException || e is InterruptedIOException) return true
        if (e is SocketException || e is EOFException || e is UnknownHostException || e is SSLException) return true
        val msg = e.message?.lowercase().orEmpty()
        return msg.contains("timeout") ||
            msg.contains("timed out") ||
            msg.contains("reset") ||
            msg.contains("connection abort") ||
            msg.contains("broken pipe") ||
            msg.contains("unexpected end of stream") ||
            msg.contains("overloaded") ||
            msg.contains("rate limit") ||
            msg.contains("too many requests")
    }

    private fun isTransientHttpStatus(code: Int): Boolean {
        return code == 408 || code == 429 || code in 500..599
    }

    private fun callGemini(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        systemPrompt: String,
        messages: List<LlmTurnMessage>,
        tools: List<ToolDefinition>
    ): LlmResponse {
        val root = JsonObject()

        if (systemPrompt.isNotBlank()) {
            val sysObj = JsonObject()
            val sysParts = JsonArray()
            val sysPart = JsonObject()
            sysPart.addProperty("text", systemPrompt)
            sysParts.add(sysPart)
            sysObj.add("parts", sysParts)
            root.add("systemInstruction", sysObj)
        }

        val contents = JsonArray()
        for (msg in messages) {
            when (msg.role) {
                "user" -> {
                    val contentObj = JsonObject()
                    contentObj.addProperty("role", "user")
                    val parts = JsonArray()
                    val part = JsonObject()
                    part.addProperty("text", msg.content.ifEmpty { "Continue." })
                    parts.add(part)
                    contentObj.add("parts", parts)
                    contents.add(contentObj)
                }
                "assistant" -> {
                    val contentObj = JsonObject()
                    contentObj.addProperty("role", "model")
                    val parts = JsonArray()
                    if (msg.content.isNotBlank()) {
                        val textPart = JsonObject()
                        textPart.addProperty("text", msg.content)
                        parts.add(textPart)
                    }
                    for (tc in msg.toolCalls) {
                        val fcPart = JsonObject()
                        val fc = JsonObject()
                        fc.addProperty("name", tc.name)
                        fc.add("args", tc.arguments)
                        fcPart.add("functionCall", fc)
                        if (!tc.thoughtSignature.isNullOrEmpty()) {
                            fcPart.addProperty("thoughtSignature", tc.thoughtSignature)
                        }
                        parts.add(fcPart)
                    }
                    if (parts.size() > 0) {
                        contentObj.add("parts", parts)
                        contents.add(contentObj)
                    }
                }
                "tool" -> {
                    val contentObj = JsonObject()
                    contentObj.addProperty("role", "user")
                    val parts = JsonArray()
                    val frPart = JsonObject()
                    val fr = JsonObject()
                    fr.addProperty("name", msg.toolName ?: "tool")
                    val responsePayload = JsonObject()
                    responsePayload.addProperty("result", msg.content)
                    fr.add("response", responsePayload)
                    frPart.add("functionResponse", fr)
                    parts.add(frPart)
                    contentObj.add("parts", parts)
                    contents.add(contentObj)
                }
            }
        }
        root.add("contents", contents)

        if (tools.isNotEmpty()) {
            val toolsArr = JsonArray()
            val toolObj = JsonObject()
            val funcDecls = JsonArray()
            for (tool in tools) {
                val decl = JsonObject()
                decl.addProperty("name", tool.name)
                decl.addProperty("description", tool.description)
                decl.add("parameters", buildJsonSchema(tool.parameters))
                funcDecls.add(decl)
            }
            toolObj.add("functionDeclarations", funcDecls)
            toolsArr.add(toolObj)
            root.add("tools", toolsArr)
        }

        val genConfig = JsonObject()
        genConfig.addProperty("temperature", 0.2)
        root.add("generationConfig", genConfig)

        val cleanModel = modelId.removePrefix("models/")
        val url = "$baseUrl/models/$cleanModel:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(gson.toJson(root).toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val msg = "Gemini API Error (${response.code}): ${extractErrorDetail(bodyStr)}"
                if (isTransientHttpStatus(response.code)) {
                    throw TransientLlmException(msg, response.code)
                }
                throw IOException(msg)
            }
            return parseGeminiResponse(bodyStr)
        }
    }

    internal fun parseGeminiResponse(bodyStr: String): LlmResponse {
        val objects = extractJsonObjectsFromBody(bodyStr)
        if (objects.isEmpty()) {
            return LlmResponse(text = bodyStr.trim().ifEmpty { "(No response from Gemini)" }, toolCalls = emptyList())
        }
        val textBuilder = StringBuilder()
        val toolCalls = mutableListOf<LlmToolCall>()
        var finishReason: String? = null
        var totalTokens = 0

        for (json in objects) {
            val candidates = json.getAsJsonArray("candidates")
            if (candidates == null || candidates.size() == 0) continue
            val firstCandidate = candidates[0].takeIf { it.isJsonObject }?.asJsonObject ?: continue
            firstCandidate.get("finishReason")?.takeIf { !it.isJsonNull }?.asString?.let { finishReason = it }
            val contentObj = firstCandidate.get("content")?.takeIf { it.isJsonObject }?.asJsonObject
            val parts = contentObj?.getAsJsonArray("parts") ?: JsonArray()

            for (element in parts) {
                if (!element.isJsonObject) continue
                val part = element.asJsonObject
                val isThought = part.get("thought")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                if (part.has("text") && !part.get("text").isJsonNull && !isThought) {
                    textBuilder.append(part.get("text").asString)
                }
                if (part.has("functionCall") && part.get("functionCall").isJsonObject) {
                    val fc = part.getAsJsonObject("functionCall")
                    val name = fc.get("name")?.takeIf { !it.isJsonNull }?.asString ?: continue
                    val args = if (fc.has("args") && fc.get("args").isJsonObject) {
                        fc.getAsJsonObject("args")
                    } else {
                        JsonObject()
                    }
                    val thoughtSig = part.get("thoughtSignature")?.takeIf { !it.isJsonNull }?.asString
                    toolCalls.add(
                        LlmToolCall(
                            id = "call_${UUID.randomUUID().toString().take(8)}",
                            name = name,
                            arguments = args,
                            thoughtSignature = thoughtSig
                        )
                    )
                }
            }
            val usage = json.get("usageMetadata")?.takeIf { it.isJsonObject }?.asJsonObject
            usage?.get("totalTokenCount")?.takeIf { !it.isJsonNull }?.asInt?.let { totalTokens = it }
        }

        val finalText = textBuilder.toString().trim()
        if (toolCalls.isEmpty() && finalText.contains("<tool_call>")) {
            val (cleanedText, inlineCalls) = extractInlineToolCalls(finalText)
            if (inlineCalls.isNotEmpty()) {
                return LlmResponse(text = cleanedText, toolCalls = inlineCalls, finishReason = finishReason, totalTokens = totalTokens)
            }
        }

        return LlmResponse(
            text = finalText.ifEmpty { if (toolCalls.isEmpty()) "(No response from Gemini)" else "" },
            toolCalls = toolCalls,
            finishReason = finishReason,
            totalTokens = totalTokens
        )
    }

    private fun callOpenAiCompatible(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        systemPrompt: String,
        messages: List<LlmTurnMessage>,
        tools: List<ToolDefinition>,
        allowPromptToolFallback: Boolean = true,
        allowTemperature: Boolean = true
    ): LlmResponse {
        val root = JsonObject()
        root.addProperty("model", modelId)
        // Rationale: o-series/gpt-5 hanya menerima temperature default (1) dan menolak nilai lain dengan HTTP 400.
        if (allowTemperature && supportsCustomTemperature(modelId)) {
            root.addProperty("temperature", 0.2)
        }
        root.addProperty("stream", false)

        val msgArray = JsonArray()
        if (systemPrompt.isNotBlank()) {
            val sysMsg = JsonObject()
            sysMsg.addProperty("role", "system")
            sysMsg.addProperty("content", systemPrompt)
            msgArray.add(sysMsg)
        }

        for (msg in messages) {
            when (msg.role) {
                "user" -> {
                    val m = JsonObject()
                    m.addProperty("role", "user")
                    m.addProperty("content", msg.content)
                    msgArray.add(m)
                }
                "assistant" -> {
                    val m = JsonObject()
                    m.addProperty("role", "assistant")
                    if (msg.content.isNotBlank()) {
                        m.addProperty("content", msg.content)
                    } else {
                        m.add("content", null)
                    }
                    if (msg.toolCalls.isNotEmpty()) {
                        val tcArr = JsonArray()
                        for (tc in msg.toolCalls) {
                            val tcObj = JsonObject()
                            tcObj.addProperty("id", tc.id)
                            tcObj.addProperty("type", "function")
                            val fn = JsonObject()
                            fn.addProperty("name", tc.name)
                            fn.addProperty("arguments", gson.toJson(tc.arguments))
                            tcObj.add("function", fn)
                            tcArr.add(tcObj)
                        }
                        m.add("tool_calls", tcArr)
                    }
                    msgArray.add(m)
                }
                "tool" -> {
                    val m = JsonObject()
                    m.addProperty("role", "tool")
                    m.addProperty("tool_call_id", msg.toolCallId ?: "call_0")
                    m.addProperty("content", msg.content)
                    msgArray.add(m)
                }
            }
        }
        root.add("messages", msgArray)

        if (tools.isNotEmpty()) {
            val toolsArr = JsonArray()
            for (tool in tools) {
                val tObj = JsonObject()
                tObj.addProperty("type", "function")
                val fn = JsonObject()
                fn.addProperty("name", tool.name)
                fn.addProperty("description", tool.description)
                fn.add("parameters", buildJsonSchema(tool.parameters))
                tObj.add("function", fn)
                toolsArr.add(tObj)
            }
            root.add("tools", toolsArr)
            root.addProperty("tool_choice", "auto")
        }

        val normalizedBase = normalizeOpenAiBaseUrl(baseUrl)
        val endpoint = if (normalizedBase.endsWith("/chat/completions")) normalizedBase else "$normalizedBase/chat/completions"
        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(gson.toJson(root).toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")

        if (apiKey.isNotEmpty()) {
            reqBuilder.header("Authorization", "Bearer $apiKey")
        }
        if (configManager?.providerType == AiProviderType.OPENROUTER) {
            reqBuilder.header("HTTP-Referer", "https://sketchware.pro")
            reqBuilder.header("X-Title", "Sketchware Pro AI Agent")
        }

        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = extractErrorDetail(bodyStr)
                // Gateway menolak temperature (model reasoning / provider tertentu): ulangi tanpa parameter tersebut.
                if (allowTemperature && response.code in 400..422 && root.has("temperature") &&
                    detail.contains("temperature", ignoreCase = true)
                ) {
                    return callOpenAiCompatible(
                        baseUrl, apiKey, modelId, systemPrompt, messages, tools,
                        allowPromptToolFallback = allowPromptToolFallback, allowTemperature = false
                    )
                }
                // If a custom/free model gateway rejects the 'tools' schema (HTTP 400/422), retry using inline <tool_call> prompt mode
                if (allowPromptToolFallback && tools.isNotEmpty() && (response.code == 400 || response.code == 422) &&
                    (detail.contains("tool", ignoreCase = true) || detail.contains("function", ignoreCase = true) || detail.contains("schema", ignoreCase = true))
                ) {
                    val fallbackSys = buildPromptBasedToolSystemPrompt(systemPrompt, tools)
                    val fallbackMsgs = messages.map { m ->
                        when (m.role) {
                            "tool" -> LlmTurnMessage(role = "user", content = "[Tool Result '${m.toolName ?: m.toolCallId}']:\n${m.content}")
                            "assistant" -> if (m.toolCalls.isEmpty()) m else LlmTurnMessage(
                                role = "assistant",
                                content = buildString {
                                    if (m.content.isNotBlank()) appendLine(m.content)
                                    for (tc in m.toolCalls) {
                                        appendLine("<tool_call>{\"name\":\"${tc.name}\",\"arguments\":${gson.toJson(tc.arguments)}}</tool_call>")
                                    }
                                }.trim()
                            )
                            else -> m
                        }
                    }
                    return callOpenAiCompatible(baseUrl, apiKey, modelId, fallbackSys, fallbackMsgs, emptyList(), allowPromptToolFallback = false, allowTemperature = allowTemperature)
                }
                val hint = when (response.code) {
                    401 -> " [API Key ditolak/salah]"
                    404 -> " [Endpoint/model tidak ditemukan: $endpoint]"
                    else -> ""
                }
                val msg = "API Error (${response.code}): $detail$hint"
                if (isTransientHttpStatus(response.code)) {
                    throw TransientLlmException(msg, response.code)
                }
                throw IOException(msg)
            }
            return parseOpenAiResponse(bodyStr)
        }
    }

    private fun supportsCustomTemperature(modelId: String): Boolean {
        val id = modelId.substringAfterLast('/').lowercase()
        return !(id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") || id.startsWith("gpt-5"))
    }

    /**
     * Rationale: user sering mengisi host saja (https://api.openai.com) sehingga request mendarat di
     * /chat/completions tanpa /v1 -> 404. Tambahkan path default hanya jika base URL tidak punya path sama sekali.
     */
    internal fun normalizeOpenAiBaseUrl(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        val schemeEnd = clean.indexOf("://")
        if (schemeEnd < 0) return clean
        val hasPath = clean.indexOf('/', schemeEnd + 3) >= 0
        if (hasPath) return clean
        val host = clean.substring(schemeEnd + 3).lowercase()
        return when {
            host == "api.openai.com" -> "$clean/v1"
            host == "openrouter.ai" -> "$clean/api/v1"
            else -> clean
        }
    }

    private data class StreamToolCallAccumulator(
        var id: String = "",
        var name: String = "",
        val argsBuilder: StringBuilder = StringBuilder()
    )

    internal fun parseOpenAiResponse(bodyStr: String): LlmResponse {
        val objects = extractJsonObjectsFromBody(bodyStr)
        if (objects.isEmpty()) {
            val trimmed = bodyStr.trim()
            if (trimmed.isEmpty()) {
                return LlmResponse(text = "(No response from model)", toolCalls = emptyList())
            }
            val (cleanText, inlineCalls) = extractInlineToolCalls(trimmed)
            return LlmResponse(text = cleanText, toolCalls = inlineCalls)
        }

        val textBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        val directToolCalls = mutableListOf<LlmToolCall>()
        val streamedToolCalls = linkedMapOf<Int, StreamToolCallAccumulator>()
        var finishReason: String? = null
        var totalTokens = 0

        for (json in objects) {
            if (json.has("error") && !json.has("choices") && objects.size == 1) {
                throw IOException("API Error: ${extractErrorDetail(bodyStr)}")
            }

            val usage = json.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
            usage?.get("total_tokens")?.takeIf { !it.isJsonNull }?.asInt?.let {
                if (it > 0) totalTokens = it
            }

            val choices = json.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray
            if (choices == null || choices.size() == 0) {
                // Some custom gateways return top-level content/response/output_text directly
                for (topKey in listOf("content", "response", "output_text", "text")) {
                    val topVal = json.get(topKey)
                    if (topVal != null && topVal.isJsonPrimitive) {
                        textBuilder.append(topVal.asString)
                        break
                    }
                }
                continue
            }

            val firstChoice = choices[0].takeIf { it.isJsonObject }?.asJsonObject ?: continue
            firstChoice.get("finish_reason")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.let {
                if (it.isNotBlank()) finishReason = it
            }

            // 1. Standard non-streaming "message" object
            val message = firstChoice.get("message")?.takeIf { it.isJsonObject }?.asJsonObject
            if (message != null) {
                appendContentField(message.get("content"), textBuilder)
                appendContentField(message.get("reasoning_content") ?: message.get("reasoning"), reasoningBuilder)

                val tcArray = message.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray
                if (tcArray != null) {
                    for (el in tcArray) {
                        if (!el.isJsonObject) continue
                        val tcObj = el.asJsonObject
                        val id = tcObj.get("id")?.takeIf { !it.isJsonNull }?.asString
                            ?: "call_${UUID.randomUUID().toString().take(8)}"
                        val fn = tcObj.get("function")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                        val name = fn.get("name")?.takeIf { !it.isJsonNull }?.asString ?: continue
                        val argsObj = parseToolArgumentsElement(fn.get("arguments"))
                        directToolCalls.add(LlmToolCall(id = id, name = name, arguments = argsObj))
                    }
                } else if (message.has("function_call") && message.get("function_call").isJsonObject) {
                    val fn = message.getAsJsonObject("function_call")
                    val name = fn.get("name")?.takeIf { !it.isJsonNull }?.asString
                    if (!name.isNullOrBlank()) {
                        val argsObj = parseToolArgumentsElement(fn.get("arguments"))
                        directToolCalls.add(
                            LlmToolCall(
                                id = "call_${UUID.randomUUID().toString().take(8)}",
                                name = name,
                                arguments = argsObj
                            )
                        )
                    }
                }
            }

            // 2. Streaming / chunked "delta" object (when server sends SSE or concatenated chunk objects)
            val delta = firstChoice.get("delta")?.takeIf { it.isJsonObject }?.asJsonObject
            if (delta != null) {
                appendContentField(delta.get("content"), textBuilder)
                appendContentField(delta.get("reasoning_content") ?: delta.get("reasoning"), reasoningBuilder)

                val deltaTcArray = delta.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray
                if (deltaTcArray != null) {
                    for ((fallbackIdx, el) in deltaTcArray.withIndex()) {
                        if (!el.isJsonObject) continue
                        val tcObj = el.asJsonObject
                        val idx = tcObj.get("index")?.takeIf { !it.isJsonNull }?.asInt ?: fallbackIdx
                        val acc = streamedToolCalls.getOrPut(idx) { StreamToolCallAccumulator() }
                        tcObj.get("id")?.takeIf { !it.isJsonNull }?.asString?.let {
                            if (it.isNotBlank()) acc.id = it
                        }
                        val fn = tcObj.get("function")?.takeIf { it.isJsonObject }?.asJsonObject
                        if (fn != null) {
                            fn.get("name")?.takeIf { !it.isJsonNull }?.asString?.let {
                                if (it.isNotBlank()) acc.name += it
                            }
                            val argPart = fn.get("arguments")
                            if (argPart != null && !argPart.isJsonNull) {
                                if (argPart.isJsonPrimitive) {
                                    acc.argsBuilder.append(argPart.asString)
                                } else if (argPart.isJsonObject) {
                                    acc.argsBuilder.append(gson.toJson(argPart))
                                }
                            }
                        }
                    }
                }
            }
        }

        for ((_, acc) in streamedToolCalls) {
            if (acc.name.isBlank()) continue
            val id = acc.id.ifBlank { "call_${UUID.randomUUID().toString().take(8)}" }
            val argsObj = parseJsonObjectLenient(acc.argsBuilder.toString())
            directToolCalls.add(LlmToolCall(id = id, name = acc.name.trim(), arguments = argsObj))
        }

        var finalText = textBuilder.toString().trim()
        if (finalText.isEmpty() && directToolCalls.isEmpty() && reasoningBuilder.isNotEmpty()) {
            finalText = reasoningBuilder.toString().trim()
        }

        if (directToolCalls.isEmpty() && finalText.contains("<tool_call>")) {
            val (cleanedText, inlineCalls) = extractInlineToolCalls(finalText)
            if (inlineCalls.isNotEmpty()) {
                finalText = cleanedText
                directToolCalls.addAll(inlineCalls)
            }
        }

        return LlmResponse(
            text = finalText,
            toolCalls = directToolCalls,
            finishReason = finishReason,
            totalTokens = totalTokens
        )
    }

    private fun callClaude(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        systemPrompt: String,
        messages: List<LlmTurnMessage>,
        tools: List<ToolDefinition>
    ): LlmResponse {
        val root = JsonObject()
        root.addProperty("model", modelId)
        root.addProperty("max_tokens", 8192)
        if (systemPrompt.isNotBlank()) {
            root.addProperty("system", systemPrompt)
        }

        val msgArray = JsonArray()
        for (msg in messages) {
            when (msg.role) {
                "user" -> {
                    val m = JsonObject()
                    m.addProperty("role", "user")
                    m.addProperty("content", msg.content.ifEmpty { "Continue." })
                    msgArray.add(m)
                }
                "assistant" -> {
                    val m = JsonObject()
                    m.addProperty("role", "assistant")
                    val contentArr = JsonArray()
                    if (msg.content.isNotBlank()) {
                        val tb = JsonObject()
                        tb.addProperty("type", "text")
                        tb.addProperty("text", msg.content)
                        contentArr.add(tb)
                    }
                    for (tc in msg.toolCalls) {
                        val tu = JsonObject()
                        tu.addProperty("type", "tool_use")
                        tu.addProperty("id", tc.id)
                        tu.addProperty("name", tc.name)
                        tu.add("input", tc.arguments)
                        contentArr.add(tu)
                    }
                    m.add("content", contentArr)
                    msgArray.add(m)
                }
                "tool" -> {
                    val m = JsonObject()
                    m.addProperty("role", "user")
                    val contentArr = JsonArray()
                    val tr = JsonObject()
                    tr.addProperty("type", "tool_result")
                    tr.addProperty("tool_use_id", msg.toolCallId ?: "call_0")
                    tr.addProperty("content", msg.content)
                    contentArr.add(tr)
                    m.add("content", contentArr)
                    msgArray.add(m)
                }
            }
        }
        root.add("messages", msgArray)

        if (tools.isNotEmpty()) {
            val toolsArr = JsonArray()
            for (tool in tools) {
                val tObj = JsonObject()
                tObj.addProperty("name", tool.name)
                tObj.addProperty("description", tool.description)
                tObj.add("input_schema", buildJsonSchema(tool.parameters))
                toolsArr.add(tObj)
            }
            root.add("tools", toolsArr)
        }

        val endpoint = if (baseUrl.endsWith("/messages")) baseUrl else "$baseUrl/messages"
        val request = Request.Builder()
            .url(endpoint)
            .post(gson.toJson(root).toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val msg = "Claude API Error (${response.code}): ${extractErrorDetail(bodyStr)}"
                if (isTransientHttpStatus(response.code)) {
                    throw TransientLlmException(msg, response.code)
                }
                throw IOException(msg)
            }
            return parseClaudeResponse(bodyStr)
        }
    }

    private fun parseClaudeResponse(bodyStr: String): LlmResponse {
        val objects = extractJsonObjectsFromBody(bodyStr)
        if (objects.isEmpty()) {
            return LlmResponse(text = bodyStr.trim(), toolCalls = emptyList())
        }
        val textBuilder = StringBuilder()
        val toolCalls = mutableListOf<LlmToolCall>()
        var finishReason: String? = null

        for (json in objects) {
            val contentArr = json.get("content")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
            json.get("stop_reason")?.takeIf { !it.isJsonNull }?.asString?.let { finishReason = it }

            for (el in contentArr) {
                if (!el.isJsonObject) continue
                val block = el.asJsonObject
                when (block.get("type")?.asString) {
                    "text" -> {
                        val t = block.get("text")?.asString.orEmpty()
                        if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                        textBuilder.append(t)
                    }
                    "tool_use" -> {
                        val id = block.get("id")?.asString ?: "call_${UUID.randomUUID().toString().take(8)}"
                        val name = block.get("name")?.asString ?: continue
                        val input = block.get("input")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
                        toolCalls.add(LlmToolCall(id = id, name = name, arguments = input))
                    }
                }
            }
        }

        return LlmResponse(
            text = textBuilder.toString().trim(),
            toolCalls = toolCalls,
            finishReason = finishReason
        )
    }

    private fun buildJsonSchema(parameters: List<ToolParameterProperty>): JsonObject {
        val schema = JsonObject()
        schema.addProperty("type", "object")
        val props = JsonObject()
        val requiredArr = JsonArray()

        for (param in parameters) {
            val propObj = JsonObject()
            propObj.addProperty("type", param.type)
            propObj.addProperty("description", param.description)
            if (!param.enumValues.isNullOrEmpty()) {
                val enumArr = JsonArray()
                param.enumValues.forEach { enumArr.add(it) }
                propObj.add("enum", enumArr)
            }
            props.add(param.name, propObj)
            if (param.required) {
                requiredArr.add(param.name)
            }
        }
        schema.add("properties", props)
        if (requiredArr.size() > 0) {
            schema.add("required", requiredArr)
        }
        return schema
    }

    /**
     * Resiliently extracts one or more root [JsonObject]s from an HTTP response body.
     * Supports:
     * 1. Standard single JSON object responses (even with trailing text/metadata after root `$`)
     * 2. Multiple concatenated JSON objects on the same line (`{...}{...}`) or NDJSON
     * 3. Server-Sent Events (`data: {...}`) streams
     */
    internal fun extractJsonObjectsFromBody(bodyStr: String): List<JsonObject> {
        val trimmed = bodyStr.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return emptyList()

        val results = mutableListOf<JsonObject>()

        // 1. Check if this is an SSE stream containing `data:` lines
        val lines = trimmed.lines()
        val ssePayloads = lines.mapNotNull { line ->
            val l = line.trim()
            if (l.startsWith("data:")) {
                l.removePrefix("data:").trim().takeIf { it.isNotEmpty() && it != "[DONE]" }
            } else {
                null
            }
        }
        if (ssePayloads.isNotEmpty()) {
            for (payload in ssePayloads) {
                parseMultipleJsonObjectsLenient(payload, results)
            }
            if (results.isNotEmpty()) return results
        }

        // 2. Parse one or more root JSON objects using lenient JsonReader
        parseMultipleJsonObjectsLenient(trimmed, results)
        if (results.isNotEmpty()) return results

        // 3. Fallback: scan balanced `{ ... }` blocks in case of prefix/suffix noise
        extractBalancedJsonObjects(trimmed, results)
        return results
    }

    private fun parseMultipleJsonObjectsLenient(raw: String, out: MutableList<JsonObject>) {
        val initialSize = out.size
        try {
            val reader = JsonReader(StringReader(raw)).apply {
                isLenient = true
            }
            while (true) {
                val token = try {
                    reader.peek()
                } catch (_: Exception) {
                    break
                }
                if (token == JsonToken.END_DOCUMENT) break
                if (token == JsonToken.BEGIN_OBJECT) {
                    val el = JsonParser.parseReader(reader)
                    if (el.isJsonObject) {
                        out.add(el.asJsonObject)
                    }
                } else if (token == JsonToken.BEGIN_ARRAY) {
                    val el = JsonParser.parseReader(reader)
                    if (el.isJsonArray) {
                        for (item in el.asJsonArray) {
                            if (item.isJsonObject) out.add(item.asJsonObject)
                        }
                    }
                } else {
                    reader.skipValue()
                }
            }
        } catch (_: Exception) {
            if (out.size == initialSize) {
                extractBalancedJsonObjects(raw, out)
            }
        }
    }

    private fun extractBalancedJsonObjects(raw: String, out: MutableList<JsonObject>) {
        var depth = 0
        var startIdx = -1
        var inString = false
        var escaped = false

        for (i in raw.indices) {
            val c = raw[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) startIdx = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && startIdx >= 0) {
                            val candidate = raw.substring(startIdx, i + 1)
                            try {
                                val reader = JsonReader(StringReader(candidate)).apply { isLenient = true }
                                val el = JsonParser.parseReader(reader)
                                if (el.isJsonObject) {
                                    out.add(el.asJsonObject)
                                }
                            } catch (_: Exception) {
                                // Ignore malformed candidate block
                            }
                            startIdx = -1
                        }
                    }
                }
            }
        }
    }

    private fun parseJsonObjectLenient(raw: String): JsonObject {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return JsonObject()
        val extracted = extractJsonObjectsFromBody(trimmed)
        if (extracted.isNotEmpty()) return extracted.first()
        return JsonObject()
    }

    private fun parseToolArgumentsElement(el: JsonElement?): JsonObject {
        if (el == null || el.isJsonNull) return JsonObject()
        if (el.isJsonObject) return el.asJsonObject
        if (el.isJsonPrimitive) {
            return parseJsonObjectLenient(el.asString)
        }
        return JsonObject()
    }

    private fun appendContentField(el: JsonElement?, out: StringBuilder) {
        if (el == null || el.isJsonNull) return
        when {
            el.isJsonPrimitive -> {
                out.append(el.asString)
            }
            el.isJsonArray -> {
                for (item in el.asJsonArray) {
                    if (item.isJsonPrimitive) {
                        out.append(item.asString)
                    } else if (item.isJsonObject) {
                        val obj = item.asJsonObject
                        val textPart = obj.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                            ?: obj.get("content")?.takeIf { it.isJsonPrimitive }?.asString
                        if (!textPart.isNullOrEmpty()) {
                            out.append(textPart)
                        }
                    }
                }
            }
        }
    }

    private fun extractInlineToolCalls(text: String): Pair<String, List<LlmToolCall>> {
        val regex = Regex("""<tool_call>\s*(\{[\s\S]*?\})\s*</tool_call>""")
        val matches = regex.findAll(text).toList()
        if (matches.isEmpty()) return text to emptyList()

        val calls = mutableListOf<LlmToolCall>()
        for (match in matches) {
            val jsonBlock = match.groupValues[1]
            val obj = parseJsonObjectLenient(jsonBlock)
            val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString
            if (!name.isNullOrBlank()) {
                val args = parseToolArgumentsElement(obj.get("arguments") ?: obj.get("parameters"))
                calls.add(
                    LlmToolCall(
                        id = "call_${UUID.randomUUID().toString().take(8)}",
                        name = name,
                        arguments = args
                    )
                )
            }
        }
        val cleaned = text.replace(regex, "").trim()
        return cleaned to calls
    }

    private fun buildPromptBasedToolSystemPrompt(
        basePrompt: String,
        tools: List<ToolDefinition>
    ): String {
        val sb = StringBuilder(basePrompt)
        sb.append("\n\n## Tool Calling Format\n")
        sb.append("When you need to invoke a tool, output a block in this exact format:\n")
        sb.append("<tool_call>\n{\"name\": \"tool_name\", \"arguments\": {\"param\": \"value\"}}\n</tool_call>\n")
        sb.append("Available tools:\n")
        for (tool in tools) {
            sb.append("- ${tool.name}: ${tool.description}\n")
        }
        return sb.toString()
    }

    private fun extractErrorDetail(body: String): String {
        return try {
            val objs = extractJsonObjectsFromBody(body)
            for (obj in objs) {
                if (obj.has("error")) {
                    val err = obj.get("error")
                    if (err.isJsonObject && err.asJsonObject.has("message")) {
                        return err.asJsonObject.get("message").asString
                    }
                    return err.toString()
                }
                if (obj.has("message") && obj.get("message").isJsonPrimitive) {
                    return obj.get("message").asString
                }
            }
            body.take(400)
        } catch (_: Exception) {
            body.take(400)
        }
    }
}
