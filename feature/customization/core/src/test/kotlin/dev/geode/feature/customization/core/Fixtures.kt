package dev.geode.feature.customization.core

import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Customization

/** Shared starting points for the tests: real built-in Styles, so the tests also exercise their data. */
object Fixtures {
    val JULIA = SceneId("julia")
    val TUNNEL = SceneId("tunnel")
    val KALEIDO = SceneId("kaleido")
    val NEBULA = SceneId("nebula")
    val WATER = SceneId("water")
    val MILKDROP = SceneId("milkdrop")
    val GONE = SceneId("silk_web")

    val styles: StyleCatalogue = BuiltInStyles.catalogue

    fun defaults(id: SceneId): Customization = Defaults.customization(styles.manifestOf(id))

    val julia: Customization = defaults(JULIA)
    val tunnel: Customization = defaults(TUNNEL)
}
