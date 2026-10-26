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

/**
 * A whole pack of MilkDrop presets brought in at once (#399): a [FOLDER] picked through the system
 * folder picker, or a [ZIP] file. Its subfolders become folders of the Visuals tab holding their
 * presets, and its textures come along.
 */
enum class MilkdropPackType {
    FOLDER,
    ZIP,
}

/** Why a file in a pack was passed over without being read (#399). */
enum class PackProblem {
    /** Neither a MilkDrop preset nor a texture, by its name: a readme, a screenshot list, an installer. */
    NOT_MILKDROP,

    /** Its path in the pack leads out of it (`..`), or starts at the top of the phone (absolute). */
    UNSAFE_PATH,
}

/** A file in a pack that was passed over: its [path] in the pack, and why. */
data class SkippedEntry(
    val path: String,
    val problem: PackProblem,
)

/** The limit a pack ran into, after which nothing more of it was read (#399). */
enum class PackLimit {
    /** It holds more files and folders than one import takes. */
    ENTRIES,

    /** What it unpacks to is larger than one import takes. */
    SIZE,
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
 *
 * A pack (#399) imports as presets: [textures] are the textures that came along with them, [skipped]
 * the files in it that were neither (or whose path was unsafe), and [stopped] the limit it ran into,
 * when it did; what came before that is in.
 */
data class ImportReport(
    val kind: MilkdropKind,
    val imported: List<String>,
    val broken: List<BrokenFile>,
    val playback: Map<String, PresetPlayback> = emptyMap(),
    val textures: List<String> = emptyList(),
    val skipped: List<SkippedEntry> = emptyList(),
    val stopped: PackLimit? = null,
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
