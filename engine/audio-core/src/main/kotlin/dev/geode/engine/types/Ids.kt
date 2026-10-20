package dev.geode.engine.types

/**
 * A track, named by its content-uri string: the library key, the queue entry and the Media3 media id.
 *
 * Every id here prints as its raw value, so a string template or a JSON writer that predates the
 * type still writes exactly what it wrote before.
 */
@JvmInline
value class TrackId(
    val value: String,
) {
    override fun toString(): String = value
}

/** One Style as the engine knows it: the scene id the native renderer selects by. */
@JvmInline
value class SceneId(
    val value: String,
) {
    override fun toString(): String = value
}

/** A saved preset, named by the name the user gave it. */
@JvmInline
value class PresetId(
    val value: String,
) {
    override fun toString(): String = value
}
