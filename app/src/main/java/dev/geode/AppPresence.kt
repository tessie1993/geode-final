package dev.geode

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app is what the person sees: true while any of its activities is started. Studio's queue
 * follows it, so with Settings' Renders keep going off a Render only runs while the app is in front
 * (#28). Watched from the Application's start, so it is right before any feature asks.
 */
object AppPresence : Application.ActivityLifecycleCallbacks {
    private var started = 0
    private val shown = MutableStateFlow(false)

    val inFront: StateFlow<Boolean> = shown.asStateFlow()

    fun watch(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    // Activity callbacks all come on the main thread, so the count needs no lock.
    override fun onActivityStarted(activity: Activity) {
        started++
        shown.value = true
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        shown.value = started > 0
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
}
