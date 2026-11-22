package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

internal const val DEFAULT_ASSETS_MANIFEST_URL =
    "https://raw.githubusercontent.com/SillyTavern/SillyTavern-Content/main/index.json"

internal val DEFAULT_ALLOWED_ASSET_HOSTS: Set<String> = setOf(
    "raw.githubusercontent.com",
    "github.com",
    "objects.githubusercontent.com",
    "cdn.jsdelivr.net",
    "sillytavern.app",
    "docs.sillytavern.app",
)

internal enum class AssetCategory(
    val wire: String,
    val label: String,
    val allowedExtensions: Set<String>,
) {
    THEME("themes", "聊天主题", setOf("json")),
    BACKGROUND("backgrounds", "背景图片", setOf("png", "jpg", "jpeg", "webp", "gif")),
    CHARACTER("characters", "官方角色卡", setOf("png", "json", "charx", "byaf")),
    SPRITE("sprites", "立绘与表情", setOf("png", "webp", "gif", "zip")),
    ;

    companion object {
        fun from(wire: String?): AssetCategory? {
            val key = wire?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return entries.firstOrNull {
                it.wire == key || it.name.lowercase(Locale.ROOT) == key ||
                    (key == "theme" && it == THEME) ||
                    (key == "background" && it == BACKGROUND) ||
                    (key == "character" && it == CHARACTER) ||
                    (key == "sprite" && it == SPRITE)
            }
        }
    }
}

internal data class AssetManifestItem(
    val id: String,
    val name: String,
    val category: AssetCategory,
    val description: String = "",
    val downloadUrl: String,
    val filename: String,
    val previewUrl: String = "",
    val author: String = "SillyTavern",
    val tags: List<String> = emptyList(),
    val sizeBytes: Long = 0L,
    val sha256: String = "",
) {
    fun json(): JSONObject = jsonOf(
        "id" to id,
        "name" to name,
        "category" to category.wire,
        "description" to description,
        "downloadUrl" to downloadUrl,
        "filename" to filename,
        "previewUrl" to previewUrl,
        "author" to author,
        "tags" to JSONArray(tags),
        "sizeBytes" to sizeBytes,
        "sha256" to sha256,
    )

    companion object {
        fun from(j: JSONObject?): AssetManifestItem? {
            if (j == null) return null
            val category = AssetCategory.from(j.string("category").ifBlank { j.string("type") }) ?: return null
            val url = j.string("downloadUrl").ifBlank { j.string("url") }.trim()
            val name = j.string("name").ifBlank { j.string("title") }.trim()
            val filename = j.string("filename").ifBlank { url.substringAfterLast('/').substringBefore('?') }.trim()
            if (url.isEmpty() || name.isEmpty() || filename.isEmpty()) return null
            val id = j.string("id").ifBlank { "${category.wire}:$filename" }
            return AssetManifestItem(
                id = id,
                name = name,
                category = category,
                description = j.string("description"),
                downloadUrl = url,
                filename = filename,
                previewUrl = j.string("previewUrl").ifBlank { j.string("thumbnail") },
                author = j.string("author").ifBlank { "SillyTavern" },
                tags = j.optJSONArray("tags").strings(),
                sizeBytes = j.optLong("sizeBytes", 0L).coerceAtLeast(0L),
                sha256 = j.string("sha256").trim().lowercase(Locale.ROOT),
            )
        }
    }
}

internal data class AssetsConfig(
    val manifestUrl: String = DEFAULT_ASSETS_MANIFEST_URL,
    val installedAssetIds: Set<String> = emptySet(),
) {
    fun json(): JSONObject = jsonOf(
        "manifestUrl" to manifestUrl,
        "installedAssetIds" to JSONArray(installedAssetIds.sorted()),
    )

    companion object {
        fun from(j: JSONObject?): AssetsConfig {
            if (j == null) return AssetsConfig()
            return AssetsConfig(
                manifestUrl = j.string("manifestUrl").ifBlank { DEFAULT_ASSETS_MANIFEST_URL },
                installedAssetIds = j.optJSONArray("installedAssetIds").strings().toSet(),
            )
        }
    }
}

internal interface AssetsHttpClient {
    suspend fun getBytes(url: String, maxBytes: Int): ByteArray
}

internal object DefaultAssetsHttpClient : AssetsHttpClient {
    override suspend fun getBytes(url: String, maxBytes: Int): ByteArray =
        request(
            url = url,
            headers = mapOf("Accept" to "*/*", "User-Agent" to "TavernNative/1.0"),
            readTimeout = 30_000,
            followRedirects = true,
        ) { input, _ -> input.readLimited(maxBytes) }
}

