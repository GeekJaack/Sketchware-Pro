package pro.sketchware.ai.ui

import pro.sketchware.ai.config.AgentLanguage
import pro.sketchware.ai.config.AiProviderType

/** Where a skill / connector / plugin item comes from; drives the source pill and the filter chips. */
enum class SourceKind { BUILTIN, CUSTOM, PLUGIN }

/** Health of an MCP connector as shown by the status dot. */
enum class McpState { CONNECTED, ERROR, IDLE, DISABLED }

/** Why an API key looks wrong for the selected provider (soft hints, never blocking). */
enum class KeyHint {
    WHITESPACE, LOOKS_URL, LOOKS_ANTHROPIC, LOOKS_GOOGLE, LOOKS_OPENROUTER,
    EXPECT_SK, EXPECT_SK_ANT, EXPECT_AIZA, EXPECT_SK_OR
}

/** UI-independent rules shared by the Skills, Plugins, MCP and Model pages. */
object CapabilityLogic {

    /** Skills seeded by SkillManager on first run. */
    val BUILTIN_SKILL_IDS: Set<String> = setOf(
        "patent-principal-software-engineer",
        "patent-terminal-linux-commander",
        "patent-rea-apk-cloner",
        "material-ui-architect",
        "rest-api-networking",
        "linux-jar-dex-builder",
        "autonomous-bug-healer"
    )

    fun skillKind(source: String, id: String): SourceKind = when {
        source.startsWith("plugin:") -> SourceKind.PLUGIN
        id in BUILTIN_SKILL_IDS -> SourceKind.BUILTIN
        else -> SourceKind.CUSTOM
    }

    fun mcpKind(source: String): SourceKind = when {
        source == "builtin" -> SourceKind.BUILTIN
        source.startsWith("plugin:") -> SourceKind.PLUGIN
        else -> SourceKind.CUSTOM
    }

    fun pluginIdOf(source: String): String? = if (source.startsWith("plugin:")) source.removePrefix("plugin:") else null

    /** `OK (3 tools)` -> connected; anything mentioning an error -> error; never synced -> idle. */
    fun mcpState(enabled: Boolean, lastStatus: String, toolCount: Int): McpState {
        if (!enabled) return McpState.DISABLED
        val s = lastStatus.trim()
        if (s.startsWith("OK", ignoreCase = true)) return McpState.CONNECTED
        if (ERROR_WORDS.any { s.contains(it, ignoreCase = true) }) return McpState.ERROR
        return if (toolCount > 0 && s.isNotEmpty()) McpState.CONNECTED else McpState.IDLE
    }

    private val ERROR_WORDS = listOf("error", "gagal", "failed", "timeout", "refused", "unauthorized", "exception")

    /** Every whitespace-separated token of [query] must occur in at least one of [fields]. */
    fun matches(query: String, vararg fields: String?): Boolean {
        val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        val haystack = fields.filterNotNull().joinToString(" ").lowercase()
        return tokens.all { haystack.contains(it) }
    }

    /** 0..3, stable for a given name: picks one of the four icon-tile tints. */
    fun tone(name: String): Int = name.fold(0) { acc, c -> acc + c.code } % 4

    fun initials(name: String): String {
        val words = name.trim().split(Regex("[\\s_\\-./]+")).filter { it.isNotEmpty() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(2).uppercase()
            else -> (words[0].take(1) + words[1].take(1)).uppercase()
        }
    }

    /** Names of at most [max] items followed by "+N" when more exist. */
    fun preview(items: List<String>, max: Int): List<String> =
        if (items.size <= max) items else items.take(max) + "+${items.size - max}"

    fun checkApiKey(provider: AiProviderType, rawKey: String): KeyHint? = ApiKeyHints.check(provider, rawKey)
}

object ApiKeyHints {

