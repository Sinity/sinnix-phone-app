package dev.sinnix.phone.sync

import android.content.Context
import android.util.Log
import dev.sinnix.phone.core.Events
import dev.sinnix.phone.core.Prefs
import dev.sinnix.phone.core.Storage
import java.io.File

/**
 * Camera and Downloads, pushed instead of pulled.
 *
 * These were the last two lanes the drain still carried, and they are the two
 * it carried worst: rsync over Termux's sshd needed a permission Termux kept
 * losing, and `/sdcard/DCIM` has mirrored nothing since November 2025 while
 * the unit reported success. The app already holds
 * MANAGE_EXTERNAL_STORAGE -- it is how the chunk directory works at all -- so
 * the same files are ordinary reads from here.
 *
 * **Nothing is ever deleted.** Every other lane in this package deletes its
 * local copy once prime confirms it, because prime is the only reader. These
 * are the operator's own photos and downloads, which the phone is expected to
 * keep; this lane copies, and the drain's `--ignore-existing` semantics are
 * what it reproduces.
 *
 * Resume uses acknowledged source (mtime, relative path), never prime's
 * upload mtimes. Old scalar preferences are deliberately ignored: a fresh
 * cursor re-offers files and prime verifies duplicate bytes. Old timestamps
 * arriving behind an established cursor still require an explicit rescan.
 */
class MediaMirror(context: Context) {

    private val ctx: Context = context.applicationContext

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "media-mirror").apply { isDaemon = true }
    }
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile private var lastRunAtMs = 0L
    private var lastFailure: String? = null

    private val hub = HubBulk(ctx)

    fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastRunAtMs < INTERVAL_MS) return
        if (!busy.compareAndSet(false, true)) return
        lastRunAtMs = now
        worker.execute {
            try {
                mirrorAll()
            } catch (e: Exception) {
                Log.w(Storage.TAG, "media-mirror: pass failed", e)
            } finally {
                busy.set(false)
            }
        }
    }

    private fun prefs() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun mirrorAll() {
        if (!Prefs.mirrorMedia(ctx)) return
        // Archive-sized by definition: a video is not something to ship over
        // cellular because a timer fired.
        if (!hub.unmeteredOrAllowed()) return note("metered")
        if (!Storage.haveAllFilesAccess()) return note("no all-files access")
        for ((lane, path) in ROOTS) {
            if (!mirrorLane(lane, File(path))) return
        }
        note(null)
    }

    /** False means "stop this pass" -- prime is unreachable or refusing. */
    private fun mirrorLane(lane: String, root: File): Boolean {
        if (!root.isDirectory) {
            note("$root is not readable")
            return false
        }
        // The previous scalar cursor could skip unoffered files. Preserve
        // those preferences but start the corrected traversal independently.
        var cursor = MediaScan.Cursor(
            prefs().getLong("$lane.cursor.time", -1L),
            prefs().getString("$lane.cursor.path", "") ?: "",
        )

        var shipped = 0
        var scanned: Int
        do {
            val pending = try {
                MediaScan.oldest(root, cursor, SCAN_CAP, MAX_DEPTH)
            } catch (e: Exception) {
                note("media scan failed")
                return false
            }
            scanned = pending.size
            var highest = cursor
            for (entry in pending) {
                val file = entry.file
                val length = entry.length
                if (!entry.unchanged()) {
                    persist(lane, highest)
                    note("file changed during scan")
                    return false
                }
                if (length <= 0L || length > MAX_BYTES) {
                    Events.record(
                        ctx, "media_mirror_skipped",
                        "lane", lane, "file", file.name, "bytes", length,
                    )
                    // Counted as seen: a 200 MB video is not going to shrink,
                    // and holding the cursor back for it would re-walk it
                    // forever.
                    highest = entry.cursor
                    continue
                }
                val body =
                    try {
                        file.readBytes()
                    } catch (e: Exception) {
                        persist(lane, highest)
                        note("read failed: ${file.name}")
                        return false
                    }
                if (!entry.unchanged()) {
                    persist(lane, highest)
                    note("file changed during read")
                    return false
                }
                val name = entry.cursor.path
                // Encoded, because these names are the operator's, not the
                // app's: `Samsung Health/report (1).pdf` has to survive the
                // query string intact for prime to write the same name the
                // rsync did.
                val encoded = java.net.URLEncoder.encode(name, "UTF-8")
                when (val reply = hub.post("${HubBulk.PHONE}/chunk?lane=$lane&name=$encoded", body)) {
                    is HubBulk.Reply.Ok -> {
                        shipped++
                        highest = entry.cursor
                    }
                    is HubBulk.Reply.Refused -> {
                        // A name prime will not take (too deep, an unexpected
                        // character) is not retryable, and must not hold the
                        // lane up behind it.
                        Events.record(
                            ctx, "media_mirror_refused",
                            "lane", lane, "file", name, "detail", reply.detail.take(120),
                        )
                        if (!MediaScan.terminalRefusal(reply.code)) {
                            persist(lane, highest)
                            note("upload refused: ${reply.code}")
                            return false
                        }
                        highest = entry.cursor
                    }
                    HubBulk.Reply.Unreachable -> {
                        // Everything acknowledged so far still counts, so an
                        // interrupted backlog resumes where it stopped rather
                        // than from the beginning.
                        persist(lane, highest)
                        note("unreachable")
                        return false
                    }
                }
            }
            persist(lane, highest)
            cursor = highest
        } while (scanned >= SCAN_CAP)

        if (shipped > 0) {
            Events.record(ctx, "media_mirrored", "lane", lane, "files", shipped)
        }
        return true
    }

    private fun persist(lane: String, cursor: MediaScan.Cursor) {
        // Both cursor components must be published together.
        prefs().edit().putLong("$lane.cursor.time", cursor.modified)
            .putString("$lane.cursor.path", cursor.path).apply()
    }

    private fun note(reason: String?) {
        if (reason == lastFailure) return
        lastFailure = reason
        if (reason != null) {
            Log.i(Storage.TAG, "media-mirror: paused ($reason)")
            Events.record(ctx, "media_mirror_blocked", "reason", reason)
        } else {
            Events.record(ctx, "media_mirror_ready")
        }
    }

    companion object {
        private const val PREFS = "sinnix-phone-mediamirror"

        /** Lane name on prime -> directory on the device. */
        private val ROOTS = listOf("camera" to "/sdcard/DCIM", "download" to "/sdcard/Download")

        /** Ten minutes: photos are not urgent, and the walk is not free. */
        private const val INTERVAL_MS = 600_000L

        /** Prime accepts four relative path segments beneath the lane root. */
        private const val MAX_DEPTH = 4

        /**
         * How many files one scan may collect. A bound on MEMORY, not on
         * throughput: a full scan is followed by another scan, so a backlog
         * drains continuously and only the list in flight is capped.
         */
        private const val SCAN_CAP = 2_000

        /** Prime's own upload ceiling is 128 MiB; refusing here saves the read. */
        private const val MAX_BYTES = 128L shl 20
    }
}
