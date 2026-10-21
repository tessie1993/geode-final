package dev.geode.design.ambient

import androidx.annotation.DrawableRes
import dev.geode.design.forest.ForestThemeId

/**
 * One floating asset (a leaf, pebble, spore, droplet or light mote) as declared in the manifest.
 *
 * It ships only once the owner has reviewed its theme's asset sheet and approved it ([approved]),
 * and only with its art in place ([drawable]). Until both hold, the ambient layer leaves it out.
 */
data class FloatingAsset(
    /** A stable, unique name, the same one the asset pipeline writes into its manifest. */
    val id: String,
    val theme: ForestThemeId,
    val layer: DepthLayer,
    /** The finished WebP, or null while the art is not produced yet. */
    @param:DrawableRes val drawable: Int? = null,
    /** The owner's approval of this asset, given on its theme's asset sheet (issue #72). */
    val approved: Boolean = false,
)

/** An asset that has passed the gate: approved and with its art in place. */
data class ShippableAsset(
    val id: String,
    val layer: DepthLayer,
    @param:DrawableRes val drawable: Int,
)

/** Something wrong with a manifest entry. [FloatingAssetManifest.problems] lists them; the real manifest has none. */
sealed interface ManifestProblem {
    val id: String

    /** Two entries share [id]. */
    data class DuplicateId(
        override val id: String,
    ) : ManifestProblem

    /** An entry has an empty or blank id. */
    data class BlankId(
        override val id: String,
    ) : ManifestProblem

    /** An entry is approved but has no art: an approval must point at a finished asset. */
    data class ApprovedWithoutArt(
        override val id: String,
    ) : ManifestProblem
}

/**
 * Every floating asset of every theme. Empty today: the art is generated and finished by hand, the
 * owner reviews each theme's sheet (issue #72), and only then are approved entries added here with
 * their drawables. Nothing procedural ever stands in, so until then the ambient layer draws nothing.
 */
object FloatingAssetManifest {
    val entries: List<FloatingAsset> = emptyList()

    /** The assets of [theme] in [entries] that may be shown: approved, with their art in place. */
    fun shippable(
        theme: ForestThemeId,
        entries: List<FloatingAsset> = this.entries,
    ): List<ShippableAsset> =
        entries.mapNotNull { asset ->
            val art = asset.drawable
            if (asset.theme == theme && asset.approved && art != null) ShippableAsset(asset.id, asset.layer, art) else null
        }

    /** What is wrong with [entries], in order; empty when every entry is sound. */
    fun problems(entries: List<FloatingAsset> = this.entries): List<ManifestProblem> {
        val blank = entries.filter { it.id.isBlank() }.map { ManifestProblem.BlankId(it.id) }
        val duplicates =
            entries
                .groupingBy { it.id }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .map { ManifestProblem.DuplicateId(it) }
        val withoutArt = entries.filter { it.approved && it.drawable == null }.map { ManifestProblem.ApprovedWithoutArt(it.id) }
        return blank + duplicates + withoutArt
    }
}
