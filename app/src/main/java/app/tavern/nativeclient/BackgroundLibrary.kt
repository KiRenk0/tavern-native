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

internal fun normalizeBackgroundFolder(path: String): String =
    path.replace('\\', '/')
        .split('/')
        .map { segment -> segment.trim().take(60).dropLastWhile(Char::isHighSurrogate) }
        .filter { it.isNotEmpty() && it != "." && it != ".." }
        .take(8)
        .joinToString("/")

internal fun backgroundParentFolder(path: String): String {
    val norm = normalizeBackgroundFolder(path)
    return if ('/' in norm) norm.substringBeforeLast('/') else ""
}

internal fun backgroundFolderName(path: String): String {
    val norm = normalizeBackgroundFolder(path)
    return if ('/' in norm) norm.substringAfterLast('/') else norm
}

internal data class BackgroundEntry(
    val image: ChatImage,
    val added: Long = System.currentTimeMillis(),
    val folder: String = "",
) {
    val normalizedFolder get() = normalizeBackgroundFolder(folder)
    val title get() = image.name.ifBlank { "背景图片" }
    val fullPath get() = if (normalizedFolder.isEmpty()) title else "$normalizedFolder/$title"

    fun json() = jsonOf("image" to image.json(), "added" to added).apply {
        val path = normalizedFolder
        if (path.isNotEmpty()) put("folder", path)
    }

    companion object {
        fun from(value: JSONObject): BackgroundEntry? =
            value.optJSONObject("image")?.let(ChatImage::from)?.let {
                BackgroundEntry(it, value.optLong("added"), normalizeBackgroundFolder(value.string("folder")))
            }
    }
}

