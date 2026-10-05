package dev.pam.audio

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.modules.ModuleResultStatus
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioPlayerInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val module = AudioPlayerModule(context)

    private data class Result(val ok: Boolean, val values: Map<String, WireValue>, val message: String)

    @Before
    fun writeFixtures() {
        val dir = File(context.filesDir, "pam-files/voice").apply { mkdirs() }
        wav(File(dir, "a.wav"), 1_200)
        wav(File(dir, "b.wav"), 900)
        wav(File(dir, "long.wav"), 8_000)
    }

    @After
    fun stopAll() {
        AudioPlayers.current()?.let { player -> onMain { AudioPlayers.stop(player.id) } }
    }

    private fun call(method: String, values: Map<String, WireValue>): Result {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result>()
        module.invoke(method, WireMap.encode(values), ModuleCompletion { status, payload ->
            result.set(if (status == ModuleResultStatus.SUCCESS) Result(true, WireMap.decode(payload), "") else Result(false, emptyMap(), String(payload)))
            latch.countDown()
        })
        assertTrue("$method timed out", latch.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    private fun play(id: String, vararg sources: String, rate: Double = 1.0, route: Int = 1, interval: Int = 100): Result = call(
        "play",
        mapOf(
            "playerId" to WireValue.Text(id),
            "sourcesJson" to WireValue.Text(JSONArray(sources.toList()).toString()),
            "rate" to WireValue.Decimal(rate),
            "route" to WireValue.Integer(route.toLong()),
            "volume" to WireValue.Decimal(1.0),
            "startAt" to WireValue.Integer(0),
            "progressInterval" to WireValue.Integer(interval.toLong()),
        ),
    )

    /** Collects pushed events the way the PHP runtime does: one pending `next` at a time. */
    private fun collect(id: String): LinkedBlockingQueue<Map<String, WireValue>> {
        val queue = LinkedBlockingQueue<Map<String, WireValue>>()
        fun arm() {
            module.invoke("next", WireMap.encode(mapOf("playerId" to WireValue.Text(id))), ModuleCompletion { status, payload ->
                if (status == ModuleResultStatus.SUCCESS) {
                    queue.put(WireMap.decode(payload))
                    arm()
                }
            })
        }
        arm()
        return queue
    }

    private fun awaitEvent(queue: LinkedBlockingQueue<Map<String, WireValue>>, timeoutMillis: Long = 10_000, predicate: (Map<String, WireValue>) -> Boolean): Map<String, WireValue> {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            val event = queue.poll(remaining.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            assertNotNull("event not received", event)
            if (predicate(event!!)) return event
        }
    }

    private fun Map<String, WireValue>.int(key: String) = (this[key] as WireValue.Integer).value

    @Test
    fun queueAdvancesNativelyWithProgressAndEndEvents() {
        assertTrue(play("q1", "voice/a.wav", "voice/b.wav").ok)
        val events = collect("q1")
        awaitEvent(events) { it.int("kind") == 2L && it.int("state") == 3L }
        val progress = awaitEvent(events) { it.int("kind") == 1L && it.int("position") > 0 }
        assertEquals(1200L, progress.int("duration"), )
        val item = awaitEvent(events) { it.int("kind") == 3L }
        assertEquals(1L, item.int("index"))
        awaitEvent(events) { it.int("kind") == 4L }
        assertTrue(AudioPlayers.get("q1")!!.finished)
        assertEquals(null, AudioPlayers.current())
    }

    @Test
    fun pauseResumeSeekAndRateApplyToTheLivePlayer() {
        assertTrue(play("p1", "voice/a.wav", rate = 1.5, interval = 50).ok)
        val events = collect("p1")
        awaitEvent(events) { it.int("kind") == 2L && it.int("state") == 3L }
        val player = AudioPlayers.get("p1")!!
        assertEquals(1.5f, onMain { player.rate() }, 0.001f)
        assertTrue(call("pause", mapOf("playerId" to WireValue.Text("p1"))).ok)
        awaitEvent(events) { it.int("kind") == 2L && it.int("state") == 4L }
        assertFalse(onMain { player.isPlaying() })
        assertTrue(call("seek", mapOf("playerId" to WireValue.Text("p1"), "position" to WireValue.Integer(600))).ok)
        assertTrue(onMain { player.position() } >= 590)
        assertTrue(call("setRate", mapOf("playerId" to WireValue.Text("p1"), "rate" to WireValue.Decimal(2.0))).ok)
        assertEquals(2f, onMain { player.rate() }, 0.001f)
        assertTrue(call("resume", mapOf("playerId" to WireValue.Text("p1"))).ok)
        awaitEvent(events) { it.int("kind") == 4L }
    }

    @Test
    fun startingAnotherPlayerStopsTheCurrentOne() {
        assertTrue(play("one", "voice/a.wav").ok)
        val first = AudioPlayers.get("one")!!
        assertTrue(play("two", "voice/b.wav").ok)
        assertTrue(first.finished)
        assertEquals("two", AudioPlayers.current()?.id)
        assertFalse(call("pause", mapOf("playerId" to WireValue.Text("one"))).ok)
    }

    @Test
    fun autoRouteSwitchesToTheEarpieceNearTheEarAndBack() {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.mode
        assertTrue(play("r1", "voice/long.wav", route = 1).ok)
        val events = collect("r1")
        val player = AudioPlayers.get("r1")!!
        onMain { player.simulateProximity(true) }
        val near = awaitEvent(events) { it.int("kind") == 6L }
        assertEquals(3L, near.int("route"))
        assertTrue(onMain { player.isEarpiece() })
        waitUntil(3_000) { audio.mode == AudioManager.MODE_IN_COMMUNICATION }
        onMain { player.simulateProximity(false) }
        assertEquals(2L, awaitEvent(events) { it.int("kind") == 6L }.int("route"))
        waitUntil(3_000) { audio.mode == before }

        assertTrue(call("setRoute", mapOf("playerId" to WireValue.Text("r1"), "route" to WireValue.Integer(2))).ok)
        onMain { player.simulateProximity(true) }
        assertFalse(onMain { player.isEarpiece() })
    }

    @Test
    fun invalidSourcesFailWithoutCrashing() {
        assertFalse(play("bad", "../secret.wav").ok)
        assertFalse(play("bad", "/data/x.wav").ok)
        assertTrue(play("missing", "voice/missing.wav").ok)
        val events = collect("missing")
        val failure = awaitEvent(events) { it.int("kind") == 5L }
        assertTrue((failure["message"] as WireValue.Text).value.isNotEmpty())
        assertFalse(call("next", mapOf("playerId" to WireValue.Text("nobody"))).ok)
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline && !condition()) Thread.sleep(25)
        assertTrue("Condition not met within ${timeoutMillis}ms", condition())
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            result.set(block())
            latch.countDown()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun wav(file: File, millis: Int) {
        val rate = 16_000
        val samples = rate * millis / 1000
        val data = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples) { data.putShort((sin(2 * PI * 440 * it / rate) * 8_000).toInt().toShort()) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }
        file.writeBytes(header.array() + data.array())
    }
}
