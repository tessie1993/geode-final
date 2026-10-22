package dev.geode.engine.bridge

/** The part of the engine a bridge call belongs to, read from the call's name in [GeodeNative]. */
enum class EngineSubsystem(
    private val prefixes: List<String>,
) {
    /** Playback: the native player and its DSP chain. */
    AUDIO(listOf("player", "dsp")),

    /** The live and offline analysis behind the Sound signals. */
    ANALYSIS(listOf("analysis", "drums", "pulse")),

    /** The Visual's renderer. */
    VISUAL(listOf("viz")),

    /** Tag reading and writing. */
    LIBRARY(listOf("tags")),

    /** The Customization snapshots on their way to the engine. */
    CUSTOMIZATION(listOf("snapshots")),

    /** Anything else: the engine as a whole. */
    ENGINE(emptyList()),
    ;

    companion object {
        /** The subsystem whose calls start like [call]; [ENGINE] for any other call. */
        fun of(call: String): EngineSubsystem =
            entries.firstOrNull { subsystem -> subsystem.prefixes.any { call.startsWith(it) } } ?: ENGINE
    }
}

/**
 * A named failure from a native bridge call (ADR-0005): what Kotlin makes of a [NativeStatus] other
 * than [NativeStatus.OK]. [call] is the [GeodeNative] function that failed.
 */
sealed interface EngineFailure {
    val call: String

    val subsystem: EngineSubsystem get() = EngineSubsystem.of(call)

    /** True when the bridge's guard caught a throw and reported it itself, before the call returned. */
    val caughtAtTheBridge: Boolean get() = false

    /** The handle was 0 or an argument was unusable; the engine did nothing. */
    data class InvalidCall(
        override val call: String,
        val status: NativeStatus,
    ) : EngineFailure

    /** A Customization snapshot was not taken: malformed, from another schema version, or crowded out. */
    data class SnapshotRefused(
        override val call: String,
        val status: NativeStatus,
    ) : EngineFailure

    /** The engine could not allocate. */
    data class OutOfMemory(
        override val call: String,
    ) : EngineFailure {
        override val caughtAtTheBridge: Boolean get() = true
    }

    /** The engine threw inside the call; the bridge caught it and abandoned the call. */
    data class EngineCrashed(
        override val call: String,
    ) : EngineFailure {
        override val caughtAtTheBridge: Boolean get() = true
    }

    /** A status this build's schema does not know: a newer engine, or a broken one. */
    data class UnknownStatus(
        override val call: String,
        val code: Int,
    ) : EngineFailure

    companion object {
        /** The failure [code] stands for when [call] returned it; null for [NativeStatus.OK]. */
        fun of(
            code: Int,
            call: String,
        ): EngineFailure? {
            val status = NativeStatus.of(code) ?: return UnknownStatus(call, code)
            return when (status) {
                NativeStatus.OK -> null
                NativeStatus.NULL_HANDLE, NativeStatus.INVALID_ARGUMENT -> InvalidCall(call, status)
                NativeStatus.MALFORMED_SNAPSHOT,
                NativeStatus.UNSUPPORTED_VERSION,
                NativeStatus.BUSY,
                -> SnapshotRefused(call, status)
                NativeStatus.OUT_OF_MEMORY -> OutOfMemory(call)
                NativeStatus.ENGINE_FAILURE -> EngineCrashed(call)
            }
        }
    }
}
