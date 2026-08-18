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
 * Which means it needs a way to know what prime already has, and the honest
 * cheap answer is an mtime watermark: the lake holds 149 camera files and
 * 196,946 downloads, put there by an `rsync -a` that preserved this phone's
 * own mtimes, so "newer than the newest file in the lane" is the same
 * question on both sides. The watermark is seeded from prime once per lane
 * and advanced locally after that.
 *
 * The blind spot, stated rather than discovered later: a file that arrives
 * with an OLD mtime -- a download whose server date is preserved, a photo
 * restored from a backup -- is behind the watermark and will not be offered.
 * It is a mirror of a directory the operator can also copy by hand, not a
 * capture lane whose gaps are unrecoverable, and buying the alternative
 * (asking prime about every one of 196,946 names) costs more than the case is
 * worth.
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
            return true // a missing Download directory is not prime's fault
        }
        var watermark = prefs().getLong(lane, -1L)
        if (watermark < 0) {
            val seed = hub.getJson("${HubBulk.PHONE}/lane?lane=$lane")
            if (seed == null) {
                note("unreachable")
                return false
            }
            watermark = seed.optLong("newest_mtime_ms", 0L)
            prefs().edit().putLong(lane, watermark).apply()
            Events.record(
                ctx, "media_mirror_seeded",
                "lane", lane, "watermark_ms", watermark, "prime_files", seed.optInt("files", 0),
            )
        }

        // Keep scanning while the scans keep filling up.
        //
        // One scan is bounded (SCAN_CAP) so the list never holds a whole
        // Downloads directory in memory -- but stopping after one bounded
        // list is what made this a trickle, and twelve files every ten
        // minutes against a lane holding 196,946 of them is a backlog
        // measured in months. A full scan means there is more behind it, so
        // the watermark advances and the next scan returns the next stretch.
        // Re-walking the tree costs seconds of stat calls against transfers
        // measured in gigabytes, which is the right side of that trade.
        var shipped = 0
        var scanned: Int
        do {
            val pending = newerThan(root, watermark)
            scanned = pending.size
            var highest = watermark
            for (file in pending) {
                val length = file.length()
                if (length <= 0L || length > MAX_BYTES) {
                    Events.record(
                        ctx, "media_mirror_skipped",
                        "lane", lane, "file", file.name, "bytes", length,
                    )
                    // Counted as seen: a 200 MB video is not going to shrink,
                    // and holding the watermark back for it would re-walk it
                    // forever.
                    highest = maxOf(highest, file.lastModified())
                    continue
                }
                val body =
                    try {
                        file.readBytes()
                    } catch (e: Exception) {
                        persist(lane, watermark, highest)
                        note("read failed: ${file.name}")
                        return false
                    }
                val name = file.absolutePath.removePrefix(root.absolutePath).trimStart('/')
                // Encoded, because these names are the operator's, not the
                // app's: `Samsung Health/report (1).pdf` has to survive the
                // query string intact for prime to write the same name the
                // rsync did.
                val encoded = java.net.URLEncoder.encode(name, "UTF-8")
                when (val reply = hub.post("${HubBulk.PHONE}/chunk?lane=$lane&name=$encoded", body)) {
                    is HubBulk.Reply.Ok -> {
                        shipped++
                        highest = maxOf(highest, file.lastModified())
                    }
                    is HubBulk.Reply.Refused -> {
                        // A name prime will not take (too deep, an unexpected
                        // character) is not retryable, and must not hold the
                        // lane up behind it.
                        Events.record(
                            ctx, "media_mirror_refused",
                            "lane", lane, "file", name, "detail", reply.detail.take(120),
                        )
                        highest = maxOf(highest, file.lastModified())
                    }
                    HubBulk.Reply.Unreachable -> {
                        // Everything acknowledged so far still counts, so an
                        // interrupted backlog resumes where it stopped rather
                        // than from the beginning.
                        persist(lane, watermark, highest)
                        note("unreachable")
                        return false
                    }
                }
            }
            persist(lane, watermark, highest)
            watermark = maxOf(watermark, highest)
        } while (scanned >= SCAN_CAP)

        if (shipped > 0) {
            Events.record(ctx, "media_mirrored", "lane", lane, "files", shipped)
        }
        return true
    }

    private fun persist(lane: String, watermark: Long, highest: Long) {
        if (highest > watermark) prefs().edit().putLong(lane, highest).apply()
    }

    /**
     * Files under [root] modified after [watermark], oldest first.
     *
     * Oldest first so the watermark only ever advances over files that have
     * actually been offered: sorting the other way would mean one successful
     * upload of today's photo skipping everything behind it.
     */
    private fun newerThan(root: File, watermark: Long): List<File> {
        val out = ArrayList<File>()
        collect(root, watermark, out, 0)
        return out.sortedBy { it.lastModified() }
    }

    private fun collect(dir: File, watermark: Long, out: MutableList<File>, depth: Int) {
        if (depth >= MAX_DEPTH || out.size >= SCAN_CAP) return
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            if (out.size >= SCAN_CAP) return
            when {
                entry.isDirectory -> collect(entry, watermark, out, depth + 1)
                entry.isFile && entry.lastModified() > watermark && !entry.name.startsWith(".") ->
                    out.add(entry)
            }
        }
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

        /** Prime accepts four path segments; the lane root is one of them. */
        private const val MAX_DEPTH = 3

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
