package app.tavern.nativeclient

import java.lang.Character.UnicodeScript

internal const val DEFAULT_CONTEXT_TOKENS = 8_192
internal const val MAX_CONTEXT_TOKENS = 10_000_000

internal data class TextTokenCounter(val label: String, val count: (String) -> Int)
internal val estimatedTokens = TextTokenCounter("本机估算", ::estimateTextTokens)

internal data class LoreBudget(val limit: Int, val counter: TextTokenCounter)

internal data class LoreBudgetUsage(val limit: Int, val used: Int, val blocked: Boolean, val counter: String)

private val denseTokenScripts = setOf(UnicodeScript.HAN, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA,
    UnicodeScript.HANGUL, UnicodeScript.THAI, UnicodeScript.LAO, UnicodeScript.KHMER, UnicodeScript.MYANMAR)

private fun estimateTextTokens(text: String): Int {
    var units = 0L
    var offset = 0
    while (offset < text.length) {
        val point = text.codePointAt(offset)
        offset += Character.charCount(point)
        units += when {
            point == 10 || point == 13 -> 2
            Character.isWhitespace(point) -> 1
            UnicodeScript.of(point) in denseTokenScripts -> 4
            point > 0xFFFF -> 8
            Character.isDigit(point) -> 2
            !Character.isLetter(point) -> 3
            point <= 0x7F -> 1
            else -> 2
        }
    }
    return ((units + 3) / 4).toInt()
}

internal fun ModelConnection.contextTokens(): Int {
    val id = model.removePrefix("models/").lowercase()
    val reported = provider.modelContexts[model] ?: provider.modelContexts[id]
    val known = when {
        provider.template == ProviderTemplate.DEEPSEEK || "deepseek" in id -> when {
            id == "deepseek-flash" || id == "deepseek-pro" || id.startsWith("deepseek-v4") || "deepseek-v4" in id -> 1_048_576
            id == "deepseek-chat" || id == "deepseek-reasoner" || "deepseek" in id -> 131_072
            else -> null
        }
        provider.template == ProviderTemplate.GOOGLE || "gemini" in id -> 1_048_576.takeIf {
            "gemini-4" in id || "gemini-3" in id || "gemini-2.5" in id || "gemini-2.0" in id || "gemini-1.5" in id
        }
        provider.template == ProviderTemplate.ANTHROPIC || provider.protocol == ApiProtocol.ANTHROPIC || "claude" in id -> 200_000
        else -> null
    }
    val automatic = reported ?: known
    val override = provider.contextLimit
    return if (override == null) automatic ?: DEFAULT_CONTEXT_TOKENS
        else minOf(override, automatic ?: override)
}

internal fun LoreSettings.modelBudget(connection: ModelConnection, prefs: Preferences, preset: PromptPreset?,
                                      counter: TextTokenCounter): LoreBudget {
    val output = prefs.maxTokens.toIntOrNull() ?: preset?.sampling?.get(PresetSampling.MAX_TOKENS)?.toIntOrNull() ?: 0
    val prompt = (connection.contextTokens() - output.coerceAtLeast(0)).coerceAtLeast(0)
    val proportional = ((prompt.toLong() * budgetPercent + 50) / 100).toInt()
    val limit = if (budgetCap > 0) minOf(proportional, budgetCap) else proportional
    return LoreBudget(limit, counter)
}
