package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipFile

internal data class ImportedCharacterArchive(
    val card: CardImport, val chats: List<Chat>, val pictures: List<GalleryImage>, val skippedImages: Int,
)

internal fun avatarDecodeSample(width: Int, height: Int, keepSize: Boolean): Int {
    if (keepSize) {
        require(width.toLong() * height <= 16_000_000L) { "头像尺寸过大，无法原尺寸转换" }
        return 1
    }
    var sample = 1
    while (maxOf(width, height) / sample > 640) sample *= 2
    return sample
}
internal fun readCharacterFiles(
    source: InputStream, filename: String, cache: File, userName: String,
    normalizePortrait: (ByteArray) -> ByteArray, saveImage: (ByteArray, String) -> ChatImage,
    includeExtras: Boolean = true,
): List<ImportedCharacterArchive> {
    val input = source.buffered()
    input.mark(8)
    val header = ByteArray(8)
    for (index in header.indices) {
        val value = input.read()
        if (value < 0) break
        header[index] = value.toByte()
    }
    input.reset()
    fun entry(card: CardImport) = ImportedCharacterArchive(card, emptyList(), emptyList(), 0)
    return when {
        CardFile.isPng(header) -> {
            val card = CardFile.read(input.readLimited())
            listOf(entry(card.copy(portrait = card.portrait?.let(normalizePortrait))))
        }
        CharacterArchive.matches(header, filename) ->
            listOf(CharacterArchive.read(input, header, cache, userName, normalizePortrait, saveImage, includeExtras))
        else -> readCharacterJson(input.readLimited()).map { entry(CardImport(it)) }
    }
}

internal object CharacterArchive {
    private const val FILE_LIMIT = 128 * 1024 * 1024
    private const val ENTRY_LIMIT = 4096
    private const val JSON_LIMIT = 24 * 1024 * 1024
    private val signature = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
    private val imageExtensions = setOf("png", "apng", "jpg", "jpeg", "webp", "gif", "avif", "bmp", "jfif")

    fun matches(header: ByteArray, name: String) = signature.indices.all { header.getOrNull(it) == signature[it] } ||
        isJpeg(header) || name.substringAfterLast('.', "").lowercase() in setOf("charx", "byaf", "zip")

    private fun isJpeg(header: ByteArray) = header.getOrNull(0) == 0xff.toByte() && header.getOrNull(1) == 0xd8.toByte()

