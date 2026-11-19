package dev.pam.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.modules.ModuleResultStatus
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioRecorderInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val module = AudioRecorderModule(context)
    private val audio = context.getSystemService(AudioManager::class.java)
    /** AudioFocusRequest on Android 8.0+, the legacy listener before (field typed Any: Android 7 has no such class). */
    private var thief: Any? = null
    private val legacyThief = AudioManager.OnAudioFocusChangeListener { }

    private data class Result(val ok: Boolean, val values: Map<String, WireValue>, val message: String)

    @Before
    fun grant() {
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO").close()
        Thread.sleep(300)
    }

    @After
    fun cleanup() {
        when (val current = thief) {
            null -> Unit
            is AudioManager.OnAudioFocusChangeListener -> @Suppress("DEPRECATION") audio.abandonAudioFocus(current)
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) audio.abandonAudioFocusRequest(current as AudioFocusRequest)
        }
        module.close()
    }

    private fun call(method: String, values: Map<String, WireValue> = emptyMap()): Result {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result>()
        module.invoke(method, WireMap.encode(values), ModuleCompletion { status, payload ->
            result.set(if (status == ModuleResultStatus.SUCCESS) Result(true, if (payload.isEmpty()) emptyMap() else WireMap.decode(payload), "") else Result(false, emptyMap(), String(payload)))
            latch.countDown()
        })
        assertTrue("$method timed out", latch.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    @Test
    fun losingAudioFocusEndsTheRecordingWithThePartialFile() {
        val started = call("start")
        assumeTrue("Microphone unavailable on this device: ${started.message}", started.ok)
        val subscription = (call("watch", mapOf("intervalMs" to WireValue.Integer(100))).values["subscription"] as WireValue.Integer).value
        val events = LinkedBlockingQueue<Map<String, WireValue>>()
        fun arm() {
            module.invoke("next", WireMap.encode(mapOf("subscription" to WireValue.Integer(subscription))), ModuleCompletion { status, payload ->
                if (status == ModuleResultStatus.SUCCESS) {
                    val values = WireMap.decode(payload)
                    events.put(values)
                    if (values["interrupted"] == null) arm()
                }
            })
        }
        arm()
        Thread.sleep(1_500)
        // A call (or any other app) takes transient focus.
        thief = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
                .build()
                .also { audio.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(legacyThief, AudioManager.STREAM_RING, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            legacyThief
        }
        val deadline = System.currentTimeMillis() + 5_000
        var interrupted: Map<String, WireValue>? = null
        while (interrupted == null && System.currentTimeMillis() < deadline) {
            val event = events.poll(500, TimeUnit.MILLISECONDS) ?: continue
            if (event["interrupted"] == WireValue.Flag(true)) interrupted = event
        }
        assertNotNull("interruption not delivered", interrupted)
        val uri = (interrupted!!["uri"] as WireValue.Text).value
        assertTrue(File(URI(uri)).length() > 0)
        assertTrue((interrupted["durationMs"] as WireValue.Integer).value >= 1_000)
        // stop() hands the same partial recording over; a new recording can start.
        val stopped = call("stop")
        assertTrue(stopped.message, stopped.ok)
        assertEquals(uri, (stopped.values["uri"] as WireValue.Text).value)
        assertFalse(call("stop").ok)
        call("discard", mapOf("uri" to WireValue.Text(uri)))
    }
}
