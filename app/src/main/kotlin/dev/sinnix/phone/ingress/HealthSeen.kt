package dev.sinnix.phone.ingress

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import dev.sinnix.phone.core.Storage

/**
 * What this phone has already told prime about, keyed by record identity.
 *
 * Health Connect is read two ways -- a full-history sweep and a changes feed
 * -- and neither is idempotent on its own. A sweep re-reads everything the
 * provider retains every time one starts, and a start is not rare: a changes
 * token expires after about thirty days of not being asked, a revoked-and-
 * regranted permission resets the type, and bumping the backfill generation
 * re-sweeps on purpose. Each of those re-emitted the entire retained history
 * as new events.
 *
 * Measured on prime, 2026-08-16: `events-20260816.jsonl` held **2,298,880
 * lines that were 11,118 distinct records** by (kind, record_id, modified,
 * client_record_version). Every forced `health-sync` re-exported the same
 * records, and `ts` -- the export-batch time -- made each copy look new. The
 * file reached 3.5 GB, and reading a day file of that size is what put the app
 * in a crash loop on a 256 MB heap.
 *
 * The previous defence was a *time* watermark, which cannot work here: Health
 * Connect stamps a record with when the measurement happened, while Mi Fitness
 * writes hours later in a batch, so a cursor over time either re-reads a
 * window or skips one. Identity is the thing that does not move. A record is
 * emitted when its (`record_id`, `lastModifiedTime`) pair has not been emitted
 * before -- so a re-sweep emits nothing, and a record the band revises
 * overnight emits exactly once more, which is the behaviour the downstream
 * revision history actually wants.
 *
 * SQLite rather than SharedPreferences or an in-memory set: the answer has to
 * survive a restart, the lookup happens once per record inside a streaming
 * page loop, and the whole point is to never hold the history in memory. An
 * XML preferences map would be read and rewritten whole on every commit --
 * the same accumulate-everything shape that caused the crash this exists to
 * prevent.
 */
internal object HealthSeen {

    private const val DB = "health-seen.db"
    private const val VERSION = 1

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, DB, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE seen (record_id TEXT PRIMARY KEY, modified INTEGER NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit

        override fun onConfigure(db: SQLiteDatabase) {
            // WAL plus synchronous=NORMAL, deliberately: this table is a
            // deduplication hint, not the capture itself. Full synchronous
            // fsyncs once per record inside a thousand-record page loop and
            // makes the sweep slower than the API quota it is spending; the
            // cost of the weaker mode is that a crash may lose the last few
            // commits, and the consequence of THAT is a handful of records
            // emitted twice. Duplicates are recoverable downstream by the very
            // identity this table stores. A dropped record is not recoverable
            // at all, and nothing here can cause one.
            db.enableWriteAheadLogging()
            db.execSQL("PRAGMA synchronous = NORMAL")
        }
    }

    @Volatile private var helper: Helper? = null

    private fun db(ctx: Context): SQLiteDatabase? =
        try {
            val h = helper ?: synchronized(this) {
                helper ?: Helper(ctx.applicationContext).also { helper = it }
            }
            h.writableDatabase
        } catch (e: Exception) {
            Log.w(Storage.TAG, "health-seen: database unavailable", e)
            null
        }

    /**
     * True when this revision of this record has not been emitted before, and
     * records that it now has.
     *
     * Fails OPEN. If the database cannot be opened or read, this answers true
     * and the record is emitted: the failure mode of this gate must be a
     * duplicate, never a silently dropped measurement. A record with no id is
     * emitted for the same reason -- nothing can be said about its identity,
     * so nothing is assumed.
     */
    fun claim(ctx: Context, recordId: String?, modifiedMillis: Long): Boolean {
        if (recordId.isNullOrEmpty()) return true
        val db = db(ctx) ?: return true
        return try {
            val seen =
                db.rawQuery("SELECT modified FROM seen WHERE record_id = ?", arrayOf(recordId))
                    .use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            // `>=` rather than `==`: a provider that hands back an OLDER
            // modification time than the one already emitted is describing a
            // revision this phone has already passed on, and re-emitting it
            // would put the revisions out of order downstream.
            if (seen != null && seen >= modifiedMillis) return false
            db.execSQL(
                "INSERT OR REPLACE INTO seen(record_id, modified) VALUES(?, ?)",
                arrayOf<Any>(recordId, modifiedMillis),
            )
            true
        } catch (e: Exception) {
            Log.w(Storage.TAG, "health-seen: claim failed for $recordId", e)
            true
        }
    }

    /**
     * Forget a record Health Connect says is deleted.
     *
     * Without this, a record deleted and then written again -- which is how
     * some providers "correct" an entry -- would be recognised as already
     * emitted and never reach the lake a second time.
     */
    fun forget(ctx: Context, recordId: String?) {
        if (recordId.isNullOrEmpty()) return
        val db = db(ctx) ?: return
        try {
            db.execSQL("DELETE FROM seen WHERE record_id = ?", arrayOf<Any>(recordId))
        } catch (e: Exception) {
            Log.w(Storage.TAG, "health-seen: forget failed for $recordId", e)
        }
    }

    /** How many distinct records this phone has emitted. Rendered, never guessed at. */
    fun count(ctx: Context): Long {
        val db = db(ctx) ?: return -1
        return try {
            db.rawQuery("SELECT COUNT(*) FROM seen", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else -1
            }
        } catch (e: Exception) {
            -1
        }
    }
}
