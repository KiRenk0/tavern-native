package app.tavern.nativeclient

import java.lang.Character.UnicodeScript

internal const val DEFAULT_CONTEXT_TOKENS = 8_192
internal const val MAX_CONTEXT_TOKENS = 10_000_000

internal data class TextTokenCounter(
    val label: String,
    val count: (String) -> Int,
    val encode: ((String) -> IntArray)? = null,
    val preview: ((String) -> TokenPreview)? = null,
)
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

internal fun ModelConnection.contextTokens(includeTextSampler: Boolean = true, presetLimit: Int? = null): Int {
    val id = model.removePrefix("models/").lowercase()
    val derived = provider.modelFormatting.takeIf { provider.usesTextCompletion && it.deriveContextSize }
        ?.backend?.get(model)?.contextTokens
    val reported = derived ?: provider.modelContexts[model] ?: provider.modelContexts[id]
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
    val automatic = when (provider.protocol) {
        ApiProtocol.NOVELAI_TEXT -> NovelAiModel.from(model)?.let { NovelAi.context(provider, it) }
        ApiProtocol.HORDE_TEXT -> hordeModelLimit(provider, model, provider.modelContexts).takeIf { provider.horde.adjustContext }
        else -> reported ?: known
    }
    val samplerLimit = if (includeTextSampler) textSampler()?.get(TextSamplerField.CONTEXT)?.toIntOrNull() else null
    val override = listOfNotNull(provider.contextLimit, samplerLimit, presetLimit).minOrNull()
    val fallback = if (provider.protocol == ApiProtocol.HORDE_TEXT) HORDE_DEFAULT_CONTEXT else DEFAULT_CONTEXT_TOKENS
    val result = if (override == null) automatic ?: fallback else minOf(override, automatic ?: override)
    return if (provider.protocol == ApiProtocol.HORDE_TEXT) result.coerceIn(80, HORDE_MAX_CONTEXT) else result
}

internal fun LoreSettings.modelBudget(connection: ModelConnection, prefs: Preferences, preset: PromptPreset?,
                                      counter: TextTokenCounter, outputTokens: Int? = null): LoreBudget {
    val output = outputTokens ?: prefs.maxTokens.toIntOrNull()
        ?: connection.textSampler()?.get(TextSamplerField.MAX_OUTPUT)?.toIntOrNull()
        ?: preset?.sampling?.get(PresetSampling.MAX_TOKENS)?.toIntOrNull() ?: 0
    val context = connection.contextTokens(presetLimit = preset.chatCompletionFor(connection)?.contextLimit)
    val prompt = (context - output.coerceAtLeast(0)).coerceAtLeast(0)
    val proportional = ((prompt.toLong() * budgetPercent + 50) / 100).toInt()
    val limit = if (budgetCap > 0) minOf(proportional, budgetCap) else proportional
    return LoreBudget(limit, counter)
}
