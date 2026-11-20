package app.tavern.nativeclient

import android.util.Base64
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.GZIPInputStream

internal enum class ExternalContentSite { DIRECT, CHUB, CHUB_BOOK, JANITOR, PYGMALION, AICC, RISU, PERCHANCE }

internal data class ExternalContentSource(val site: ExternalContentSite, val id: String, val url: String) {
    val storedLink get() = url.takeUnless { site == ExternalContentSite.DIRECT || site == ExternalContentSite.CHUB_BOOK }
}

private val uuidText = Regex("(?i)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
private val risuId = Regex("(?i)(?:[0-9a-f]{64}|[0-9a-f]{32})")
private val shareGzip = Regex("(?i)[0-9a-f]{32}\\.gz")
private val slugPart = Regex("""[\p{L}\p{N}\p{M}_.-]{1,180}""")

internal fun externalWebAddress(address: String): URI {
    val uri = runCatching { URI(address.trim()) }.getOrNull()
    require(uri != null && uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && uri.host != null &&
        uri.rawUserInfo == null && (uri.port == -1 || uri.port in 1..65535)) { "请输入有效的 HTTP 或 HTTPS 链接" }
    return uri
}

private fun hostIn(host: String, vararg sites: String) = sites.any { host == it || host.endsWith(".$it") }
private fun decodeSegment(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
private fun encodeSegment(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
private fun String.slug() = takeIf { slugPart.matches(it) && it != "." && it != ".." } ?: error("分享链接的角色 ID 无效")

internal fun parseExternalContentSource(value: String): ExternalContentSource {
    val address = value.trim()
    require(address.isNotEmpty() && address.length <= 8192) { "分享链接为空或过长" }
    fun chub(parts: List<String>): ExternalContentSource {
        val site = if (parts.firstOrNull().equals("lorebooks", true)) ExternalContentSite.CHUB_BOOK else ExternalContentSite.CHUB
        val card = if (parts.firstOrNull()?.lowercase(Locale.ROOT) in setOf("characters", "lorebooks")) parts.drop(1) else parts
        require(card.size == 2) { "请输入 Chub 的作者名和卡片 ID" }
        val path = card.joinToString("/") { encodeSegment(it.slug()) }
        val kind = if (site == ExternalContentSite.CHUB_BOOK) "lorebooks" else "characters"
        return ExternalContentSource(site, path, "https://chub.ai/$kind/$path")
    }
    fun uuid(site: ExternalContentSite, text: String): ExternalContentSource {
        val id = uuidText.find(text)?.value?.lowercase(Locale.ROOT) ?: error("分享链接缺少有效的角色 UUID")
        val url = if (site == ExternalContentSite.JANITOR) "https://janitorai.com/characters/$id"
            else "https://pygmalion.chat/$id"
        return ExternalContentSource(site, id, url)
    }
    fun aicc(parts: List<String>): ExternalContentSource {
        require(parts.size >= 2) { "请输入 AICharacterCards 的作者名和卡片 ID" }
        val id = parts.takeLast(2).joinToString("/") { encodeSegment(it.slug()) }
        return ExternalContentSource(ExternalContentSite.AICC, id, "https://aicharactercards.com/character/$id")
    }
    fun perchance(share: String): ExternalContentSource {
        val slug = share.substringAfterLast('~').lowercase(Locale.ROOT)
        require(shareGzip.matches(slug)) { "分享链接缺少有效的 Perchance 文件 ID" }
        return ExternalContentSource(ExternalContentSite.PERCHANCE, slug,
            "https://perchance.org/ai-character-chat?data=${encodeSegment(share)}")
    }
    if ("://" !in address) {
        if (address.startsWith("AICC/", true)) return aicc(address.split('/').drop(1))
        if (uuidText.matches(address)) return uuid(ExternalContentSite.PYGMALION, address)
        if ("_character" in address) return uuid(ExternalContentSite.JANITOR, address.substringBefore('_'))
        if ('~' in address && address.endsWith(".gz", true)) return perchance(address)
        return chub(address.split('/').filter(String::isNotEmpty))
    }
    val uri = externalWebAddress(address)
    val host = uri.host.lowercase(Locale.ROOT)
    val parts = uri.rawPath.split('/').filter(String::isNotEmpty).map(::decodeSegment)
    return when {
        hostIn(host, "chub.ai", "characterhub.org") -> chub(parts)
        hostIn(host, "janitorai.com", "jannyai.com", "jannyai.me") -> uuid(ExternalContentSite.JANITOR, parts.joinToString("/"))
            .copy(url = "https://$host${uri.rawPath}")
        hostIn(host, "pygmalion.chat") -> uuid(ExternalContentSite.PYGMALION, parts.joinToString("/"))
        hostIn(host, "aicharactercards.com") -> aicc(parts).copy(url = "https://$host${uri.rawPath}")
        host == "realm.risuai.net" -> {
            val id = parts.getOrNull(1).orEmpty().lowercase(Locale.ROOT)
            require(parts.size == 2 && parts[0] == "character" &&
                (uuidText.matches(id) || risuId.matches(id))) { "请输入 RisuRealm 的角色分享链接" }
            ExternalContentSource(ExternalContentSite.RISU, id, "https://realm.risuai.net/character/$id")
        }
        hostIn(host, "perchance.org") -> {
            val params = uri.rawQuery.orEmpty().split('&')
            val share = params.firstOrNull { it.substringBefore('=') == "data" }?.substringAfter('=', "").orEmpty()
            perchance(decodeSegment(share))
        }
        else -> {
            val url = uri.toASCIIString().substringBefore('#')
            ExternalContentSource(ExternalContentSite.DIRECT, url, url)
        }
    }
}

internal sealed interface ExternalContent {
    data class Characters(val cards: List<ImportedCharacterArchive>) : ExternalContent
    data class Book(val book: WorldBook) : ExternalContent
}

internal interface ExternalContentTransport {
    suspend fun <T> get(address: String, body: JSONObject? = null, allowed: (URI) -> Boolean, read: (InputStream) -> T): T
}

internal object ExternalHttp : ExternalContentTransport {
    override suspend fun <T> get(address: String, body: JSONObject?, allowed: (URI) -> Boolean, read: (InputStream) -> T): T {
        var uri = externalWebAddress(address)
        var post = body
        for (attempt in 0..5) {
            require(allowed(uri)) { "分享链接指向了来源范围之外的地址" }
            try {
                return request(uri.toASCIIString(), post, headers = mapOf("Accept" to "*/*", "User-Agent" to "TavernNative"),
                    readTimeout = 30_000, reportRedirects = true) { input, _ -> read(input) }
            } catch (redirect: HttpRedirect) {
                if (attempt == 5) error("分享链接重定向次数过多")
                val next = externalWebAddress(uri.resolve(redirect.location).toASCIIString())
                require(uri.scheme.equals("http", true) || next.scheme.equals("https", true)) { "分享链接要求降级到 HTTP" }
                if (redirect.status in setOf(301, 302, 303)) post = null
                require(post == null || next.host.equals(uri.host, true) && next.port == uri.port) {
                    "下载请求不能跨站重发"
                }
                uri = next
            }
        }
        error("无法读取分享链接")
    }
}

internal class ExternalContentImporter(
    private val readCards: (InputStream, String) -> List<ImportedCharacterArchive>,
    private val normalizePortrait: (ByteArray) -> ByteArray,
    private val transport: ExternalContentTransport = ExternalHttp,
) {
    private fun httpsHosts(vararg hosts: String): (URI) -> Boolean = { uri ->
        uri.scheme.equals("https", true) && (uri.port == -1 || uri.port == 443) && hostIn(uri.host.lowercase(Locale.ROOT), *hosts)
    }
    private val chub = httpsHosts("chub.ai", "characterhub.org")
    private val janny = httpsHosts("jannyai.com", "jannyai.me")
    private val pygmalion = httpsHosts("pygmalion.chat", "r2.dev", "sfo3.digitaloceanspaces.com")
    private val aicc = httpsHosts("aicharactercards.com")
    private val risu = httpsHosts("risuai.net")
    private val perchance = httpsHosts("perchance.org", "uploads.dev")

    suspend fun load(source: ExternalContentSource): ExternalContent {
        val id = source.id
        val payload = when (source.site) {
            ExternalContentSite.DIRECT -> files(source.url, externalWebAddress(source.url).path.substringAfterLast('/')) { true }
            ExternalContentSite.CHUB -> chubCharacter(id)
            ExternalContentSite.CHUB_BOOK -> {
                val meta = json("https://api.chub.ai/api/lorebooks/$id", chub)
                val project = meta.optJSONObject("node")?.string("id")?.toLongOrNull()?.takeIf { it > 0 }
                    ?: error("Chub 没有返回有效的世界书项目")
                val book = json("https://api.chub.ai/api/v4/projects/$project/repository/files/raw%252Fsillytavern_raw.json/raw", chub)
                return ExternalContent.Book(WorldBook.import(book, decodeSegment(id.substringAfterLast('/'))))
            }
            ExternalContentSite.JANITOR -> {
                val result = json("https://api.jannyai.com/api/v1/download", janny, jsonOf("characterId" to id))
                require(result.string("status") == "ok") { "该 JanitorAI 角色暂时无法下载" }
                files(result.string("downloadUrl"), "character.png", janny)
            }
            ExternalContentSite.PYGMALION -> {
                val root = json("https://server.pygmalion.chat/api/export/character/$id/v2", pygmalion)
                val card = requireNotNull(root.optJSONObject("character")) { "Pygmalion 没有返回角色卡" }
                listOf(character(card, card.optJSONObject("data")?.string("avatar").orEmpty(), pygmalion))
            }
            ExternalContentSite.AICC -> files("https://aicharactercards.com/wp-json/pngapi/v1/image/$id", "character.png", aicc)
            ExternalContentSite.RISU -> files("https://realm.risuai.net/api/v1/download/png-v3/$id", "character.png", risu)
            ExternalContentSite.PERCHANCE -> perchanceCharacter(id)
        }
        return ExternalContent.Characters(payload.map { item ->
            val link = source.storedLink
            if (link == null) item else item.copy(card = item.card.copy(card = item.card.card.withSourceLink(link)))
        })
    }

    private suspend fun files(address: String, name: String, allowed: (URI) -> Boolean) =
        transport.get(address, allowed = allowed) { readCards(it, name) }

    private suspend fun json(address: String, allowed: (URI) -> Boolean, body: JSONObject? = null) =
        transport.get(address, body, allowed) { readJson(it) }

    private fun readJson(input: InputStream) =
        boundedJsonObject(input.readLimited().decodeToString().trimStart('\uFEFF'), "下载内容的 JSON 格式无效")

    private suspend fun character(card: JSONObject, avatar: String, allowed: (URI) -> Boolean): ImportedCharacterArchive {
        val entry = Card.from(card)
        val picture = try {
            when {
                avatar.isBlank() -> null
                avatar.startsWith("data:image/") && ";base64," in avatar -> {
                    val data = avatar.substringAfter(";base64,")
                    require(data.length <= (MAX_IMAGE_SOURCE.toLong() + 2L) / 3L * 4L) { "头像数据过大" }
                    normalizePortrait(Base64.decode(data, Base64.DEFAULT))
                }
                else -> transport.get(avatar, allowed = allowed) { normalizePortrait(it.readLimited(MAX_IMAGE_SOURCE)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return ImportedCharacterArchive(CardImport(entry.copy(hasAvatar = picture != null), picture), emptyList(),
            emptyList(), if (picture == null && avatar.isNotBlank()) 1 else 0)
    }

    private suspend fun chubCharacter(id: String): List<ImportedCharacterArchive> {
        val meta = json("https://api.chub.ai/api/characters/$id?full=true", chub)
        val node = requireNotNull(meta.optJSONObject("node")) { "Chub 没有返回角色资料" }
        val definition = requireNotNull(node.optJSONObject("definition")) { "Chub 角色缺少定义" }
        val data = jsonOf(
            "name" to definition.string("name"), "description" to definition.string("personality"),
            "personality" to definition.string("tavern_personality"), "scenario" to definition.string("scenario"),
            "first_mes" to definition.string("first_message"), "mes_example" to definition.string("example_dialogs"),
            "creator_notes" to definition.string("description"), "system_prompt" to definition.string("system_prompt"),
            "post_history_instructions" to definition.string("post_history_instructions"),
            "alternate_greetings" to (definition.optJSONArray("alternate_greetings") ?: JSONArray()),
            "tags" to (node.optJSONArray("topics") ?: JSONArray()), "creator" to decodeSegment(id.substringBefore('/')),
            "character_version" to definition.string("character_version"),
            "character_book" to definition.optJSONObject("embedded_lorebook"),
            "extensions" to (definition.optJSONObject("extensions") ?: JSONObject()),
        )
        return listOf(character(jsonOf("spec" to "chara_card_v2", "spec_version" to "2.0", "data" to data),
            node.string("max_res_url"), chub))
    }

    private suspend fun perchanceCharacter(id: String): List<ImportedCharacterArchive> {
        val raw = transport.get("https://user.uploads.dev/file/$id", allowed = perchance) { it.readLimited() }
        val root = GZIPInputStream(raw.inputStream()).use(::readJson)
        val original = requireNotNull(root.optJSONObject("addCharacter")) { "Perchance 文件缺少角色定义" }
        val avatar = original.optJSONObject("avatar")?.string("url").orEmpty()
        val extension = jsonOf("perchance_data" to jsonOf("slug" to id, "uuid" to original.string("uuid"),
            "folder_path" to original.string("folderPath"), "folder_name" to original.string("folderName"),
            "custom_data" to original.optJSONObject("customData")))
        val data = jsonOf("name" to original.string("name").ifBlank { "Perchance 角色" },
            "description" to original.string("roleInstruction"), "personality" to original.string("reminderMessage"),
            "creator" to original.string("metaTitle"), "creator_notes" to original.string("metaDescription"),
            "extensions" to extension)
        return listOf(character(Card.from(data).portableJson(), avatar, perchance))
    }
}
