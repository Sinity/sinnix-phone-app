package dev.sinnix.phone.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import dev.sinnix.phone.core.Prefs
import dev.sinnix.phone.core.Storage
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * The transfer half of the phone's one plane.
 *
 * [dev.sinnix.phone.prime.HubClient] carries the control plane -- small JSON
 * objects, short timeouts, a failed call means "fall back". This carries
 * bytes: event batches up, capture files up, inbox entries down. Different
 * timeouts (a multi-megabyte body over a relayed tailnet is not a screen
 * waiting on a spinner) and, more importantly, a different answer shape.
 *
 * A control-plane call only has to say whether it worked. A transfer has to
 * distinguish three outcomes, because the caller deletes its only copy on one
 * of them:
 *
 * - [Reply.Ok] -- prime says it wrote the bytes. Safe to forget them.
 * - [Reply.Refused] -- prime answered, and said no. The body carries why, and
 *   sometimes what to do instead (an event batch that starts past prime's
 *   cursor comes back with the cursor). Never a reason to delete.
 * - [Reply.Unreachable] -- nobody answered. Retry on the next tick.
 *
 * Collapsing the last two is the bug this class exists to prevent: "refused"
 * and "unreachable" are the same silence from a caller's point of view, and
 * one of them means the phone is holding data prime will never accept until
 * something changes.
 */
class HubBulk(context: Context) {

    private val ctx: Context = context.applicationContext

    private val http =
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.MINUTES)
            .readTimeout(1, TimeUnit.MINUTES)
            .callTimeout(3, TimeUnit.MINUTES)
            // No transparent retry: every caller here already retries on its
            // own schedule, and a silent one underneath just delays the honest
            // answer.
            .retryOnConnectionFailure(false)
            .build()

    sealed class Reply {
        data class Ok(val body: JSONObject) : Reply()
        data class Refused(val code: Int, val body: JSONObject?, val detail: String) : Reply()
        object Unreachable : Reply()
    }

    /**
     * POST bytes to a phone route, trying each hub base in turn.
     *
     * A refusal from the first base ends it -- prime answered, and the second
     * base is the same prime. Only an unreachable first base is worth a second
     * attempt, which is the MagicDNS failure this app has already been bitten
     * by twice.
     */
    fun post(path: String, body: ByteArray, sha: String = sha256(body)): Reply {
        if (!HubAvailability.mayAttempt(ctx)) return Reply.Unreachable
        var last: Reply = Reply.Unreachable
        for (base in Prefs.hubCandidates(ctx)) {
            val reply = postTo(base + path, body, sha)
            if (reply !is Reply.Unreachable) {
                HubAvailability.succeeded()
                return reply
            }
            last = reply
        }
        HubAvailability.failed()
        return last
    }

    private fun postTo(url: String, body: ByteArray, sha: String): Reply =
        try {
            val request =
                Request.Builder()
                    .url(url)
                    .header("X-Sinnix-Sha256", sha)
                    .post(body.toRequestBody(OCTETS))
                    .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = try { JSONObject(text) } catch (e: Exception) { null }
                if (response.isSuccessful && json?.optBoolean("ok", false) == true) {
                    Reply.Ok(json)
                } else {
                    Reply.Refused(
                        response.code,
                        json,
                        json?.optString("detail").orEmpty().ifEmpty { text.take(200) },
                    )
                }
            }
        } catch (e: Exception) {
            Log.i(Storage.TAG, "hub-bulk: $url unreachable: ${e.javaClass.simpleName}")
            Reply.Unreachable
        }

    /** A JSON answer from a phone route, or null when nobody answered. */
    fun getJson(path: String): JSONObject? {
        if (!HubAvailability.mayAttempt(ctx)) return null
        for (base in Prefs.hubCandidates(ctx)) {
            try {
                http.newCall(Request.Builder().url(base + path).get().build()).execute().use { r ->
                    val text = r.body?.string()
                    if (r.isSuccessful && text != null) {
                        HubAvailability.succeeded()
                        return JSONObject(text)
                    }
                }
            } catch (e: Exception) {
                Log.i(Storage.TAG, "hub-bulk: GET $base$path failed: ${e.javaClass.simpleName}")
            }
        }
        HubAvailability.failed()
        return null
    }

    /**
     * Bytes from a phone route, verified against the sha prime declared.
     *
     * Verification here rather than at the call site because the call sites
     * are the ones that then write the bytes into the inbox and act on them: a
     * truncated receipt is a notification with half a sentence in it, and a
     * truncated deck is an instrument that runs wrong.
     */
    fun getBytes(path: String): ByteArray? {
        if (!HubAvailability.mayAttempt(ctx)) return null
        for (base in Prefs.hubCandidates(ctx)) {
            try {
                http.newCall(Request.Builder().url(base + path).get().build()).execute().use { r ->
                    val bytes = r.body?.bytes()
                    if (!r.isSuccessful || bytes == null) return@use
                    val declared = r.header("X-Sinnix-Sha256")
                    if (declared != null && !declared.equals(sha256(bytes), ignoreCase = true)) {
                        Log.w(Storage.TAG, "hub-bulk: $base$path arrived with the wrong sha256")
                        return@use
                    }
                    HubAvailability.succeeded()
                    return bytes
                }
            } catch (e: Exception) {
                Log.i(Storage.TAG, "hub-bulk: GET $base$path failed: ${e.javaClass.simpleName}")
            }
        }
        HubAvailability.failed()
        return null
    }

    /**
     * Whether the network is one that large transfers may use.
     *
     * Same gate the ambient uploader applies, for the same reason: archive
     * traffic waits for wifi, and nothing downstream needs a photo within the
     * hour. Events and intents do not consult this at all -- they are
     * kilobytes and their whole value is latency.
     */
    fun unmeteredOrAllowed(): Boolean {
        if (Prefs.uploadOnMetered(ctx)) return true
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        const val PHONE = "/phone/v1"
        private val OCTETS = "application/octet-stream".toMediaType()

        fun sha256(body: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
    }
}
