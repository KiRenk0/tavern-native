package app.tavern.nativeclient

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType
import com.knuddels.jtokkit.api.IntArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Locale

internal const val TOKEN_PREVIEW_PIECES = 512
internal const val TOKEN_INSPECT_TEXT_LIMIT = 200_000

internal enum class TextTokenizer(val label: String, val encoding: EncodingType? = null) {
    MODEL("按模型自动选择"),
    DEEPSEEK_41("DeepSeek V4.1"),
    O200K("o200k_base", EncodingType.O200K_BASE),
    CL100K("cl100k_base", EncodingType.CL100K_BASE),
    P50K("p50k_base", EncodingType.P50K_BASE),
    P50K_EDIT("p50k_edit", EncodingType.P50K_EDIT),
    R50K("r50k_base（GPT-2）", EncodingType.R50K_BASE),
    MISTRAL_NEMO("Mistral / Nemo (Tekken)"),
    QWEN("Qwen (2 / 2.5 / 3)"),
    GEMMA("Gemma (2 / 3)"),
    CLAUDE("Claude"),
    COMMAND_R("Command-R / Command-A"),
    LLAMA_1_2("Llama 1 / 2"),
    YI("Yi (1.5 / Large)"),
    ESTIMATE("本机估算");

    companion object {
        fun from(value: String) = entries.firstOrNull { it.name == value } ?: MODEL
    }
}

internal data class TokenPiece(val id: Int, val text: String? = null)
internal data class TokenPreview(val source: String, val count: Int, val pieces: List<TokenPiece> = emptyList())

internal val ModelConnection.supportsRemoteTokenCount get() =
    provider.protocol == ApiProtocol.KOBOLD_TEXT || provider.protocol == ApiProtocol.GEMINI_NATIVE ||
        AdditionalTextBackends.specFor(provider.protocol)?.supportsRemoteTokenCount == true ||
        provider.protocol == ApiProtocol.OPENAI_COMPATIBLE && provider.template == ProviderTemplate.GOOGLE &&
        provider.endpoint.trimEnd('/') == ProviderTemplate.GOOGLE.endpoint

internal fun tokenPieceText(bytes: ByteArray): String = try {
    bytes.decodeToString(throwOnInvalidSequence = true)
} catch (_: java.nio.charset.CharacterCodingException) {
    bytes.joinToString(" ") { "[%02X]".format(it.toInt() and 255) }
}

internal object OpenAiTokenizers {
    private val registry by lazy(Encodings::newLazyEncodingRegistry)

    fun encoding(type: EncodingType) = registry.getEncoding(type)

    fun forModel(id: String): EncodingType? {
        val model = id.lowercase(Locale.ROOT).removePrefix("openai/").removePrefix("ft:").substringBefore(':')
        return when {
            listOf("gpt-5", "gpt-4o", "chatgpt-4o", "gpt-4.1", "gpt-4.5", "gpt-oss-").any(model::startsWith) ||
                model in setOf("o1", "o3", "o4-mini") || listOf("o1-", "o3-", "o4-mini-").any(model::startsWith) ->
                EncodingType.O200K_BASE
            model == "gpt-4" || listOf("gpt-4-", "gpt-3.5-turbo", "gpt-35-turbo").any(model::startsWith) ||
                model in setOf("davinci-002", "babbage-002") -> EncodingType.CL100K_BASE
            model in setOf("text-davinci-003", "text-davinci-002", "code-davinci-002", "code-davinci-001") -> EncodingType.P50K_BASE
            model in setOf("gpt2", "gpt-2", "davinci", "curie", "babbage", "ada") -> EncodingType.R50K_BASE
            else -> null
        }
    }
}

internal class ChatTokenizers(private val open: (String) -> InputStream) {
    private val deepSeek by lazy { runCatching { DeepSeekTokens.read(open("tokenizers/deepseek-v41.bpe")) }.getOrNull() }
    private val nerdstash by lazy {
        NerdstashTokens.read(open("tokenizers/nerdstash.bpe")).counter("Nerdstash 本机分词")
    }
    private val nerdstashV2 by lazy {
        NerdstashTokens.read(open("tokenizers/nerdstash_v2.bpe")).counter("Nerdstash V2 本机分词")
    }
    private val llama3 by lazy { llama3TokenCounter(open("tokenizers/novelai-llama3.bpe")) }

