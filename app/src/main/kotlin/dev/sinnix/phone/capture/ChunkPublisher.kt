package dev.sinnix.phone.capture

import android.content.Context
import android.util.Log
import dev.sinnix.phone.core.Events
import dev.sinnix.phone.core.Storage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Shared-storage latency must never block the live encoder. */
class ChunkPublisher(context: Context) {
    private val ctx = context.applicationContext
    private val busy = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "chunk-publish") }

    fun tick() {
        if (!busy.compareAndSet(false, true)) return
        worker.execute {
            try {
                val source = Storage.recordingDir(ctx)
                val target = Storage.chunkDir(ctx)
                if (source != null && target != null) {
                    for (file in source.listFiles().orEmpty().sortedBy { it.name }) {
                        if (!file.isFile || !(file.name.endsWith(".m4a") || file.name.endsWith(".orphan"))) continue
                        VerifiedChunkCopy.publish(file, File(target, file.name))
                        Events.record(ctx, "chunk_published", "chunk", file.name)
                    }
                }
            } catch (e: Exception) {
                Log.w(Storage.TAG, "chunk publication failed; private copy retained", e)
                Events.record(ctx, "chunk_publish_failed", "detail", e.toString())
            } finally {
                busy.set(false)
            }
        }
    }

    fun stop() { worker.shutdown() }
}
