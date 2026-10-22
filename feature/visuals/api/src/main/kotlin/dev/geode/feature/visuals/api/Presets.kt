package dev.geode.feature.visuals.api

import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId

/**
 * A folder of saved presets, named by its path inside the preset folder (Settings > Default paths):
 * its names from the top down, with `/` between them. [TOP] is the preset folder itself.
 */
@JvmInline
value class PresetFolderPath(
    val value: String,
) {
    /** Whether this is the preset folder itself. */
    val isTop: Boolean get() = value.isEmpty()

    /** The folder's own name; empty at the top. */
    val name: String get() = value.substringAfterLast(SEPARATOR)

    /** The folder this one is in; null at the top. */
    val parent: PresetFolderPath? get() = if (isTop) null else PresetFolderPath(value.substringBeforeLast(SEPARATOR, ""))

    /** The folder named [name] inside this one. */
    fun child(name: String): PresetFolderPath = PresetFolderPath(if (isTop) name else "$value$SEPARATOR$name")

    /** Whether this folder is [other] or lies somewhere inside it. */
    fun within(other: PresetFolderPath): Boolean = other.isTop || value == other.value || value.startsWith(other.value + SEPARATOR)

    override fun toString(): String = value

    companion object {
        const val SEPARATOR: Char = '/'
        val TOP: PresetFolderPath = PresetFolderPath("")
    }
}

/** A preset the person saved (or imported): its [id] (its name), the [style] it was made on and its [folder]. */
data class SavedPreset(
    val id: PresetId,
    val style: SceneId,
    val folder: PresetFolderPath = PresetFolderPath.TOP,
)

/** A preset that ships with Geode, made for [style]. It can be applied, never changed. */
data class BuiltInPreset(
    val id: PresetId,
    val style: SceneId,
)

/**
 * The presets the Presets browser shows: the [builtIn] ones, the [saved] ones, and every [folders]
 * of saved presets, empty ones included, at any depth.
 */
data class PresetShelf(
    val builtIn: List<BuiltInPreset> = emptyList(),
    val saved: List<SavedPreset> = emptyList(),
    val folders: List<PresetFolderPath> = emptyList(),
) {
    fun saved(id: PresetId): SavedPreset? = saved.firstOrNull { it.id == id }

    fun builtIn(id: PresetId): BuiltInPreset? = builtIn.firstOrNull { it.id == id }

    /** The folders directly inside [parent]. */
    fun foldersIn(parent: PresetFolderPath): List<PresetFolderPath> = folders.filter { it.parent == parent }

    /** The saved presets directly inside [folder]. */
    fun savedIn(folder: PresetFolderPath): List<SavedPreset> = saved.filter { it.folder == folder }

    /** The built-in presets made for [style]. */
    fun builtInFor(style: SceneId): List<BuiltInPreset> = builtIn.filter { it.style == style }
}

/** Why a shared preset was not imported. */
enum class ImportProblem {
    /** The text holds no Geode preset link. */
    NO_LINK,

    /** The link or file is not a preset this version can read. */
    NOT_A_PRESET,
}

/** A saved preset made ready to share through the share sheet. */
sealed interface PresetShare {
    val id: PresetId

    /** As a link ([link]) that opens in Geode, when the preset is small enough for one. */
    data class Link(
        override val id: PresetId,
        val link: String,
    ) : PresetShare

    /** As its file, when it is too big for a link. */
    data class File(
        override val id: PresetId,
    ) : PresetShare
}
