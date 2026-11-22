package app.tavern.nativeclient

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import androidx.core.database.sqlite.transaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object TavernSchema {
    const val LATEST_VERSION = 10

    val createLibrariesStatements = listOf(
        "CREATE TABLE character_groups (id TEXT PRIMARY KEY, payload TEXT NOT NULL)",
        "CREATE TABLE world_books (id TEXT PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE UNIQUE, enabled INTEGER NOT NULL, payload TEXT NOT NULL)",
    )

    val createChatsTableSql = """
        CREATE TABLE chats (
            id TEXT PRIMARY KEY, card_id TEXT REFERENCES cards(id) ON DELETE CASCADE,
            group_id TEXT REFERENCES character_groups(id) ON DELETE CASCADE,
            title TEXT NOT NULL, updated INTEGER NOT NULL, payload TEXT NOT NULL,
            CHECK ((card_id IS NULL) <> (group_id IS NULL))
        )
    """.trimIndent()

    val createChatIndicesStatements = listOf(
        "CREATE INDEX chats_per_card ON chats(card_id, updated)",
        "CREATE INDEX chats_per_group ON chats(group_id, updated)",
    )

    val addChatWorldBindingsStatements = listOf(
        "ALTER TABLE chats ADD COLUMN world_book_id TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE chats ADD COLUMN world_book_name TEXT NOT NULL DEFAULT ''",
    )

    val createPromptPresetsSql = "CREATE TABLE prompt_presets (id TEXT PRIMARY KEY, name TEXT NOT NULL, payload TEXT NOT NULL)"

    val createSearchIndexStatements = listOf(
        """
            CREATE TABLE vector_embeddings (
                scope TEXT NOT NULL, model_space TEXT NOT NULL, digest TEXT NOT NULL, vector BLOB NOT NULL,
                PRIMARY KEY (scope, model_space, digest)
            ) WITHOUT ROWID
        """.trimIndent(),
        """
            CREATE TRIGGER discard_chat_vectors AFTER DELETE ON chats BEGIN
                DELETE FROM vector_embeddings WHERE scope = 'chat:' || OLD.id;
            END
        """.trimIndent(),
    )

    val createDataBankStatements = listOf(
        """
            CREATE TABLE data_bank (
                id TEXT PRIMARY KEY, title TEXT NOT NULL, enabled INTEGER NOT NULL, source TEXT NOT NULL,
                card_id TEXT REFERENCES cards(id) ON DELETE CASCADE, chat_id TEXT REFERENCES chats(id) ON DELETE CASCADE,
                updated INTEGER NOT NULL, payload TEXT NOT NULL,
                CHECK(card_id IS NULL OR chat_id IS NULL)
            )
        """.trimIndent(),
        "CREATE INDEX data_bank_by_card ON data_bank(card_id)",
        "CREATE INDEX data_bank_by_chat ON data_bank(chat_id)",
        """
            CREATE TRIGGER discard_deleted_document_vectors AFTER DELETE ON data_bank BEGIN
                DELETE FROM vector_embeddings WHERE scope = 'document:' || OLD.id;
            END
        """.trimIndent(),
        """
            CREATE TRIGGER discard_changed_document_vectors AFTER UPDATE OF title, payload ON data_bank
            WHEN OLD.title <> NEW.title OR OLD.payload <> NEW.payload BEGIN
                DELETE FROM vector_embeddings WHERE scope = 'document:' || OLD.id;
            END
        """.trimIndent(),
    )

    val createGalleryStatements = listOf(
        """
            CREATE TABLE drawing_gallery (
                id TEXT PRIMARY KEY,
                card_id TEXT REFERENCES cards(id) ON DELETE CASCADE,
                group_id TEXT REFERENCES character_groups(id) ON DELETE CASCADE,
                created INTEGER NOT NULL, image TEXT NOT NULL, prompt TEXT NOT NULL,
                CHECK ((card_id IS NULL) <> (group_id IS NULL))
            )
        """.trimIndent(),
        "CREATE INDEX drawings_per_card ON drawing_gallery(card_id, created)",
        "CREATE INDEX drawings_per_group ON drawing_gallery(group_id, created)",
    )

    val createComfyWorkflowsSql = """
        CREATE TABLE comfy_workflows (
            id TEXT PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE UNIQUE,
            source TEXT NOT NULL, replacements TEXT NOT NULL
        )
    """.trimIndent()

    val createGlobalScriptVariablesSql = "CREATE TABLE script_globals (id TEXT PRIMARY KEY, payload TEXT NOT NULL)"

    fun createStatements(): List<String> = buildList {
        add("CREATE TABLE cards (id TEXT PRIMARY KEY, payload TEXT NOT NULL, portrait INTEGER NOT NULL)")
        addAll(createLibrariesStatements)
        add(createChatsTableSql)
        addAll(createChatIndicesStatements)
        addAll(addChatWorldBindingsStatements)
        add(createPromptPresetsSql)
        addAll(createSearchIndexStatements)
        addAll(createDataBankStatements)
        addAll(createGalleryStatements)
        add(createComfyWorkflowsSql)
        add(createGlobalScriptVariablesSql)
        addAll(ChatBackupSchema.statements)
    }

    fun upgradeStatements(oldVersion: Int, newVersion: Int): List<String> {
        check(oldVersion in 1 until LATEST_VERSION && newVersion == LATEST_VERSION) {
            "未知数据库版本 $oldVersion → $newVersion"
        }
        return buildList {
            if (oldVersion == 1) {
                addAll(createLibrariesStatements)
                add("ALTER TABLE chats RENAME TO chats_v1")
                add(createChatsTableSql)
                add("INSERT INTO chats (id, card_id, title, updated, payload) SELECT id, card_id, title, updated, payload FROM chats_v1")
                add("DROP TABLE chats_v1")
                addAll(createChatIndicesStatements)
            }
            if (oldVersion < 3) add(createPromptPresetsSql)
            if (oldVersion < 4) addAll(createSearchIndexStatements)
            if (oldVersion < 5) addAll(createDataBankStatements)
            if (oldVersion < 6) addAll(createGalleryStatements)
            if (oldVersion < 7) add(createComfyWorkflowsSql)
            if (oldVersion < 8) addAll(addChatWorldBindingsStatements)
            if (oldVersion < 9) add(createGlobalScriptVariablesSql)
            addAll(ChatBackupSchema.statements)
        }
    }
}

