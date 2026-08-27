package dev.sinnix.phone.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock

/** Bounds work while the tailnet or prime is unavailable. */
internal object HubAvailability {
    private const val INITIAL_BACKOFF_MS = 20_000L
    private const val MAX_BACKOFF_MS = 10 * 60_000L

    private val lock = Any()
    private var failures = 0
    private var retryAtMs = 0L

    fun mayAttempt(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        synchronized(lock) { return SystemClock.elapsedRealtime() >= retryAtMs }
    }

    fun succeeded() = synchronized(lock) {
        failures = 0
        retryAtMs = 0L
    }

    fun failed() = synchronized(lock) {
        failures = (failures + 1).coerceAtMost(6)
        val delay = (INITIAL_BACKOFF_MS shl (failures - 1)).coerceAtMost(MAX_BACKOFF_MS)
        retryAtMs = SystemClock.elapsedRealtime() + delay
    }
}
