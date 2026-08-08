package com.example.llama

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ConversationRow(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class StoredMessage(
    val role: String,
    val content: String,
    /** Encoded [TurnStatsCodec] blob, or null for user turns and for answers written
     *  before this column existed. */
    val stats: String?,
)

class ChatDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE conversations (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT, " +
                "created_at INTEGER, " +
                "updated_at INTEGER)"
        )
        db.execSQL(
            "CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "conversation_id INTEGER, " +
                "role TEXT, " +
                "content TEXT, " +
                "created_at INTEGER, " +
                "stats TEXT, " +
                "FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE)"
        )
    }

    // Existing installs hold real conversations, so a schema change has to migrate them
    // rather than recreate the table. Adding a nullable column is the whole migration:
    // answers generated before this version simply have no stats to show.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN stats TEXT")
        }
    }

    fun createConversation(now: Long): Long {
        val values = ContentValues().apply {
            putNull("title")
            put("created_at", now)
            put("updated_at", now)
        }
        return writableDatabase.insert("conversations", null, values)
    }

    fun setTitleIfEmpty(conversationId: Long, title: String) {
        writableDatabase.execSQL(
            "UPDATE conversations SET title = ? WHERE id = ? AND (title IS NULL OR title = '')",
            arrayOf<Any>(title, conversationId)
        )
    }

    fun renameConversation(conversationId: Long, title: String) {
        val values = ContentValues().apply { put("title", title) }
        writableDatabase.update("conversations", values, "id = ?", arrayOf(conversationId.toString()))
    }

    fun deleteConversation(conversationId: Long) {
        writableDatabase.delete("conversations", "id = ?", arrayOf(conversationId.toString()))
    }

    fun clearAll() {
        writableDatabase.delete("conversations", null, null)
    }

    fun insertMessage(
        conversationId: Long,
        role: String,
        content: String,
        now: Long,
        stats: String? = null,
    ): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("conversation_id", conversationId)
            put("role", role)
            put("content", content)
            put("created_at", now)
            put("stats", stats)
        }
        val id = db.insert("messages", null, values)
        db.execSQL(
            "UPDATE conversations SET updated_at = ? WHERE id = ?",
            arrayOf(now, conversationId)
        )
        return id
    }

    fun deleteLastAssistantMessage(conversationId: Long) {
        writableDatabase.execSQL(
            "DELETE FROM messages WHERE id = (" +
                "SELECT id FROM messages WHERE conversation_id = ? AND role = 'assistant' " +
                "ORDER BY id DESC LIMIT 1)",
            arrayOf(conversationId)
        )
    }

    fun latestConversationId(): Long? {
        readableDatabase.rawQuery(
            "SELECT id FROM conversations ORDER BY updated_at DESC, id DESC LIMIT 1", null
        ).use { c -> return if (c.moveToFirst()) c.getLong(0) else null }
    }

    fun listConversations(): List<ConversationRow> {
        val out = mutableListOf<ConversationRow>()
        readableDatabase.rawQuery(
            "SELECT id, title, created_at, updated_at FROM conversations " +
                "ORDER BY updated_at DESC, id DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(ConversationRow(c.getLong(0), c.getString(1) ?: "", c.getLong(2), c.getLong(3)))
            }
        }
        return out
    }

    fun messagesFor(conversationId: Long): List<StoredMessage> {
        val out = mutableListOf<StoredMessage>()
        readableDatabase.rawQuery(
            "SELECT role, content, stats FROM messages WHERE conversation_id = ? ORDER BY id ASC",
            arrayOf(conversationId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(StoredMessage(c.getString(0), c.getString(1), c.getString(2)))
            }
        }
        return out
    }

    companion object {
        private const val DB_NAME = "chats.db"
        // 2 added messages.stats.
        private const val DB_VERSION = 2
    }
}