internal class TavernStore(context: Context) : SQLiteOpenHelper(context, "tavern.db", null, TavernSchema.LATEST_VERSION), VectorCache {
    private val settings = context.getSharedPreferences("tavern", Context.MODE_PRIVATE)
    private val chatDrafts by lazy { context.getSharedPreferences("chat_drafts", Context.MODE_PRIVATE) }
    private val portraits = File(context.filesDir, "portraits")
    private val personaAvatars = File(context.filesDir, "persona-avatars")

    override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)

    override fun onCreate(db: SQLiteDatabase) {
        TavernSchema.createStatements().forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1 until TavernSchema.LATEST_VERSION && newVersion == TavernSchema.LATEST_VERSION) {
            "未知数据库版本 $oldVersion → $newVersion"
        }
        if (oldVersion == 1) {
            TavernSchema.createLibrariesStatements.forEach(db::execSQL)
            db.execSQL("ALTER TABLE chats RENAME TO chats_v1")
            db.execSQL(TavernSchema.createChatsTableSql)
            db.execSQL("INSERT INTO chats (id, card_id, title, updated, payload) SELECT id, card_id, title, updated, payload FROM chats_v1")
            db.execSQL("DROP TABLE chats_v1")
            TavernSchema.createChatIndicesStatements.forEach(db::execSQL)
        }
        if (oldVersion < 3) db.execSQL(TavernSchema.createPromptPresetsSql)
        if (oldVersion < 4) TavernSchema.createSearchIndexStatements.forEach(db::execSQL)
        if (oldVersion < 5) TavernSchema.createDataBankStatements.forEach(db::execSQL)
        if (oldVersion < 6) TavernSchema.createGalleryStatements.forEach(db::execSQL)
        if (oldVersion < 7) db.execSQL(TavernSchema.createComfyWorkflowsSql)
        if (oldVersion < 8) migrateChatWorldBindings(db)
        if (oldVersion < 9) createGlobalScriptVariables(db)
        ChatBackupSchema.statements.forEach(db::execSQL)
    }

    private fun migrateChatWorldBindings(db: SQLiteDatabase) {
        TavernSchema.addChatWorldBindingsStatements.forEach(db::execSQL)
        db.rawQuery("SELECT id, length(payload) FROM chats ORDER BY id", null).use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getString(0)
                val payload = readPayload("chats", id, rows.getInt(1), db)
                val options = savedWorldBinding(payload)
                if (options.hasWorldBookOverride) {
                    db.update("chats", ContentValues().apply { putWorldBinding(options) }, "id = ?", arrayOf(id))
                }
            }
        }
    }

    private fun createGlobalScriptVariables(db: SQLiteDatabase) {
        db.execSQL(TavernSchema.createGlobalScriptVariablesSql)
        val legacy = settings.getString("preferences", null)?.let(::JSONObject)?.optJSONObject("scriptVariables")
        if (legacy != null) db.saveGlobalScriptVariables(legacy.variableStrings())
    }

    private fun SQLiteDatabase.saveGlobalScriptVariables(variables: Map<String, String>) {
        val values = ContentValues().apply { put("id", "globals"); put("payload", JSONObject(variables).toString()) }
        check(insertWithOnConflict("script_globals", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
            "无法保存全局变量"
        }
    }

    private fun globalScriptVariables(): Map<String, String> {
        val db = readableDatabase
        return db.rawQuery("SELECT length(payload) FROM script_globals WHERE id = 'globals'", null).use { rows ->
            if (rows.moveToFirst()) JSONObject(readPayload("script_globals", "globals", rows.getInt(0), db)).variableStrings()
            else emptyMap()
        }
    }

    fun commitScriptVariables(chat: Chat?, globals: Map<String, String>?) = writableDatabase.transaction {
        if (chat != null) writeChat(chat)
        if (globals != null) saveGlobalScriptVariables(globals)
        Unit
    }

    private fun savedWorldBinding(payload: String): ConversationOptions = try {
        var header: String? = null
        var options: ConversationOptions? = null
        JsonReader(StringReader(payload)).use { reader ->
            reader.beginObject()
            while (reader.hasNext() && options == null) {
                when (reader.nextName()) {
                    "header" -> if (reader.peek() == JsonToken.STRING) header = reader.nextString() else reader.skipValue()
                    "options" -> if (reader.peek() == JsonToken.BEGIN_OBJECT) options = reader.worldBinding() else reader.skipValue()
                    else -> reader.skipValue()
                }
            }
        }
        options ?: ConversationOptions.fromMetadata(header?.let { JSONObject(it).optJSONObject("chat_metadata") })
    } catch (_: IOException) {
        ConversationOptions()
    } catch (_: IllegalStateException) {
        ConversationOptions()
    } catch (_: org.json.JSONException) {
        ConversationOptions()
    }

    private fun JsonReader.worldBinding(): ConversationOptions {
        var id = ""
        var name = ""
        beginObject()
        while (hasNext()) {
            when (nextName()) {
                "tavern_native_world_book" -> if (peek() == JsonToken.STRING) id = nextString() else skipValue()
                "world_info" -> if (peek() == JsonToken.STRING) name = nextString() else skipValue()
                else -> skipValue()
            }
        }
        endObject()
        return ConversationOptions(worldBookId = id, worldBookName = name)
    }

    fun comfyWorkflows(): List<ComfyWorkflow> =
        readableDatabase.query("comfy_workflows", arrayOf("id", "name", "source", "replacements"),
            null, null, null, null, "name").use { rows ->
            buildList {
                while (rows.moveToNext()) add(ComfyWorkflow(rows.getString(0), rows.getString(1), rows.getString(2),
                    JSONObject(rows.getString(3)).variableStrings()))
            }
        }

    fun saveComfyWorkflow(workflow: ComfyWorkflow) = writableDatabase.transaction {
        val values = ContentValues().apply {
            put("id", workflow.id)
            put("name", workflow.name)
            put("source", workflow.source)
            put("replacements", JSONObject(workflow.replacements).toString())
        }
        if (update("comfy_workflows", values, "id = ?", arrayOf(workflow.id)) == 0)
            insertOrThrow("comfy_workflows", null, values)
    }

    fun deleteComfyWorkflow(id: String) {
        writableDatabase.delete("comfy_workflows", "id = ?", arrayOf(id))
    }

    fun gallery(cardId: String?, groupId: String?): List<GalleryImage> {
        val owner = cardId ?: groupId ?: return emptyList()
        val column = if (cardId != null) "card_id" else "group_id"
        return readableDatabase.query("drawing_gallery", arrayOf("image", "prompt", "created"), "$column = ?",
            arrayOf(owner), null, null, "created DESC, rowid DESC").use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    val image = ChatImage.from(JSONObject(rows.getString(0))) ?: continue
                    add(GalleryImage(image, rows.getString(1), rows.getLong(2)))
                }
            }
        }
    }

    fun dataDocuments(owner: DataOwner? = null): List<DataDocument> = when (owner?.scope) {
        DataScope.GLOBAL -> queryDataDocuments("card_id IS NULL AND chat_id IS NULL", null)
        DataScope.CHARACTER -> queryDataDocuments("card_id = ?", arrayOf(requireNotNull(owner.id)))
        DataScope.CHAT -> queryDataDocuments("chat_id = ?", arrayOf(requireNotNull(owner.id)))
        null -> queryDataDocuments(null, null)
    }

    fun availableDocuments(cardId: String, chatId: String): List<DataDocument> = queryDataDocuments(
        "enabled = 1 AND ((card_id IS NULL AND chat_id IS NULL) OR card_id = ? OR chat_id = ?)", arrayOf(cardId, chatId))

    private fun queryDataDocuments(where: String?, args: Array<String>?): List<DataDocument> = readableDatabase.query(
        "data_bank", arrayOf("id", "title", "card_id", "chat_id", "enabled", "length(payload)", "source", "updated"),
        where, args, null, null, "updated DESC, id").use { rows ->
        buildList {
            while (rows.moveToNext()) {
                val card = rows.getString(2)
                val chat = rows.getString(3)
                val owner = when {
                    card != null -> DataOwner(DataScope.CHARACTER, card)
                    chat != null -> DataOwner(DataScope.CHAT, chat)
                    else -> DataOwner(DataScope.GLOBAL)
                }
                add(DataDocument(rows.getString(0), rows.getString(1), owner, rows.getInt(4) != 0,
                    rows.getInt(5), rows.getString(6), rows.getLong(7)))
            }
        }
    }

    fun documentText(id: String, maxCharacters: Int = MAX_DOCUMENT_CHARACTERS): String? = readableDatabase.rawQuery(
        "SELECT length(payload) FROM data_bank WHERE id = ?", arrayOf(id)).use { row ->
        if (row.moveToFirst()) readPayload("data_bank", id, minOf(maxCharacters, row.getInt(0))) else null
    }

    fun saveDocument(document: DataDocument, text: String?): Unit = writableDatabase.transaction {
        val values = ContentValues().apply {
            put("title", document.title)
            put("enabled", if (document.enabled) 1 else 0)
            put("source", document.source)
            put("card_id", document.owner.id.takeIf { document.owner.scope == DataScope.CHARACTER })
            put("chat_id", document.owner.id.takeIf { document.owner.scope == DataScope.CHAT })
            put("updated", document.updated)
            if (text != null) put("payload", text)
        }
        if (update("data_bank", values, "id = ?", arrayOf(document.id)) == 0) {
            require(text != null) { "找不到指定资料" }
            values.put("id", document.id)
            insertOrThrow("data_bank", null, values)
        }
    }

    fun deleteDocument(id: String) {
        writableDatabase.delete("data_bank", "id = ?", arrayOf(id))
    }

    override fun readVectors(scope: String, space: String, keys: List<String>): Map<String, FloatArray> {
        if (keys.isEmpty()) return emptyMap()
        val placeholders = keys.joinToString(",") { "?" }
        return readableDatabase.query("vector_embeddings", arrayOf("digest", "vector"),
            "scope = ? AND model_space = ? AND digest IN ($placeholders)", (listOf(scope, space) + keys).toTypedArray(),
            null, null, null).use { rows ->
            buildMap {
                while (rows.moveToNext()) vectorFloats(rows.getBlob(1))?.let { put(rows.getString(0), it) }
            }
        }
    }

    override fun saveVectors(scope: String, space: String, values: Map<String, FloatArray>) {
        writableDatabase.transaction {
            for ((key, vector) in values) {
                val row = ContentValues().apply {
                    put("scope", scope)
                    put("model_space", space)
                    put("digest", key)
                    put("vector", vectorBytes(vector))
                }
                check(insertWithOnConflict("vector_embeddings", null, row, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "无法保存向量索引" }
            }
        }
    }

    override fun clearVectors(scope: String) {
        writableDatabase.delete("vector_embeddings", "scope = ?", arrayOf(scope))
    }

    fun cards(): List<Card> =
        readableDatabase.rawQuery(
            "SELECT id, CASE WHEN length(payload) <= 128000 THEN payload END, portrait, length(payload) FROM cards", null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val payload = cursor.getString(1) ?: readPayload("cards", id, cursor.getInt(3))
                    add(Card(id, payload, cursor.getInt(2) == 1))
                }
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }

    fun chats(cardId: String? = null, groupId: String? = null, limit: Int? = null): List<ChatSummary> {
        val (column, id) = if (groupId != null) "group_id" to groupId else "card_id" to requireNotNull(cardId)
        val maximum = limit?.let { " LIMIT ${it.coerceAtLeast(0)}" }.orEmpty()
        return chatSummaries("$column = ?", arrayOf(id), maximum)
    }

    fun statisticsChatIds(cardId: String?, groupId: String?): List<String> {
        if (cardId != null || groupId != null) return chats(cardId, groupId).map(ChatSummary::id)
        return readableDatabase.rawQuery("SELECT id FROM chats", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }

    fun sharedMemoryChats(card: Card, world: WorldBook, excludeId: String, groupId: String? = null): List<ChatSummary> {
        val (column, id) = if (groupId != null) "group_id" to groupId else "card_id" to card.id
        val inheritedName = card.worldName
        val nameMatches = if (inheritedName == world.name) "world_book_name IN (?, '')" else "world_book_name = ?"
        return chatSummaries(
            "$column = ? AND id <> ? AND (world_book_id = ? OR (world_book_id = '' AND $nameMatches))",
            arrayOf(id, excludeId, world.id, world.name), " LIMIT $SHARED_WORLD_CHAT_LIMIT",
        )
    }

    private fun chatSummaries(where: String, args: Array<String>, limit: String): List<ChatSummary> =
        readableDatabase.rawQuery("SELECT id, title, updated FROM chats WHERE $where ORDER BY updated DESC$limit",
            args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(ChatSummary(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
            }
        }

    suspend fun searchChats(
        scope: ChatArchiveScope, query: ChatArchiveQuery, after: ChatArchiveCursor?, pageSize: Int = 40,
    ): ChatArchivePage {
        require(pageSize > 0)
        val context = currentCoroutineContext()
        val args = mutableListOf<String>()
        val filters = mutableListOf<String>()
        scope.cardId?.let { filters += "card_id = ?"; args += it }
        scope.groupId?.let { filters += "group_id = ?"; args += it }
        if (after != null) {
            filters += "(updated < ? OR (updated = ? AND id > ?))"
            args += listOf(after.updated.toString(), after.updated.toString(), after.chatId)
        }
        val where = if (filters.isEmpty()) "" else filters.joinToString(" AND ", prefix = " WHERE ")
        val db = readableDatabase
        val hits = mutableListOf<ChatArchiveHit>()
        db.rawQuery("SELECT id, title, updated, card_id, group_id FROM chats$where ORDER BY updated DESC, id",
            args.toTypedArray()).use { rows ->
            while (rows.moveToNext()) {
                context.ensureActive()
                val summary = ChatSummary(rows.getString(0), rows.getString(1), rows.getLong(2))
                val match = if (query.terms.isEmpty()) null else {
                    try {
                        scanChatArchive(query, ArchivePayloadReader(db, summary) { context.ensureActive() })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: IOException) {
                        // A row that changed during the scan can be read on the next search.
                        null
                    } catch (_: IllegalStateException) {
                        // Skip malformed chat data while continuing the search.
                        null
                    }
                }
                if (match == null && !query.matchesTitle(summary.title)) continue
                if (hits.size == pageSize) {
                    val last = hits.last().chat
                    return ChatArchivePage(hits, ChatArchiveCursor(last.updated, last.id))
                }
                hits += ChatArchiveHit(summary, rows.getString(3), rows.getString(4), match)
            }
        }
        return ChatArchivePage(hits)
    }

    private class ArchivePayloadReader(
        private val db: SQLiteDatabase, private val chat: ChatSummary, private val checkActive: () -> Unit,
    ) : Reader() {
        private var start = 1
        private var chunk = ""
        private var index = 0

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (index == chunk.length) {
                checkActive()
                val count = 128_000
                chunk = db.rawQuery("SELECT substr(payload, ?, ?) FROM chats WHERE id = ? AND updated = ?",
                    arrayOf(start.toString(), count.toString(), chat.id, chat.updated.toString())).use { cursor ->
                    if (!cursor.moveToFirst()) throw IOException("Chat was updated during the search")
                    cursor.getString(0)
                }
                if (chunk.isEmpty()) return -1
                start += count
                index = 0
            }
            val copied = minOf(len, chunk.length - index)
            chunk.toCharArray(cbuf, off, index, index + copied)
            index += copied
            return copied
        }

        override fun close() = Unit
    }

    fun recentChats(pinnedIds: List<String>): List<RecentChat> {
        val pins = pinnedIds.take(RECENT_CHAT_LIMIT)
        val order = if (pins.isEmpty()) "updated DESC, id" else
            "id IN (${pins.joinToString(",") { "?" }}) DESC, updated DESC, id"
        return readableDatabase.rawQuery(
            "SELECT id, title, updated, card_id, group_id FROM chats ORDER BY $order LIMIT $RECENT_CHAT_LIMIT",
            pins.toTypedArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(RecentChat(
                    ChatSummary(cursor.getString(0), cursor.getString(1), cursor.getLong(2)),
                    cursor.getString(3), cursor.getString(4)))
            }
        }
    }

    fun libraryUsage(): Map<String, LibraryUsage> = readableDatabase.rawQuery(
        "SELECT card_id, group_id, count(*), max(updated) FROM chats GROUP BY card_id, group_id", null,
    ).use { rows ->
        buildMap {
            while (rows.moveToNext()) {
                val id = if (rows.isNull(0)) libraryGroupId(rows.getString(1)) else libraryCardId(rows.getString(0))
                put(id, LibraryUsage(rows.getInt(2), rows.getLong(3)))
            }
        }
    }

    fun rememberLastChat(id: String) {
        settings.edit().putString("lastChatId", id).apply()
    }

    fun lastChatId(): String? = settings.getString("lastChatId", null)

    fun chatDraft(id: String): String = chatDrafts.getString(id, "").orEmpty()

    fun saveChatDraft(id: String, draft: String) {
        if (chatDraft(id) == draft) return
        chatDrafts.edit().apply {
            if (draft.isEmpty()) remove(id) else putString(id, draft)
        }.apply()
    }

    fun chat(id: String): Chat = readChat("chats", id)

    fun chatBackup(id: Long): Chat = readableDatabase.transaction {
        readChat("chat_backups", id.toString(), this)
    }

    private fun readChat(table: String, id: String, db: SQLiteDatabase = readableDatabase): Chat {
        val original = if (table == "chats") "id" else "chat_id"
        return db.rawQuery("SELECT card_id, title, updated, length(payload), group_id, world_book_id, world_book_name, $original FROM $table WHERE id = ?",
            arrayOf(id)).use { cursor ->
            check(cursor.moveToFirst()) { "找不到聊天记录" }
            val payload = JSONObject(readPayload(table, id, cursor.getInt(3), db))
            val header = payload.string("header").ifBlank { null }
            val metadata = header?.let { JSONObject(it).optJSONObject("chat_metadata") }
            val saved = payload.optJSONObject("options")
            val options = if (saved != null) {
                val current = ConversationOptions.fromMetadata(saved)
                current.copy(parent = current.parent ?: ChatPointer.from(metadata, "main_chat", "tavern_native_parent_chat"),
                    variables = if (saved.has("variables")) current.variables else metadata?.optJSONObject("variables").variableStrings(),
                    exampleOverride = if (saved.has("mes_example")) current.exampleOverride else metadata?.string("mes_example").orEmpty(),
                    systemPromptOverride = if (saved.has("system_prompt")) current.systemPromptOverride else metadata?.string("system_prompt").orEmpty())
            } else {
                val imported = ConversationOptions.fromMetadata(metadata)
                imported.copy(personaId = imported.personaId
                    ?: metadata?.string("persona")?.takeIf(String::isNotBlank)?.let(::previewPersonaId))
            }
            Chat(cursor.getString(7), cursor.getString(0), cursor.getString(1), cursor.getLong(2),
                payload.optJSONArray("messages")?.objects().orEmpty().map(Message::from).bindImportedSummaries(),
                header, ModelSelection.fromJson(payload.optJSONObject("modelOverride")), cursor.getString(4),
                options.copy(worldBookId = cursor.getString(5), worldBookName = cursor.getString(6)))
        }
    }

    fun chatBackups(): ChatBackupLibrary = readableDatabase.transaction {
        val enabled = rawQuery("SELECT enabled FROM chat_backup_policy WHERE id = 1", null).use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) != 0
        }
        val entries = rawQuery("SELECT id, card_id, group_id, title, saved_at, byte_count FROM chat_backups ORDER BY id DESC", null)
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(ChatBackup(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getLong(4), cursor.getLong(5)))
                }
            }
        ChatBackupLibrary(enabled, entries)
    }

    fun setAutomaticChatBackups(enabled: Boolean) {
        writableDatabase.execSQL("UPDATE chat_backup_policy SET enabled = ? WHERE id = 1", arrayOf(if (enabled) 1 else 0))
    }

    fun saveChatBackup(id: String) {
        writableDatabase.transaction {
            rawQuery("SELECT length(CAST(payload AS BLOB)) FROM chats WHERE id = ?", arrayOf(id)).use {
                check(it.moveToFirst()) { "找不到要备份的聊天记录" }
                check(it.getLong(0) <= ChatBackupSchema.maxTotalBytes) {
                    "这段聊天超过本机快照存储上限，可导出聊天及附件 ZIP"
                }
            }
            execSQL(ChatBackupSchema.captureCurrent, arrayOf(id))
        }
    }

    fun deleteChatBackup(id: Long) {
        writableDatabase.delete("chat_backups", "id = ?", arrayOf(id.toString()))
    }

    // Slice the payload without placing a full chat or world book in one cursor row.
    private fun readPayload(table: String, id: String, length: Int, db: SQLiteDatabase = readableDatabase): String = buildString {
        val chunkSize = 128_000
        for (offset in 1..length step chunkSize) {
            db.rawQuery("SELECT substr(payload, ?, ?) FROM $table WHERE id = ?",
                arrayOf(offset.toString(), minOf(chunkSize, length - offset + 1).toString(), id)).use { cursor ->
                check(cursor.moveToFirst()) { "找不到指定记录" }
                append(cursor.getString(0))
            }
        }
    }

    fun save(card: Card) {
        insertCard(card, SQLiteDatabase.CONFLICT_IGNORE)
        writableDatabase.update("cards", card.values(), "id = ?", arrayOf(card.id))
    }

    fun replaceCard(card: Card, portrait: ByteArray?) {
        val file = portraitFile(card.id)
        val before = if (portrait != null && file.isFile) file.inputStream().use { it.readLimited() } else null
        var wrote = false
        try {
            writableDatabase.transaction {
                check(update("cards", card.values(), "id = ?", arrayOf(card.id)) == 1) { "找不到要更新的角色" }
                if (portrait != null) {
                    writePortrait(card.id, portrait)
                    wrote = true
                }
            }
        } catch (error: Exception) {
            if (wrote) {
                try {
                    if (before == null) AtomicFile(file).delete() else writePortrait(card.id, before)
                } catch (restore: Exception) {
                    error.addSuppressed(restore)
                }
            }
            throw error
        }
    }

    fun import(card: CardImport, chats: List<Chat>, pictures: List<GalleryImage> = emptyList()): Int =
        writableDatabase.transaction {
            if (insertCard(card.card, SQLiteDatabase.CONFLICT_IGNORE)) {
                card.portrait?.let { writePortrait(card.card.id, it) }
                pictures.forEach { addGalleryImage(card.card.id, null, it) }
            }
            chats.count { insertWithOnConflict("chats", null, it.values(), SQLiteDatabase.CONFLICT_IGNORE) != -1L }
        }

    fun importCharacters(cards: List<Card>): Int = writableDatabase.transaction {
        cards.count { insertCard(it, SQLiteDatabase.CONFLICT_IGNORE) }
    }

    fun containsChat(id: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM chats WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }

    fun importOriginalChat(chat: Chat): Boolean =
        writableDatabase.insertWithOnConflict("chats", null, chat.values(), SQLiteDatabase.CONFLICT_IGNORE) != -1L

    fun importOriginalGallery(cardId: String?, groupId: String?, items: List<GalleryImage>) {
        if (items.isEmpty()) return
        writableDatabase.transaction {
            val existing = gallery(cardId, groupId).mapTo(hashSetOf()) { it.image.id }
            items.filter { existing.add(it.image.id) }.forEach { addGalleryImage(cardId, groupId, it) }
        }
    }

    fun save(chat: Chat, drawing: GalleryImage? = null, replyGallery: List<GalleryImage> = emptyList()) {
        writableDatabase.transaction {
            writeChat(chat)
            if (drawing != null) addDrawing(chat, drawing)
            replyGallery.forEach { addDrawing(chat, it) }
        }
    }

    private fun SQLiteDatabase.writeChat(chat: Chat) {
        val values = chat.values()
        if (update("chats", values, "id = ?", arrayOf(chat.id)) == 0) insertOrThrow("chats", null, values)
    }

    fun importChats(entries: List<Chat>, cardId: String?, groupId: String?): List<Chat> = writableDatabase.transaction {
        val titles = chats(cardId, groupId).mapTo(hashSetOf(), ChatSummary::title)
        entries.map { chat ->
            check(chat.cardId == cardId && chat.groupId == groupId) { "聊天归属不匹配" }
            val saved = chat.copy(title = chat.availableTitle(titles), messages = chat.messages.removeInvalidSummaries())
            insertOrThrow("chats", null, saved.values())
            titles += saved.title
            saved
        }
    }

    fun addDrawing(chat: Chat, drawing: GalleryImage) = addGalleryImage(chat.cardId, chat.groupId, drawing)

    private fun addGalleryImage(cardId: String?, groupId: String?, drawing: GalleryImage) {
        writableDatabase.insertOrThrow("drawing_gallery", null, ContentValues().apply {
            put("id", newId())
            put("card_id", cardId)
            put("group_id", groupId)
            put("created", drawing.created)
            put("image", drawing.image.json().toString())
            put("prompt", drawing.prompt)
        })
    }

    fun saveFork(source: Chat, fork: Chat) = writableDatabase.transaction {
        save(source)
        insertOrThrow("chats", null, fork.values())
        Unit
    }

    fun refreshCheckpoint(sourceId: String, targetId: String, position: Int): Chat = writableDatabase.transaction {
        val source = readChat("chats", sourceId, this)
        val target = readChat("chats", targetId, this)
        val history = chats(source.cardId, source.groupId)
        val (parent, refreshed) = source.refreshCheckpoint(position, target, history)
        writeChat(refreshed)
        if (parent != source) writeChat(parent)
        parent
    }

    fun renameChatFile(id: String, title: String, cardId: String?, groupId: String?): List<ChatSummary> = writableDatabase.transaction {
        val history = chats(cardId, groupId)
        val original = requireNotNull(history.firstOrNull { it.id == id }) { "找不到所选聊天" }
        if (original.title == title) return@transaction history
        require(history.none { it.id != id && it.title.equals(title, ignoreCase = true) }) { "聊天名称已存在" }
        val values = ContentValues().apply { put("title", title) }
        check(update("chats", values, "id = ?", arrayOf(id)) == 1) { "找不到所选聊天" }
        if (history.count { it.title == original.title } == 1) {
            val (column, ownerId) = if (groupId == null) "card_id" to requireNotNull(cardId) else "group_id" to groupId
            val quoted = JSONObject.quote(original.title)
            val embedded = JSONObject.quote(quoted).removeSurrounding("\"")
            val linkedIds = rawQuery("SELECT id FROM chats WHERE $column = ? AND (instr(payload, ?) > 0 OR instr(payload, ?) > 0)",
                arrayOf(ownerId, quoted, embedded)).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            for (linkedId in linkedIds) {
                val chat = readChat("chats", linkedId, this)
                val updated = chat.retargetLegacyChatLinks(original, title)
                if (chat != updated) writeChat(updated)
            }
        }
        chats(cardId, groupId)
    }

    fun deleteChat(id: String) {
        writableDatabase.delete("chats", "id = ?", arrayOf(id))
        saveChatDraft(id, "")
    }

    fun deleteCard(id: String, retained: TavernGroup? = null, inheritedWorld: String = ""): TavernGroup? {
        val archive = writableDatabase.transaction {
            val destination = retained?.takeIf {
                rawQuery("SELECT 1 FROM chats WHERE card_id = ? UNION ALL SELECT 1 FROM chat_backups WHERE card_id = ? LIMIT 1",
                    arrayOf(id, id)).use { cursor -> cursor.moveToFirst() }
            }
            if (destination != null) {
                save(destination)
                for (table in listOf("chats", "chat_backups")) {
                    execSQL(
                        "UPDATE $table SET card_id = NULL, group_id = ?, " +
                            "world_book_name = CASE WHEN world_book_id = '' AND world_book_name = '' THEN ? ELSE world_book_name END " +
                            "WHERE card_id = ?",
                        arrayOf(destination.id, inheritedWorld, id),
                    )
                }
                execSQL("UPDATE drawing_gallery SET card_id = NULL, group_id = ? WHERE card_id = ?", arrayOf(destination.id, id))
            }
            check(delete("cards", "id = ?", arrayOf(id)) == 1) { "角色已不存在" }
            groups().filter { id in it.memberIds }.forEach { group ->
                save(group.copy(memberIds = group.memberIds - id, mutedIds = group.mutedIds - id))
            }
            destination
        }
        AtomicFile(portraitFile(id)).delete()
        return archive
    }

    fun groups(): List<TavernGroup> = readableDatabase.rawQuery("SELECT id, payload FROM character_groups", null).use { c ->
        buildList { while (c.moveToNext()) add(TavernGroup.from(c.getString(0), c.getString(1))) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun save(group: TavernGroup) {
        val values = ContentValues().apply { put("id", group.id); put("payload", group.json().toString()) }
        if (writableDatabase.update("character_groups", values, "id = ?", arrayOf(group.id)) == 0) {
            writableDatabase.insertOrThrow("character_groups", null, values)
        }
    }

    internal fun updateGroupRoster(id: String, transform: (TavernGroup) -> TavernGroup): Boolean = writableDatabase.transaction {
        val existing = rawQuery("SELECT payload FROM character_groups WHERE id = ?", arrayOf(id)).use {
            check(it.moveToFirst()) { "群组已不存在" }
            TavernGroup.from(id, it.getString(0))
        }
        val updated = transform(existing)
        if (updated == existing) return@transaction false
        val values = ContentValues().apply { put("payload", updated.json().toString()) }
        update("character_groups", values, "id = ?", arrayOf(id)) == 1
    }

    fun import(group: TavernGroup, chats: List<Chat>): Int = writableDatabase.transaction {
        val values = ContentValues().apply { put("id", group.id); put("payload", group.json().toString()) }
        val inserted = insertWithOnConflict("character_groups", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (!inserted && group.avatar != null) {
            val existing = rawQuery("SELECT payload FROM character_groups WHERE id = ?", arrayOf(group.id)).use { row ->
                if (row.moveToFirst()) JSONObject(row.getString(0)) else null
            }
            if (existing != null && !existing.has("avatar_url")) {
                val updated = ContentValues().apply { put("payload", existing.put("avatar_url", group.avatar.dataUrl).toString()) }
                update("character_groups", updated, "id = ?", arrayOf(group.id))
            }
        }
        chats.count { insertWithOnConflict("chats", null, it.values(), SQLiteDatabase.CONFLICT_IGNORE) != -1L }
    }

    fun deleteGroup(id: String) {
        writableDatabase.delete("character_groups", "id = ?", arrayOf(id))
    }

    fun books(): List<WorldBook> = readableDatabase.rawQuery(
        "SELECT id, name, enabled, length(payload) FROM world_books ORDER BY name", null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                val id = c.getString(0)
                add(WorldBook(id, c.getString(1), c.getInt(2) != 0, readPayload("world_books", id, c.getInt(3))))
            }
        }
    }

    fun save(book: WorldBook) = writableDatabase.transaction {
        val old = books().firstOrNull { it.id == book.id }
        val values = ContentValues().apply {
            put("id", book.id)
            put("name", book.name)
            put("enabled", if (book.enabled) 1 else 0)
            put("payload", book.raw)
        }
        if (old == null) insertOrThrow("world_books", null, values)
        else {
            update("world_books", values, "id = ?", arrayOf(book.id))
            if (old.name != book.name) {
                cards().filter { it.worldName == old.name }.forEach { save(it.withWorld(book.name)) }
                // A legacy chat may identify its world by name alone. Keep it attached across renames.
                update("chats", ContentValues().apply {
                    put("world_book_id", book.id)
                    put("world_book_name", book.name)
                }, "world_book_id = ? OR (world_book_id = '' AND world_book_name = ?)", arrayOf(book.id, old.name))
            }
        }
    }

    fun deleteBook(book: WorldBook) = writableDatabase.transaction {
        delete("world_books", "id = ?", arrayOf(book.id))
        for (card in cards()) {
            val primary = card.worldName == book.name
            if (!primary && book.id !in card.additionalWorldBookIds) continue
            val updated = if (primary) card.withWorld("") else card
            save(updated.withAdditionalWorldBooks(updated.additionalWorldBookIds - book.id))
        }
    }

    fun transferWorldEntry(sourceId: String, targetId: String, entryId: Int, move: Boolean): Int = writableDatabase.transaction {
        val catalog = books().associateBy(WorldBook::id)
        val source = requireNotNull(catalog[sourceId]) { "找不到来源世界书" }
        val target = requireNotNull(catalog[targetId]) { "找不到目标世界书" }
        val result = source.transferEntry(target, entryId, move)
        for (book in listOfNotNull(result.destination, result.source.takeIf { move })) {
            val values = ContentValues().apply { put("payload", book.raw) }
            check(update("world_books", values, "id = ?", arrayOf(book.id)) == 1) { "世界书已不存在" }
        }
        result.entryId
    }

    fun updateWorldActivation(changes: List<WorldBook>) = writableDatabase.transaction {
        for (book in changes) {
            val values = ContentValues().apply { put("enabled", if (book.enabled) 1 else 0) }
            check(update("world_books", values, "id = ?", arrayOf(book.id)) == 1) { "世界书已不存在" }
        }
    }

    fun createBoundWorldBook(book: WorldBook, binding: app.tavern.nativeclient.script.ScriptWorldBinding) {
        writableDatabase.transaction {
            require(books().none { it.name.equals(book.name, true) }) { "世界书名称已存在" }
            save(book)
            when (binding) {
                is app.tavern.nativeclient.script.ScriptWorldBinding.Conversation ->
                    if (!binding.chat.isTemporary) writeChat(binding.chat) else Unit
                is app.tavern.nativeclient.script.ScriptWorldBinding.Character -> {
                    val card = binding.card
                    check(update("cards", card.values(), "id = ?", arrayOf(card.id)) == 1) { "找不到要关联的角色" }
                }
                is app.tavern.nativeclient.script.ScriptWorldBinding.Persona -> Unit
            }
        }
        if (binding is app.tavern.nativeclient.script.ScriptWorldBinding.Persona) {
            try {
                save(binding.prefs)
            } catch (e: Exception) {
                writableDatabase.delete("world_books", "id = ?", arrayOf(book.id))
                throw e
            }
        }
    }

    fun promptPresets(): List<PromptPreset> =
        readableDatabase.rawQuery("SELECT id, name, length(payload) FROM prompt_presets ORDER BY name COLLATE NOCASE", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    add(PromptPreset.read(JSONObject(readPayload("prompt_presets", id, c.getInt(2))), c.getString(1), id))
                }
            }
        }

    fun save(preset: PromptPreset) {
        val values = ContentValues().apply {
            put("id", preset.id)
            put("name", preset.name)
            put("payload", preset.json().toString())
        }
        if (writableDatabase.update("prompt_presets", values, "id = ?", arrayOf(preset.id)) == 0) {
            writableDatabase.insertOrThrow("prompt_presets", null, values)
        }
    }

    fun deletePreset(id: String) {
        writableDatabase.delete("prompt_presets", "id = ?", arrayOf(id))
    }

    fun portraitFile(id: String) = File(portraits, "$id.png")

    fun personaAvatar(id: String) = File(personaAvatars, "$id.png")

    fun writePersonaAvatar(id: String, bytes: ByteArray) = writeImage(personaAvatar(id), bytes)

    fun deletePersonaAvatar(id: String) = AtomicFile(personaAvatar(id)).delete()

    fun prefs(): Preferences {
        val saved = JSONObject(settings.getString("preferences", "{}") ?: "{}")
        return decodePreferences(saved, ::unseal, globalScriptVariables())
    }

    fun captureSettingsSnapshot(name: String, prefs: Preferences) =
        app.tavern.nativeclient.backup.SettingsSnapshot.capture(name, prefs, ::seal)

    fun preferencesFromSnapshot(snapshot: app.tavern.nativeclient.backup.SettingsSnapshot, current: Preferences) =
        snapshot.restore(current, ::unseal)

    fun save(prefs: Preferences) {
        val json = encodePreferences(prefs, ::seal)
        val updatedGlobals = prefs.scriptVariables.takeUnless { it == globalScriptVariables() }
        check(settings.edit().putString("preferences", json.toString()).commit()) { "无法保存设置" }
        if (updatedGlobals != null) commitScriptVariables(null, updatedGlobals)
    }

    private fun writePortrait(id: String, bytes: ByteArray) = writeImage(portraitFile(id), bytes)

    private fun writeImage(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    private fun insertCard(card: Card, conflict: Int): Boolean =
        writableDatabase.insertWithOnConflict("cards", null, card.values(), conflict) != -1L

    private fun Card.values() = ContentValues().apply {
        put("id", id)
        put("payload", original)
        put("portrait", if (hasAvatar) 1 else 0)
    }

    private fun Chat.values() = ContentValues().apply {
        put("id", id)
        put("card_id", cardId)
        put("group_id", groupId)
        put("title", title)
        put("updated", updated)
        put("payload", payload().toString())
        putWorldBinding(options)
    }

    private fun ContentValues.putWorldBinding(options: ConversationOptions) {
        put("world_book_id", options.worldBookId.takeIf(String::isNotBlank).orEmpty())
        put("world_book_name", options.worldBookName.takeIf(String::isNotBlank).orEmpty())
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    private fun seal(value: String): String {
        if (value.isEmpty()) return ""
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key())
            Base64.encodeToString(iv + doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }
    }

    private fun unseal(value: String): String {
        if (value.isEmpty()) return ""
        return runCatching {
            val bytes = Base64.decode(value, Base64.DEFAULT)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
                doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

    private companion object { const val KEY_ALIAS = "tavern-api-key" }
}

internal data class ChatSummary(val id: String, val title: String, val updated: Long)
