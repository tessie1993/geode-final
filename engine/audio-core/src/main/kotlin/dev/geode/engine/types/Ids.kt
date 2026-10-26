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

/**
 * Every MilkDrop preset in the MilkDrop folder is a Style of its own (#305, as decided on #189): it is
 * chosen, recorded and switched to like any other Style, and carries the Shared core like any other.
 * Its id is [PREFIX] and the preset's file name, so [of] and [file] go back and forth between the two.
 * Customization gives it the MilkDrop family's manifest, and the engine draws it with the MilkDrop
 * scene and that preset.
 */
object MilkdropStyles {
    /** What every MilkDrop preset Style's id starts with. */
    const val PREFIX: String = "milkdrop:"

    /** The Style that plays the preset file [file] (a name in the MilkDrop folder, such as `Ocean.milk`). */
    fun of(file: String): SceneId = SceneId(PREFIX + file)

    /** The preset file [style] plays, or null when it is not a MilkDrop preset Style. */
    fun file(style: SceneId): String? = style.value.removePrefix(PREFIX).takeIf { style.value.startsWith(PREFIX) && it.isNotEmpty() }
}