internal data class BackgroundLibrary(
    val items: List<BackgroundEntry> = emptyList(),
    val selectedId: String? = null,
    val fit: BackgroundFit = BackgroundFit.COVER,
    val folders: List<String> = emptyList(),
) {
    val selected get() = items.firstOrNull { it.image.id == selectedId }?.image
    fun effective(chat: Chat?) = chat?.options?.background ?: selected

    fun add(entries: List<BackgroundEntry>): BackgroundLibrary {
        val normalizedEntries = entries.map { it.copy(folder = it.normalizedFolder) }
        val addedFolders = normalizedEntries.map(BackgroundEntry::normalizedFolder).filter(String::isNotEmpty)
        return copy(
            items = (items + normalizedEntries).distinctBy { it.image.id },
            folders = (folders.map(::normalizeBackgroundFolder) + addedFolders).filter(String::isNotEmpty).distinct(),
        )
    }

    fun remove(id: String) = copy(items = items.filterNot { it.image.id == id }, selectedId = selectedId?.takeUnless { it == id })

    fun renamed(id: String, name: String) = copy(items = items.map { entry ->
        if (entry.image.id == id) entry.copy(image = entry.image.copy(name = backgroundTitle(name))) else entry
    })

    fun allFolders(): List<String> {
        val raw = (folders + items.map(BackgroundEntry::folder)).map(::normalizeBackgroundFolder).filter(String::isNotEmpty)
        val expanded = linkedSetOf<String>()
        for (path in raw) {
            val parts = path.split('/')
            for (depth in 1..parts.size) {
                expanded += parts.take(depth).joinToString("/")
            }
        }
        return expanded.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun childFolders(currentFolder: String): List<String> {
        val base = normalizeBackgroundFolder(currentFolder)
        val prefix = if (base.isEmpty()) "" else "$base/"
        return allFolders().filter { folder ->
            if (base.isEmpty()) '/' !in folder
            else folder.startsWith(prefix) && '/' !in folder.removePrefix(prefix)
        }
    }

    fun itemsInFolder(currentFolder: String, includeSubfolders: Boolean = false): List<BackgroundEntry> {
        val base = normalizeBackgroundFolder(currentFolder)
        if (includeSubfolders && base.isEmpty()) return items
        val prefix = "$base/"
        return items.filter { entry ->
            val folder = entry.normalizedFolder
            folder == base || (includeSubfolders && folder.startsWith(prefix))
        }
    }

    fun folderItemCount(folderPath: String): Int = itemsInFolder(folderPath, includeSubfolders = true).size

    fun breadcrumbs(currentFolder: String): List<Pair<String, String>> {
        val base = normalizeBackgroundFolder(currentFolder)
        val trail = mutableListOf("" to "根目录")
        if (base.isEmpty()) return trail
        val parts = base.split('/')
        for (i in parts.indices) {
            trail += parts.take(i + 1).joinToString("/") to parts[i]
        }
        return trail
    }

    fun createFolder(path: String): BackgroundLibrary {
        val normalized = normalizeBackgroundFolder(path)
        require(normalized.isNotEmpty()) { "请输入有效的文件夹名称" }
        val updated = (folders.map(::normalizeBackgroundFolder) + normalized).filter(String::isNotEmpty).distinct()
        return copy(folders = updated)
    }

    fun moveItem(id: String, targetFolder: String): BackgroundLibrary {
        val target = normalizeBackgroundFolder(targetFolder)
        val updatedItems = items.map { entry ->
            if (entry.image.id == id) entry.copy(folder = target) else entry
        }
        val updatedFolders = if (target.isEmpty()) folders
        else (folders.map(::normalizeBackgroundFolder) + target).filter(String::isNotEmpty).distinct()
        return copy(items = updatedItems, folders = updatedFolders)
    }

    fun renameFolder(oldPath: String, newName: String): BackgroundLibrary {
        val oldNorm = normalizeBackgroundFolder(oldPath)
        require(oldNorm.isNotEmpty()) { "根目录无法重命名" }
        val cleanName = normalizeBackgroundFolder(newName).substringAfterLast('/').trim()
        require(cleanName.isNotEmpty()) { "请输入有效的文件夹名称" }
        val parent = backgroundParentFolder(oldNorm)
        val newNorm = if (parent.isEmpty()) cleanName else "$parent/$cleanName"
        if (newNorm == oldNorm) return this
        fun remap(path: String): String {
            val norm = normalizeBackgroundFolder(path)
            return when {
                norm == oldNorm -> newNorm
                norm.startsWith("$oldNorm/") -> "$newNorm/${norm.removePrefix("$oldNorm/")}"
                else -> norm
            }
        }
        return copy(
            items = items.map { it.copy(folder = remap(it.folder)) },
            folders = (folders.map(::remap) + newNorm).filter(String::isNotEmpty).distinct(),
        )
    }

    fun deleteFolder(path: String): BackgroundLibrary {
        val target = normalizeBackgroundFolder(path)
        if (target.isEmpty()) return this
        val parent = backgroundParentFolder(target)
        val prefix = "$target/"
        val updatedItems = items.map { entry ->
            val folder = entry.normalizedFolder
            if (folder == target || folder.startsWith(prefix)) entry.copy(folder = parent) else entry
        }
        val updatedFolders = folders.map(::normalizeBackgroundFolder)
            .filter { it.isNotEmpty() && it != target && !it.startsWith(prefix) }
            .distinct()
        return copy(items = updatedItems, folders = updatedFolders)
    }

    fun find(value: String): BackgroundEntry? {
        val query = value.trim()
        if (query.isEmpty()) return null
        val normalizedQuery = query.replace('\\', '/').trimStart('/')
        return items.firstOrNull { it.image.id.equals(query, true) || it.image.filename.equals(query, true) }
            ?: items.firstOrNull { it.fullPath.equals(normalizedQuery, true) || it.fullPath.substringBeforeLast('.').equals(normalizedQuery, true) }
            ?: items.firstOrNull { it.title.equals(query, true) || it.title.substringBeforeLast('.').equals(query, true) }
            ?: items.firstOrNull { it.title.startsWith(query, true) }
            ?: items.firstOrNull { it.title.contains(query, true) || it.fullPath.contains(normalizedQuery, true) }
    }

    fun json() = jsonOf("items" to JSONArray(items.map(BackgroundEntry::json)), "selected" to selected?.id, "fit" to fit.original).apply {
        val cleanFolders = folders.map(::normalizeBackgroundFolder).filter(String::isNotEmpty).distinct()
        if (cleanFolders.isNotEmpty()) put("folders", JSONArray(cleanFolders))
    }

    companion object {
        fun from(value: JSONObject?) = BackgroundLibrary(
            value?.optJSONArray("items")?.objects().orEmpty().mapNotNull(BackgroundEntry::from).distinctBy { it.image.id },
            value?.string("selected")?.takeIf(String::isNotBlank),
            BackgroundFit.from(value?.string("fit").orEmpty()),
            value?.optJSONArray("folders").strings().map(::normalizeBackgroundFolder).filter(String::isNotEmpty).distinct(),
        )
    }
}

internal fun backgroundTitle(name: String) = name.trim().take(120).dropLastWhile(Char::isHighSurrogate)
    .also { require(it.isNotBlank()) { "请输入背景名称" } }
