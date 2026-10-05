package dev.pam.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.modules.NativeModule
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray

/** PAM module `audio-player`: headless players addressed by id; one plays at a time. */
class AudioPlayerModule(private val context: Context) : NativeModule {
    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            val values = WireMap.decode(payload)
            val id = values.text("playerId")
            if (method == "next") {
                return AudioPlayers.get(id)?.events?.next(completion) ?: completion.failure("Player $id not found")
            }
            AudioPlayers.onMain(completion) {
                when (method) {
                    "play" -> AudioPlayers.play(context, config(id, values))
                    "stop" -> AudioPlayers.stop(id)
                    else -> {
                        val player = AudioPlayers.get(id)?.takeUnless { it.finished } ?: throw IllegalStateException("Player $id is not playing")
                        when (method) {
                            "pause" -> player.pause()
                            "resume" -> player.resume()
                            "seek" -> player.seek(values.integer("position", 0))
                            "skipTo" -> player.skipTo(values.integer("index", 0).toInt())
                            "setRate" -> player.setRate(values.decimal("rate", 1.0).toFloat())
                            "setRoute" -> player.setRoute(values.integer("route", 1).toInt())
                            "setVolume" -> player.setVolume(values.decimal("volume", 1.0).toFloat())
                            else -> throw IllegalArgumentException("Unknown audio player method $method")
                        }
                    }
                }
            }
        }.onFailure { completion.failure(it.message ?: "Audio operation failed") }
    }

    private fun config(id: String, values: Map<String, WireValue>): PlayerConfig {
        val array = JSONArray(values.text("sourcesJson"))
        val sources = List(array.length()) { array.getString(it) }
        require(sources.isNotEmpty() && sources.size <= 200) { "Provide between 1 and 200 sources" }
        sources.forEach { require(SOURCE.matches(it) || (!it.startsWith("/") && !it.contains("..") && !it.contains("://"))) { "Invalid source $it" } }
        return PlayerConfig(
            id = id,
            sources = sources,
            rate = values.decimal("rate", 1.0).toFloat().coerceIn(0.25f, 4f),
            route = values.integer("route", PamAudioPlayer.ROUTE_AUTO.toLong()).toInt(),
            volume = values.decimal("volume", 1.0).toFloat().coerceIn(0f, 1f),
            startAt = values.integer("startAt", 0).coerceAtLeast(0),
            progressInterval = values.integer("progressInterval", 250).coerceIn(50, 5_000),
        )
    }

    private companion object {
        val SOURCE = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    }
}

/** Process-wide player registry. */
object AudioPlayers {
    private val handler = Handler(Looper.getMainLooper())
    private val players = ConcurrentHashMap<String, PamAudioPlayer>()

    fun get(id: String): PamAudioPlayer? = players[id]

    fun current(): PamAudioPlayer? = players.values.firstOrNull { !it.finished }

    internal fun play(context: Context, config: PlayerConfig) {
        players.values.toList().forEach {
            if (!it.finished) it.stop()
            if (it.finished && it.events.pendingCount() == 0) players.remove(it.id)
        }
        players.remove(config.id)?.stop()
        PamAudioPlayer(context, config).also {
            players[config.id] = it
            it.start()
        }
    }

    fun stop(id: String) {
        players.remove(id)?.stop()
    }

    internal fun onMain(completion: ModuleCompletion, block: () -> Unit) {
        val run = Runnable {
            runCatching(block)
                .onSuccess { completion.success() }
                .onFailure { completion.failure(it.message ?: it.javaClass.simpleName) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else handler.post(run)
    }
}

internal fun Map<String, WireValue>.decimal(key: String, fallback: Double): Double = when (val value = get(key)) {
    is WireValue.Decimal -> value.value
    is WireValue.Integer -> value.value.toDouble()
    else -> fallback
}