    fun counterFor(connection: ModelConnection?, selection: TextTokenizer = TextTokenizer.MODEL): TextTokenCounter {
        val preferred = if (selection == TextTokenizer.MODEL) connection?.provider?.textTokenizer ?: selection else selection
        if (preferred == TextTokenizer.MODEL && connection?.provider?.protocol == ApiProtocol.NOVELAI_TEXT) {
            return when (NovelAiModel.from(connection.model)) {
                NovelAiModel.CLIO -> nerdstash
                NovelAiModel.KAYRA -> nerdstashV2
                NovelAiModel.ERATO -> llama3
                null -> estimatedTokens
            }
        }
        val mode = if (preferred == TextTokenizer.MODEL) connection?.automaticTokenizer() ?: TextTokenizer.ESTIMATE else preferred
        if (mode == TextTokenizer.DEEPSEEK_41) {
            val delegate = deepSeek ?: return estimatedTokens
            val label = "${mode.label} 本机分词"
            return TextTokenCounter(label, delegate::count) { text -> delegate.preview(text, label) }
        }
        AdditionalTokenizers.counterFor(mode)?.let { return it }
        val type = mode.encoding ?: return estimatedTokens
        val encoder = OpenAiTokenizers.encoding(type)
        val label = "${type.name.lowercase(Locale.ROOT)} 本机分词"
        return TextTokenCounter(label, encoder::countTokensOrdinary) { text ->
            val ids = encoder.encodeOrdinary(text)
            val one = IntArrayList(1).apply { add(0) }
            val pieces = List(minOf(ids.size(), TOKEN_PREVIEW_PIECES)) { index ->
                val id = ids[index]
                one[0] = id
                TokenPiece(id, tokenPieceText(encoder.decodeBytes(one)))
            }
            TokenPreview(label, ids.size(), pieces)
        }
    }

    suspend fun inspect(connection: ModelConnection?, text: String, selection: TextTokenizer, remote: Boolean): TokenPreview {
        require(text.length <= TOKEN_INSPECT_TEXT_LIMIT) { "单次分词预览最多支持 $TOKEN_INSPECT_TEXT_LIMIT 个字符" }
        if (remote && connection != null) return ProviderProtocols.inspectRemoteTokens(connection, text)
        return withContext(Dispatchers.Default) {
            val counter = counterFor(connection, selection)
            counter.preview?.invoke(text) ?: TokenPreview(counter.label, counter.count(text))
        }
    }

    suspend fun countChat(chat: Chat, connection: ModelConnection?, selection: TextTokenizer): TokenPreview =
        withContext(Dispatchers.Default) {
            val counter = counterFor(connection, selection)
            TokenPreview(counter.label, counter.count(chatTokenText(chat)))
        }

    private fun ModelConnection.automaticTokenizer(): TextTokenizer {
        val id = model.lowercase(Locale.ROOT).substringAfterLast('/').substringBefore(':')
        if (id.startsWith("deepseek-v4.1") || id.startsWith("deepseek-v41") ||
            provider.template == ProviderTemplate.DEEPSEEK && id in setOf("deepseek-flash", "deepseek-pro")) {
            return TextTokenizer.DEEPSEEK_41
        }
        OpenAiTokenizers.forModel(model)?.let { encoding ->
            return TextTokenizer.entries.first { it.encoding == encoding }
        }
        return AdditionalTokenizers.forModel(model) ?: TextTokenizer.ESTIMATE
    }
}

internal fun chatTokenText(chat: Chat) =
    chat.messages.asSequence().filter { it.role != "system" && it.toolTurn == null && it.text.isNotEmpty() }
        .joinToString(" ", transform = Message::text)

internal fun trimTokenText(text: String, limit: Int, fromStart: Boolean, counter: TextTokenCounter): String {
    if (limit <= 0 || text.isEmpty()) return ""
    if (counter.count(text) <= limit) return text
    val length = text.codePointCount(0, text.length)
    fun keep(points: Int): String = if (fromStart) text.substring(text.offsetByCodePoints(text.length, -points))
        else text.substring(0, text.offsetByCodePoints(0, points))
    var low = 0
    var high = length - 1
    while (low < high) {
        val middle = low + (high - low + 1) / 2
        if (counter.count(keep(middle)) <= limit) low = middle else high = middle - 1
    }
    return keep(low)
}
