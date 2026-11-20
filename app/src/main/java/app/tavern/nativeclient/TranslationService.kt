package app.tavern.nativeclient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.net.URI
import java.util.Locale
import kotlin.random.Random

internal enum class TranslationSource(
    val label: String,
    val addressHint: String? = null,
    val chunkSize: Int = 4_000,
    val requiresKey: Boolean = false,
) {
    MODEL("聊天模型"),
    GOOGLE("Google 翻译"),
    LIBRE("LibreTranslate", "http://服务器:5000/translate"),
    LINGVA("Lingva", "https://lingva.ml/api/v1", 600),
    DEEPL("DeepL", requiresKey = true),
    DEEPLX("DeepLX", "http://服务器:1188/translate", 1_500),
    ONERING("OneRingTranslator", "http://服务器:4990/translate", 600),
    MICROSOFT("微软翻译（Bing / Azure）", chunkSize = 1_000),
    YANDEX("Yandex 翻译");

    val acceptsKey get() = requiresKey || this in setOf(GOOGLE, LIBRE, DEEPLX, MICROSOFT, YANDEX)
}

internal enum class TranslationFormality(val key: String, val label: String) {
    DEFAULT("default", "默认"), MORE("prefer_more", "偏正式"), LESS("prefer_less", "偏口语")
}

internal data class TranslationProfile(
    val endpoint: String = "",
    val key: String = "",
    val region: String = "",
    val pro: Boolean = false,
    val formality: TranslationFormality = TranslationFormality.DEFAULT,
) {
    fun json(protect: (String) -> String) = jsonOf("endpoint" to endpoint, "key" to protect(key),
        "region" to region, "pro" to pro, "formality" to formality.name)

    companion object {
        fun from(row: JSONObject, unprotect: (String) -> String) = TranslationProfile(row.string("endpoint"),
            unprotect(row.string("key")), row.string("region"), row.optBoolean("pro"),
            TranslationFormality.entries.firstOrNull { it.name == row.string("formality") } ?: TranslationFormality.DEFAULT)
    }
}

internal data class TranslationServices(
    val source: TranslationSource = TranslationSource.MODEL,
    val model: ModelSelection? = null,
    val profiles: Map<TranslationSource, TranslationProfile> = emptyMap(),
) {
    fun profile() = profiles[source] ?: TranslationProfile()

    fun validated(): TranslationServices {
        val profile = profile()
        val clean = profile.copy(endpoint = profile.endpoint.trim(), key = profile.key.trim(), region = profile.region.trim())
        if (source.requiresKey) require(clean.key.isNotBlank()) { "请填写${source.label}的 API Key" }
        if (source.addressHint != null) {
            if (source != TranslationSource.LINGVA) require(clean.endpoint.isNotBlank()) { "请填写${source.label}的服务地址" }
            if (clean.endpoint.isNotBlank()) checkedAddress(clean.endpoint)
        }
        return copy(profiles = profiles + (source to clean))
    }

    fun json(protect: (String) -> String) = jsonOf("source" to source.name, "model" to model?.json(),
        "profiles" to JSONObject(profiles.mapKeys { it.key.name }.mapValues { it.value.json(protect) }))

    companion object {
        fun from(row: JSONObject?, unprotect: (String) -> String): TranslationServices {
            if (row == null) return TranslationServices()
            val profiles = row.optJSONObject("profiles")
            return TranslationServices(TranslationSource.entries.firstOrNull { it.name == row.string("source") } ?: TranslationSource.MODEL,
                ModelSelection.fromJson(row.optJSONObject("model")),
                TranslationSource.entries.mapNotNull { source ->
                    profiles?.optJSONObject(source.name)?.let { source to TranslationProfile.from(it, unprotect) }
                }.toMap())
        }
    }
}

internal fun Preferences.utilityModel(chat: Chat?, selection: ModelSelection?, subject: String): ModelConnection =
    if (selection == null) requireNotNull(connectionFor(chat)) { "请先配置对话模型" }
    else requireNotNull(resolve(selection)) { "请重新选择${subject}模型" }

