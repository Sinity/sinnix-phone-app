package dev.sinnix.phone.ingress

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import dev.sinnix.phone.core.Events

/** Uses the notification-listener grant; observes transport without controlling it. */
class MediaSessions(private val ctx: Context) {
    private val manager = ctx.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(ctx, PhoneNotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val controllers = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var listening = false
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { reconcile(it.orEmpty()) }

    fun start() {
        if (listening) return
        try {
            manager.addOnActiveSessionsChangedListener(listener, component, handler)
            listening = true
            reconcile(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            Events.record(ctx, "signal_unavailable", "source", "media_session", "error", e.javaClass.simpleName)
        }
    }

    fun stop() {
        if (listening) manager.removeOnActiveSessionsChangedListener(listener)
        listening = false
        controllers.values.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        controllers.clear()
    }

    private fun reconcile(active: List<MediaController>) {
        val tokens = active.map { it.sessionToken }.toSet()
        controllers.keys.filter { it !in tokens }.toList().forEach { remove(it) }
        active.forEach { controller ->
            if (controller.sessionToken !in controllers) {
                val callback = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) { record(controller, "metadata", metadata) }
                    override fun onPlaybackStateChanged(state: PlaybackState?) { record(controller, "playback", state = state) }
                    override fun onSessionDestroyed() { remove(controller.sessionToken) }
                }
                controllers[controller.sessionToken] = controller to callback
                controller.registerCallback(callback, handler)
                record(controller, "connected")
            }
        }
        Events.record(ctx, "media_sessions", "count", active.size)
    }

    private fun remove(token: MediaSession.Token) {
        val (controller, callback) = controllers.remove(token) ?: return
        controller.unregisterCallback(callback)
        Events.record(ctx, "media_session", "app", controller.packageName, "reason", "disconnected")
    }

    private fun record(controller: MediaController, reason: String,
        metadata: MediaMetadata? = controller.metadata, state: PlaybackState? = controller.playbackState) {
        Events.record(ctx, "media_session", "app", controller.packageName, "reason", reason,
            "title", metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            "artist", metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            "album", metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            "duration_ms", metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION),
            "state", state?.state, "position_ms", state?.position, "speed", state?.playbackSpeed,
            "actions", state?.actions, "updated_elapsed_ms", state?.lastPositionUpdateTime)
    }
}
