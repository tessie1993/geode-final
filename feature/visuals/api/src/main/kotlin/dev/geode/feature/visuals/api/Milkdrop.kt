package dev.geode.feature.visuals.api

/**
 * One MilkDrop preset the MilkDrop Style can play (#145): [file] is its file name in the MilkDrop
 * folder, [name] the name it is shown by. A [builtIn] one is from the curated set Geode ships (#93),
 * with its [author] credited; any other is the person's own, imported or saved, shown by its file name.
 */
data class MilkdropPreset(
    val file: String,
    val name: String,
    val author: String?,
    val builtIn: Boolean,
)

/** What the person brings in through the system picker: MilkDrop presets, or images presets can sample. */
enum class MilkdropKind {
    PRESETS,
    TEXTURES,
}

/** Why a picked file was not imported. Nothing about it reached the MilkDrop folder. */
enum class MilkdropImportProblem {
    /** Not a MilkDrop preset: no `[preset00]` section, or not text at all. */
    NOT_A_PRESET,

    /** Its contents are not the image its name says it is. */
    NOT_AN_IMAGE,

    /** Not a kind of image MilkDrop can sample (PNG, JPEG, BMP, TGA or DDS). */
    WRONG_TYPE,
    TOO_LARGE,
    EMPTY,

    /** The phone could not read it from where it was picked. */
    UNREADABLE,

    /** One by that name is already there, built-in or the person's own; it was left as it was. */
    ALREADY_THERE,

    /** It was read and checked, but could not be written into the MilkDrop folder. */
    NOT_SAVED,
}

/** A picked file that was not imported: the [name] it was picked by, and why. */
data class BrokenFile(
    val name: String,
    val problem: MilkdropImportProblem,
)

/**
 * How an imported preset will play in Geode's MilkDrop, as reading it tells (#305). Each preset gets
 * exactly one; when its shaders cannot run, that is what is said, since the textures it is missing are
 * read only by those shaders.
 */
sealed interface PresetPlayback {
    /** It plays as its author wrote it. */
    data object Full : PresetPlayback

    /**
     * Its shader code uses [unsupported] (names Geode's MilkDrop cannot translate), so it plays without
     * that shader: its motion, waves and shapes still run, under MilkDrop's plain warp and composite.
     */
    data class WithoutShader(
        val unsupported: List<String>,
    ) : PresetPlayback

    /** It samples the textures [textures], which the MilkDrop folder does not hold; import them to complete it. */
    data class MissingTextures(
        val textures: List<String>,
    ) : PresetPlayback
}

/**
 * How one import went: the [kind] of files picked, the names of those [imported] (as the lists show
 * them), and every file that was [broken]. A broken file never stops the others. For an import of
 * presets, [playback] says how each one imported (by the name it is shown by) will play.
 */
data class ImportReport(
    val kind: MilkdropKind,
    val imported: List<String>,
    val broken: List<BrokenFile>,
    val playback: Map<String, PresetPlayback> = emptyMap(),
)

/**
 * The MilkDrop presets and textures, built-in and imported side by side. [presets] are sorted by
 * name, built-in and the person's own together; [textures] are the image files presets can sample,
 * by file name. While [importing], another import waits; [lastImport] says how the last one went
 * until the person dismisses it.
 */
data class MilkdropShelf(
    val presets: List<MilkdropPreset> = emptyList(),
    val textures: List<String> = emptyList(),
    val importing: Boolean = false,
    val lastImport: ImportReport? = null,
)
