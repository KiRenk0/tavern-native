package app.tavern.nativeclient

import org.json.JSONObject
import java.util.Base64

internal fun readWorldBook(bytes: ByteArray, fallbackName: String): WorldBook {
    val raw = if (CardFile.isPng(bytes)) {
        val encoded = requireNotNull(CardFile.pngTextFields(bytes, setOf("naidata"))["naidata"]) { "PNG 中没有 NovelAI 世界书数据" }
        Base64.getMimeDecoder().decode(encoded).decodeToString()
    } else bytes.decodeToString()
    val json = boundedJsonObject(raw.trimStart('\uFEFF'), "无法读取世界书 JSON")
    return WorldBook.import(json, fallbackName)
}

internal fun convertForeignLorebook(data: JSONObject): List<LoreEntry>? {
    val (field, convert) = when {
        data.has("lorebookVersion") -> "entries" to ::novelEntry
        data.string("kind") == "memory" -> "entries" to ::agnaiEntry
        data.string("type") == "risu" -> "data" to ::risuEntry
        else -> return null
    }
    val array = requireNotNull(data.optJSONArray(field)) { "世界书缺少条目数组" }
    return List(array.length()) { index ->
        val row = requireNotNull(array.optJSONObject(index)) { "世界书第 ${index + 1} 项不是对象" }
        convert(index, row)
    }
}

private fun novelEntry(index: Int, row: JSONObject) = LoreEntry(id = index, title = row.string("displayName"),
    content = row.string("text"), keywords = row.optJSONArray("keys").strings(),
    priority = row.optJSONObject("contextConfig")?.optInt("budgetPriority") ?: 0,
    disabled = !row.optBoolean("enabled"))

private fun agnaiEntry(index: Int, row: JSONObject) = LoreEntry(id = index, title = row.string("name"),
    content = row.string("entry"), keywords = row.optJSONArray("keywords").strings(),
    priority = row.optInt("weight", 100), disabled = !row.optBoolean("enabled"))

private fun risuEntry(index: Int, row: JSONObject) = LoreEntry(id = index, title = row.string("comment"),
    content = row.string("content"), keywords = splitRisuKeys(row.string("key")),
    secondary = splitRisuKeys(row.string("secondkey")), selective = row.optBoolean("selective"),
    constant = row.optBoolean("alwaysActive"), priority = row.optInt("insertorder", 100),
    probability = row.optInt("activationPercent", 100).coerceIn(0, 100))

private fun splitRisuKeys(text: String) = text.split(',').map(String::trim).filter(String::isNotBlank)
