package dev.sinnix.phone.capture

import android.content.Context
import android.util.Log
import dev.sinnix.phone.core.Events
import dev.sinnix.phone.core.Prefs
import dev.sinnix.phone.core.Storage
import dev.sinnix.phone.sync.HubBulk

/**
 * The archive ships itself.
 *
 * Ambient chunks used to leave this phone only when prime came and took them:
 * a half-hourly drain that ssh'd into Termux and rsynced `/sdcard/sinnix-
 * ambient`. That made the largest data path here depend on the least
 * reliable component in it -- Termux does not survive a reboot, its sshd only
 * runs while it does, and a storage-blind sshd answers rsync with an empty
 * listing and exit 0. Every one of those failures is silent on the phone and
 * looks like "no audio yet" on prime.
 *
 * A finalized chunk is a complete, immutable file that already knows it is
 * done (the recorder renames `.part` away only after a successful stop), so
 * there is nothing to coordinate: this uploads it, prime verifies the hash it
 * was given, and only an ok deletes the local copy. That last clause is the
 * whole safety argument -- a lost response, an unreachable prime, a truncated
 * body, a full disk on the other side all leave the audio exactly where it
 * was, to be retried on the next heartbeat.
 *
 * HTTP through the hub rather than the receiver's socket, deliberately. The
 * receiver speaks line-delimited JSON sized for VAD utterances; a 3.6 MB
 * archive file base64'd through it would be a second bulk-transfer mechanism
 * built on a protocol that was not designed for one. The hub already carries
 * this phone's control plane, already has a route, and answers with a status
 * code -- which is exactly the acknowledgement a delete-after-send needs.
 *
 * Unmetered-only by default, and that is the honest replacement for the
 * drain's `termux-wifi-connectioninfo` gate: continuous ambient audio is
 * ~43 MB/hour, which is archive traffic, not the live signals the speech and
 * event lanes push regardless of network. The chunks wait; they do not
 * expire.
 */
class ChunkUploader(context: Context) {

    private val ctx: Context = context.applicationContext

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "chunk-upload").apply { isDaemon = true }
    }
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Reported once per transition, so a week offline is one event, not thousands. */
    private var lastFailure: String? = null

    // The transfer client every bulk lane shares. Its timeouts are this
    // lane's old ones -- minutes, not seconds, because a multi-megabyte body
    // over a relayed tailnet is not a screen waiting on a spinner -- and its
    // three-outcome answer is this lane's old distinction between "prime said
    // no" and "nobody answered", which is the difference between keeping a
    // chunk and losing it.
    private val hub = HubBulk(ctx)

    fun tick() {
        if (!busy.compareAndSet(false, true)) return
        worker.execute {
            try {
                uploadPending()
            } catch (e: Exception) {
                Log.w(Storage.TAG, "chunk-upload: pass failed", e)
            } finally {
                busy.set(false)
            }
        }
    }

    private fun uploadPending() {
        if (!Prefs.uploadChunks(ctx)) return
        if (!hub.unmeteredOrAllowed()) return note("metered")
        val dir = Storage.chunkDir(ctx) ?: return note("no chunk directory")

        val pending =
            dir.listFiles { f -> f.isFile && shippable(f.name) }
                ?.sortedBy { it.name }
                ?.take(MAX_PER_TICK)
                ?: return note("chunk directory unreadable")
        if (pending.isEmpty()) return note(null)

        for (chunk in pending) {
            val length = chunk.length()
            if (length <= 0L || length > MAX_BYTES) {
                // Neither is retryable by waiting, and both are worth seeing
                // in the events plane rather than silently skipping forever.
                Events.record(ctx, "chunk_upload_skipped", "chunk", chunk.name, "bytes", length)
                continue
            }
            val body = try {
                chunk.readBytes()
            } catch (e: Exception) {
                note("read failed: ${e.javaClass.simpleName}")
                return
            }
            if (!upload(chunk.name, body)) return
            // Verified landed. The file has served its purpose here.
            if (chunk.delete()) {
                Events.record(ctx, "chunk_uploaded", "chunk", chunk.name, "bytes", length)
            } else {
                // Prime has it; a chunk that will not delete would be uploaded
                // again forever, so say so loudly and stop this pass.
                Events.record(ctx, "chunk_upload_undeletable", "chunk", chunk.name)
                return
            }
        }
        note(null)
    }

    /**
     * True only for a body prime confirmed it wrote.
     *
     * A 2xx alone is not that confirmation, and neither is silence: the route
     * answers `ok:false` for a hash mismatch, an unknown lane and a name it
     * will not accept, and each of those must leave the local copy in place
     * exactly as an unreachable prime does. [HubBulk] keeps those two apart,
     * and tries the second hub base only when the first did not answer at all
     * -- a refusal comes from the same prime either way, so asking it twice
     * would only slow the honest answer down.
     */
    private fun upload(name: String, body: ByteArray): Boolean =
        when (val reply = hub.post("${HubBulk.PHONE}/chunk?lane=ambient&name=$name", body)) {
            is HubBulk.Reply.Ok -> true
            is HubBulk.Reply.Refused -> {
                note("prime refused ${reply.code}: ${reply.detail.take(120)}")
                false
            }
            HubBulk.Reply.Unreachable -> {
                note("unreachable")
                false
            }
        }

    /** Records a state change in the upload path, and only a change. */
    private fun note(reason: String?) {
        if (reason == lastFailure) return
        lastFailure = reason
        if (reason != null) {
            Log.i(Storage.TAG, "chunk-upload: paused ($reason)")
            Events.record(ctx, "chunk_upload_blocked", "reason", reason)
        } else {
            Events.record(ctx, "chunk_upload_ready")
        }
    }

    /**
     * A chunk is shippable exactly when the recorder is finished with it.
     *
     * `.part` is a file still being written and is the one thing that must
     * never leave. `.orphan` is a recording a crash cut short -- the recorder
     * keeps it rather than deleting it, and so does this: truncated audio is
     * still audio.
     */
    private fun shippable(name: String): Boolean =
        !name.endsWith(".part") &&
            !name.startsWith("status.json") &&
            (name.endsWith(".m4a") || name.endsWith(".m4a.orphan"))

    companion object {
        /**
         * Four per heartbeat (20s) drains a backlog at roughly 12 chunks a
         * minute -- an hour of accumulated audio clears in about a minute --
         * while leaving the recorder's own thread untouched, since this runs
         * on its own worker and one pass never holds more than one chunk in
         * memory.
         */
        private const val MAX_PER_TICK = 4

        /** A 5-minute chunk is ~3.6 MB; anything past this is not one. */
        private const val MAX_BYTES = 64L shl 20
    }
}
