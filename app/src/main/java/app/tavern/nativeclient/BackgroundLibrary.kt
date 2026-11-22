package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject

internal enum class BackgroundFit(val label: String, val original: String) {
    COVER("填满裁剪", "cover"), CONTAIN("完整显示", "contain"),
    STRETCH("拉伸填满", "stretch"), CENTER("居中", "center");

    companion object {
        fun from(value: String) = entries.firstOrNull { it.original.equals(value, true) } ?: COVER
    }
}

internal data class BackgroundEntry(val image: ChatImage, val added: Long = System.currentTimeMillis()) {
    val title get() = image.name.ifBlank { "背景图片" }
    fun json() = jsonOf("image" to image.json(), "added" to added)

    companion object {
        fun from(value: JSONObject): BackgroundEntry? =
            value.optJSONObject("image")?.let(ChatImage::from)?.let { BackgroundEntry(it, value.optLong("added")) }
    }
}

internal data class BackgroundLibrary(
    val items: List<BackgroundEntry> = emptyList(),
    val selectedId: String? = null,
    val fit: BackgroundFit = BackgroundFit.COVER,
) {
    val selected get() = items.firstOrNull { it.image.id == selectedId }?.image
    fun effective(chat: Chat?) = chat?.options?.background ?: selected

    fun add(entries: List<BackgroundEntry>) = copy(items = (items + entries).distinctBy { it.image.id })

    fun remove(id: String) = copy(items = items.filterNot { it.image.id == id }, selectedId = selectedId?.takeUnless { it == id })

    fun renamed(id: String, name: String) = copy(items = items.map { entry ->
        if (entry.image.id == id) entry.copy(image = entry.image.copy(name = backgroundTitle(name))) else entry
    })

    fun find(value: String): BackgroundEntry? {
        val query = value.trim()
        if (query.isEmpty()) return null
        return items.firstOrNull { it.image.id.equals(query, true) || it.image.filename.equals(query, true) }
            ?: items.firstOrNull { it.title.equals(query, true) || it.title.substringBeforeLast('.').equals(query, true) }
            ?: items.firstOrNull { it.title.startsWith(query, true) }
            ?: items.firstOrNull { it.title.contains(query, true) }
    }

    fun json() = jsonOf("items" to JSONArray(items.map(BackgroundEntry::json)), "selected" to selected?.id, "fit" to fit.original)

    companion object {
        fun from(value: JSONObject?) = BackgroundLibrary(
            value?.optJSONArray("items")?.objects().orEmpty().mapNotNull(BackgroundEntry::from).distinctBy { it.image.id },
            value?.string("selected")?.takeIf(String::isNotBlank),
            BackgroundFit.from(value?.string("fit").orEmpty()),
        )
    }
}

internal fun backgroundTitle(name: String) = name.trim().take(120).dropLastWhile(Char::isHighSurrogate)
    .also { require(it.isNotBlank()) { "请输入背景名称" } }
