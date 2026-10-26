package dev.geode.engine.audio

/**
 * Where the player's sound comes out, as far as the latency past the output buffer goes; the codes
 * are GEODE_FEED_ROUTE_* in geode_api.h. An output that cannot time itself (no timestamp) is read
 * that much later by the Visual (core/audio/feed/HeardClock.cpp); one that can already counts it.
 */
enum class OutputRoute(
    val code: Int,
) {
    UNKNOWN(0),
    SPEAKER(1),
    WIRED(2),
    USB(3),

    /** Classic Bluetooth audio (A2DP), a couple of hundred milliseconds behind. */
    BLUETOOTH(4),

    /** LE Audio, with its much shorter presentation delay. */
    BLUETOOTH_LE(5),
    HDMI(6),
}
