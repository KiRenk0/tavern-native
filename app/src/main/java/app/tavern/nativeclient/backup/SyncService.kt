package app.tavern.nativeclient.backup

import android.content.Context
import app.tavern.nativeclient.Card
import app.tavern.nativeclient.CardFile
import app.tavern.nativeclient.Chat
import app.tavern.nativeclient.Preferences
import app.tavern.nativeclient.PromptPreset
import app.tavern.nativeclient.RemoteCard
import app.tavern.nativeclient.TavernServer
import app.tavern.nativeclient.TavernStore
import app.tavern.nativeclient.WorldBook
import app.tavern.nativeclient.jsonOf
import app.tavern.nativeclient.newId
import app.tavern.nativeclient.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.xml.parsers.DocumentBuilderFactory

internal enum class SyncConflictPolicy(val label: String) {
    NEWEST_WINS("较新时间戳优先"),
    LOCAL_WINS("以本机为准"),
    REMOTE_WINS("以远端为准"),
}

internal data class SyncSettings(
    val webDavUrl: String = "",
    val webDavUsername: String = "",
    val webDavPassword: String = "",
    val syncCards: Boolean = true,
    val syncChats: Boolean = true,
    val syncWorlds: Boolean = true,
    val syncPresets: Boolean = true,
    val conflictPolicy: SyncConflictPolicy = SyncConflictPolicy.NEWEST_WINS,
    val lastSyncAt: Long = 0L,
    val lastSyncSummary: String = "",
) {
    val configured: Boolean
        get() = webDavUrl.trim().let { it.startsWith("https://", true) || it.startsWith("http://", true) }

    fun json(seal: (String) -> String = { it }): JSONObject = jsonOf(
        "webDavUrl" to webDavUrl,
        "webDavUsername" to webDavUsername,
        "webDavPassword" to seal(webDavPassword),
        "syncCards" to syncCards,
        "syncChats" to syncChats,
        "syncWorlds" to syncWorlds,
        "syncPresets" to syncPresets,
        "conflictPolicy" to conflictPolicy.name,
        "lastSyncAt" to lastSyncAt,
        "lastSyncSummary" to lastSyncSummary,
    )

    companion object {
        fun from(json: JSONObject?, unseal: (String) -> String = { it }): SyncSettings {
            if (json == null) return SyncSettings()
            val policy = runCatching {
                SyncConflictPolicy.valueOf(json.string("conflictPolicy"))
            }.getOrDefault(SyncConflictPolicy.NEWEST_WINS)
            return SyncSettings(
                webDavUrl = json.string("webDavUrl"),
                webDavUsername = json.string("webDavUsername"),
                webDavPassword = unseal(json.string("webDavPassword")),
                syncCards = json.optBoolean("syncCards", true),
                syncChats = json.optBoolean("syncChats", true),
                syncWorlds = json.optBoolean("syncWorlds", true),
                syncPresets = json.optBoolean("syncPresets", true),
                conflictPolicy = policy,
                lastSyncAt = json.optLong("lastSyncAt", 0L),
                lastSyncSummary = json.string("lastSyncSummary"),
            )
        }
    }
}

internal enum class SyncEntityKind(val folder: String, val ext: String) {
    CARD("cards", "png"),
    CHAT("chats", "jsonl"),
    WORLD("worlds", "json"),
    PRESET("presets", "json"),
}

internal data class SyncItemMeta(
    val kind: SyncEntityKind,
    val key: String,
    val updatedAt: Long,
    val hash: String,
    val filename: String,
) {
    fun json(): JSONObject = jsonOf(
        "kind" to kind.name,
        "key" to key,
        "updatedAt" to updatedAt,
        "hash" to hash,
        "filename" to filename,
    )

    companion object {
        fun from(json: JSONObject): SyncItemMeta? {
            val kind = runCatching { SyncEntityKind.valueOf(json.string("kind")) }.getOrNull() ?: return null
            val key = json.string("key").ifBlank { return null }
            val filename = json.string("filename").ifBlank { return null }
            return SyncItemMeta(
                kind = kind,
                key = key,
                updatedAt = json.optLong("updatedAt", 0L),
                hash = json.string("hash"),
                filename = filename,
            )
        }
    }
}

internal enum class SyncDirection {
    UPLOAD,
    DOWNLOAD,
    SKIP_EQUAL,
    CONFLICT_RESOLVED_LOCAL,
    CONFLICT_RESOLVED_REMOTE,
}

internal data class SyncAction(
    val kind: SyncEntityKind,
    val key: String,
    val filename: String,
    val direction: SyncDirection,
)

