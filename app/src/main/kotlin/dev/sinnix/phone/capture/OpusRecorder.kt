package dev.sinnix.phone.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import dev.sinnix.phone.core.Storage
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Microphone to Ogg Opus files, driven from this process.
 *
 * MediaRecorder runs the same encoder and the same Ogg writer inside
 * mediaserver, and moves every 20 ms frame through three processes as it
 * arrives: measured on this phone, that kept 0.9 of a core busy around the
 * clock. Here one thread wakes once a second, takes whatever the microphone
 * buffered, and pushes it through the encoder and the writer in one burst.
 *
 * The microphone stays open across a change of output file, so consecutive
 * chunks are contiguous to the sample: [rotate] finishes one file and starts
 * the next while the audio buffer keeps filling. Each file gets its own
 * encoder instance, because an Opus stream that starts mid-conversation has no
 * valid pre-skip and decodes its first frames against state it never had.
 */
class OpusRecorder(
    private val ctx: Context,
    private val sampleRate: Int,
    private val bitrate: Int,
    /** A failure nobody asked for: the microphone or the encoder died mid-chunk. */
    private val onError: (String) -> Unit,
) {
    private val frameSamples = sampleRate / FRAMES_PER_SECOND
    private val framer = PcmFramer(frameSamples)
    private val block = ShortArray(sampleRate)
    private val peak = AtomicInteger(0)
    private val commands = LinkedBlockingQueue<Command>()

    private var audio: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var dead = false

    /** One request to the capture thread: continue into [file], or stop when null. */
    private class Command(val file: File?) {
        val done = CountDownLatch(1)
        @Volatile var failure: Exception? = null
    }

    /** Opens the microphone and starts writing [file]. Throws when either refuses. */
    @SuppressLint("MissingPermission")
    fun start(file: File) {
        check(thread == null) { "recorder already started" }
        val minimum =
            AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "$sampleRate Hz capture is not supported" }
        val builder =
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minimum, sampleRate * BYTES_PER_SAMPLE * BUFFER_SECONDS))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setContext(ctx)
        val record = builder.build()
        var sink: Sink? = null
        try {
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "the microphone did not start" }
            // The buffer is what makes the once-a-second read safe: it has to
            // hold more than one period plus a file change.
            if (record.bufferSizeInFrames < sampleRate * 2) {
                Log.w(Storage.TAG, "audio buffer holds only ${record.bufferSizeInFrames} frames at $sampleRate Hz")
            }
            sink = Sink(file)
        } catch (e: Exception) {
            sink?.abandon()
            record.release()
            throw e
        }
        audio = record
        val first = sink
        thread = Thread({ run(first) }, "ambient-capture").apply { start() }
    }

    /** Finishes the current file and continues into [file] without dropping a sample. */
    fun rotate(file: File) = send(Command(file))

    /** Finishes the current file and closes the microphone. */
    fun stop() {
        try {
            if (thread != null && !dead) send(Command(null))
        } finally {
            thread?.join(JOIN_MILLIS)
            thread = null
            audio?.let {
                try {
                    it.stop()
                } catch (ignored: Exception) {
                    // already stopped by the platform; release is what matters
                }
                it.release()
            }
            audio = null
        }
    }

    /**
     * Largest absolute sample since the previous call, 0 to 32767.
     *
     * Destructive, like MediaRecorder's getMaxAmplitude: one caller owns it.
     */
    fun takePeak(): Int = peak.getAndSet(0)

    val activeRecordingConfiguration: AudioRecordingConfiguration?
        get() = audio?.activeRecordingConfiguration

    fun registerAudioRecordingCallback(executor: Executor, callback: AudioManager.AudioRecordingCallback) {
        audio?.registerAudioRecordingCallback(executor, callback)
    }

    fun unregisterAudioRecordingCallback(callback: AudioManager.AudioRecordingCallback) {
        audio?.unregisterAudioRecordingCallback(callback)
    }

    private fun send(command: Command) {
        check(thread != null && !dead) { "recorder is not running" }
        commands.put(command)
        // The capture thread fails every queued command on its way out, so a
        // death between the check above and the put still releases this wait.
        if (dead) failPending(IllegalStateException("recorder stopped"))
        if (!command.done.await(COMMAND_WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw IllegalStateException("recorder did not answer within ${COMMAND_WAIT_SECONDS}s")
        }
        command.failure?.let { throw it }
    }

    private fun failPending(cause: Exception) {
        while (true) {
            val command = commands.poll() ?: return
            command.failure = cause
            command.done.countDown()
        }
    }

    private fun run(first: Sink) {
        var sink: Sink? = first
        var asked: Command? = null
        try {
            while (true) {
                val command = commands.poll(TICK_MILLIS, TimeUnit.MILLISECONDS)
                asked = command
                val current = sink ?: throw IllegalStateException("no open file")
                pump(current)
                if (command == null) continue
                sink = null
                if (command.file == null) {
                    framer.flush(current::write)
                    current.finish()
                    command.done.countDown()
                    return
                }
                current.finish()
                sink = Sink(command.file)
                command.done.countDown()
                asked = null
            }
        } catch (e: Exception) {
            Log.w(Storage.TAG, "ambient capture thread failed", e)
            sink?.abandon()
            dead = true
            val waiting = asked
            if (waiting != null && waiting.done.count > 0) {
                // The caller of rotate() or stop() gets the failure directly.
                waiting.failure = e
                waiting.done.countDown()
            } else {
                onError("capture failed: $e")
            }
        } finally {
            dead = true
            failPending(IllegalStateException("recorder stopped"))
        }
    }

    /** Moves everything the microphone has buffered into [sink]. Never waits for audio. */
    private fun pump(sink: Sink) {
        val record = audio ?: throw IllegalStateException("microphone closed")
        while (true) {
            val n = record.read(block, 0, block.size, AudioRecord.READ_NON_BLOCKING)
            if (n < 0) throw IllegalStateException("microphone read failed: $n")
            if (n == 0) break
            var loudest = 0
            for (i in 0 until n) {
                val sample = block[i].toInt()
                val magnitude = if (sample < 0) minOf(-sample, Short.MAX_VALUE.toInt()) else sample
                if (magnitude > loudest) loudest = magnitude
            }
            peak.accumulateAndGet(loudest) { a, b -> maxOf(a, b) }
            framer.add(block, n, sink::write)
            if (n < block.size) break
        }
        sink.submit()
    }

    /** One output file: an encoder instance and the Ogg writer it feeds. */
    private inner class Sink(file: File) {
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        private var muxer: MediaMuxer? = null
        private val info = MediaCodec.BufferInfo()
        private var track = -1
        private var framesQueued = 0L
        private var ended = false

        // Frames wait here until the read that produced them is over, then go
        // to the encoder together: every encoder call is a round trip to its
        // looper thread, and one per 20 ms frame cost more than the encoding.
        private val staged = ShortArray(frameSamples * FRAMES_PER_SECOND)
        private var stagedFrames = 0

        init {
            try {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1)
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, staged.size * BYTES_PER_SAMPLE)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            } catch (e: Exception) {
                abandon()
                throw e
            }
        }

        /** Takes one 20 ms frame; it reaches the encoder at the next [submit]. */
        fun write(frame: ShortArray) {
            System.arraycopy(frame, 0, staged, stagedFrames * frameSamples, frameSamples)
            stagedFrames++
            if (stagedFrames * frameSamples == staged.size) submit()
        }

        /**
         * Hands the staged frames to the encoder, as many whole frames per
         * input buffer as it holds, taking finished packets out whenever it is
         * full. The encoder cuts its input back into 20 ms packets itself.
         */
        fun submit() {
            val deadline = System.nanoTime() + STALL_NANOS
            var sent = 0
            while (sent < stagedFrames) {
                val index = codec.dequeueInputBuffer(INPUT_WAIT_MICROS)
                if (index < 0) {
                    drain(0)
                    check(System.nanoTime() < deadline) { "encoder accepted no input for ${STALL_NANOS / 1_000_000_000}s" }
                    continue
                }
                val buffer = codec.getInputBuffer(index) ?: throw IllegalStateException("encoder input buffer missing")
                buffer.clear()
                val frames = minOf(stagedFrames - sent, buffer.remaining() / (frameSamples * BYTES_PER_SAMPLE))
                check(frames > 0) { "encoder input buffer too small" }
                buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(staged, sent * frameSamples, frames * frameSamples)
                codec.queueInputBuffer(
                    index, 0, frames * frameSamples * BYTES_PER_SAMPLE, framesQueued * FRAME_MICROS, 0,
                )
                framesQueued += frames
                sent += frames
            }
            stagedFrames = 0
            drain(0)
        }

        /** Writes every packet the encoder has ready; waits up to [waitMicros] for the next one. */
        private fun drain(waitMicros: Long) {
            while (!ended) {
                val index = codec.dequeueOutputBuffer(info, waitMicros)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(track < 0) { "encoder changed its output format mid-file" }
                        val writer = muxer ?: throw IllegalStateException("writer closed")
                        track = writer.addTrack(codec.outputFormat)
                        writer.start()
                    }
                    index >= 0 -> {
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0) {
                            check(track >= 0) { "encoder produced audio before its stream header" }
                            val packet = codec.getOutputBuffer(index) ?: throw IllegalStateException("encoder output buffer missing")
                            muxer?.writeSampleData(track, packet, info)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
                        codec.releaseOutputBuffer(index, false)
                    }
                    else -> return
                }
            }
        }

        /** Ends the stream, writes the packets still inside the encoder, and closes the file. */
        fun finish() {
            try {
                submit()
                val deadline = System.nanoTime() + STALL_NANOS
                var signalled = false
                while (!ended) {
                    if (!signalled) {
                        val index = codec.dequeueInputBuffer(INPUT_WAIT_MICROS)
                        if (index >= 0) {
                            codec.queueInputBuffer(
                                index, 0, 0, framesQueued * FRAME_MICROS, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            signalled = true
                        }
                    }
                    drain(INPUT_WAIT_MICROS)
                    check(System.nanoTime() < deadline) { "encoder did not finish within ${STALL_NANOS / 1_000_000_000}s" }
                }
                check(track >= 0) { "nothing was encoded" }
                muxer?.stop()
            } finally {
                abandon()
            }
        }

        /** Releases the encoder and the writer without completing the file. */
        fun abandon() {
            try {
                codec.release()
            } catch (ignored: Exception) {
                // a codec that will not release has nothing left to give back
            }
            try {
                muxer?.release()
            } catch (ignored: Exception) {
                // the bytes already written stay in the file either way
            }
            muxer = null
        }
    }

    companion object {
        private const val FRAMES_PER_SECOND = 50
        private const val FRAME_MICROS = 1_000_000L / FRAMES_PER_SECOND
        private const val BYTES_PER_SAMPLE = 2

        /** How often the capture thread wakes to move audio. */
        private const val TICK_MILLIS = 1_000L

        /** Microphone buffer: several ticks, so a slow file change or a stalled tick loses nothing. */
        private const val BUFFER_SECONDS = 5

        private const val INPUT_WAIT_MICROS = 10_000L
        private const val STALL_NANOS = 5_000_000_000L
        private const val COMMAND_WAIT_SECONDS = 15L
        private const val JOIN_MILLIS = 5_000L
    }
}
