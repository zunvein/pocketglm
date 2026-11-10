package dev.zunvein.pocketglm

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Creates the empty SQLite token DB the Go bridge expects at boot.
 *
 * The Go bridge's boot guard stats `tokens.sqlite` in its working directory
 * and exits with status 1 when the file is missing. On first launch (or after
 * the user cleared app data) we create the file with the exact schema the
 * bridge reads, using the Android framework SQLite — guaranteed a valid
 * on-disk format the pure-Go driver (modernc.org/sqlite) can open.
 *
 * The WebView token farm inserts rows into this file later.
 */
object BridgeDb {

    const val DEFAULT_NAME = "tokens.sqlite"

    /** Schema must match cmd/token-collector's mint output. */
    private const val CREATE_TOKENS =
        "CREATE TABLE IF NOT EXISTS tokens (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "token TEXT NOT NULL)"

    /**
     * Ensure `workDir/tokens.sqlite` exists and contains the tokens table.
     * Idempotent: safe to call on every start.
     */
    fun ensure(workDir: File, fileName: String = DEFAULT_NAME) {
        val dbFile = File(workDir, fileName)
        if (dbFile.exists() && dbFile.length() > 0L) return
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            db.execSQL(CREATE_TOKENS)
            // Force WAL checkpoint to a clean state so no -wal file is needed
            // by the first Go-side open (ghost-row lesson from the VPS port).
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        } finally {
            db.close()
        }
    }
}
