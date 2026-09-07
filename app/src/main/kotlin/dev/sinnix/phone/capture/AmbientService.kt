package dev.sinnix.phone.capture

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dev.sinnix.phone.R
import dev.sinnix.phone.core.Events
import dev.sinnix.phone.core.Notifications
import dev.sinnix.phone.core.Stamps
import dev.sinnix.phone.core.Storage
import dev.sinnix.phone.sync.InboxWatcher
import java.io.File

/**
 * Continuous ambient audio capture.
 *
 * Runs as a microphone-typed foreground service, which is what lets the
 * recorder keep receiving real samples with the screen off. The service owns a
 * single [MediaRecorder] at a time and rotates it on a fixed wall-clock
 * cadence, producing one chunk file per period.
 *
 * Files are recorded into private storage as `ambient-<UTC>.m4a.part`.
 * After the muxer closes, encoded duration is verified and [ChunkPublisher]
 * publishes a checked copy in shared storage without blocking the encoder.
 * The desktop drain rsyncs the shared directory with
 * `--remove-source-files`, so an in-progress file under its final name would
 * eventually be deleted out from under the open descriptor and lose the
 * trailing moov atom. The suffix, plus an rsync exclude on the desktop side,
 * makes "closed" an explicit state rather than an inference from mtime.
 *
 * This service is also the process that stays alive, so it carries two
 * passengers that need continuous residence and would otherwise each need
 * their own reason to wake the phone: the passive sensor lane and the inbox
 * watcher.
 */
