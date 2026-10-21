package dev.geode.engine.bridge

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hands [EngineFailure]s to whoever listens: the app-wide error channel, and the audio, which restarts
 * itself after an engine failure. Listeners run on the thread of the call that failed, so they hand
 * their work on rather than doing it there.
 */
open class FailureRelay {
    private val listeners = CopyOnWriteArrayList<(EngineFailure) -> Unit>()

    /** Starts telling [listener] about every failure; closing the result stops it. */
    fun listen(listener: (EngineFailure) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    /**
     * The failure a status [code] returned by [call] stands for, or null when it is OK. It is reported
     * here unless the bridge already reported it when it caught the throw.
     */
    fun check(
        code: Int,
        call: String,
    ): EngineFailure? {
        val failure = EngineFailure.of(code, call) ?: return null
        if (!failure.caughtAtTheBridge) report(failure)
        return failure
    }

    /** The bridge caught a throw inside [call] and mapped it to [code]: always reported. */
    fun caught(
        code: Int,
        call: String,
    ) {
        report(EngineFailure.of(code, call) ?: EngineFailure.EngineCrashed(call))
    }

    private fun report(failure: EngineFailure) {
        listeners.forEach { it(failure) }
    }
}

/** The process-wide relay the native bridge reports to (jni_guard.cpp calls [onCaught]). */
object NativeFailures : FailureRelay() {
    /** Called from native code only, by name: see app/src/main/cpp/jni_guard.cpp and the R8 rules. */
    @JvmStatic
    fun onCaught(
        code: Int,
        call: String,
    ) {
        caught(code, call)
    }
}