internal data class DavResource(
    val href: String,
    val name: String,
    val lastModifiedMs: Long,
    val sizeBytes: Long,
    val isCollection: Boolean,
)

internal data class SyncReport(
    val uploaded: Int,
    val downloaded: Int,
    val skipped: Int,
    val conflictsResolved: Int,
    val summary: String,
    val timestamp: Long = System.currentTimeMillis(),
)

internal fun computeSyncPlan(
    localItems: Map<String, SyncItemMeta>,
    remoteItems: Map<String, SyncItemMeta>,
    previousHashes: Map<String, String>,
    policy: SyncConflictPolicy,
): List<SyncAction> {
    val keys = (localItems.keys + remoteItems.keys).sorted()
    return keys.mapNotNull { key ->
        val local = localItems[key]
        val remote = remoteItems[key]
        when {
            local != null && remote == null ->
                SyncAction(local.kind, key, local.filename, SyncDirection.UPLOAD)
            local == null && remote != null ->
                SyncAction(remote.kind, key, remote.filename, SyncDirection.DOWNLOAD)
            local != null && remote != null -> {
                if (local.hash.isNotBlank() && local.hash == remote.hash) {
                    SyncAction(local.kind, key, local.filename, SyncDirection.SKIP_EQUAL)
                } else {
                    val base = previousHashes[key]
                    val direction = when {
                        base != null && local.hash == base && remote.hash != base -> SyncDirection.DOWNLOAD
                        base != null && remote.hash == base && local.hash != base -> SyncDirection.UPLOAD
                        policy == SyncConflictPolicy.LOCAL_WINS -> SyncDirection.CONFLICT_RESOLVED_LOCAL
                        policy == SyncConflictPolicy.REMOTE_WINS -> SyncDirection.CONFLICT_RESOLVED_REMOTE
                        local.updatedAt >= remote.updatedAt -> SyncDirection.CONFLICT_RESOLVED_LOCAL
                        else -> SyncDirection.CONFLICT_RESOLVED_REMOTE
                    }
                    val filename = if (direction == SyncDirection.DOWNLOAD || direction == SyncDirection.CONFLICT_RESOLVED_REMOTE) {
                        remote.filename
                    } else {
                        local.filename
                    }
                    SyncAction(local.kind, key, filename, direction)
                }
            }
            else -> null
        }
    }
}

internal fun parsePropfindHrefs(xml: String, collectionPath: String = ""): List<DavResource> {
    if (xml.isBlank()) return emptyList()
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }
    val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    val normalizedCollection = normalizeDavPath(collectionPath)
    val responses = doc.getElementsByTagNameNS("*", "response")
    return buildList {
        for (i in 0 until responses.length) {
            val element = responses.item(i) as? Element ?: continue
            val hrefRaw = firstChildText(element, "href").trim()
            if (hrefRaw.isBlank()) continue
            val rawPath = runCatching { URI(hrefRaw).rawPath }.getOrNull() ?: hrefRaw
            val normalizedPath = normalizeDavPath(rawPath)
            if (normalizedCollection.isNotEmpty() && normalizedPath == normalizedCollection) continue
            val isCollection = element.getElementsByTagNameNS("*", "collection").length > 0
            val lastModRaw = firstChildText(element, "getlastmodified").trim()
            val lastModMs = parseDavTimestamp(lastModRaw)
            val sizeBytes = firstChildText(element, "getcontentlength").trim().toLongOrNull() ?: 0L
            val decodedName = normalizedPath.substringAfterLast('/').let { segment ->
                runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
            }
            if (decodedName.isBlank()) continue
            add(DavResource(
                href = rawPath,
                name = decodedName,
                lastModifiedMs = lastModMs,
                sizeBytes = sizeBytes,
                isCollection = isCollection,
            ))
        }
    }
}

private fun firstChildText(parent: Element, localName: String): String {
    val nodes = parent.getElementsByTagNameNS("*", localName)
    return if (nodes.length > 0) nodes.item(0).textContent.orEmpty() else ""
}

private fun normalizeDavPath(raw: String): String {
    val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    return "/" + decoded.trim('/').replace(Regex("/+"), "/")
}

