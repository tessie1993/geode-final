package dev.geode.render

import android.content.Context
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import dev.geode.util.bestEffort

/**
 * An Android performance-hint session (ADPF) for the GL thread (#286).
 *
 * The system's CPU governor otherwise sees only a thread that wakes once a vsync, and either keeps
 * the cores clocked high for work that fits easily (heat) or lets them sag until a heavy frame
 * misses (a hitch). The session tells it how long each frame has, [FrameCap.frameBudgetNanos] at
 * the rate actually paced, and how long each frame's work really took on the GL thread, so it can
 * clock for exactly that.
 *
 * GL thread only: [open] binds the session to the calling thread, and a new GL thread (a surface
 * recreated after the view was detached) opens a new one.
 */
class FrameWorkHint(
    context: Context,
) {
    private val manager: PerformanceHintManager? =
        context.applicationContext.getSystemService(PerformanceHintManager::class.java)
    private var session: PerformanceHintManager.Session? = null
    private var targetNanos = 0L

    /** Opens a session for the calling (GL) thread at [fps], closing the one an earlier GL thread had. */
    fun open(fps: Float) {
        close()
        val hints = manager ?: return
        targetNanos = FrameCap.frameBudgetNanos(fps)
        // Null where the device offers no hint sessions; the renderer then simply reports to nothing.
        bestEffort(TAG, "createHintSession") { session = hints.createHintSession(intArrayOf(Process.myTid()), targetNanos) }
    }

    /** Follows a change of paced rate (the thermal frame cap engaging or letting go). Cheap when unchanged. */
    fun retarget(fps: Float) {
        val target = FrameCap.frameBudgetNanos(fps)
        if (target == targetNanos) return
        targetNanos = target
        val open = session ?: return
        bestEffort(TAG, "updateTargetWorkDuration") { open.updateTargetWorkDuration(target) }
    }

    /** How long this frame's work took on the GL thread, in nanoseconds. */
    fun report(workNanos: Long) {
        val open = session ?: return
        if (workNanos <= 0L) return
        try {
            open.reportActualWorkDuration(workNanos)
        } catch (gone: IllegalStateException) {
            // The system closed the session under us; stop reporting rather than throw every frame.
            Log.w(TAG, "hint session closed", gone)
            session = null
        }
    }

    fun close() {
        session?.close()
        session = null
    }

    private companion object {
        const val TAG = "FrameWorkHint"
    }
}
