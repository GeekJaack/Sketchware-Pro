package pro.sketchware.ai.ui

import pro.sketchware.ai.config.AgentLanguage
import pro.sketchware.ai.config.AiProviderType

/** Why an API key looks wrong for the selected provider (soft hints, never blocking). */
enum class KeyHint {
    WHITESPACE, LOOKS_URL, LOOKS_ANTHROPIC, LOOKS_GOOGLE, LOOKS_OPENROUTER,
    EXPECT_SK, EXPECT_SK_ANT, EXPECT_AIZA, EXPECT_SK_OR
}

/** Detects the most common copy/paste and wrong-provider mistakes from the key's prefix. */
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

/** Indonesian when the agent language resolves to ID, English otherwise. */
class ExtText(lang: AgentLanguage) {
    private val indonesian = lang.resolved() == AgentLanguage.ID

    fun t(id: String, en: String): String = if (indonesian) id else en

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
