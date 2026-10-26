package dev.geode.render.scene

import dev.geode.engine.types.SceneId

/**
 * One Style, as its data pack declares it: the folder `styles/<id>/` holding `manifest.json` and, for
 * a shader Style, its fragment shader. The format is described in docs/style-packs.md and checked in
 * CI by tools/ci/check_style_packs.py; the native renderer reads the same files
 * (`geode/viz/StylePacks.hpp`), so both sides build one registry from one source.
 */
data class StylePack(
    val id: SceneId,
    val label: String,
    /** The renderer that draws it, named by the manifest's `family`. */
    val kind: SceneKind,
    /** Asset path of the fragment shader (`styles/<id>/<file>`); null for a family that brings its own. */
    val shaderPath: String?,
    val renderScale: Float,
    val detail: DetailUse,
    /** Every parameter of the family, resolved: the Style's value, else the family default, clamped. */
    val parameters: Map<String, Float>,
    val extras: List<StyleExtra>,
    val macros: List<StyleMacro>,
    val reactions: List<StyleReaction>,
    /** The Shared core properties its family's renderer never reads, by name ([FamilySchema.ignores]). */
    val ignores: Set<String> = emptySet(),
)

/** What the user's Detail control drives on a Style beyond the shared quality ladder. */
enum class DetailUse(
    val manifestName: String,
) {
    NONE("none"),

    /** The Style raymarches and breaks its loop on the [MarchBudget] Detail sets. */
    MARCH("march"),
}

/**
 * A Style's own control on top of the Shared core, declared generically: the same [name] means the
 * same control, with the same range, on every Style that has one.
 */
data class StyleExtra(
    val name: String,
    val min: Float,
    val max: Float,
    val default: Float,
)

/** One of a Style's default Macros: a single value moving each linked target by its amount. */
data class StyleMacro(
    val name: String,
    val links: List<MacroLink>,
)

data class MacroLink(
    val target: String,
    /** -1..1: how far, and which way, the Macro moves the target across its range. */
    val amount: Float,
)

/** One of a Style's default Reactions: a Sound signal (0..1) moving one target. */
data class StyleReaction(
    val signal: String,
    val target: String,
    /** -1..1: how far, and which way, the signal moves the target across its range. */
    val amount: Float,
    val riseMs: Float,
    val fallMs: Float,
    val curve: ReactionCurve,
)

enum class ReactionCurve(
    val manifestName: String,
) {
    LINEAR("linear"),
    EASE_IN("ease_in"),
    EASE_OUT("ease_out"),
    SMOOTH("smooth"),
}

/** The type of one family parameter. */
enum class ParameterType(
    val manifestName: String,
) {
    INT("int"),
    FLOAT("float"),
}

/** One parameter a family's renderer reads from the Style it draws (`styles/families.json`). */
data class ParameterSpec(
    val key: String,
    val type: ParameterType,
    val min: Float,
    val max: Float,
    /** Null: every Style of the family must set the parameter itself. */
    val default: Float?,
)

data class FamilySchema(
    val kind: SceneKind,
    val parameters: List<ParameterSpec>,
    /**
     * The Shared core properties the family's renderer never reads, named as presets name them
     * (`SPEED`, `AUDIO_DRIVE`): Customize greys them out on its Styles (#365). Empty: it reads them all.
     */
    val ignores: Set<String> = emptySet(),
)

/** Why a pack could not be read. The registry leaves that Style out and keeps every other one. */
enum class PackError {
    MISSING_FILE,
    NOT_JSON,
    WRONG_VERSION,
    MISSING_FIELD,
    BAD_VALUE,
    ID_MISMATCH,
    DUPLICATE_ID,
    UNKNOWN_FAMILY,
    UNKNOWN_PARAMETER,
}

data class PackProblem(
    val error: PackError,
    /** The file it is about: `index.json`, `families.json` or the Style id. */
    val where: String,
    val detail: String,
)
