package dev.geode.design.ambient

import dev.geode.design.forest.ForestThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingAssetManifestTest {
    private val leaf = FloatingAsset("leaf", ForestThemeId.DeepForest, DepthLayer.Mid, drawable = LEAF_ART, approved = true)

    @Test
    fun `nothing ships today, so the app looks unchanged`() {
        for (theme in ForestThemeId.entries) {
            assertEquals(emptyList<ShippableAsset>(), FloatingAssetManifest.shippable(theme))
        }
    }

    @Test
    fun `the real manifest is sound`() {
        assertEquals(emptyList<ManifestProblem>(), FloatingAssetManifest.problems())
    }

    @Test
    fun `an approved asset with its art ships for its own theme`() {
        assertEquals(
            listOf(ShippableAsset("leaf", DepthLayer.Mid, LEAF_ART)),
            FloatingAssetManifest.shippable(ForestThemeId.DeepForest, listOf(leaf)),
        )
    }

    @Test
    fun `an asset never ships for another theme`() {
        assertEquals(emptyList<ShippableAsset>(), FloatingAssetManifest.shippable(ForestThemeId.NightForest, listOf(leaf)))
        assertEquals(emptyList<ShippableAsset>(), FloatingAssetManifest.shippable(ForestThemeId.EarthLichen, listOf(leaf)))
    }

    @Test
    fun `an asset the owner has not approved never ships`() {
        val pending = leaf.copy(approved = false)
        assertEquals(emptyList<ShippableAsset>(), FloatingAssetManifest.shippable(ForestThemeId.DeepForest, listOf(pending)))
    }

    @Test
    fun `assets start unapproved and without art`() {
        val fresh = FloatingAsset("mote", ForestThemeId.NightForest, DepthLayer.Far)
        assertEquals(false, fresh.approved)
        assertEquals(null, fresh.drawable)
    }

    @Test
    fun `an asset without its art never ships`() {
        val missing = leaf.copy(drawable = null)
        assertEquals(emptyList<ShippableAsset>(), FloatingAssetManifest.shippable(ForestThemeId.DeepForest, listOf(missing)))
    }

    @Test
    fun `only the gated assets are left out`() {
        val pebble = FloatingAsset("pebble", ForestThemeId.DeepForest, DepthLayer.Near, drawable = PEBBLE_ART, approved = true)
        val pending = FloatingAsset("droplet", ForestThemeId.DeepForest, DepthLayer.Far, drawable = DROPLET_ART)
        assertEquals(
            listOf("leaf", "pebble"),
            FloatingAssetManifest.shippable(ForestThemeId.DeepForest, listOf(leaf, pending, pebble)).map { it.id },
        )
    }

    @Test
    fun `a sound manifest has no problems`() {
        val pending = FloatingAsset("droplet", ForestThemeId.DeepForest, DepthLayer.Far)
        assertEquals(emptyList<ManifestProblem>(), FloatingAssetManifest.problems(listOf(leaf, pending)))
    }

    @Test
    fun `a duplicate id is a problem`() {
        val twin = leaf.copy(theme = ForestThemeId.EarthLichen)
        assertEquals(listOf(ManifestProblem.DuplicateId("leaf")), FloatingAssetManifest.problems(listOf(leaf, twin)))
    }

    @Test
    fun `a blank id is a problem`() {
        val blank = leaf.copy(id = " ")
        assertEquals(listOf(ManifestProblem.BlankId(" ")), FloatingAssetManifest.problems(listOf(blank)))
    }

    @Test
    fun `an approval without art is a problem`() {
        val approvedEarly = leaf.copy(drawable = null)
        assertEquals(listOf(ManifestProblem.ApprovedWithoutArt("leaf")), FloatingAssetManifest.problems(listOf(approvedEarly)))
    }

    @Test
    fun `focus blur is baked into the far and near layers only`() {
        assertTrue(DepthLayer.Far.focusBlurBaked)
        assertTrue(DepthLayer.Near.focusBlurBaked)
        assertEquals(false, DepthLayer.Mid.focusBlurBaked)
    }

    @Test
    fun `nearer layers drift faster and follow the scroll more`() {
        val layers = DepthLayer.entries
        assertEquals(listOf(DepthLayer.Far, DepthLayer.Mid, DepthLayer.Near), layers)
        for ((back, front) in layers.zipWithNext()) {
            assertTrue("$front drifts slower than $back", front.traverseSeconds < back.traverseSeconds)
            assertTrue("$front follows less scroll than $back", front.parallax > back.parallax)
        }
        for (layer in layers) {
            assertTrue("$layer parallax out of range", layer.parallax in 0f..1f)
            assertTrue("$layer alpha out of range", layer.alpha > 0f && layer.alpha <= 1f)
        }
    }

    private companion object {
        const val LEAF_ART = 1
        const val PEBBLE_ART = 2
        const val DROPLET_ART = 3
    }
}