private fun parseDavTimestamp(raw: String): Long {
    if (raw.isBlank()) return 0L
    runCatching { return ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
    runCatching { return Instant.parse(raw).toEpochMilli() }
    return 0L
}

private data class HttpRawResponse(
    val code: Int,
    val message: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

internal object RawWebDavClient {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_BODY_BYTES = 48 * 1024 * 1024
    private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8" ?><D:propfind xmlns:D="DAV:"><D:prop><D:getlastmodified/><D:getcontentlength/><D:resourcetype/></D:prop></D:propfind>"""

    suspend fun testConnection(settings: SyncSettings): String = withContext(Dispatchers.IO) {
        val base = normalizedCollectionUrl(settings.webDavUrl)
        val resp = sendWithRedirects(
            method = "PROPFIND",
            url = base,
            username = settings.webDavUsername,
            password = settings.webDavPassword,
            headers = mapOf("Depth" to "0", "Content-Type" to "application/xml; charset=utf-8"),
            body = PROPFIND_BODY.toByteArray(Charsets.UTF_8),
        )
        when (resp.code) {
            in 200..299, 207 -> "WebDAV 连接成功 (${resp.code})"
            401, 403 -> throw IllegalStateException("WebDAV 认证失败 (${resp.code})，请检查用户名与密码/应用专用密码")
            404 -> {
                mkcol(base, settings.webDavUsername, settings.webDavPassword)
                "WebDAV 连接成功，已创建同步目录"
            }
            else -> throw IllegalStateException("WebDAV 返回异常状态 ${resp.code}: ${resp.message}")
        }
    }

    suspend fun mkcol(url: String, username: String, password: String): Unit = withContext(Dispatchers.IO) {
        val resp = sendWithRedirects("MKCOL", normalizedCollectionUrl(url), username, password)
        if (resp.code !in setOf(200, 201, 204, 301, 405)) {
            throw IllegalStateException("创建 WebDAV 目录失败 (${resp.code}): $url")
        }
    }

    suspend fun propfind(url: String, username: String, password: String, depth: Int = 1): List<DavResource> =
        withContext(Dispatchers.IO) {
            val target = normalizedCollectionUrl(url)
            val uri = URI(target)
            val resp = sendWithRedirects(
                method = "PROPFIND",
                url = target,
                username = username,
                password = password,
                headers = mapOf("Depth" to depth.toString(), "Content-Type" to "application/xml; charset=utf-8"),
                body = PROPFIND_BODY.toByteArray(Charsets.UTF_8),
            )
            if (resp.code == 404) return@withContext emptyList()
            if (resp.code !in 200..299 && resp.code != 207) {
                throw IllegalStateException("PROPFIND 失败 (${resp.code}): ${resp.message}")
            }
            parsePropfindHrefs(resp.body.toString(Charsets.UTF_8), uri.rawPath.orEmpty())
        }

    suspend fun getBytes(url: String, username: String, password: String): ByteArray? = withContext(Dispatchers.IO) {
        val resp = sendWithRedirects("GET", url, username, password)
        when (resp.code) {
            404 -> null
            in 200..299 -> resp.body
            else -> throw IllegalStateException("下载 WebDAV 文件失败 (${resp.code}): $url")
        }
    }

    suspend fun putBytes(url: String, username: String, password: String, body: ByteArray, contentType: String) =
        withContext(Dispatchers.IO) {
            val resp = sendWithRedirects(
                method = "PUT",
                url = url,
                username = username,
                password = password,
                headers = mapOf("Content-Type" to contentType, "Overwrite" to "T"),
                body = body,
            )
            if (resp.code !in setOf(200, 201, 204)) {
                throw IllegalStateException("上传 WebDAV 文件失败 (${resp.code}): $url")
            }
        }

    internal fun normalizedCollectionUrl(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.startsWith("https://", true) || trimmed.startsWith("http://", true)) {
            "请输入有效的 http:// 或 https:// WebDAV 地址"
        }
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    private fun sendWithRedirects(
        method: String,
        url: String,
        username: String,
        password: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): HttpRawResponse {
        var current = url
        repeat(4) {
            val response = sendOnce(method, current, username, password, headers, body)
            if (response.code in setOf(301, 302, 303, 307, 308)) {
                val loc = response.headers["location"]?.trim().orEmpty()
                if (loc.isNotBlank()) {
                    current = URI(current).resolve(loc).toString()
                    return@repeat
                }
            }
            return response
        }
        throw IllegalStateException("WebDAV 重定向次数过多")
    }

    private fun sendOnce(
        method: String,
        url: String,
        username: String,
        password: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): HttpRawResponse {
        val uri = URI(url)
        val https = uri.scheme.equals("https", true)
        val host = uri.host ?: error("无效的 WebDAV 主机地址")
        val port = if (uri.port > 0) uri.port else if (https) 443 else 80
        val defaultPort = if (https) 443 else 80
        val hostHeader = if (port == defaultPort) host else "$host:$port"
        val path = buildString {
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        val payload = body ?: ByteArray(0)

        val socket = openSocket(host, port, https)
        socket.use { sock ->
            sock.soTimeout = READ_TIMEOUT_MS
            val out = sock.getOutputStream()
            val req = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: $hostHeader\r\n")
                append("User-Agent: TavernNative-WebDAV/1.0\r\n")
                append("Connection: close\r\n")
                if (username.isNotEmpty() || password.isNotEmpty()) {
                    val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
                    append("Authorization: Basic $token\r\n")
                }
                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                if (payload.isNotEmpty() || method == "PUT" || method == "PROPFIND") {
                    append("Content-Length: ${payload.size}\r\n")
                }
                append("\r\n")
            }
            out.write(req.toByteArray(Charsets.ISO_8859_1))
            if (payload.isNotEmpty()) out.write(payload)
            out.flush()

            val input = sock.getInputStream()
            val statusLine = readHeaderLine(input)
            val parts = statusLine.split(' ', limit = 3)
            val code = parts.getOrNull(1)?.toIntOrNull()
                ?: throw IllegalStateException("无效 HTTP 响应: $statusLine")
            val message = parts.getOrNull(2).orEmpty()

            val responseHeaders = linkedMapOf<String, String>()
            while (true) {
                val line = readHeaderLine(input)
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                    val value = line.substring(colon + 1).trim()
                    responseHeaders[key] = value
                }
            }

            val respBody = when {
                code == 204 || code == 304 -> ByteArray(0)
                responseHeaders["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true ->
                    readChunkedBody(input)
                responseHeaders["content-length"] != null -> {
                    val length = responseHeaders["content-length"]!!.toInt().coerceIn(0, MAX_BODY_BYTES)
                    readExact(input, length)
                }
                else -> readBoundedEof(input)
            }
            return HttpRawResponse(code, message, responseHeaders, respBody)
        }
    }

    private fun openSocket(host: String, port: Int, https: Boolean): Socket {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        if (!https) return raw
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
        ssl.startHandshake()
        return ssl
    }

    private fun readHeaderLine(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString(Charsets.ISO_8859_1.name())
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(data, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == length) data else data.copyOf(offset)
    }

    private fun readChunkedBody(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readHeaderLine(input).substringBefore(';').trim()
            if (sizeLine.isEmpty()) break
            val size = sizeLine.toIntOrNull(16) ?: break
            if (size <= 0) break
            if (out.size() + size > MAX_BODY_BYTES) {
                throw IllegalStateException("WebDAV 响应体积超出限制")
            }
            out.write(readExact(input, size))
            readHeaderLine(input) // trailing CRLF
        }
        return out.toByteArray()
    }

    private fun readBoundedEof(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > MAX_BODY_BYTES) {
                throw IllegalStateException("WebDAV 响应体积超出限制")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

internal object SyncEngine {
    private const val MANIFEST_FILE = "tavern-sync-manifest.json"
    private const val SYNC_ID_KEY = "tavern_native_id"
    private const val SYNC_CARD_NAME_KEY = "tavern_native_card_name"
    private const val SYNC_UPDATED_KEY = "tavern_native_updated"

    // 1x1 transparent PNG fallback when a card has no custom portrait on disk
    private val blankPortraitPng: ByteArray by lazy {
        Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
        )
    }

    private val invalidFilenameChars = Regex("""[^A-Za-z0-9\u4E00-\u9FFF._-]+""")

    suspend fun syncWebDav(context: Context, store: TavernStore, prefs: Preferences): Pair<SyncSettings, SyncReport> {
        val settings = prefs.sync
        val baseUrl = RawWebDavClient.normalizedCollectionUrl(settings.webDavUrl)
        val user = settings.webDavUsername
        val pass = settings.webDavPassword

        RawWebDavClient.mkcol(baseUrl, user, pass)
        SyncEntityKind.entries.forEach { kind ->
            RawWebDavClient.mkcol("$baseUrl${kind.folder}/", user, pass)
        }

        val manifestUrl = "$baseUrl$MANIFEST_FILE"
        val remoteManifestBytes = RawWebDavClient.getBytes(manifestUrl, user, pass)
        val remoteManifest = parseManifest(remoteManifestBytes)
        val localManifestFile = localManifestFile(context, baseUrl)
        val previousHashes = parsePreviousHashes(localManifestFile)

        // Discover actual files in WebDAV directories (includes files manually dropped into WebDAV folders)
        val remoteItems = linkedMapOf<String, SyncItemMeta>()
        remoteManifest.forEach { (k, v) -> remoteItems[k] = v }

        val enabledKinds = enabledKinds(settings)
        val discoveredByFilename = mutableMapOf<Pair<SyncEntityKind, String>, DavResource>()
        for (kind in enabledKinds) {
            val resources = RawWebDavClient.propfind("$baseUrl${kind.folder}/", user, pass, depth = 1)
            resources.filterNot { it.isCollection }.forEach { res ->
                discoveredByFilename[kind to res.name] = res
                val existingEntry = remoteItems.values.firstOrNull { it.kind == kind && it.filename == res.name }
                if (existingEntry == null) {
                    val syntheticKey = "${kind.name.lowercase()}_file_${sha256Hex(res.name.toByteArray()).take(16)}"
                    remoteItems[syntheticKey] = SyncItemMeta(
                        kind = kind,
                        key = syntheticKey,
                        updatedAt = res.lastModifiedMs,
                        hash = "dav-${res.sizeBytes}-${res.lastModifiedMs}",
                        filename = res.name,
                    )
                }
            }
        }
        // Remove manifest entries whose remote files no longer exist on disk
        val iterator = remoteItems.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.kind in enabledKinds && (entry.kind to entry.filename) !in discoveredByFilename) {
                iterator.remove()
            }
        }

        // Snapshot local items
        val localSnapshots = snapshotLocalItems(store, prefs, settings)
        val localMeta = localSnapshots.mapValues { it.value.meta }
        val filteredRemoteMeta = remoteItems.filterValues { it.kind in enabledKinds }

        val actions = computeSyncPlan(localMeta, filteredRemoteMeta, previousHashes, settings.conflictPolicy)
        var uploaded = 0
        var downloaded = 0
        var skipped = 0
        var conflicts = 0
        val updatedHashes = previousHashes.toMutableMap()
        val nextRemoteManifest = remoteManifest.toMutableMap()

        for (action in actions) {
            when (action.direction) {
                SyncDirection.SKIP_EQUAL -> {
                    skipped++
                    localMeta[action.key]?.let { updatedHashes[action.key] = it.hash }
                }
                SyncDirection.UPLOAD, SyncDirection.CONFLICT_RESOLVED_LOCAL -> {
                    val snap = localSnapshots[action.key] ?: continue
                    val targetUrl = "$baseUrl${action.kind.folder}/${urlSegment(snap.meta.filename)}"
                    val contentType = when (action.kind) {
                        SyncEntityKind.CARD -> "image/png"
                        SyncEntityKind.CHAT -> "application/x-ndjson; charset=utf-8"
                        SyncEntityKind.WORLD, SyncEntityKind.PRESET -> "application/json; charset=utf-8"
                    }
                    RawWebDavClient.putBytes(targetUrl, user, pass, snap.bytes, contentType)
                    updatedHashes[action.key] = snap.meta.hash
                    nextRemoteManifest[action.key] = snap.meta
                    uploaded++
                    if (action.direction == SyncDirection.CONFLICT_RESOLVED_LOCAL) conflicts++
                }
                SyncDirection.DOWNLOAD, SyncDirection.CONFLICT_RESOLVED_REMOTE -> {
                    val remoteMeta = filteredRemoteMeta[action.key] ?: continue
                    val remoteUrl = "$baseUrl${action.kind.folder}/${urlSegment(remoteMeta.filename)}"
                    val bytes = RawWebDavClient.getBytes(remoteUrl, user, pass) ?: continue
                    val importedKey = applyDownloadedItem(store, action.kind, remoteMeta.filename, bytes, remoteMeta.updatedAt)
                    val hash = sha256Hex(bytes)
                    updatedHashes[action.key] = hash
                    if (importedKey != null) updatedHashes[importedKey] = hash
                    downloaded++
                    if (action.direction == SyncDirection.CONFLICT_RESOLVED_REMOTE) conflicts++
                }
            }
        }

        // Persist updated remote & local manifests
        val manifestJson = JSONObject().apply {
            put("version", 1)
            put("updatedAt", System.currentTimeMillis())
            put("items", JSONObject().apply {
                nextRemoteManifest.forEach { (k, v) -> put(k, v.json()) }
            })
        }.toString(2).toByteArray(Charsets.UTF_8)
        RawWebDavClient.putBytes(manifestUrl, user, pass, manifestJson, "application/json; charset=utf-8")
        savePreviousHashes(localManifestFile, updatedHashes)

        val now = System.currentTimeMillis()
        val summary = "WebDAV 同步完成：上传 $uploaded · 下载 $downloaded · 跳过 $skipped" +
            if (conflicts > 0) " · 解决冲突 $conflicts" else ""
        return settings.copy(lastSyncAt = now, lastSyncSummary = summary) to SyncReport(
            uploaded = uploaded,
            downloaded = downloaded,
            skipped = skipped,
            conflictsResolved = conflicts,
            summary = summary,
            timestamp = now,
        )
    }

    suspend fun syncLanTwoWay(
        store: TavernStore,
        prefs: Preferences,
        address: String,
        handle: String,
        password: String,
        basicUser: String,
        basicPass: String,
        includeChats: Boolean,
    ): SyncReport {
        val server = TavernServer(address, handle, basicUser, basicPass)
        val remoteCards = server.list(password)
        val localCardsByName = store.cards().associateBy { it.name.lowercase(Locale.ROOT) }
        var uploaded = 0
        var downloaded = 0
        var skipped = 0
        var conflicts = 0

        for (remote in remoteCards) {
            val existingLocal = localCardsByName[remote.name.lowercase(Locale.ROOT)]
            if (existingLocal == null) {
                val (cardImport, remoteChats) = server.download(remote, includeChats)
                downloaded += if (store.import(cardImport, remoteChats) >= 0) 1 + remoteChats.size else 1
                continue
            }
            if (!includeChats) {
                skipped++
                continue
            }
            val remoteChats = server.listChats(remote)
            val localSummaries = store.chats(cardId = existingLocal.id)
            val remoteByStem = remoteChats.associateBy { safeStem(it.fileName.removeSuffix(".jsonl")) }
            val localByStem = localSummaries.associateBy { safeStem(it.title) }

            for (rChat in remoteChats) {
                val stem = safeStem(rChat.fileName.removeSuffix(".jsonl"))
                val lSummary = localByStem[stem]
                if (lSummary == null) {
                    val rows = server.downloadChatRows(remote, rChat.fileName)
                    if (rows.isNotEmpty()) {
                        val imported = Chat.import(
                            cardId = existingLocal.id,
                            title = rChat.fileName.removeSuffix(".jsonl"),
                            rows = rows,
                        )
                        store.save(imported)
                        downloaded++
                    }
                } else {
                    val localChat = store.chat(lSummary.id)
                    if (localChat.messages.size == rChat.messageCount) {
                        skipped++
                    } else {
                        val downloadWins = when (prefs.sync.conflictPolicy) {
                            SyncConflictPolicy.LOCAL_WINS -> false
                            SyncConflictPolicy.REMOTE_WINS -> true
                            SyncConflictPolicy.NEWEST_WINS ->
                                (rChat.lastMessageMs > localChat.updated) ||
                                    (rChat.lastMessageMs == 0L && rChat.messageCount > localChat.messages.size)
                        }
                        conflicts++
                        if (downloadWins) {
                            val rows = server.downloadChatRows(remote, rChat.fileName)
                            if (rows.isNotEmpty()) {
                                val updated = Chat.import(
                                    cardId = existingLocal.id,
                                    title = localChat.title,
                                    rows = rows,
                                    id = localChat.id,
                                ).copy(updated = maxOf(System.currentTimeMillis(), rChat.lastMessageMs))
                                store.save(updated)
                                downloaded++
                            }
                        } else {
                            val personaName = prefs.personaFor(localChat).name
                            server.saveChat(remote, localChat, rChat.fileName.removeSuffix(".jsonl"), personaName)
                            uploaded++
                        }
                    }
                }
            }
            for (lSummary in localSummaries) {
                val stem = safeStem(lSummary.title)
                if (stem !in remoteByStem) {
                    val localChat = store.chat(lSummary.id)
                    val personaName = prefs.personaFor(localChat).name
                    server.saveChat(remote, localChat, lSummary.title, personaName)
                    uploaded++
                }
            }
        }
        val summary = "局域网双向同步完成：上传 $uploaded · 下载 $downloaded · 一致跳过 $skipped" +
            if (conflicts > 0) " · 解决冲突 $conflicts" else ""
        return SyncReport(uploaded, downloaded, skipped, conflicts, summary)
    }

    private data class LocalSnapshot(val meta: SyncItemMeta, val bytes: ByteArray)

    private fun enabledKinds(settings: SyncSettings): Set<SyncEntityKind> = buildSet {
        if (settings.syncCards) add(SyncEntityKind.CARD)
        if (settings.syncChats) add(SyncEntityKind.CHAT)
        if (settings.syncWorlds) add(SyncEntityKind.WORLD)
        if (settings.syncPresets) add(SyncEntityKind.PRESET)
    }

    private fun snapshotLocalItems(
        store: TavernStore,
        prefs: Preferences,
        settings: SyncSettings,
    ): Map<String, LocalSnapshot> {
        val out = linkedMapOf<String, LocalSnapshot>()
        val cards = store.cards()
        val cardsById = cards.associateBy(Card::id)

        if (settings.syncCards) {
            cards.forEach { card ->
                val portrait = store.portraitFile(card.id).takeIf { it.isFile }?.readBytes() ?: blankPortraitPng
                val root = card.portableJson()
                val data = root.optJSONObject("data") ?: JSONObject().also { root.put("data", it) }
                val ext = data.optJSONObject("extensions") ?: JSONObject().also { data.put("extensions", it) }
                ext.put(SYNC_ID_KEY, card.id)
                val portable = Card.from(root, card.id)
                val bytes = CardFile.png(portable, portrait)
                val key = "card_${card.id}"
                val filename = "${safeStem(card.name)}_${card.id.take(8)}.png"
                val contentHash = sha256Hex(card.original.toByteArray(Charsets.UTF_8) + portrait)
                out[key] = LocalSnapshot(
                    SyncItemMeta(SyncEntityKind.CARD, key, card.original.length.toLong(), contentHash, filename),
                    bytes,
                )
            }
        }
        if (settings.syncWorlds) {
            store.books().forEach { book ->
                val obj = JSONObject(book.raw).apply {
                    put("name", book.name)
                    put("enabled", book.enabled)
                    put(SYNC_ID_KEY, book.id)
                }
                val bytes = obj.toString(2).toByteArray(Charsets.UTF_8)
                val key = "world_${book.id}"
                val filename = "${safeStem(book.name)}_${book.id.take(8)}.json"
                out[key] = LocalSnapshot(
                    SyncItemMeta(SyncEntityKind.WORLD, key, bytes.size.toLong(), sha256Hex(bytes), filename),
                    bytes,
                )
            }
        }
        if (settings.syncPresets) {
            store.promptPresets().forEach { preset ->
                val obj = preset.json().apply { put(SYNC_ID_KEY, preset.id) }
                val bytes = obj.toString(2).toByteArray(Charsets.UTF_8)
                val key = "preset_${preset.id}"
                val filename = "${safeStem(preset.name)}_${preset.id.take(8)}.json"
                out[key] = LocalSnapshot(
                    SyncItemMeta(SyncEntityKind.PRESET, key, bytes.size.toLong(), sha256Hex(bytes), filename),
                    bytes,
                )
            }
        }
        if (settings.syncChats) {
            cards.forEach { card ->
                store.chats(cardId = card.id).forEach { summary ->
                    val chat = store.chat(summary.id)
                    val subject = cardsById[chat.cardId]?.name ?: card.name
                    val persona = prefs.personaFor(chat).name
                    val rawLines = chat.jsonl(subject, persona).decodeToString().lines().toMutableList()
                    if (rawLines.isNotEmpty()) {
                        val header = runCatching { JSONObject(rawLines[0]) }.getOrElse { JSONObject() }
                        val meta = header.optJSONObject("chat_metadata") ?: JSONObject().also { header.put("chat_metadata", it) }
                        meta.put(SYNC_ID_KEY, chat.id)
                        meta.put(SYNC_CARD_NAME_KEY, subject)
                        meta.put(SYNC_UPDATED_KEY, chat.updated)
                        rawLines[0] = header.toString()
                    }
                    val bytes = rawLines.joinToString("\n").toByteArray(Charsets.UTF_8)
                    val key = "chat_${chat.id}"
                    val filename = "${safeStem(subject)}_${safeStem(chat.title)}_${chat.id.take(8)}.jsonl"
                    out[key] = LocalSnapshot(
                        SyncItemMeta(SyncEntityKind.CHAT, key, chat.updated, sha256Hex(bytes), filename),
                        bytes,
                    )
                }
            }
        }
        return out
    }

    private fun applyDownloadedItem(
        store: TavernStore,
        kind: SyncEntityKind,
        filename: String,
        bytes: ByteArray,
        remoteUpdated: Long,
    ): String? {
        return when (kind) {
            SyncEntityKind.CARD -> {
                val fallbackId = newId()
                val imported = CardFile.read(bytes, fallbackId)
                val root = runCatching { JSONObject(imported.card.original) }.getOrNull()
                val embeddedId = root?.optJSONObject("data")?.optJSONObject("extensions")?.string(SYNC_ID_KEY).orEmpty()
                val existing = store.cards().firstOrNull {
                    (embeddedId.isNotBlank() && it.id == embeddedId) || it.name.equals(imported.card.name, true)
                }
                if (existing != null) {
                    store.replaceCard(imported.card.copy(id = existing.id, hasAvatar = imported.portrait != null || existing.hasAvatar), imported.portrait)
                    "card_${existing.id}"
                } else {
                    val targetId = embeddedId.ifBlank { fallbackId }
                    store.import(imported.copy(card = imported.card.copy(id = targetId)), emptyList())
                    "card_$targetId"
                }
            }
            SyncEntityKind.WORLD -> {
                val json = JSONObject(bytes.decodeToString().trimStart('\uFEFF'))
                val embeddedId = json.string(SYNC_ID_KEY)
                val fallbackName = filename.substringBeforeLast('.').replace(Regex("_[a-fA-F0-9]{8}$"), "")
                val parsed = WorldBook.import(json, fallbackName)
                val existing = store.books().firstOrNull {
                    (embeddedId.isNotBlank() && it.id == embeddedId) || it.name.equals(parsed.name, true)
                }
                val finalBook = parsed.copy(
                    id = existing?.id ?: embeddedId.ifBlank { parsed.id },
                    enabled = json.optBoolean("enabled", existing?.enabled ?: true),
                )
                store.save(finalBook)
                "world_${finalBook.id}"
            }
            SyncEntityKind.PRESET -> {
                val json = JSONObject(bytes.decodeToString().trimStart('\uFEFF'))
                val embeddedId = json.string(SYNC_ID_KEY)
                val fallbackName = filename.substringBeforeLast('.').replace(Regex("_[a-fA-F0-9]{8}$"), "")
                val parsed = PromptPreset.read(json, fallbackName)
                val existing = store.promptPresets().firstOrNull {
                    (embeddedId.isNotBlank() && it.id == embeddedId) || it.name.equals(parsed.name, true)
                }
                val finalPreset = parsed.copy(id = existing?.id ?: embeddedId.ifBlank { parsed.id })
                store.save(finalPreset)
                "preset_${finalPreset.id}"
            }
            SyncEntityKind.CHAT -> {
                val rows = bytes.decodeToString().trimStart('\uFEFF')
                    .lineSequence().filter(String::isNotBlank).map(::JSONObject).toList()
                if (rows.isEmpty()) return null
                val header = rows.first()
                val meta = header.optJSONObject("chat_metadata")
                val embeddedId = meta?.string(SYNC_ID_KEY).orEmpty()
                val charName = meta?.string(SYNC_CARD_NAME_KEY).orEmpty().ifBlank { header.string("character_name") }
                val ownerCard = store.cards().firstOrNull { it.name.equals(charName, true) }
                    ?: return null
                val title = meta?.string("tavern_native_backup_title").orEmpty().ifBlank {
                    filename.substringBeforeLast('.').replace(Regex("_[a-fA-F0-9]{8}$"), "")
                }
                val targetId = embeddedId.ifBlank {
                    store.chats(cardId = ownerCard.id).firstOrNull { it.title == title }?.id ?: newId()
                }
                val imported = Chat.import(ownerCard.id, title, rows, targetId)
                val stamp = maxOf(meta?.optLong(SYNC_UPDATED_KEY, 0L) ?: 0L, remoteUpdated, System.currentTimeMillis())
                store.save(imported.copy(updated = stamp))
                "chat_${imported.id}"
            }
        }
    }

    private fun parseManifest(bytes: ByteArray?): Map<String, SyncItemMeta> {
        if (bytes == null || bytes.isEmpty()) return emptyMap()
        return runCatching {
            val root = JSONObject(bytes.decodeToString())
            val items = root.optJSONObject("items") ?: return emptyMap()
            buildMap {
                items.keys().forEach { key ->
                    items.optJSONObject(key)?.let(SyncItemMeta::from)?.let { put(key, it) }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun localManifestFile(context: Context, baseUrl: String): File {
        val dir = File(context.filesDir, "sync_manifests").apply { mkdirs() }
        return File(dir, "${sha256Hex(baseUrl.toByteArray()).take(16)}.json")
    }

    private fun parsePreviousHashes(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            val json = JSONObject(file.readText())
            buildMap { json.keys().forEach { key -> put(key, json.string(key)) } }
        }.getOrDefault(emptyMap())
    }

    private fun savePreviousHashes(file: File, hashes: Map<String, String>) {
        val json = JSONObject()
        hashes.forEach { (k, v) -> json.put(k, v) }
        file.writeText(json.toString())
    }

    private fun safeStem(raw: String): String =
        raw.trim().replace(invalidFilenameChars, "_").trim('_').ifBlank { "item" }.take(40)

    private fun urlSegment(filename: String): String =
        java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
