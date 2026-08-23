package uk.org.baker_net.gpswaypoint.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * PreferencesDbHelper.kt
 *
 * SQLite-backed key/value store for user preferences (measurement units,
 * heart-rate monitor selection). A single generic table is used because the
 * preference set is small, forward-compatible with new keys, and does not
 * need relational queries.
 */
class PreferencesDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "preferences.db"
        private const val DATABASE_VERSION = 1
        const val TABLE_NAME = "preferences"
        const val COLUMN_KEY = "key"
        const val COLUMN_VALUE = "value"
    }

    /**
     * Creates the preferences table on first use.
     *
     * Input:  @param db Writable database supplied by the framework.
     * Output: `preferences` table created.
     */
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_NAME (
                $COLUMN_KEY TEXT PRIMARY KEY,
                $COLUMN_VALUE TEXT
            )
            """.trimIndent()
        )
    }

    /**
     * Handles schema upgrades. Currently just recreates the table since the
     * preferences it holds are simple user settings, not data the user
     * would need preserved across an incompatible schema change.
     *
     * Input:  @param db Writable database.
     *         @param oldVersion Previous schema version.
     *         @param newVersion New schema version.
     * Output: `preferences` table dropped and recreated.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    /**
     * Reads a single preference value.
     *
     * Input:  @param key Preference key.
     * Output: @return Stored string value, or null if the key is not set.
     */
    fun get(key: String): String? {
        readableDatabase.query(
            TABLE_NAME,
            arrayOf(COLUMN_VALUE),
            "$COLUMN_KEY = ?",
            arrayOf(key),
            null, null, null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    /**
     * Writes (inserts or replaces) a single preference value, or removes it
     * if [value] is null.
     *
     * Input:  @param key Preference key.
     *         @param value New string value, or null to delete the key.
     * Output: Row inserted, updated, or removed in the preferences table.
     */
    fun set(key: String, value: String?) {
        if (value == null) {
            writableDatabase.delete(TABLE_NAME, "$COLUMN_KEY = ?", arrayOf(key))
            return
        }
        val values = ContentValues().apply {
            put(COLUMN_KEY, key)
            put(COLUMN_VALUE, value)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }
}