    fun check(provider: AiProviderType, rawKey: String): KeyHint? {
        val key = rawKey.trim()
        if (key.isEmpty()) return null
        if (key.contains(Regex("\\s"))) return KeyHint.WHITESPACE
        if (key.startsWith("http://") || key.startsWith("https://")) return KeyHint.LOOKS_URL
        val looksAnthropic = key.startsWith("sk-ant-")
        val looksGoogle = key.startsWith("AIza")
        val looksOpenRouter = key.startsWith("sk-or-")
        return when (provider) {
            AiProviderType.OPENAI -> when {
                looksAnthropic -> KeyHint.LOOKS_ANTHROPIC
                looksGoogle -> KeyHint.LOOKS_GOOGLE
                looksOpenRouter -> KeyHint.LOOKS_OPENROUTER
                !key.startsWith("sk-") -> KeyHint.EXPECT_SK
                else -> null
            }
            AiProviderType.CLAUDE -> when {
                looksGoogle -> KeyHint.LOOKS_GOOGLE
                looksOpenRouter -> KeyHint.LOOKS_OPENROUTER
                !looksAnthropic -> KeyHint.EXPECT_SK_ANT
                else -> null
            }
            AiProviderType.GEMINI -> when {
                looksAnthropic -> KeyHint.LOOKS_ANTHROPIC
                looksOpenRouter -> KeyHint.LOOKS_OPENROUTER
                !looksGoogle && key.startsWith("sk-") -> KeyHint.EXPECT_AIZA
                else -> null
            }
            AiProviderType.OPENROUTER -> when {
                looksAnthropic -> KeyHint.LOOKS_ANTHROPIC
                looksGoogle -> KeyHint.LOOKS_GOOGLE
                !looksOpenRouter -> KeyHint.EXPECT_SK_OR
                else -> null
            }
            AiProviderType.CUSTOM -> null
        }
    }
}

/** Two-language UI strings (Indonesian when the agent language resolves to ID, English otherwise). */
class ExtText(lang: AgentLanguage) {
    private val indonesian = lang.resolved() == AgentLanguage.ID

    fun t(id: String, en: String): String = if (indonesian) id else en

    fun enabledOfTotal(enabled: Int, total: Int): String =
        t("$enabled dari $total aktif", "$enabled of $total enabled")

    fun keyHint(hint: KeyHint): String = when (hint) {
        KeyHint.WHITESPACE -> t("Key mengandung spasi atau baris baru. Tempel ulang tanpa spasi.", "The key contains spaces or a line break. Paste it again without whitespace.")
        KeyHint.LOOKS_URL -> t("Ini tampak seperti URL, bukan API key.", "This looks like a URL, not an API key.")
        KeyHint.LOOKS_ANTHROPIC -> t("Awalan sk-ant- adalah key Anthropic. Pilih penyedia Claude.", "The sk-ant- prefix is an Anthropic key. Choose the Claude provider.")
        KeyHint.LOOKS_GOOGLE -> t("Awalan AIza adalah key Google. Pilih penyedia Gemini.", "The AIza prefix is a Google key. Choose the Gemini provider.")
        KeyHint.LOOKS_OPENROUTER -> t("Awalan sk-or- adalah key OpenRouter. Pilih penyedia OpenRouter.", "The sk-or- prefix is an OpenRouter key. Choose the OpenRouter provider.")
        KeyHint.EXPECT_SK -> t("Key OpenAI biasanya diawali sk-.", "OpenAI keys usually start with sk-.")
        KeyHint.EXPECT_SK_ANT -> t("Key Anthropic biasanya diawali sk-ant-.", "Anthropic keys usually start with sk-ant-.")
        KeyHint.EXPECT_AIZA -> t("Key Gemini biasanya diawali AIza.", "Gemini keys usually start with AIza.")
        KeyHint.EXPECT_SK_OR -> t("Key OpenRouter biasanya diawali sk-or-.", "OpenRouter keys usually start with sk-or-.")
    }
}