internal data class TranslationEndpoints(
    val google: String = "https://translate.google.com/_/TranslateWebserverUi/data/batchexecute",
    val googleCloud: String = "https://translation.googleapis.com/language/translate/v2",
    val deeplFree: String = "https://api-free.deepl.com/v2/translate",
    val deeplPro: String = "https://api.deepl.com/v2/translate",
    val microsoft: String = "https://api.cognitive.microsofttranslator.com/translate",
    val yandex: String = "https://translate.api.cloud.yandex.net/translate/v2/translate",
    val yandexPublic: String = "https://translate.yandex.net/api/v1/tr.json/translate",
    val bingPage: String = "https://www.bing.com/translator",
)

internal class TranslationService(private val endpoints: TranslationEndpoints = TranslationEndpoints()) {
    private val bing = BingTranslation(endpoints.bingPage)
    suspend fun translate(prefs: Preferences, chat: Chat?, options: TranslationOptions, text: String, target: String): String {
        require(validTranslationLanguage(target)) { "请输入有效的语言标签，例如 zh-CN 或 en" }
        if (text.isBlank()) return text
        val config = prefs.translationServices
        if (config.source == TranslationSource.MODEL) return translateText(prefs.utilityModel(chat, config.model, "翻译"), text, target)
        val profile = config.validated().profile()
        val language = Locale.forLanguageTag(target).toLanguageTag()
        val from = if (target.equals(options.requestLanguage, true)) options.target else options.requestLanguage
        val context = currentCoroutineContext()
        return try {
            buildString {
                for (part in translationChunks(text, config.source.chunkSize)) {
                    context.ensureActive()
                    val trimmed = part.trim()
                    if (trimmed.isEmpty()) {
                        append(part)
                        continue
                    }
                    val start = part.indexOfFirst { !it.isWhitespace() }
                    append(part, 0, start)
                    val translated = translateChunk(config.source, profile, trimmed, language, from).trim()
                    check(translated.isNotEmpty()) { "服务未返回译文" }
                    append(translated)
                    append(part, start + trimmed.length, part.length)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val detail = e.localizedMessage.orEmpty().redactValue(profile.key).redactValue(profile.endpoint)
            error("${config.source.label}翻译失败" + detail.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty())
        }
    }

    private suspend fun translateChunk(source: TranslationSource, profile: TranslationProfile, text: String, target: String,
                                       from: String): String = when (source) {
        TranslationSource.MODEL -> error("请使用聊天模型连接")
        TranslationSource.GOOGLE -> {
            val lang = if (target in setOf("pt-BR", "pt-PT")) "pt" else target
            if (profile.key.isEmpty()) {
                googleTranslate(text, lang)
            } else {
                json(endpoints.googleCloud, jsonOf("q" to text, "target" to lang, "format" to "text"),
                    mapOf("x-goog-api-key" to profile.key)).getJSONObject("data").firstTranslation("translatedText")
            }
        }
        TranslationSource.LIBRE -> {
            val lang = when (target) {
                "zh-TW", "zh-Hant", "zh-HK" -> "zt"
                else -> collapseLanguages(target)
            }
            val body = jsonOf("q" to text, "source" to "auto", "target" to lang, "format" to "text").apply {
                if (profile.key.isNotEmpty()) put("api_key", profile.key)
            }
            json(profile.endpoint, body).getString("translatedText")
        }
        TranslationSource.LINGVA -> {
            val base = profile.endpoint.ifBlank { "https://lingva.ml/api/v1" }.trimEnd('/')
            val root = if (java.net.URI(base).path.orEmpty().isEmpty()) "$base/api/v1" else base
            json("$root/auto/${collapseLanguages(target)}/${encoded(text)}").getString("translation")
        }
        TranslationSource.DEEPL -> {
            val lang = deepLLanguage(target)
            val formality = profile.formality.key.takeIf {
                lang.substringBefore('-') in setOf("DE", "FR", "IT", "ES", "NL", "JA", "RU", "PT", "PL")
            }
            val body = jsonOf("text" to JSONArray().put(text), "target_lang" to lang).apply {
                if (formality != null) put("formality", formality)
            }
            json(if (profile.pro) endpoints.deeplPro else endpoints.deeplFree, body,
                mapOf("Authorization" to "DeepL-Auth-Key ${profile.key}")).firstTranslation()
        }
        TranslationSource.DEEPLX -> {
            val lang = if (target.startsWith("zh", true)) "ZH" else deepLLanguage(target)
            val headers = profile.key.takeIf(String::isNotEmpty)?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()
            json(profile.endpoint, jsonOf("text" to text, "source_lang" to "auto", "target_lang" to lang), headers).getString("data")
        }
        TranslationSource.ONERING -> {
            val params = "text=${encoded(text)}&from_lang=${encoded(from)}&to_lang=${encoded(target)}"
            json("${profile.endpoint}?$params").getString("result")
        }
        TranslationSource.MICROSOFT -> {
            val lang = when (target) {
                "zh", "zh-CN", "zh-SG" -> "zh-Hans"
                "zh-TW", "zh-HK" -> "zh-Hant"
                "pt-BR" -> "pt"
                else -> target
            }
            if (profile.key.isEmpty()) {
                bing.translate(text, lang)
            } else {
                val headers = buildMap {
                    put("Ocp-Apim-Subscription-Key", profile.key)
                    if (profile.region.isNotEmpty()) put("Ocp-Apim-Subscription-Region", profile.region)
                }
                val body = JSONArray().put(jsonOf("Text" to text)).toString()
                val result = response("${endpoints.microsoft}?api-version=3.0&to=${encoded(lang)}", headers = headers,
                    upload = HttpUpload("application/json; charset=UTF-8", body.encodeToByteArray()))
                JSONArray(result).getJSONObject(0).firstTranslation()
            }
        }
        TranslationSource.YANDEX -> {
            val lang = target.substringBefore('-')
            if (profile.key.isEmpty()) {
                val uuid = newId().replace("-", "")
                val body = "text=${encoded(text)}&lang=${encoded(lang)}"
                val upload = HttpUpload("application/x-www-form-urlencoded; charset=UTF-8", body.encodeToByteArray())
                val result = JSONObject(response("${endpoints.yandexPublic}?ucid=$uuid&srv=android&format=text", upload = upload))
                check(result.optInt("code") == 200) { "Yandex 翻译响应码为 ${result.optInt("code")}" }
                result.optJSONArray("text").strings().joinToString("")
            } else {
                json(endpoints.yandex, jsonOf("texts" to JSONArray().put(text), "format" to "PLAIN_TEXT", "targetLanguageCode" to lang),
                    mapOf("Authorization" to "Api-Key ${profile.key}")).firstTranslation()
            }
        }
    }

    private suspend fun googleTranslate(text: String, target: String): String {
        val args = JSONArray(listOf(listOf(text, "auto", target, false), listOf(null))).toString()
        val rows = JSONArray(listOf(listOf(listOf("MkEWBc", args, null, "0")))).toString()
        val form = HttpUpload("application/x-www-form-urlencoded; charset=UTF-8", "f.req=${encoded(rows)}".encodeToByteArray())
        val query = "rpcids=MkEWBc&source-path=%2F&hl=en-US&soc-app=1&soc-platform=1&soc-device=1&_reqid=${Random.nextInt(1_000, 10_000)}&rt=c"
        val result = response("${endpoints.google}?$query", upload = form)
        return result.lineSequence().map(String::trim).filter { it.startsWith('[') }.firstNotNullOfOrNull { line ->
            val records = JSONArray(line)
            val row = (0 until records.length()).firstNotNullOfOrNull { index ->
                records.optJSONArray(index)?.takeIf { it.optString(0) == "wrb.fr" && it.optString(1) == "MkEWBc" }
            } ?: return@firstNotNullOfOrNull null
            val translated = JSONArray(row.getString(2)).getJSONArray(1).getJSONArray(0).getJSONArray(0)
            val parts = translated.optJSONArray(5) ?: return@firstNotNullOfOrNull translated.optString(0)
            (0 until parts.length()).mapNotNull { parts.optJSONArray(it)?.optString(0)?.takeIf(String::isNotBlank) }
                .joinToString(" ")
        } ?: error("Google 翻译响应缺少译文")
    }

    private suspend fun json(url: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap()) =
        JSONObject(response(url, body, headers))

    private suspend fun response(url: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap(),
                                 upload: HttpUpload? = null): String =
        request(url, body, headers, upload = upload, readTimeout = 30_000) { input, _ -> input.readLimited().decodeToString() }
}

private class BingTranslation(private val page: String) {
    private val lock = Mutex()
    private var active: BrowserSession? = null
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 Edg/122.0.0.0"
    private val pageId = Regex("""IG:"([^"]+)"""")
    private val interaction = Regex("""data-iid="([^"]+)"""")
    private val sessionParameters = Regex("""params_AbusePreventionHelper\s*=\s*(\[[^]]+])""")

    suspend fun translate(text: String, target: String): String {
        val session = session()
        val form = mapOf("fromLang" to "auto-detect", "to" to target, "text" to text,
            "token" to session.token, "key" to session.key).entries.joinToString("&") { (k, v) -> "$k=${encoded(v)}" }
        try {
            val result = request(session.url, headers = mapOf("User-Agent" to userAgent, "Referer" to session.page),
                readTimeout = 30_000, upload = HttpUpload("application/x-www-form-urlencoded; charset=UTF-8", form.encodeToByteArray())) { input, _ ->
                input.readLimited().decodeToString().trim()
            }
            if (!result.startsWith('[')) {
                val blocked = runCatching { JSONObject(result).optBoolean("ShowCaptcha") }.getOrDefault(false)
                error(if (blocked) "Bing 当前要求验证，请稍后重试或使用 Azure API Key" else "Bing 未返回译文")
            }
            return JSONArray(result).getJSONObject(0).firstTranslation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lock.withLock { if (active === session) active = null }
            throw e
        }
    }

    private suspend fun session(): BrowserSession = lock.withLock {
        active?.takeIf { it.expiresAt > System.nanoTime() }?.let { return@withLock it }
        var resolved = page
        val html = request(page, headers = mapOf("User-Agent" to userAgent, "Accept" to "text/html"),
            readTimeout = 30_000, followRedirects = true, responseAddress = { resolved = it }) { input, _ ->
            input.readLimited().decodeToString()
        }
        val initial = URI(page)
        val actual = URI(resolved)
        require((initial.scheme == actual.scheme && initial.host == actual.host && initial.port == actual.port) ||
            (initial.host == "www.bing.com" && actual.scheme == "https" && actual.host == "cn.bing.com" && actual.port == -1)) {
            "Bing 返回了未知的服务地址"
        }
        val ig = pageId.find(html)?.groupValues?.get(1) ?: error("无法读取 Bing 翻译会话")
        val iid = interaction.find(html)?.groupValues?.get(1) ?: error("无法读取 Bing 翻译会话")
        val raw = sessionParameters.find(html)?.groupValues?.get(1) ?: error("无法读取 Bing 翻译会话")
        val parameters = JSONArray(raw)
        val lifetime = parameters.optLong(2, 120_000L).coerceIn(1_000, 600_000)
        val url = actual.resolve("/ttranslatev3").toString() + "?isVertical=1&IG=${encoded(ig)}&IID=${encoded(iid)}"
        BrowserSession(resolved, url, parameters.get(0).toString(), parameters.getString(1),
            System.nanoTime() + lifetime * 1_000_000L).also { active = it }
    }

    private data class BrowserSession(val page: String, val url: String, val key: String, val token: String, val expiresAt: Long)
}

private fun JSONObject.firstTranslation(key: String = "text") = getJSONArray("translations").getJSONObject(0).getString(key)
private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

private fun collapseLanguages(value: String) = when {
    value.startsWith("zh-") -> "zh"
    value.startsWith("pt-") -> "pt"
    else -> value
}

private fun deepLLanguage(value: String) = when (value) {
    "en" -> "EN-US"
    "pt" -> "PT-PT"
    "zh", "zh-CN", "zh-SG" -> "ZH-HANS"
    "zh-TW", "zh-HK" -> "ZH-HANT"
    else -> value.uppercase(Locale.ROOT)
}

internal fun translationChunks(text: String, size: Int): Sequence<String> = sequence {
    require(size >= 2)
    var start = 0
    while (start < text.length) {
        var end = minOf(start + size, text.length)
        if (end < text.length) {
            if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            val boundary = (end - 1 downTo start + size / 2).firstOrNull { text[it].isWhitespace() }
            if (boundary != null) end = boundary + 1
        }
        yield(text.substring(start, end))
        start = end
    }
}
