package dev.sinnix.phone.sync

import android.content.Context
import android.util.Log
import dev.sinnix.phone.core.Events
import dev.sinnix.phone.core.Prefs
import dev.sinnix.phone.core.Storage
import java.io.File
import java.io.RandomAccessFile

/**
 * The events plane ships itself, completely, in order.
 *
 * This replaces two things that between them never added up to a complete
 * copy on prime. The drain re-copied today's whole day file every thirty
 * minutes over an ssh transport that dies with Termux -- and one of those day
 * files is 3.5 GB, so "copy it again" was the expensive half of a sync that
 * mostly did not run. Alongside it, a live TCP mirror streamed new lines to
 * the receiver into a *different* lane, starting its cursor at the END of the
 * current file, which meant it was structurally incapable of carrying a
 * backlog and its output could never be treated as the record.
 *
 * One lane replaces both: byte ranges of the day file, pushed to prime's
 * /events route, which pwrites each batch at the offset it declares. That
 * makes the file plane authoritative AND fast -- a batch leaves on the next
 * 20s heartbeat, which is what the mirror was for, while the bytes land in
 * the same day file the lake already holds, which is what the drain was for.
 *
 * Three properties worth stating because each one was a bug in the pair this
 * replaces:
 *
 * - **The cursor is prime's, not ours.** On first sight of a day file the
 *   uploader ASKS how much prime holds instead of assuming zero (which would
 *   re-ship gigabytes the drain already delivered) or assuming the end (which
 *   is what made the mirror lossy). A 409 during a push carries prime's cursor
 *   too, so a lake restored from an older copy heals on the next tick.
 * - **Only whole lines are shipped.** A batch ends at the last newline in
 *   range, so prime's copy is always parseable JSONL rather than sometimes
 *   ending mid-record.
 * - **Nothing is deleted here.** Chunks and blobs are deleted once prime
 *   confirms them because prime is their only reader; the day files are also
 *   the app's own history -- the ribbon, the hole list and the offer policy
 *   are reductions of them -- so an acknowledged day file stays on the device.
 */
class EventUploader(context: Context) {

