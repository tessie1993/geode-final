package dev.geode

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.geode.kernel.ErrorChannel
import dev.geode.playback.EngineFailureNotices
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class GeodeApp : Application() {
    val container: GeodeContainer by lazy { GeodeContainer(this) }

    @Inject
    lateinit var errors: ErrorChannel

    override fun onCreate() {
        super.onCreate()
        // Failures caught at the native bridge go to the app-wide error channel for the life of the process.
        EngineFailureNotices.reportTo(errors, describe = { getString(EngineFailureNotices.messageFor(it)) })
        if (BuildConfig.DEBUG) {
            // Logcat echo is a debug convenience; release keeps failures in the
            // ring buffer alone, where the crash report already includes them.
            RingLog.echo = { tag, line -> android.util.Log.w(tag, line) }
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                File(filesDir, "crash-latest.txt").writeText(
                    "Geode ${BuildConfig.VERSION_NAME}\n" +
                        "Thread: ${thread.name}\n\n" +
                        android.util.Log.getStackTraceString(throwable) +
                        "\n\n-- recent non-fatal failures --\n" +
                        RingLog.dump(),
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
