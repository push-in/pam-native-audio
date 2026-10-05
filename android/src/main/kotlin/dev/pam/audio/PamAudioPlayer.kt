package dev.pam.audio

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dev.pam.nativeapp.protocol.WireValue
import java.io.File

internal data class PlayerConfig(
    val id: String,
    val sources: List<String>,
    val rate: Float,
    val route: Int,
    val volume: Float,
    val startAt: Long,
    val progressInterval: Long,
)

/**
 * One ExoPlayer instance with a native queue, a native progress ticker,
 * audio focus, becoming-noisy handling and proximity-driven earpiece routing.
 * Every method must run on the main looper.
 */
@OptIn(UnstableApi::class)
class PamAudioPlayer internal constructor(context: Context, private val config: PlayerConfig) : SensorEventListener {
    val id: String = config.id
    internal val events = EventChannel(capacity = 64)
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val sensors = app.getSystemService(SensorManager::class.java)
    private val power = app.getSystemService(PowerManager::class.java)
    private lateinit var player: ExoPlayer
    private var route = config.route
    private var earpiece = false
    private var near = false
    private var listeningProximity = false
    private var previousMode = AudioManager.MODE_NORMAL
    private var proximityLock: PowerManager.WakeLock? = null
    private var lastState = 0

    @Volatile
    var finished = false
        private set
    private var released = false

    private val ticker = object : Runnable {
        override fun run() {
            emitProgress()
            handler.postDelayed(this, config.progressInterval)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> emitState(STATE_BUFFERING)
                Player.STATE_ENDED -> finish()
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (finished) return
            handler.removeCallbacks(ticker)
            if (isPlaying) {
                emitState(STATE_PLAYING)
                handler.post(ticker)
            } else if (player.playbackState == Player.STATE_READY) {
                emitProgress()
                emitState(STATE_PAUSED)
            }
            updateProximityListening(isPlaying)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
            emit(EVENT_ITEM_CHANGED, "index" to WireValue.Integer(player.currentMediaItemIndex.toLong()))
        }

        override fun onPlayerError(error: PlaybackException) {
            emitState(STATE_FAILED)
            emit(EVENT_FAILURE, "message" to WireValue.Text("${error.errorCodeName}: ${error.message.orEmpty()}"))
            release(ended = true)
        }
    }

