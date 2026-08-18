package dev.sinnix.phone.sync

import android.content.Context
import android.util.Log
import dev.sinnix.phone.core.Events
import dev.sinnix.phone.core.Storage
import java.io.File
import java.net.URLEncoder

/**
 * The inbound half, fetched rather than received.
 *
 * Prime used to rsync `glance.json`, `steering.json`, receipts, notifications
 * and decks onto the device every drain. That direction is the harder one and
 * always was: prime has to reach a phone that roams, sleeps, sits behind
 * carrier NAT and runs an ssh server only while a terminal app happens to be
 * open, while the phone reaching prime is one HTTP call to a fixed tailnet
 * address that is always up.
 *
 * So the phone comes and takes it. Each pass lists what prime holds, fetches
 * anything whose sha256 the device does not already have, writes it into the
 * same inbox directory [Inbox] and [InboxWatcher] already read, and confirms
 * each entry it landed. Confirmation is what lets prime forget a receipt --
 * and prime deletes only on a sha that matches the file still sitting there,
 * so a truncated download can never consume the copy it failed to deliver.
 *
 * The rendering side did not change at all: files appear in the same place by
 * the same atomic write, the FileObserver fires, and receipts become
 * notifications exactly as before.
 */
class InboxFetcher(context: Context) {

    private val ctx: Context = context.applicationContext

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "inbox-fetch").apply { isDaemon = true }
    }
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile private var lastRunAtMs = 0L
    private var lastFailure: String? = null

    private val hub = HubBulk(ctx)

    /**
     * Fetch if it is time, or [force] when the operator has just opened the app
     * and a thirty-second-old glance is worse than a moment's wait.
     */
    fun tick(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRunAtMs < INTERVAL_MS) return
        if (!busy.compareAndSet(false, true)) return
        lastRunAtMs = now
        worker.execute {
            try {
                fetch()
            } catch (e: Exception) {
                Log.w(Storage.TAG, "inbox-fetch: pass failed", e)
            } finally {
                busy.set(false)
            }
        }
    }

    private fun fetch() {
        val root = Inbox.dir(ctx) ?: return note("no inbox directory")
        val listing = hub.getJson("${HubBulk.PHONE}/inbox") ?: return note("unreachable")
        val files = listing.optJSONArray("files") ?: return note("prime sent no listing")

        var landed = 0
        for (i in 0 until files.length()) {
            val entry = files.optJSONObject(i) ?: continue
            val name = entry.optString("name")
            if (name.isEmpty()) continue
            val sha = entry.optString("sha256")
            val target = File(root, name)
            if (target.isFile && HubBulk.sha256(target.readBytes()).equals(sha, true)) {
                // Already here, byte for byte. Still confirmed: a one-shot
                // whose acknowledgement was lost must not sit on prime
                // forever, and confirming something prime has already
                // forgotten is a no-op it answers ok.
                confirm(name, sha)
                continue
            }
            val encoded = URLEncoder.encode(name, "UTF-8")
            val body = hub.getBytes("${HubBulk.PHONE}/inbox/file?name=$encoded")
                ?: return note("could not fetch $name")
            target.parentFile?.mkdirs()
            // The same atomic write every durable record here gets: the
            // FileObserver fires on the rename, so a watcher never sees a
            // half-written receipt.
            if (!Storage.writeAtomically(target, body)) return note("could not write $name")
            landed++
            confirm(name, sha)
        }
        if (landed > 0) {
            Events.record(ctx, "inbox_fetched", "files", landed)
            // Receipts and notifications that just landed become notifications
            // now rather than at the FileObserver's convenience -- the watcher
            // only runs while the capture service is up.
            Inbox.drainNotifications(ctx)
        }
        note(null)
    }

    private fun confirm(name: String, sha: String) {
        val encoded = URLEncoder.encode(name, "UTF-8")
        hub.post("${HubBulk.PHONE}/inbox/confirm?name=$encoded&sha256=$sha", ByteArray(0), sha)
    }

    private fun note(reason: String?) {
        if (reason == lastFailure) return
        lastFailure = reason
        if (reason != null) {
            Log.i(Storage.TAG, "inbox-fetch: paused ($reason)")
            Events.record(ctx, "inbox_fetch_blocked", "reason", reason)
        } else {
            Events.record(ctx, "inbox_fetch_ready")
        }
    }

    companion object {
        /**
         * A minute. The heartbeat is 20s and this is three of them: receipts
         * are worth arriving promptly, and a listing request against an
         * unreachable prime costs a connect timeout per candidate base, which
         * is not worth paying three times a minute on a phone in a pocket.
         */
        private const val INTERVAL_MS = 60_000L
    }
}
