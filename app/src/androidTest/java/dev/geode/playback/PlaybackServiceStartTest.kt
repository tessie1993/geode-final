package dev.geode.playback

import android.content.ComponentName
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Playing flips isPlaying on, then something (a Bluetooth headset connecting, a transient focus
 * loss) can flip it off again before Media3 promotes the service. A start that promised Android a
 * foreground service then never gets its startForeground(), and Android kills the app with
 * ForegroundServiceDidNotStartInTimeException. Asking for the service while nothing plays is the
 * smallest form of that race: the service must come up and the app must survive the window.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackServiceStartTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun askingForTheServiceWhileNothingPlaysDoesNotCrashTheApp() {
        instrumentation.runOnMainSync { PlaybackService.ensureRunning(context) }

        // Android 12 allows ten seconds for startForeground(); outlast it.
        Thread.sleep(FOREGROUND_WINDOW_MS)

        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        var controller: MediaController? = null
        instrumentation.runOnMainSync {
            val future = MediaController.Builder(context, token).buildAsync()
            future.addListener({ controller = future.get() }, context.mainExecutor)
        }
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(CONNECT_TIMEOUT_S)
        while (controller == null && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        assertNotNull("the playback session never answered", controller)
        instrumentation.runOnMainSync {
            controller?.release()
            PlaybackService.stop(context)
        }
    }

    private companion object {
        const val FOREGROUND_WINDOW_MS = 15_000L
        const val CONNECT_TIMEOUT_S = 10L
        const val POLL_MS = 100L
    }
}