    fun start() {
        player = ExoPlayer.Builder(app)
            .setAudioAttributes(attributes(earpiece = false), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(listener)
        player.setMediaSources(config.sources.map(::mediaSource))
        player.playbackParameters = PlaybackParameters(config.rate)
        player.volume = config.volume
        if (config.startAt > 0) player.seekTo(0, config.startAt)
        player.prepare()
        player.playWhenReady = true
        applyRoute()
    }

    fun pause() {
        player.pause()
    }

    fun resume() {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    fun seek(position: Long) {
        player.seekTo(position.coerceAtLeast(0))
        emitProgress()
    }

    fun skipTo(index: Int) {
        require(index in 0 until player.mediaItemCount) { "Queue index out of range" }
        player.seekTo(index, 0)
    }

    fun setRate(rate: Float) {
        player.playbackParameters = PlaybackParameters(rate.coerceIn(0.25f, 4f))
    }

    fun setVolume(volume: Float) {
        player.volume = volume.coerceIn(0f, 1f)
    }

    fun setRoute(route: Int) {
        this.route = route
        updateProximityListening(player.isPlaying)
        applyRoute()
    }

    fun isEarpiece(): Boolean = earpiece

    fun isPlaying(): Boolean = !finished && player.isPlaying

    fun position(): Long = if (finished) 0 else player.currentPosition

    fun rate(): Float = player.playbackParameters.speed

    fun index(): Int = player.currentMediaItemIndex

    /** Test hook equivalent to a proximity sensor change. */
    internal fun simulateProximity(near: Boolean) = onProximity(near)

    fun stop() = release(ended = false)

    private fun finish() {
        if (finished) return
        emitProgress()
        emitState(STATE_ENDED)
        finished = true
        emit(EVENT_ENDED)
        release(ended = true)
    }

    private fun release(ended: Boolean) {
        if (released) {
            if (!ended) events.close()
            return
        }
        released = true
        finished = true
        handler.removeCallbacks(ticker)
        updateProximityListening(false)
        routeToEarpiece(false, report = false)
        player.removeListener(listener)
        player.release()
        if (!ended) events.close()
    }

    private fun mediaSource(source: String): MediaSource {
        val uri = if (source.startsWith("http://", true) || source.startsWith("https://", true)) {
            Uri.parse(source)
        } else {
            val root = File(app.filesDir, "pam-files").canonicalFile
            val file = File(root, source).canonicalFile
            require(file.path.startsWith(root.path + File.separator)) { "Audio path escapes the sandbox" }
            Uri.fromFile(file)
        }
        val factory = if (uri.scheme == "file") DefaultDataSource.Factory(app) else AudioCache.factory(app)
        return ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(uri))
    }

    // region routing

    private fun applyRoute() {
        when (route) {
            ROUTE_SPEAKER -> routeToEarpiece(false)
            ROUTE_EARPIECE -> routeToEarpiece(true)
            else -> routeToEarpiece(near && !headsetConnected())
        }
    }

    private fun updateProximityListening(playing: Boolean) {
        val wanted = playing && route == ROUTE_AUTO && !finished
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (wanted && !listeningProximity && sensor != null) {
            listeningProximity = sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
        } else if (!wanted && listeningProximity) {
            sensors.unregisterListener(this)
            listeningProximity = false
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val range = event.sensor.maximumRange
        onProximity(event.values.firstOrNull()?.let { it < minOf(5f, range) } ?: false)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun onProximity(near: Boolean) {
        this.near = near
        if (route == ROUTE_AUTO && !finished) applyRoute()
    }

    private fun routeToEarpiece(on: Boolean, report: Boolean = true) {
        if (on == earpiece) return
        earpiece = on
        // ExoPlayer only manages focus for media usages; voice routing holds its own transient focus.
        if (!finished) player.setAudioAttributes(attributes(on), !on)
        if (on) requestVoiceFocus() else abandonVoiceFocus()
        if (on) {
            previousMode = audio.mode
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    ?.let(audio::setCommunicationDevice)
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = false
            }
            acquireProximityLock()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
            audio.mode = previousMode
            proximityLock?.let { if (it.isHeld) it.release() }
            proximityLock = null
        }
        if (report) emit(EVENT_ROUTE, "route" to WireValue.Integer(if (on) ROUTE_EARPIECE.toLong() else ROUTE_SPEAKER.toLong()))
    }

    private var voiceFocus: AudioFocusRequest? = null

    private fun requestVoiceFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener({ change: Int ->
                if (change == AudioManager.AUDIOFOCUS_LOSS && !finished) player.pause()
            }, handler)
            .build()
        audio.requestAudioFocus(request)
        voiceFocus = request
    }

    private fun abandonVoiceFocus() {
        voiceFocus?.let(audio::abandonAudioFocusRequest)
        voiceFocus = null
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireProximityLock() {
        if (!power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        proximityLock = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "pam:audio-earpiece").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun headsetConnected(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type in HEADSET_TYPES
    }

    private fun attributes(earpiece: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (earpiece) C.USAGE_VOICE_COMMUNICATION else C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
        .build()

    // endregion

    // region events

    private fun emitProgress() {
        if (finished) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
        events.offer(
            mapOf(
                "kind" to WireValue.Integer(EVENT_PROGRESS.toLong()),
                "position" to WireValue.Integer(player.currentPosition.coerceAtLeast(0)),
                "duration" to WireValue.Integer(duration.coerceAtLeast(0)),
                "buffered" to WireValue.Integer(player.bufferedPosition.coerceAtLeast(0)),
                "index" to WireValue.Integer(player.currentMediaItemIndex.toLong()),
            ),
            coalesce = true,
        )
    }

    private fun emitState(state: Int) {
        if (state == lastState) return
        lastState = state
        emit(EVENT_STATE, "state" to WireValue.Integer(state.toLong()))
    }

    private fun emit(kind: Int, vararg values: Pair<String, WireValue>) {
        events.offer(mapOf("kind" to WireValue.Integer(kind.toLong()), *values))
    }

    // endregion

    companion object {
        const val ROUTE_AUTO = 1
        const val ROUTE_SPEAKER = 2
        const val ROUTE_EARPIECE = 3
        const val STATE_BUFFERING = 2
        const val STATE_PLAYING = 3
        const val STATE_PAUSED = 4
        const val STATE_ENDED = 5
        const val STATE_FAILED = 6
        const val EVENT_PROGRESS = 1
        const val EVENT_STATE = 2
        const val EVENT_ITEM_CHANGED = 3
        const val EVENT_ENDED = 4
        const val EVENT_FAILURE = 5
        const val EVENT_ROUTE = 6

        @SuppressLint("InlinedApi")
        private val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}

/** Shared 64 MiB LRU cache for remote audio (voice notes are fetched once). */
@OptIn(UnstableApi::class)
internal object AudioCache {
    @Volatile
    private var cache: SimpleCache? = null

    @Synchronized
    private fun cache(context: Context): SimpleCache = cache ?: SimpleCache(
        File(context.cacheDir, "pam-audio-cache"),
        LeastRecentlyUsedCacheEvictor(64L * 1024 * 1024),
        StandaloneDatabaseProvider(context),
    ).also { cache = it }

    fun factory(context: Context): CacheDataSource.Factory {
        val app = context.applicationContext
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(false)
        return CacheDataSource.Factory()
            .setCache(cache(app))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(app, http))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
