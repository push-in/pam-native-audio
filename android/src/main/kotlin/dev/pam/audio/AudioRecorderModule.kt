package dev.pam.audio

import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.modules.ModuleResultStatus
import dev.pam.nativeapp.modules.NativeModule
import dev.pam.nativeapp.modules.WatchChannel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Voice-note recorder. While recording it holds transient audio focus; losing
 * it (an incoming call, another app recording) interrupts the recording: the
 * recorder stops and keeps the partial file, watchers receive a final
 * `interrupted` event with it, and `stop()` returns it (`cancel()` deletes it).
 */
class AudioRecorderModule(private val context: Context) : NativeModule, AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var focus: Any? = null // AudioFocusRequest (API 26+) or the legacy listener
    /** The partial recording an interruption finished, until stop() or cancel() collects it. */
    private var interrupted: Map<String, WireValue>? = null
    private val nextWatchId = AtomicInteger(1)
    private val watches = ConcurrentHashMap<Int, RecorderWatch>()
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAt = 0L

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            when (method) {
                "start" -> start(completion)
                "stop" -> stop(completion)
                "cancel" -> cancel(completion)
                "discard" -> discard(payload, completion)
                "watch" -> watch(payload, completion)
                "next" -> recorderWatch(payload).channel.next(completion)
                "unwatch" -> {
                    stopWatch(subscription(payload))
                    completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                }
                else -> error("Unknown audio recorder method $method")
            }
        }.onFailure { error ->
            completion.complete(
                ModuleResultStatus.FAILURE,
                (error.message ?: "Audio recorder operation failed").toByteArray(),
            )
        }
    }

    private fun start(completion: ModuleCompletion) {
        require(recorder == null) { "An audio recording is already active" }
        interrupted = null
        require(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        ) { "Microphone permission is required" }

        val recordings = File(context.filesDir, "pam-files/recordings").apply { mkdirs() }
        val file = File.createTempFile("pam-voice-", ".m4a", recordings)
        val next = createMediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        output = file
        recorder = next
        startedAt = SystemClock.elapsedRealtime()
        requestFocus()
        completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (RecorderFocus.interrupts(change)) main.post(::interrupt)
    }

    private fun requestFocus() {
        val manager = audio ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusListener, main)
                .build()
            manager.requestAudioFocus(request)
            focus = request
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(focusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            focus = focusListener
        }
    }

    private fun abandonFocus() {
        val current = focus ?: return
        focus = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && current is AudioFocusRequest) {
            audio?.abandonAudioFocusRequest(current)
        } else {
            @Suppress("DEPRECATION")
            audio?.abandonAudioFocus(focusListener)
        }
    }

    /** Focus lost mid-recording: finish the partial file and tell the watchers. */
    private fun interrupt() {
        val active = recorder ?: return
        val file = output ?: return
        val durationMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        recorder = null
        output = null
        startedAt = 0L
        abandonFocus()
        val finished = runCatching { active.stop() }.isSuccess
        runCatching { active.reset() }
        runCatching { active.release() }
        val recording = if (finished) recordingValues(file, durationMs) else null.also { file.delete() }
        interrupted = recording
        val event = WireMap.encode(
            (recording ?: emptyMap()) + ("interrupted" to WireValue.Flag(true)),
        )
        // Watchers keep their channel (closing would drop the queued event) until PHP unwatches.
        watches.values.forEach {
            main.removeCallbacks(it.runnable)
            it.channel.offer(event)
        }
    }

    private fun recordingValues(file: File, durationMs: Long): Map<String, WireValue> = mapOf(
        "uri" to WireValue.Text(file.toURI().toString()),
        "relativePath" to WireValue.Text("recordings/${file.name}"),
        "fileName" to WireValue.Text(file.name),
        "mimeType" to WireValue.Text("audio/mp4"),
        "durationMs" to WireValue.Integer(durationMs),
        "size" to WireValue.Integer(file.length()),
    )

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

    private fun stop(completion: ModuleCompletion) {
        interrupted?.let { recording ->
            interrupted = null
            stopWatches()
            completion.complete(ModuleResultStatus.SUCCESS, WireMap.encode(recording))
            return
        }
        val active = recorder ?: error("No audio recording is active")
        val file = output ?: error("Audio recording output is unavailable")
        val durationMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        stopWatches()
        recorder = null
        output = null
        startedAt = 0L
        abandonFocus()
        runCatching { active.stop() }.onFailure {
            active.reset()
            active.release()
            file.delete()
            throw IllegalStateException("Audio recording was too short", it)
        }
        active.reset()
        active.release()
        completion.complete(ModuleResultStatus.SUCCESS, WireMap.encode(recordingValues(file, durationMs)))
    }

    private fun cancel(completion: ModuleCompletion) {
        release(deleteOutput = true)
        completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
    }

    private fun discard(payload: ByteArray, completion: ModuleCompletion) {
        val uri = (WireMap.decode(payload)["uri"] as? WireValue.Text)?.value
            ?: error("Audio recording URI is required")
        val file = File(URI(uri)).canonicalFile
        val recordings = File(context.filesDir, "pam-files/recordings").canonicalFile
        require(file.parentFile == recordings && file.name.startsWith("pam-voice-")) {
            "Audio recording URI is outside the recorder sandbox"
        }
        if (file.exists()) require(file.delete()) { "Unable to delete audio recording" }
        completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
    }

    private fun watch(payload: ByteArray, completion: ModuleCompletion) {
        require(recorder != null) { "No audio recording is active" }
        val interval = ((WireMap.decode(payload)["intervalMs"] as? WireValue.Integer)?.value ?: 100)
            .coerceIn(50, 1_000)
        val id = nextWatchId.getAndIncrement()
        val channel = WatchChannel()
        lateinit var runnable: Runnable
        runnable = Runnable {
            val active = recorder
            if (!watches.containsKey(id) || active == null) {
                stopWatch(id)
                return@Runnable
            }
            val amplitude = runCatching { active.maxAmplitude }.getOrDefault(0)
                .toDouble()
                .div(32_767.0)
                .coerceIn(0.0, 1.0)
            channel.offer(
                WireMap.encode(
                    mapOf(
                        "durationMs" to WireValue.Integer(
                            (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
                        ),
                        "amplitude" to WireValue.Decimal(amplitude),
                    ),
                ),
            )
            main.postDelayed(runnable, interval)
        }
        watches[id] = RecorderWatch(channel, runnable)
        main.post(runnable)
        completion.complete(
            ModuleResultStatus.SUCCESS,
            WireMap.encode(mapOf("subscription" to WireValue.Integer(id.toLong()))),
        )
    }

    private fun subscription(payload: ByteArray): Int =
        ((WireMap.decode(payload)["subscription"] as? WireValue.Integer)?.value
            ?: error("Audio recorder subscription is required")).toInt()

    private fun recorderWatch(payload: ByteArray): RecorderWatch =
        watches[subscription(payload)] ?: error("Audio recorder observation not found")

    private fun stopWatch(id: Int) {
        watches.remove(id)?.let {
            main.removeCallbacks(it.runnable)
            it.channel.close()
        }
    }

    private fun stopWatches() {
        watches.keys.toList().forEach(::stopWatch)
    }

    private fun release(deleteOutput: Boolean) {
        stopWatches()
        abandonFocus()
        if (deleteOutput) {
            (interrupted?.get("uri") as? WireValue.Text)?.let { runCatching { File(URI(it.value)).delete() } }
        }
        interrupted = null
        recorder?.let { active ->
            runCatching { active.stop() }
            runCatching { active.reset() }
            runCatching { active.release() }
        }
        recorder = null
        if (deleteOutput) output?.delete()
        output = null
        startedAt = 0L
    }

    override fun close() {
        release(deleteOutput = true)
    }

    internal object RecorderFocus {
        /** A call or another recorder takes the microphone; ducking requests do not. */
        fun interrupts(change: Int): Boolean =
            change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
    }

    private data class RecorderWatch(
        val channel: WatchChannel,
        val runnable: Runnable,
    )
}