internal object AssetValidator {
    private val reservedNames = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )
    private val invalidFileChars = Regex("""[^A-Za-z0-9._\-\u4E00-\u9FFF]""")

    fun validateAssetUrl(
        rawUrl: String,
        allowedHosts: Set<String> = DEFAULT_ALLOWED_ASSET_HOSTS,
    ): URI {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 2048) { "素材下载链接为空或过长" }
        val uri = runCatching { URI(trimmed) }.getOrNull()
        require(uri != null && uri.scheme.equals("https", ignoreCase = true)) {
            "出于安全考虑，素材下载仅允许 HTTPS 链接"
        }
        val host = uri.host?.lowercase(Locale.ROOT)
        require(!host.isNullOrBlank() && uri.rawUserInfo == null) { "素材链接主机名无效" }
        require(uri.port == -1 || uri.port in 1..65535) { "素材链接端口无效" }
        val normalizedAllowed = allowedHosts.map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotEmpty)
        require(normalizedAllowed.any { allowed -> host == allowed || host.endsWith(".$allowed") }) {
            "素材链接不在受信任的官方主机白名单内：$host"
        }
        require(!uri.rawPath.orEmpty().contains("..")) { "素材链接路径包含非法遍历段" }
        return uri
    }

    fun sanitizeAssetFilename(rawFilename: String, category: AssetCategory): String {
        val raw = rawFilename.trim()
        require(raw.isNotEmpty()) { "素材文件名不能为空" }
        require('\u0000' !in raw && '/' !in raw && '\\' !in raw && ".." !in raw) {
            "素材文件名包含非法路径字符：$raw"
        }
        val noControl = raw.filterNot(Char::isISOControl)
        val dotIndex = noControl.lastIndexOf('.')
        require(dotIndex > 0 && dotIndex < noControl.lastIndex) { "素材文件名缺少扩展名：$raw" }
        val ext = noControl.substring(dotIndex + 1).lowercase(Locale.ROOT)
        require(ext in category.allowedExtensions) {
            "${category.label} 不支持 .$ext 文件（仅支持 ${category.allowedExtensions.joinToString(", ")}）"
        }
        val baseRaw = noControl.substring(0, dotIndex).replace(invalidFileChars, "_").trim('.', '_', ' ')
        require(baseRaw.isNotEmpty() && baseRaw.lowercase(Locale.ROOT) !in reservedNames) {
            "素材文件名无效：$raw"
        }
        val safeBase = baseRaw.take(100)
        return "$safeBase.$ext"
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

internal val builtInOfficialAssets: List<AssetManifestItem> = listOf(
    AssetManifestItem(
        id = "themes:catppuccin-mocha.json",
        name = "Catppuccin Mocha",
        category = AssetCategory.THEME,
        description = "经典的深色柔和护眼 SillyTavern 主题预设。",
        downloadUrl = "https://raw.githubusercontent.com/SillyTavern/SillyTavern-Content/main/themes/Catppuccin-Mocha.json",
        filename = "Catppuccin-Mocha.json",
        author = "SillyTavern",
        tags = listOf("dark", "theme", "catppuccin"),
    ),
    AssetManifestItem(
        id = "themes:nord-frost.json",
        name = "Nord Frost",
        category = AssetCategory.THEME,
        description = "北欧冷色调高可读性主题预设。",
        downloadUrl = "https://raw.githubusercontent.com/SillyTavern/SillyTavern-Content/main/themes/Nord-Frost.json",
        filename = "Nord-Frost.json",
        author = "SillyTavern",
        tags = listOf("dark", "nord", "theme"),
    ),
    AssetManifestItem(
        id = "backgrounds:tavern-hearth.png",
        name = "Tavern Hearth",
        category = AssetCategory.BACKGROUND,
        description = "温暖壁炉酒馆场景背景图。",
        downloadUrl = "https://raw.githubusercontent.com/SillyTavern/SillyTavern-Content/main/backgrounds/tavern-hearth.png",
        filename = "tavern-hearth.png",
        author = "SillyTavern",
        tags = listOf("tavern", "fantasy", "warm"),
    ),
    AssetManifestItem(
        id = "backgrounds:cyberpunk-rain.png",
        name = "Cyberpunk Rain",
        category = AssetCategory.BACKGROUND,
        description = "赛博霓虹雨夜都市背景图。",
        downloadUrl = "https://raw.githubusercontent.com/SillyTavern/SillyTavern-Content/main/backgrounds/cyberpunk-rain.png",
        filename = "cyberpunk-rain.png",
        author = "SillyTavern",
        tags = listOf("scifi", "cyberpunk", "night"),
    ),
    AssetManifestItem(
        id = "characters:seraphina.png",
        name = "Seraphina",
        category = AssetCategory.CHARACTER,
        description = "SillyTavern 官方示例角色卡。",
        downloadUrl = "https://raw.githubusercontent.com/SillyTavern/SillyTavern-Content/main/characters/Seraphina.png",
        filename = "Seraphina.png",
        author = "SillyTavern",
        tags = listOf("starter", "official", "fantasy"),
    ),
    AssetManifestItem(
        id = "sprites:seraphina-sprites.zip",
        name = "Seraphina Expression Pack",
        category = AssetCategory.SPRITE,
        description = "官方表情立绘素材包（含喜悦、惊讶、悲伤等表情）。",
        downloadUrl = "https://raw.githubusercontent.com/SillyTavern/SillyTavern-Content/main/sprites/Seraphina.zip",
        filename = "Seraphina.zip",
        author = "SillyTavern",
        tags = listOf("sprites", "expressions", "official"),
    ),
)

internal class AssetsManager(
    private val storageDir: File,
    private val http: AssetsHttpClient = DefaultAssetsHttpClient,
    private val allowedHosts: Set<String> = DEFAULT_ALLOWED_ASSET_HOSTS,
) {
    fun parseManifestJson(rawJson: String): List<AssetManifestItem> {
        val trimmed = rawJson.trim().trimStart('\uFEFF')
        if (trimmed.isEmpty()) return emptyList()
        val items = mutableListOf<AssetManifestItem>()
        if (trimmed.startsWith("[")) {
            val array = runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyList()
            array.objects().mapNotNullTo(items, AssetManifestItem::from)
            return items
        }
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
        // Format 1: { "assets": [ ... ] }
        root.optJSONArray("assets")?.objects()?.mapNotNullTo(items, AssetManifestItem::from)
        // Format 2: Official SillyTavern-Content grouped keys { "themes": [...], "backgrounds": [...], "characters": [...], "sprites": [...] }
        AssetCategory.entries.forEach { cat ->
            root.optJSONArray(cat.wire)?.objects()?.forEach { row ->
                val enriched = JSONObject(row.toString()).apply {
                    if (!has("category") && !has("type")) put("category", cat.wire)
                }
                AssetManifestItem.from(enriched)?.let(items::add)
            }
        }
        return items.distinctBy(AssetManifestItem::id)
    }

    suspend fun fetchManifest(manifestUrl: String = DEFAULT_ASSETS_MANIFEST_URL): List<AssetManifestItem> {
        val uri = AssetValidator.validateAssetUrl(manifestUrl, allowedHosts)
        val bytes = http.getBytes(uri.toASCIIString(), 8 * 1024 * 1024)
        return parseManifestJson(bytes.decodeToString())
    }

    fun filterAssets(
        items: List<AssetManifestItem>,
        query: String = "",
        category: AssetCategory? = null,
    ): List<AssetManifestItem> {
        val needle = query.trim().lowercase(Locale.ROOT)
        return items.filter { item ->
            val catMatch = category == null || item.category == category
            val queryMatch = needle.isEmpty() ||
                item.name.lowercase(Locale.ROOT).contains(needle) ||
                item.description.lowercase(Locale.ROOT).contains(needle) ||
                item.author.lowercase(Locale.ROOT).contains(needle) ||
                item.tags.any { it.lowercase(Locale.ROOT).contains(needle) }
            catMatch && queryMatch
        }
    }

    fun targetFileFor(item: AssetManifestItem): File {
        val safeName = AssetValidator.sanitizeAssetFilename(item.filename, item.category)
        val catDir = File(storageDir, item.category.wire)
        val target = File(catDir, safeName)
        val canonicalBase = storageDir.canonicalFile.toPath()
        val canonicalTarget = target.canonicalFile.toPath()
        require(canonicalTarget.startsWith(canonicalBase)) { "目标路径超出了素材目录范围" }
        return target
    }

    fun isInstalled(item: AssetManifestItem): Boolean =
        runCatching { targetFileFor(item).isFile }.getOrDefault(false)

    fun installedFiles(category: AssetCategory? = null): List<File> {
        val dirs = if (category != null) listOf(File(storageDir, category.wire))
        else AssetCategory.entries.map { File(storageDir, it.wire) }
        return dirs.flatMap { dir -> dir.listFiles()?.filter(File::isFile).orEmpty() }.sortedBy(File::getName)
    }

    suspend fun downloadAsset(
        item: AssetManifestItem,
        maxBytes: Int = 32 * 1024 * 1024,
    ): File {
        val uri = AssetValidator.validateAssetUrl(item.downloadUrl, allowedHosts)
        val target = targetFileFor(item)
        val bytes = http.getBytes(uri.toASCIIString(), maxBytes)
        require(bytes.isNotEmpty()) { "下载的素材内容为空" }
        if (item.sha256.isNotBlank()) {
            val actual = AssetValidator.sha256Hex(bytes)
            require(actual.equals(item.sha256, ignoreCase = true)) {
                "素材 SHA-256 校验失败（期望 ${item.sha256}，实际 $actual）"
            }
        }
        val parent = target.parentFile ?: storageDir
        Files.createDirectories(parent.toPath())
        val staged = Files.createTempFile(parent.toPath(), ".asset-", ".tmp")
        try {
            Files.write(staged, bytes)
            Files.move(staged, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staged)
        }
        return target
    }

    fun deleteAsset(item: AssetManifestItem): Boolean =
        runCatching { targetFileFor(item).delete() }.getOrDefault(false)
}
