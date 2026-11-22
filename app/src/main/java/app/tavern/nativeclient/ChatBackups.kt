package app.tavern.nativeclient

internal data class ChatBackup(
    val id: Long,
    val cardId: String?,
    val groupId: String?,
    val title: String,
    val savedAt: Long,
    val byteCount: Long,
)

internal data class ChatBackupLibrary(val automatic: Boolean, val entries: List<ChatBackup>)

internal object ChatBackupSchema {
    private const val maxVersions = 50
    private const val maxBackups = 1000
    const val maxTotalBytes = 128 * 1024 * 1024
    private const val columns = "chat_id, card_id, group_id, title, updated, world_book_id, world_book_name, payload, saved_at, byte_count"

    private fun capture(fields: String, source: String, filter: String): String {
        fun column(name: String) = "$fields.$name"
        val original = listOf("id", "card_id", "group_id", "title", "updated", "world_book_id", "world_book_name", "payload")
            .joinToString(", ", transform = ::column)
        val size = "length(CAST(${column("payload")} AS BLOB))"
        return """
            INSERT INTO chat_backups ($columns)
            SELECT $original, CAST(strftime('%s', 'now') AS INTEGER) * 1000, $size $source
            WHERE $filter AND $size <= $maxTotalBytes
              AND NOT EXISTS (
                SELECT 1 FROM chat_backups
                WHERE id = (SELECT max(id) FROM chat_backups WHERE chat_id = ${column("id")})
                  AND title = ${column("title")} AND payload = ${column("payload")}
              )
        """.trimIndent()
    }

    // Use a table alias so the correlation inside the deduplication query always refers to the live chat.
    val captureCurrent = capture("live", "FROM chats AS live", "live.id = ?")

    val statements = listOf(
        """
        CREATE TABLE chat_backups (
            id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id TEXT NOT NULL,
            card_id TEXT, group_id TEXT, title TEXT NOT NULL, updated INTEGER NOT NULL,
            world_book_id TEXT NOT NULL, world_book_name TEXT NOT NULL, payload TEXT NOT NULL,
            saved_at INTEGER NOT NULL, byte_count INTEGER NOT NULL,
            CHECK ((card_id IS NULL) <> (group_id IS NULL))
        )
        """.trimIndent(),
        "CREATE INDEX chat_backups_by_chat ON chat_backups(chat_id, id)",
        "CREATE TABLE chat_backup_policy (id INTEGER PRIMARY KEY CHECK (id = 1), enabled INTEGER NOT NULL)",
        "INSERT INTO chat_backup_policy (id, enabled) VALUES (1, 1)",
        """
        CREATE TRIGGER capture_chat_before_update BEFORE UPDATE OF title, payload ON chats
        WHEN OLD.title <> NEW.title OR OLD.payload <> NEW.payload
        BEGIN
            ${capture("OLD", "", "(SELECT enabled FROM chat_backup_policy WHERE id = 1) = 1")};
        END
        """.trimIndent(),
        """
        CREATE TRIGGER capture_chat_before_delete BEFORE DELETE ON chats
        BEGIN
            ${capture("OLD", "", "(SELECT enabled FROM chat_backup_policy WHERE id = 1) = 1")};
        END
        """.trimIndent(),
        """
        CREATE TRIGGER prune_chat_backup_versions AFTER INSERT ON chat_backups
        BEGIN
            DELETE FROM chat_backups WHERE chat_id = NEW.chat_id AND id < (
                SELECT id FROM chat_backups WHERE chat_id = NEW.chat_id
                ORDER BY id DESC LIMIT 1 OFFSET ${maxVersions - 1}
            );
            DELETE FROM chat_backups WHERE id < (
                SELECT id FROM chat_backups ORDER BY id DESC LIMIT 1 OFFSET ${maxBackups - 1}
            );
            DELETE FROM chat_backups WHERE (SELECT total(byte_count) FROM chat_backups) > $maxTotalBytes AND id IN (
                SELECT older.id FROM chat_backups AS older
                WHERE (SELECT total(newer.byte_count) FROM chat_backups AS newer WHERE newer.id >= older.id) > $maxTotalBytes
            );
        END
        """.trimIndent(),
        """
        CREATE TRIGGER discard_card_backups AFTER DELETE ON cards
        BEGIN DELETE FROM chat_backups WHERE card_id = OLD.id; END
        """.trimIndent(),
        """
        CREATE TRIGGER discard_group_backups AFTER DELETE ON character_groups
        BEGIN DELETE FROM chat_backups WHERE group_id = OLD.id; END
        """.trimIndent(),
    )
}