class AmbientService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var wakeLock: PowerManager.WakeLock? = null

    private var recorder: MediaRecorder? = null
    private var currentPart: File? = null
    private var chunkStartedAtMs = 0L
    private var chunkStartedElapsedMs = 0L
    private var recordingSilenced = false
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private var samplingRate = SAMPLING_RATE_LADDER[0]

    private val status = Status()
    private var sensors: AmbientSensors? = null
    private var inbox: InboxWatcher? = null
    private var passive: PassiveLanes? = null
    private var heartRate: HeartRateLane? = null
    private var usage: UsageLane? = null
    // The one transport plane, in four lanes: the event log, the outbox
    // spool, the inbox fetch, and the media mirror. All of them ride this
    // service's heartbeat rather than a schedule of their own, because this
    // process is already awake every 20s and each of them is cheap when there
    // is nothing to do.
    private var eventUploader: dev.sinnix.phone.sync.EventUploader? = null
    private var outboxUploader: dev.sinnix.phone.sync.OutboxUploader? = null
    private var inboxFetcher: dev.sinnix.phone.sync.InboxFetcher? = null
    private var mediaMirror: dev.sinnix.phone.sync.MediaMirror? = null
    private var chunkUploader: ChunkUploader? = null
    private var chunkPublisher: ChunkPublisher? = null
    private var location: dev.sinnix.phone.ingress.LocationLane? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("ambient-rotate").apply { start() }
        handler = Handler(thread.looper)

        val pm = getSystemService(PowerManager::class.java)
        // The foreground service keeps mic access; it does not by itself keep
        // the CPU scheduled tightly enough for the rotation handler to fire on
        // time in Doze. The partial wakelock is what keeps chunk boundaries
        // near 300s instead of drifting to whatever maintenance window is next.
        wakeLock =
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sinnix:ambient")?.apply {
                setReferenceCounted(false)
                acquire()
            }

        status.attach(this)
        liveStatus = status
        sensors = AmbientSensors(this).also { it.start() }
        inbox = InboxWatcher(this).also { it.start() }
        passive = PassiveLanes(this)
        heartRate = HeartRateLane(this)
        usage = UsageLane(this)
        eventUploader = dev.sinnix.phone.sync.EventUploader(this)
        outboxUploader = dev.sinnix.phone.sync.OutboxUploader(this)
        inboxFetcher = dev.sinnix.phone.sync.InboxFetcher(this)
        mediaMirror = dev.sinnix.phone.sync.MediaMirror(this)
        chunkPublisher = ChunkPublisher(this)
        chunkUploader = ChunkUploader(this)
        location = dev.sinnix.phone.ingress.LocationLane(this).also { it.start() }
        // The capture preference lives in credential-protected storage, which
        // a locked boot cannot read. Mirroring it here — from the one place
        // that only runs when capture is actually meant to be on — is what
        // lets the pre-unlock recorder know whether to run at all.
        DirectBoot.mirror(this, dev.sinnix.phone.core.Prefs.enabled(this))
        sweepOrphans()
    }

    /**
     * Retire `.part` files left behind by a previous process.
     *
     * Nothing else writes that suffix, so any one present at startup belongs to
     * a recorder that died without closing its muxer. The drain skips `.part`
     * to avoid deleting a live chunk, which means an orphan would otherwise sit
     * on the phone forever, invisible and never collected.
     *
     * Renamed rather than deleted. An MP4 missing its trailing moov atom is not
     * playable, but it is not nothing either — the samples are there and
     * recoverable — and discarding captured audio on the device's own say-so is
     * not this service's call to make.
     */
    private fun sweepOrphans() {
        val dirs = listOfNotNull(Storage.recordingDir(this), Storage.chunkDir(this))
        for (part in dirs.flatMap { dir -> dir.listFiles { _, name -> name.endsWith(".m4a.part") }.orEmpty().toList() }) {
            preserveFailedPart(part)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // startForeground must happen before any mic access, and within a few
        // seconds of startForegroundService, or the platform kills us with an
        // ANR-shaped crash.
        startForeground(Notifications.ID_ONGOING, buildNotification("starting"))
        running = true
        status.serviceStarted()

        handler.removeCallbacksAndMessages(null)
        handler.post(::rotate)
        handler.postDelayed(::heartbeat, HEARTBEAT_MILLIS)

        Watchdog.schedule(this)

        // START_STICKY so a low-memory kill is followed by a restart with a
        // null intent, which the branch above treats as "start capturing".
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        handler.post {
            closeChunk()
            status.serviceStopped()
            chunkPublisher?.stop()
        }
        sensors?.stop()
        inbox?.stop()
        heartRate?.stop()
        location?.stop()
        usage?.stop()
        thread.quitSafely()
        wakeLock?.takeIf { it.isHeld }?.release()
        liveStatus = null
        super.onDestroy()
    }

    // --- recording ---------------------------------------------------------

    /** Close the open chunk, open the next one, schedule the next rotation. */
    private fun rotate() {
        closeChunk()
        val ok = openChunk()
        handler.postDelayed(::rotate, if (ok) CHUNK_MILLIS else RETRY_MILLIS)
        updateNotification()
    }

    private fun openChunk(): Boolean {
        val dir = Storage.recordingDir(this)
        if (dir == null) {
            status.recordFailure("no writable private recording directory")
            return false
        }
        // compact, NOT iso: this is a FILENAME. Extended ISO carries colons,
        // which sdcardfs rejects outright — every open fails with EPERM and the
        // recorder logs a start failure per attempt while looking otherwise
        // healthy. The lake's ambient-<UTC basic>.m4a convention depends on
        // this shape too.
        val part = File(dir, "ambient-${Stamps.compact(System.currentTimeMillis())}.m4a.part")

        // Start every chunk back at the preferred rate. A codec refusal is
        // often transient (another app holding the encoder), and without this
        // reset one such moment would degrade every remaining chunk until the
        // service restarts. The cost when the refusal is permanent is one
        // failed prepare() per rotation.
        samplingRate = SAMPLING_RATE_LADDER[0]

        for (rate in SAMPLING_RATE_LADDER) {
            samplingRate = rate
            val r = newRecorder()
            r.setOutputFile(part.absolutePath)
            r.setOnErrorListener { _, what, extra ->
                handler.post {
                    if (recorder === r) onRecorderError("error $what/$extra")
                }
            }
            try {
                r.prepare()
                r.start()
            } catch (e: Exception) {
                // Not every codec path accepts every rate. Walk down the ladder
                // rather than dropping capture entirely — a degraded chunk
                // beats a hole.
                release(r)
                if (!preserveFailedPart(part)) {
                    status.recordFailure("cannot preserve failed recording ${part.name}")
                    return false
                }
                Log.w(Storage.TAG, "$rate Hz recorder failed", e)
                if (rate == SAMPLING_RATE_LADDER.last()) {
                    status.recordFailure("recorder start failed: $e")
                    return false
                }
                continue
            }
            recorder = r
            currentPart = part
            chunkStartedAtMs = System.currentTimeMillis()
            chunkStartedElapsedMs = SystemClock.elapsedRealtime()
            recordingSilenced = false
            val callback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    if (recorder !== r) return
                    reportRecordingConfiguration(r)
                }
            }
            recordingCallback = callback
            try {
                r.registerAudioRecordingCallback({ task -> handler.post(task) }, callback)
                reportRecordingConfiguration(r)
            } catch (e: Exception) {
                Log.w(Storage.TAG, "recording configuration unavailable", e)
            }
            status.chunkOpened(part, chunkStartedAtMs, samplingRate)
            return true
        }
        return false
    }

    private fun newRecorder(): MediaRecorder {
        val r =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
            else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(samplingRate)
        // 96 kbps mono AAC, the same archive bitrate the desktop lane encodes
        // at, so a phone chunk and a desktop chunk are the same grade of
        // evidence. ~3.6 MB per 5 min chunk.
        r.setAudioEncodingBitRate(96_000)
        return r
    }

    private fun closeChunk() {
        val r = recorder
        val part = currentPart
        recorder = null
        currentPart = null
        if (r == null) return

        val elapsedMs = (SystemClock.elapsedRealtime() - chunkStartedElapsedMs).coerceAtLeast(0L)
        try {
            recordingCallback?.let { r.unregisterAudioRecordingCallback(it) }
        } catch (e: Exception) {
            Log.w(Storage.TAG, "cannot unregister recording callback", e)
        }
        recordingCallback = null
        var stopped = false
        try {
            r.stop()
            stopped = true
        } catch (e: Exception) {
            Log.w(Storage.TAG, "recorder stop failed", e)
            status.recordFailure("recorder stop failed: $e")
        } finally {
            release(r)
        }

        if (part == null) return
        if (!stopped || !part.isFile || part.length() == 0L) {
            preserveFailedPart(part)
            return
        }
        val mediaMs = mediaDurationMs(part)
        val chunkBytes = part.length()
        val finalFile = File(part.parentFile, stripPart(part.name))
        if (finalFile.exists() || !part.renameTo(finalFile)) {
            Log.w(Storage.TAG, "could not rename $part -> $finalFile")
            status.recordFailure("rename failed for ${part.name}")
            return
        }
        val closedAt = System.currentTimeMillis()
        val complete = CaptureIntegrity.complete(elapsedMs, mediaMs) && !recordingSilenced
        val peak = status.chunkPeak()
        status.chunkClosed(finalFile, closedAt, elapsedMs, mediaMs, complete, chunkBytes)
        // The chunk itself leaves the phone on the next drain, so the coverage
        // record has to outlive it: the ribbon and the hole list are reductions
        // of these lines, not of the directory listing. Peak amplitude travels
        // with it because a chunk that captured nothing is otherwise
        // indistinguishable from one that captured a quiet room.
        Events.record(
            this,
            "chunk_closed",
            "chunk", finalFile.name,
            "started_at", Stamps.iso(chunkStartedAtMs),
            "seconds", (mediaMs ?: 0L) / 1000.0,
            "elapsed_seconds", elapsedMs / 1000.0,
            "closed_at", Stamps.iso(closedAt),
            "duration_verified", mediaMs != null,
            "complete", complete,
            "microphone_silenced", recordingSilenced,
            "bytes", chunkBytes,
            "peak_amplitude", peak,
            "captured_nothing", peak == 0,
            "sampling_rate", samplingRate,
        )
        if (!complete) {
            val detail = "incomplete audio: ${mediaMs ?: -1} ms encoded / $elapsedMs ms elapsed"
            status.recordFailure(detail)
            Events.record(this, "capture_failure", "detail", detail, "chunk", finalFile.name)
            Notifications.alertCaptureBroken(this, detail)
        }
        if (complete && peak > 0) {
            // A chunk with sound in it is the only thing that retires the
            // alarm: "the service is running again" is exactly the claim that
            // was wrong when this whole failure mode was discovered.
            Notifications.clearAlert(this)
        }
        // A closed chunk changes the ribbon and the unbroken count, which is
        // most of what the widget says.
        chunkPublisher?.tick()
        dev.sinnix.phone.ui.widget.SinnixWidget.refresh(this)
    }

    private fun mediaDurationMs(file: File): Long? {
        val metadata = MediaMetadataRetriever()
        return try {
            metadata.setDataSource(file.absolutePath)
            metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            Log.w(Storage.TAG, "cannot verify ${file.name}", e)
            null
        } finally {
            metadata.release()
        }
    }

    private fun preserveFailedPart(part: File): Boolean {
        if (!part.isFile) return true
        if (part.length() == 0L) {
            return part.delete()
        }
        val dest = File(part.parentFile, stripPart(part.name).removeSuffix(".m4a") + "-failed${System.currentTimeMillis()}.m4a.orphan")
        if (part.renameTo(dest)) {
            Events.record(this, "chunk_orphaned", "chunk", dest.name, "bytes", dest.length())
            return true
        }
        Log.e(Storage.TAG, "cannot preserve failed recording ${part.name}")
        return false
    }

    private fun reportRecordingConfiguration(r: MediaRecorder) {
        val config = try {
            r.activeRecordingConfiguration
        } catch (e: Exception) {
            Log.w(Storage.TAG, "recording configuration unavailable", e)
            null
        } ?: return
        recordingSilenced = recordingSilenced || config.isClientSilenced
        Events.record(
            this, "audio_recording_configuration",
            "silenced", config.isClientSilenced,
            "source", config.clientAudioSource,
            "device_type", config.audioDevice?.type ?: -1,
            "sample_rate", config.format.sampleRate,
        )
    }

    private fun onRecorderError(detail: String) {
        Log.w(Storage.TAG, "recorder error: $detail")
        status.recordFailure(detail)
        Events.record(this, "capture_failure", "detail", detail)
        // Interrupt, because this class of failure otherwise announces itself
        // days later as a hole in the archive. Withdrawn again as soon as a
        // chunk closes with sound in it.
        Notifications.alertCaptureBroken(this, detail)
        handler.post {
            handler.removeCallbacksAndMessages(null)
            closeChunk()
            handler.postDelayed(::rotate, RETRY_MILLIS)
            handler.postDelayed(::heartbeat, HEARTBEAT_MILLIS)
        }
    }

    // --- liveness ----------------------------------------------------------

    private fun heartbeat() {
        val size = currentPart?.length() ?: 0L
        status.heartbeat(size, elapsedChunkSeconds(), sampleAmplitude())
        if (recorder != null && CaptureIntegrity.underproducing(elapsedChunkSeconds() * 1000, size)) {
            onRecorderError("audio byte production below expected AAC rate: $size bytes in ${elapsedChunkSeconds()}s")
            return
        }
        // Two lanes that need no schedule of their own: this process is
        // already awake, so power and sleep-estimate readings are free here
        // and would cost a wakeup anywhere else.
        passive?.tick()
        heartRate?.tick()
        usage?.tick()
        // The transport plane. Each lane decides for itself whether this tick
        // is one it acts on: events and the outbox go every heartbeat (they
        // are kilobytes and their value is latency), the inbox fetch every
        // third, the media mirror every thirtieth.
        eventUploader?.tick()
        outboxUploader?.tick()
        inboxFetcher?.tick()
        mediaMirror?.tick()
        // Ships only chunks the recorder has already closed, so it can never
        // race the file this heartbeat is measuring.
        chunkPublisher?.tick()
        chunkUploader?.tick()

        // A chunk whose file has stopped growing means the recorder has stopped
        // producing frames while still believing it is running.
        if (status.stalled()) {
            Log.w(Storage.TAG, "chunk file stopped growing; cycling recorder")
            onRecorderError("chunk file stopped growing")
            return
        }
        // The more dangerous case, and the one file growth cannot see:
        // Android's concurrent-capture arbitration hands the microphone to a
        // foreground app and feeds this one digital silence instead. The
        // encoder keeps running at the same bitrate, so the file grows exactly
        // as it should while the audio is nothing. Amplitude is the only signal
        // that separates them — a live microphone in a silent room still
        // reports its noise floor, so a sustained run of exact zeroes means
        // muted, not quiet.
        if (status.muted()) {
            Log.w(Storage.TAG, "microphone reporting digital silence; cycling recorder")
            onRecorderError("microphone muted (lost capture arbitration?)")
            return
        }
        updateNotification()
        handler.postDelayed(::heartbeat, HEARTBEAT_MILLIS)
    }

    /**
     * Peak amplitude since the previous call, or -1 when unavailable.
     *
     * Deliberately read once per heartbeat and nowhere else: the reading is
     * destructive (it resets the running peak), so a second caller would blind
     * the silence check.
     */
    private fun sampleAmplitude(): Int =
        try {
            recorder?.maxAmplitude ?: -1
        } catch (e: Exception) {
            -1
        }

    private fun elapsedChunkSeconds(): Long =
        if (recorder == null) 0L else (SystemClock.elapsedRealtime() - chunkStartedElapsedMs) / 1000L

    // --- notification ------------------------------------------------------

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val detail =
            if (recorder == null) {
                "not recording — ${status.lastErrorShort()}"
            } else {
                "recording ${elapsedChunkSeconds()}s / ${CHUNK_MILLIS / 1000}s" +
                    " · ${status.chunksClosed()} chunks"
            }
        nm.notify(Notifications.ID_ONGOING, buildNotification(detail))
    }

    private fun buildNotification(detail: String): Notification {
        Notifications.ensureChannels(this)
        return Notification.Builder(this, Notifications.CHANNEL_STATUS)
            .setContentTitle("Sinnix ambient capture")
            .setContentText(detail)
            .setSmallIcon(R.drawable.ic_capture)
            .setOngoing(true)
            .setContentIntent(Notifications.openApp(this, "capture"))
            .build()
    }

    companion object {
        const val ACTION_START = "dev.sinnix.phone.START"
        const val ACTION_STOP = "dev.sinnix.phone.STOP"

        /** Chunk length. Matches the 300s convention the lake already holds. */
        const val CHUNK_MILLIS = 300_000L

        /** Cadence for refreshing the on-device liveness file. */
        const val HEARTBEAT_MILLIS = 20_000L

        /** Backoff after a recorder failure (mic stolen by a call, etc.). */
        const val RETRY_MILLIS = 2_000L

        /**
         * Capture rates in preference order. This is an archive lane, not an
         * ASR feed: 16 kHz caps audio bandwidth at 8 kHz, discarding the room —
         * music, environment, the timbre diarization and speaker identification
         * need — before it is ever written, and nothing downstream can recover
         * a band that was never captured. Lower entries are degraded fallbacks,
         * not alternatives.
         *
         * Verified in the file rather than in the API's own account of itself:
         * 48 kHz yields mean −37.4 dB / max −7.0 dB over a full 300s chunk. A
         * chunk recorded while a second recorder held the microphone came back
         * at mean = max = −91.0 dB — digital silence at full bitrate and exact
         * duration, with prepare() and start() both reporting success. Nothing
         * structural separates those two files; only their samples do.
         */
        private val SAMPLING_RATE_LADDER = intArrayOf(48_000, 44_100, 16_000)

        /** Set by the service itself so the watchdog and UI can ask cheaply. */
        @Volatile var running: Boolean = false

        /**
         * The live [Status] while the service runs, for in-process readers.
         * Out-of-process readers use status.json, which is the real contract;
         * this only spares the UI a file read on every recomposition.
         */
        @Volatile var liveStatus: Status? = null

        fun start(ctx: Context) {
            val i = Intent(ctx, AmbientService::class.java).setAction(ACTION_START)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, AmbientService::class.java).setAction(ACTION_STOP))
        }

        private fun stripPart(name: String): String = name.removeSuffix(".part")

        private fun release(r: MediaRecorder) {
            try {
                r.reset()
            } catch (ignored: Exception) {
                // reset on a dead recorder is not actionable
            }
            try {
                r.release()
            } catch (ignored: Exception) {
                // ditto
            }
        }
    }
}
