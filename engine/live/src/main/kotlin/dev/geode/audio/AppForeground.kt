package dev.geode.audio

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Whether any of the app's activities is started, told to [onChanged] on the main thread. Going to the
 * background is told only after [STOP_GRACE_MS], so a rotation, which stops one activity and starts the
 * next, is not a trip to the background. There is no microphone foreground service, so Live input stops
 * when the app does (#297).
 */
internal class AppForeground(
    application: Application,
    private val onChanged: (Boolean) -> Unit,
) {
    private val count = StartedCount(initiallyStarted = startedNow())
    private val handler = Handler(Looper.getMainLooper())
    private var told = count.started
    private val goBackground = Runnable { tell(false) }

    val started: Boolean get() = told

    init {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    if (count.onStart()) {
                        handler.removeCallbacks(goBackground)
                        tell(true)
                    }
                }

                override fun onActivityStopped(activity: Activity) {
                    if (count.onStop()) handler.postDelayed(goBackground, STOP_GRACE_MS)
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    private fun tell(started: Boolean) {
        if (told == started) return
        told = started
        onChanged(started)
    }

    private companion object {
        const val STOP_GRACE_MS = 700L

        fun startedNow(): Boolean {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        }
    }
}

/** How many of the app's activities are started; registered late, it assumes one when the app already shows. */
internal class StartedCount(
    initiallyStarted: Boolean,
) {
    private var count = if (initiallyStarted) 1 else 0

    val started: Boolean get() = count > 0

    /** An activity started; true when it is the first, bringing the app to the foreground. */
    fun onStart(): Boolean {
        count++
        return count == 1
    }

    /** An activity stopped; true when it was the last. A stop never counts below none. */
    fun onStop(): Boolean {
        if (count == 0) return false
        count--
        return count == 0
    }
}
