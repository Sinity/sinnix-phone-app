package dev.sinnix.phone.sync

import android.content.Context
import android.util.Log
import dev.sinnix.phone.core.Events
import dev.sinnix.phone.core.Storage
import dev.sinnix.phone.estate.HubClient
import java.io.File
import org.json.JSONObject

/**
 * The spool drains itself.
 *
 * [Outbox] has always written what the operator asked for into a directory and
 * carried on, which is what makes the app usable on a phone that is offline
 * half the time. What it lacked was the second half of that promise: the
 * directory was emptied by prime coming to take it, so an intent written on a
 * train left the phone whenever Termux, ssh, wifi and the drain timer next
 * agreed -- and the receipt the operator was waiting for came no sooner.
 *
 * Now the spool is drained from this side, on the same heartbeat everything
 * else rides. Two kinds of file, two routes:
 *
 * - `intent-*.json` is a request, and goes to /intent, which EXECUTES it. The
 *   same route [dev.sinnix.phone.estate.Transport] posts to when it is live,
 *   with the same object and the same `send_token` -- so an intent that went
 *   out live and was queued anyway executes once and still gets its receipt.
 *   That is the token's entire job and the reason this can be dumb.
 * - blobs (a voice note, a PPG trace, a shared file) and their `.json`
 *   sidecars are data, and go to /chunk on the `estate-outbox` lane, the same
 *   directory in the lake the drain's rsync landed them in.
 *
 * Ordering inside a pair is deliberate: the blob first, the sidecar second,
 * and neither deleted until both are acknowledged. A sidecar that arrives
 * without its blob describes a file prime does not have, and prime's scorer
 * would be right to treat that as a broken record rather than a pending one.
 */
class OutboxUploader(context: Context) {

    private val ctx: Context = context.applicationContext

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "outbox-upload").apply { isDaemon = true }
    }
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    private var lastFailure: String? = null

    private val hub = HubBulk(ctx)
    private val client = HubClient(ctx)

    fun tick() {
        if (!busy.compareAndSet(false, true)) return
        worker.execute {
            try {
                drain()
            } catch (e: Exception) {
                Log.w(Storage.TAG, "outbox-upload: pass failed", e)
            } finally {
                busy.set(false)
            }
        }
    }

    private fun drain() {
        val dir = Storage.estateDir(ctx, Storage.OUTBOX) ?: return note("no outbox directory")
        // `.part` is still being written; `.rejected` is something prime has
        // already refused and this pass must not offer again (nor mistake for
        // a blob, since it no longer ends in .json).
        val files = dir.listFiles { f ->
            f.isFile && !f.name.endsWith(".part") && !f.name.endsWith(".rejected")
        }
            ?.sortedBy { it.name }
            ?: return note("outbox directory unreadable")
        if (files.isEmpty()) return note(null)

        val intents = files.filter { it.name.startsWith("intent-") && it.name.endsWith(".json") }
        val sidecars = files.filter { it.name.endsWith(".json") && !it.name.startsWith("intent-") }
        val blobs = files.filter { !it.name.endsWith(".json") }

        for (intent in intents.take(MAX_PER_TICK)) {
            val body = Storage.readText(intent) ?: continue
            val parsed = try {
                JSONObject(body)
            } catch (e: Exception) {
                // Left in place, loudly: a malformed intent is a bug worth
                // seeing, and deleting the evidence makes it a bug nobody can
                // see. It also cannot be retried into working.
                Events.record(ctx, "intent_unparseable", "file", intent.name)
                continue
            }
            val answer = client.postIntent(parsed) ?: return note("unreachable")
            if (answer.optBoolean("ok", false) || answer.optBoolean("duplicate", false)) {
                intent.delete()
                Events.record(
                    ctx, "intent_delivered",
                    "intent_kind", parsed.optString("kind"),
                    "send_token", parsed.optString("send_token"),
                    "duplicate", answer.optBoolean("duplicate", false),
                )
            } else {
                // Prime answered and said no -- an unknown kind, a steer that
                // failed. Retrying cannot fix that, and this pass runs every
                // twenty seconds, so a refused intent left in place would be
                // re-posted forever and would hold up everything queued behind
                // it. Set aside rather than deleted: the operator asked for
                // this, and the file is the evidence of what was asked.
                val detail = answer.optString("detail").take(200)
                if (intent.renameTo(File(intent.parentFile, intent.name + ".rejected"))) {
                    Events.record(
                        ctx, "intent_rejected",
                        "intent_kind", parsed.optString("kind"),
                        "send_token", parsed.optString("send_token"),
                        "detail", detail,
                    )
                } else {
                    return note("prime refused an intent that will not move aside: $detail")
                }
            }
        }

        // Blobs are archive-sized (a voice note, an IMU window), so they wait
        // for an unmetered network the way ambient chunks do. Intents above do
        // not: they are hundreds of bytes and the operator is waiting.
        if (blobs.isNotEmpty() && !hub.unmeteredOrAllowed()) return note("metered")
        for (blob in blobs.take(MAX_PER_TICK)) {
            val sidecar = sidecars.firstOrNull { it.name == blob.name + ".json" }
            if (!ship(blob)) return
            if (sidecar != null && !ship(sidecar)) return
            blob.delete()
            sidecar?.delete()
            Events.record(ctx, "blob_uploaded", "file", blob.name, "bytes", blob.length())
        }
        // A sidecar whose blob is already gone (the pair was interrupted after
        // the blob's delete) still belongs to prime.
        for (orphan in sidecars.filter { s -> blobs.none { s.name == it.name + ".json" } }) {
            if (!ship(orphan)) return
            orphan.delete()
        }
        note(null)
    }

    /** Upload one file to the outbox lane. False means "stop this pass". */
    private fun ship(file: File): Boolean {
        val body = try {
            file.readBytes()
        } catch (e: Exception) {
            note("read failed: ${file.name}")
            return false
        }
        if (body.isEmpty()) {
            // Not retryable by waiting, and prime rejects an empty body.
            Events.record(ctx, "blob_upload_skipped", "file", file.name, "reason", "empty")
            file.delete()
            return true
        }
        val name = java.net.URLEncoder.encode(file.name, "UTF-8")
        return when (val reply =
            hub.post("${HubBulk.PHONE}/chunk?lane=estate-outbox&name=$name", body)) {
            is HubBulk.Reply.Ok -> true
            is HubBulk.Reply.Refused -> {
                note("prime refused ${file.name}: ${reply.detail.take(120)}")
                false
            }
            HubBulk.Reply.Unreachable -> {
                note("unreachable")
                false
            }
        }
    }

    private fun note(reason: String?) {
        if (reason == lastFailure) return
        lastFailure = reason
        if (reason != null) {
            Log.i(Storage.TAG, "outbox-upload: paused ($reason)")
            Events.record(ctx, "outbox_upload_blocked", "reason", reason)
        } else {
            Events.record(ctx, "outbox_upload_ready")
        }
    }

    companion object {
        /** Enough to clear an ordinary backlog quickly without a long pass. */
        private const val MAX_PER_TICK = 8
    }
}