    fun read(input: InputStream, header: ByteArray, cache: File, userName: String,
             portrait: (ByteArray) -> ByteArray, saveImage: (ByteArray, String) -> ChatImage,
             includeExtras: Boolean = true): ImportedCharacterArchive {
        val file = File.createTempFile("tavern-character-", ".zip", cache)
        try {
            val cover = file.outputStream().buffered().use { output -> copyZip(input, output, isJpeg(header)) }
            return ZipFile(file).use { zip ->
                val contents = Contents(zip, portrait, saveImage, includeExtras)
                when {
                    contents.has("card.json") -> contents.charX(cover)
                    contents.has("manifest.json") -> contents.byaf(userName)
                    else -> error("角色包缺少 card.json 或 manifest.json")
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun copyZip(input: InputStream, output: OutputStream, jpeg: Boolean): ByteArray? {
        val prefix = if (jpeg) ByteArrayOutputStream() else null
        var matched = 0
        var scanned = 0
        while (matched < signature.size) {
            val next = input.read()
            require(next >= 0 && scanned < MAX_IMAGE_SOURCE) { "找不到角色包的 ZIP 数据" }
            scanned++
            prefix?.write(next)
            matched = if (next == signature[matched].toInt()) matched + 1 else if (next == signature[0].toInt()) 1 else 0
        }
        output.write(signature)
        val bytes = ByteArray(32 * 1024)
        var total = scanned
        while (true) {
            val count = input.read(bytes)
            if (count < 0) break
            require(count <= FILE_LIMIT - total) { "角色包超过 128 MB" }
            total += count
            output.write(bytes, 0, count)
        }
        return prefix?.toByteArray()?.let { it.copyOf(it.size - signature.size) }
    }

    private fun safePath(path: String): String? {
        val text = path.replace('\\', '/')
        if (text.startsWith('/') || ':' in text || '\u0000' in text) return null
        val parts = text.split('/').filter { it.isNotEmpty() && it != "." }
        return parts.takeIf { it.isNotEmpty() && ".." !in it }?.joinToString("/")
    }

    private class Contents(private val zip: ZipFile, private val portrait: (ByteArray) -> ByteArray,
                           private val saveImage: (ByteArray, String) -> ChatImage, private val includeExtras: Boolean) {
        private val entries = buildMap {
            require(zip.size() <= ENTRY_LIMIT) { "角色包条目过多" }
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val name = safePath(entry.name) ?: continue
                require(put(name, entry) == null) { "角色包中有重复的文件路径" }
            }
        }
        private var budget = FILE_LIMIT
        private var jsonBudget = 32 * 1024 * 1024
        private var skipped = 0
        private val pictures = linkedMapOf<String, GalleryImage?>()

        fun has(name: String) = name in entries

        private fun bytes(path: String, limit: Int): ByteArray? {
            val entry = entries[safePath(path)] ?: return null
            val allowed = minOf(limit, budget)
            require(entry.size in 0..allowed.toLong()) { "角色包中的文件或累计解压量超过限制" }
            return zip.getInputStream(entry).use { it.readLimited(allowed) }.also { budget -= it.size }
        }

        private fun json(path: String): JSONObject? = bytes(path, minOf(JSON_LIMIT, jsonBudget))?.let {
            jsonBudget -= it.size
            boundedJsonObject(it.decodeToString().trimStart('\uFEFF'), "角色包中的 JSON 无法读取")
        }

        private fun <T> optionalImage(read: () -> T?): T? {
            val result = try { read() } catch (_: IllegalArgumentException) { null } catch (_: java.io.IOException) { null }
            if (result == null) skipped++
            return result
        }

        private fun avatar(path: String): ByteArray? = optionalImage { bytes(path, MAX_IMAGE_SOURCE)?.let(portrait) }

        private fun picture(path: String, label: String): ChatImage? {
            val key = safePath(path)
            if (key == null) { skipped++; return null }
            if (key !in pictures) pictures[key] = optionalImage {
                bytes(key, MAX_IMAGE_SOURCE)?.let { GalleryImage(saveImage(it, label), label) }
            }
            return pictures[key]?.image
        }

        private fun result(card: Card, avatar: ByteArray?, chats: List<Chat> = emptyList()) =
            ImportedCharacterArchive(CardImport(card.copy(hasAvatar = avatar != null), avatar), chats, pictures.values.filterNotNull(), skipped)

        fun charX(cover: ByteArray?): ImportedCharacterArchive {
            val root = requireNotNull(json("card.json")) { "CharX 缺少 card.json" }
            require(root.string("spec") in setOf("chara_card_v2", "chara_card_v3")) { "CharX 角色卡规格无效" }
            val data = requireNotNull(root.optJSONObject("data")) { "CharX 缺少角色数据" }
            require(!(data.opt("name") as? String).isNullOrBlank()) { "角色卡缺少名称" }
            val card = Card.from(root)
            val assets = data.optJSONArray("assets")?.objects().orEmpty().mapNotNull { asset ->
                val uri = asset.string("uri").trim()
                val prefix = listOf("embeded://", "embedded://", "__asset:").firstOrNull { uri.startsWith(it, ignoreCase = true) }
                    ?: return@mapNotNull null
                val path = uri.substring(prefix.length)
                val extension = asset.string("ext").trim().removePrefix(".").ifBlank { path.substringAfterLast('.', "") }
                if (extension.lowercase() !in imageExtensions) return@mapNotNull null
                CharXImage(path, asset.string("type").lowercase(), asset.string("name").ifBlank { path.substringAfterLast('/') })
            }
            var main: String? = null
            val avatar = assets.filter { it.type == "icon" }.sortedBy { !it.name.equals("main", true) }
                .firstNotNullOfOrNull { asset -> avatar(asset.path)?.also { main = safePath(asset.path) } }
                ?: cover?.let { optionalImage { portrait(it) } }
            if (!includeExtras) return result(card, avatar)
            val sprites = mutableListOf<ExpressionSprite>()
            for (asset in assets) {
                if (asset.type == "icon" && main != null && safePath(asset.path) == main) continue
                val category = when (asset.type) {
                    "background" -> "背景"
                    "emotion", "expression" -> "表情"
                    "icon", "user_icon" -> "头像"
                    else -> "素材"
                }
                val image = picture(asset.path, "$category · ${asset.name.take(100)}")
                if (image != null && (asset.type == "emotion" || asset.type == "expression")) {
                    spriteLabel(asset.name)?.let { sprites += ExpressionSprite(it, image) }
                }
            }
            return result(if (sprites.isEmpty()) card else card.withExpressionSprites(sprites), avatar)
        }

        fun byaf(userName: String): ImportedCharacterArchive {
            val manifest = requireNotNull(json("manifest.json")) { "BYAF 缺少 manifest.json" }
            val path = requireNotNull(manifest.optJSONArray("characters").strings().firstOrNull()) { "BYAF 缺少角色条目" }
            val characterPath = requireNotNull(safePath(path)) { "BYAF 的角色路径无效" }
            val character = requireNotNull(json(characterPath)) { "BYAF 缺少角色数据" }
            val scenarios = manifest.optJSONArray("scenarios").strings().distinct().mapNotNull { scenario ->
                try { json(scenario) } catch (_: IllegalArgumentException) { null }
            }
            val card = byafCard(manifest, character, scenarios)
            val directory = characterPath.substringBeforeLast('/', "")
            val icons = character.optJSONArray("images")?.objects().orEmpty()
            var portraitBytes: ByteArray? = null
            for (icon in icons) {
                val child = icon.string("path")
                val relative = safePath(child)
                if (relative == null) { skipped++; continue }
                val file = if (directory.isEmpty()) relative else "$directory/$relative"
                if (portraitBytes == null) portraitBytes = avatar(file)
                else picture(file, "头像 · ${icon.string("label").ifBlank { relative.substringAfterLast('/') }.take(100)}")
                if (!includeExtras && portraitBytes != null) break
            }
            if (!includeExtras) return result(card, portraitBytes)
            val chats = scenarios.mapIndexed { index, scenario ->
                val title = scenario.string("title").trim().ifBlank { "${card.name} · 场景 ${index + 1}" }
                val background = scenario.string("backgroundImage").takeIf(String::isNotBlank)?.let { picture(it, "背景 · ${title.take(100)}") }
                byafChat(card, scenario, userName, title, background)
            }
            return result(card, portraitBytes, chats)
        }
    }

    private data class CharXImage(val path: String, val type: String, val name: String)
}

private val byafMacro = Regex("(?i)(?:#\\{(user|character)\\}:|(?<!\\{)\\{(user|character)\\}(?!\\}))")
private fun String.byafMacros() = byafMacro.replace(this) { match ->
    val name = match.groupValues[1].ifBlank { match.groupValues[2] }
    (if (name.equals("user", true)) "{{user}}" else "{{char}}") + if (match.value.endsWith(':')) ":" else ""
}

private fun JSONObject?.greeting() = this?.optJSONArray("firstMessages")?.optJSONObject(0)?.string("text").orEmpty().byafMacros()
private fun JSONObject?.examples() = this?.optJSONArray("exampleMessages")?.objects().orEmpty()
    .map { it.string("text").byafMacros() }.filter(String::isNotBlank).joinToString("\n") { "<START>\n$it" }

private fun byafCard(manifest: JSONObject, character: JSONObject, scenarios: List<JSONObject>): Card {
    val name = (character.opt("name") as? String).orEmpty().trim()
        .ifBlank { (character.opt("displayName") as? String).orEmpty().trim() }
    require(name.isNotBlank()) { "BYAF 角色缺少名称" }
    val first = scenarios.firstOrNull()
    val firstGreeting = first.greeting()
    val author = manifest.optJSONObject("author")
    val lore = character.optJSONArray("loreItems")?.objects().orEmpty().mapIndexed { index, item ->
        jsonOf("keys" to JSONArray(item.string("key").byafMacros().split(',').map(String::trim).filter(String::isNotBlank)),
            "content" to item.string("value").byafMacros(), "enabled" to true, "insertion_order" to index)
    }
    val card = jsonOf("spec" to "chara_card_v2", "spec_version" to "2.0", "data" to jsonOf(
        "name" to name, "description" to character.string("persona").byafMacros(), "personality" to "",
        "scenario" to first?.string("narrative").orEmpty().byafMacros(), "first_mes" to firstGreeting,
        "mes_example" to first.examples(), "creator_notes" to author?.string("backyardURL").orEmpty(),
        "system_prompt" to first?.string("formattingInstructions").orEmpty().byafMacros(), "post_history_instructions" to "",
        "alternate_greetings" to JSONArray(scenarios.drop(1).map { it.greeting() }.filter { it.isNotBlank() && it != firstGreeting }.distinct()),
        "tags" to JSONArray(if (character.optBoolean("isNSFW")) listOf("nsfw") else emptyList<String>()),
        "creator" to author?.string("name").orEmpty(), "character_version" to "",
        "extensions" to jsonOf("display_name" to character.string("displayName")),
    ))
    if (lore.isNotEmpty()) card.getJSONObject("data").put("character_book", jsonOf("entries" to JSONArray(lore)))
    return Card.from(card)
}

private fun byafTime(value: String): Long? = value.toLongOrNull() ?: try {
    Instant.parse(value).toEpochMilli()
} catch (_: java.time.DateTimeException) { null } catch (_: ArithmeticException) { null }

private fun byafChat(card: Card, scenario: JSONObject, userName: String, title: String, background: ChatImage?): Chat {
    val rows = scenario.optJSONArray("messages")?.objects().orEmpty()
    val userRows = rows.filter { it.string("type") == "human" }
    val botRows = rows.filter { it.string("type") == "ai" }
    val ordered = if (userRows.size > 1 && userRows.size == botRows.size && userRows.size + botRows.size == rows.size &&
        rows.take(userRows.size) == userRows) userRows.zip(botRows).flatMap { (user, bot) -> listOf(user, bot) } else rows
    fun date(value: JSONObject, fallback: String): String = byafTime(value.string("createdAt"))?.let { Instant.ofEpochMilli(it).toString() }
        ?: fallback
    val now = Instant.now().toString()
    val start = ordered.firstOrNull()?.let { date(it, now) } ?: now
    val history = buildList {
        val greeting = scenario.greeting()
        if (greeting.isNotBlank()) add(Message("assistant", card.name, expand(greeting, card, userName), start, characterId = card.id))
        for (row in ordered) {
            when (row.string("type")) {
                "human" -> add(Message("user", userName, row.string("text"), date(row, start)))
                "ai" -> {
                    val outputs = row.optJSONArray("outputs")?.objects().orEmpty().filter { it.opt("text") is String }
                    val choice = outputs.indices.maxWithOrNull(compareBy<Int> {
                        byafTime(outputs[it].string("activeTimestamp")) ?: Long.MIN_VALUE
                    }.thenBy { it }) ?: continue
                    val swipes = outputs.map { it.string("text") }
                    add(Message("assistant", card.name, swipes[choice], date(outputs[choice], date(row, start)),
                        swipes.takeIf { it.size > 1 }.orEmpty(), if (swipes.size > 1) choice else 0, characterId = card.id))
                }
            }
        }
    }
    val options = ConversationOptions(scenario = scenario.string("narrative").byafMacros(), background = background,
        exampleOverride = scenario.examples(), systemPromptOverride = scenario.string("formattingInstructions").byafMacros())
    val metadata = jsonOf("mes_examples_optional" to scenario.optBoolean("canDeleteExampleMessages"),
        "byaf_model_settings" to jsonOf("model" to scenario.opt("model"), "temperature" to scenario.opt("temperature"),
            "top_k" to scenario.opt("topK"), "top_p" to scenario.opt("topP"), "min_p" to scenario.opt("minP"),
            "min_p_enabled" to scenario.opt("minPEnabled"), "repeat_penalty" to scenario.opt("repeatPenalty"),
            "repeat_penalty_tokens" to scenario.opt("repeatLastN"), "by_prompt_template" to scenario.opt("promptTemplate"),
            "grammar" to scenario.opt("grammar")))
    val header = jsonOf("character_name" to card.name, "user_name" to userName, "create_date" to start, "chat_metadata" to metadata)
    return Chat(newId(), card.id, title, messages = history, header = header.toString(), options = options)
}