    private val ctx: Context = context.applicationContext

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "event-upload").apply { isDaemon = true }
    }
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Reported once per transition, so a week offline is one event, not thousands. */
    private var lastFailure: String? = null

    private val hub = HubBulk(ctx)

    fun tick() {
        if (!busy.compareAndSet(false, true)) return
        worker.execute {
            try {
                uploadPending()
            } catch (e: Exception) {
                Log.w(Storage.TAG, "event-upload: pass failed", e)
            } finally {
                busy.set(false)
            }
        }
    }

    private fun prefs() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun cursorOf(name: String): Long = prefs().getLong(name, -1L)

    private fun setCursor(name: String, offset: Long) =
        prefs().edit().putLong(name, offset).apply()

    private fun uploadPending() {
        if (!Prefs.uploadEvents(ctx)) return
        val dir = Storage.estateDir(ctx, Storage.EVENTS) ?: return note("no events directory")
        val files =
            dir.listFiles { f -> f.isFile && DAY_FILE.matches(f.name) }
                ?.sortedBy { it.name }
                ?: return note("events directory unreadable")

        // Oldest first: a phone catching up after days offline should hand
        // prime a history that grows forwards, not one that starts with today
        // and backfills later.
        var budget = MAX_BATCHES_PER_TICK
        for (file in files) {
            if (budget <= 0) break
            val day = file.name.removePrefix(PREFIX).removeSuffix(SUFFIX)
            var cursor = cursorOf(file.name)
            if (cursor < 0) {
                cursor = primeCursor(day) ?: return note("unreachable")
                if (cursor > file.length()) {
                    // Prime holds more of this day than the phone does. Not a
                    // fault to repair from here -- and emphatically not a
                    // reason to overwrite prime with a shorter file.
                    Events.record(
                        ctx, "events_cursor_ahead",
                        "file", file.name, "prime_bytes", cursor, "local_bytes", file.length(),
                    )
                    cursor = file.length()
                }
                setCursor(file.name, cursor)
            }
            while (budget > 0 && cursor < file.length()) {
                val body = readBatch(file, cursor) ?: return note("read failed: ${file.name}")
                if (body.isEmpty()) {
                    // No complete line in range. Ordinarily that is the record
                    // being appended right now, and the next tick ships it --
                    // but a single line longer than the batch cap would stall
                    // this file forever, silently, which is the failure shape
                    // this whole lane exists to stop having.
                    if (file.length() - cursor > MAX_BATCH_BYTES) {
                        Events.record(
                            ctx, "events_upload_stalled",
                            "file", file.name, "at_offset", cursor,
                            "reason", "no line terminator within the batch cap",
                        )
                    }
                    break
                }
                budget--
                when (val reply = hub.post("${HubBulk.PHONE}/events?day=$day&offset=$cursor", body)) {
                    is HubBulk.Reply.Ok -> {
                        cursor += body.size
                        setCursor(file.name, cursor)
                    }
                    is HubBulk.Reply.Refused -> {
                        val expected = reply.body?.optLong("expected_offset", -1L) ?: -1L
                        if (reply.code == CONFLICT && expected >= 0) {
                            // Prime is missing earlier bytes: rewind and
                            // re-send from where it actually is.
                            cursor = expected.coerceAtMost(file.length())
                            setCursor(file.name, cursor)
                            Events.record(
                                ctx, "events_cursor_rewound",
                                "file", file.name, "to_offset", cursor,
                            )
                        } else {
                            return note("prime refused ${reply.code}: ${reply.detail.take(120)}")
                        }
                    }
                    HubBulk.Reply.Unreachable -> return note("unreachable")
                }
            }
        }
        note(null)
    }

    /** How much of that day prime already holds, or null when it did not answer. */
    private fun primeCursor(day: String): Long? {
        val answer = hub.getJson("${HubBulk.PHONE}/events?day=$day") ?: return null
        if (!answer.optBoolean("ok", false)) return null
        return answer.optLong("bytes", 0L)
    }

    /**
     * Bytes from [offset] up to the last complete line within the batch cap.
     *
     * Empty means the only thing available is a line still being appended,
     * which is the ordinary state of the file the app is writing to right now.
     */
    private fun readBatch(file: File, offset: Long): ByteArray? =
        try {
            RandomAccessFile(file, "r").use { input ->
                val want = minOf(MAX_BATCH_BYTES, input.length() - offset).toInt()
                val buffer = ByteArray(want)
                input.seek(offset)
                input.readFully(buffer)
                val end = buffer.lastIndexOf('\n'.code.toByte()) + 1
                if (end <= 0) ByteArray(0) else buffer.copyOf(end)
            }
        } catch (e: Exception) {
            Log.w(Storage.TAG, "event-upload: could not read ${file.name}", e)
            null
        }

    /** Records a state change in the upload path, and only a change. */
    private fun note(reason: String?) {
        if (reason == lastFailure) return
        lastFailure = reason
        if (reason != null) {
            Log.i(Storage.TAG, "event-upload: paused ($reason)")
            Events.record(ctx, "events_upload_blocked", "reason", reason)
        } else {
            Events.record(ctx, "events_upload_ready")
        }
    }

    /** How far behind prime this phone is, in bytes. Rendered, never guessed at. */
    fun pendingBytes(): Long {
        val dir = Storage.estateDir(ctx, Storage.EVENTS) ?: return 0
        val files = dir.listFiles { f -> f.isFile && DAY_FILE.matches(f.name) } ?: return 0
        return files.sumOf { f ->
            val cursor = cursorOf(f.name)
            if (cursor < 0) 0L else (f.length() - cursor).coerceAtLeast(0L)
        }
    }

    companion object {
        private const val PREFS = "sinnix-phone-eventupload"
        private const val PREFIX = "events-"
        private const val SUFFIX = ".jsonl"
        private val DAY_FILE = Regex("^events-\\d{8}\\.jsonl$")

        private const val CONFLICT = 409

        /**
         * 512 KiB a batch, four batches a heartbeat: 2 MiB every 20s, which
         * clears an ordinary day's backlog in a couple of ticks and the 3.5 GB
         * outlier without ever holding more than one batch in memory or
         * monopolising the heartbeat it rides on.
         */
        private const val MAX_BATCH_BYTES = 512L shl 10
        private const val MAX_BATCHES_PER_TICK = 4
    }
}
