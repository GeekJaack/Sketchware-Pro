package pro.sketchware.ai.ui

import android.content.Context

/**
 * Backward-compatible router that opens the dedicated full-screen studio view for:
 * - `initialTab = 0` -> [McpStudioScreen] (MCP Server, Outbound Connectors & 205 REA Tools Explorer)
 * - `initialTab = 1` -> [PluginStudioScreen] (Dedicated Plugins `plugin.json` Studio)
 * - `initialTab = 2` -> [SkillStudioScreen] (Dedicated Skills `SKILL.md` Studio)
 */
object ExtensionsHubDialog {

    @JvmStatic
    fun show(
        context: Context,
        initialTab: Int = 0,
        onUsePrompt: ((String) -> Unit)? = null,
        onChanged: (() -> Unit)? = null
    ) {
        when (initialTab) {
            1 -> PluginStudioScreen.show(context, onUsePrompt, onChanged)
            2 -> SkillStudioScreen.show(context, onUsePrompt, onChanged)
            else -> McpStudioScreen.show(context, onUsePrompt, onChanged)
        }
    }
}
