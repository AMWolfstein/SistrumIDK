package com.amwolfstein.sistrum.library.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The FTS4 index mirrors `library_search` through triggers, so every canonical
 * insert/update/delete is reflected in the same SQLite transaction. Room only owns
 * the canonical table; this SQL is shared by the migration and first creation.
 */
object SearchIndexSql {
    fun createFts(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS library_search_fts USING fts4(" +
                "kind, entityId, title, subtitle, terms, tokenize=unicode61)",
        )
    }

    fun createTriggers(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS library_search_ai AFTER INSERT ON library_search BEGIN " +
                "INSERT INTO library_search_fts(kind, entityId, title, subtitle, terms) " +
                "VALUES (NEW.kind, NEW.entityId, NEW.title, NEW.subtitle, NEW.terms); END",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS library_search_ad AFTER DELETE ON library_search BEGIN " +
                "DELETE FROM library_search_fts WHERE kind = OLD.kind AND entityId = OLD.entityId; END",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS library_search_au AFTER UPDATE ON library_search BEGIN " +
                "DELETE FROM library_search_fts WHERE kind = OLD.kind AND entityId = OLD.entityId; " +
                "INSERT INTO library_search_fts(kind, entityId, title, subtitle, terms) " +
                "VALUES (NEW.kind, NEW.entityId, NEW.title, NEW.subtitle, NEW.terms); END",
        )
    }

    /** Escapes one user token for an FTS4 MATCH expression. */
    fun matchQuery(text: String): String = text
        .split(Regex("\\s+"))
        .map { it.replace("\"", "\"\"") }
        .filter { it.isNotBlank() }
        .joinToString(" ") { "\"$it\"*" }
}
