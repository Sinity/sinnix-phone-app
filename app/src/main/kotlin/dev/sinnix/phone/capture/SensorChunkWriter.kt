package dev.sinnix.phone.capture

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.sinnix.phone.core.Storage
import dev.sinnix.phone.sync.Outbox
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Append-only binary sensor chunks. The header anchors Android's monotonic
 * sensor timestamps to wall time; records retain the timestamp supplied by
 * SensorEvent and the original values without quantisation.
 */
class SensorChunkWriter(private val context: Context) {
    private var stream: DataOutputStream? = null
    private var part: File? = null
    private var openedWallMs = 0L
    private var openedElapsedNs = 0L
    private var accelerometerSamples = 0
    private var lightSamples = 0

    @Synchronized fun start() {
        if (stream == null) open()
    }

    @Synchronized fun stop() {
        close()
    }

    @Synchronized fun accelerometer(timestampNs: Long, x: Float, y: Float, z: Float) {
        if (stream == null) open()
        try {
            stream?.writeByte(TYPE_ACCELEROMETER)
            stream?.writeLong(timestampNs)
            stream?.writeFloat(x)
            stream?.writeFloat(y)
            stream?.writeFloat(z)
            accelerometerSamples++
            rotateIfNeeded()
        } catch (e: Exception) {
            Log.w(Storage.TAG, "sensor chunk write failed", e)
            close()
        }
    }

    @Synchronized fun light(timestampNs: Long, lux: Float) {
        if (stream == null) open()
        try {
            stream?.writeByte(TYPE_LIGHT)
            stream?.writeLong(timestampNs)
            stream?.writeFloat(lux)
            lightSamples++
            rotateIfNeeded()
        } catch (e: Exception) {
            Log.w(Storage.TAG, "sensor chunk write failed", e)
            close()
        }
    }

    private fun open() {
        val file = Outbox.blobFile(context, "sensor", "bin") ?: return
        val temporary = File(file.parentFile, file.name + ".part")
        try {
            openedWallMs = System.currentTimeMillis()
            openedElapsedNs = SystemClock.elapsedRealtimeNanos()
            val output = DataOutputStream(BufferedOutputStream(temporary.outputStream()))
            output.write(MAGIC)
            output.writeInt(VERSION)
            output.writeLong(openedWallMs)
            output.writeLong(openedElapsedNs)
            output.writeInt(0)
            output.flush()
            part = temporary
            stream = output
        } catch (e: Exception) {
            Log.w(Storage.TAG, "could not open sensor chunk", e)
            file.delete()
            temporary.delete()
        }
    }

    private fun close() {
        val output = stream ?: return
        stream = null
        try {
            output.flush()
            output.close()
            val source = part ?: return
            val final = File(source.parentFile, source.name.removeSuffix(".part"))
            if (!source.renameTo(final)) return
            Outbox.writeSidecar(
                context,
                final,
                org.json.JSONObject()
                    .put("kind", "sensor_chunk")
                    .put("format", "sinnix.phone.sensor-binary/1")
                    .put("wall_start_ms", openedWallMs)
                    .put("elapsed_start_ns", openedElapsedNs)
                    .put("accelerometer_samples", accelerometerSamples)
                    .put("light_samples", lightSamples),
            )
        } catch (e: Exception) {
            Log.w(Storage.TAG, "could not close sensor chunk", e)
        } finally {
            part = null
            accelerometerSamples = 0
            lightSamples = 0
        }
    }

    private fun rotateIfNeeded() {
        if (System.currentTimeMillis() - openedWallMs >= CHUNK_MILLIS) {
            close()
            open()
        }
    }

    companion object {
        private val MAGIC = byteArrayOf('S'.code.toByte(), 'N'.code.toByte(), 'S'.code.toByte(), 'R'.code.toByte())
        private const val VERSION = 1
        private const val TYPE_ACCELEROMETER = 1
        private const val TYPE_LIGHT = 2
        private const val CHUNK_MILLIS = 5 * 60 * 1000L
    }
}
