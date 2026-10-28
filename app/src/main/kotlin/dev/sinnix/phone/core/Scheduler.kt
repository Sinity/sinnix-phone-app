package dev.sinnix.phone.core

import android.content.Context
import android.util.Log
import dev.sinnix.phone.ingress.HealthLane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Non-capture periodic work, driven by the existing watchdog alarm. */
object Scheduler {

    /** Called from the watchdog receiver, every ~10 minutes. */
    fun tick(ctx: Context) {
        maybeSyncHealth(ctx)
        // Prime's way back in. Rides this tick for the same reason everything
        // else does, and because the failure it repairs is only observable
        // from the device -- prime cannot ask a phone it cannot reach.
        dev.sinnix.phone.prime.TransportGuard.tick(ctx)
        dev.sinnix.phone.ui.widget.SinnixWidget.refresh(ctx)
    }

    /**
     * Pull whatever the band has written since the last look.
     *
     * Every tick, not hourly. It used to be hourly on the reasoning that
     * Health Connect writes in batches when the band syncs, so asking more
     * often would mostly ask a database that had not changed -- true, and
     * irrelevant: the query is against a local database and costs nothing,
     * while the hour it saved was an hour of latency on every reading. Ten
     * minutes is now the whole phone-side lag, and the rest is Mi Fitness's
     * own band-sync cadence, which is not ours to set.
     *
     * Nothing is stamped here any more. The lane keeps its own changes token
     * and only advances it on a successful read, which is what makes a failed
     * or unpermitted sync retry instead of silently skipping the window it
     * could not read -- the old stamp was written BEFORE the attempt, so an
     * hour's data was dropped every time a sync failed for any reason.
     */
    private fun maybeSyncHealth(ctx: Context) {
        if (!Prefs.healthLane(ctx)) {
            Log.i(Storage.TAG, "health lane: off, skipping")
            return
        }
        // Unconditional, because the absence of any health event was
        // indistinguishable from the tick never running -- and it turned out
        // to be worth knowing which.
        Log.i(Storage.TAG, "health lane: syncing")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val n = HealthLane.sync(ctx)
                if (n > 0) Log.i(Storage.TAG, "health lane: $n record(s)")
            } catch (e: Exception) {
                Log.w(Storage.TAG, "health sync failed", e)
            }
        }
    }

}
