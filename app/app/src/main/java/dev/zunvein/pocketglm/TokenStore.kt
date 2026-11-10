package dev.zunvein.pocketglm

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Inserts harvested device tokens into the bridge's SQLite pool.
 *
 * The pool file lives in filesDir/bridge/tokens.sqlite — the same file the
 * Go bridge opened at boot. Insertions go through the framework SQLite with
 * the same WAL settings the bridge uses, so both processes can read the
 * growing pool concurrently.
 */
object TokenStore {

    fun poolFile(context: Context): File =
        File(File(context.filesDir, "bridge"), BridgeDb.DEFAULT_NAME)

    fun insert(context: Context, token: String) {
        val dbFile = poolFile(context)
        BridgeDb.ensure(dbFile.parentFile!!)
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE
        )
        try {
            db.execSQL(
                "INSERT INTO tokens (token) VALUES (?)",
                arrayOf(token)
            )
        } finally {
            db.close()
        }
    }

    fun count(context: Context): Int {
        val dbFile = poolFile(context)
        if (!dbFile.exists()) return 0
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        )
        return try {
            db.rawQuery("SELECT COUNT(*) FROM tokens", null).use {
                it.moveToFirst(); it.getInt(0)
            }
        } finally {
            db.close()
        }
    }
}
