package dev.geode.feature.visuals.core

import dev.geode.engine.types.MilkdropStyles
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.StyleFamily
import dev.geode.feature.customization.api.StyleManifest
import dev.geode.feature.visuals.api.MilkdropPreset
import dev.geode.feature.visuals.api.StyleEntry
import dev.geode.feature.visuals.api.StyleGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class StyleEntriesTest {
    private fun manifest(
        id: String,
        family: StyleFamily,
    ) = StyleManifest(SceneId(id), family, false, emptyList(), emptyMap(), emptyList(), emptyList())

    @Test
    fun `every family has its own group`() {
        val groups =
            mapOf(
                StyleFamily.SHADER to StyleGroup.SHADERS,
                StyleFamily.LIFE to StyleGroup.LIFE,
                StyleFamily.MYCELIUM to StyleGroup.MYCELIUM,
                StyleFamily.ACID to StyleGroup.ACID,
                StyleFamily.FLUID to StyleGroup.FLUIDS,
                StyleFamily.CURL_FLOW to StyleGroup.CURL_FLOW,
                StyleFamily.WATER to StyleGroup.WATER,
                StyleFamily.CYMATICS to StyleGroup.CYMATICS,
                StyleFamily.MILKDROP to StyleGroup.MILKDROP,
            )

        for ((family, group) in groups) {
            assertEquals(family.name, group, StyleEntries.from(listOf(manifest("x", family))).single().group)
        }
        assertEquals(StyleFamily.entries.toSet(), groups.keys)
    }

    @Test
    fun `Styles are listed group by group, each group in the manifests' order`() {
        val entries =
            StyleEntries.from(
                listOf(
                    manifest("milkdrop", StyleFamily.MILKDROP),
                    manifest("fluid_ink", StyleFamily.FLUID),
                    manifest("tunnel", StyleFamily.SHADER),
                    manifest("fluid", StyleFamily.FLUID),
                    manifest("bars", StyleFamily.SHADER),
                ),
            )

        assertEquals(
            listOf(
                StyleEntry(SceneId("tunnel"), "Tunnel", StyleGroup.SHADERS),
                StyleEntry(SceneId("bars"), "Bars", StyleGroup.SHADERS),
                StyleEntry(SceneId("fluid_ink"), "Fluid Ink", StyleGroup.FLUIDS),
                StyleEntry(SceneId("fluid"), "Fluid", StyleGroup.FLUIDS),
                StyleEntry(SceneId("milkdrop"), "Milkdrop", StyleGroup.MILKDROP),
            ),
            entries,
        )
    }

    @Test
    fun `every MilkDrop preset is listed as a Style of its own, in place of the plain MilkDrop Style`() {
        val tunnel = StyleEntry(SceneId("tunnel"), "Tunnel", StyleGroup.SHADERS)
        val plain = StyleEntry(SceneId("milkdrop"), "Milkdrop", StyleGroup.MILKDROP)
        val reef = MilkdropPreset("reef.milk", "Coral Reef", "Geode", builtIn = true)
        val ocean = MilkdropPreset("Ocean.milk", "Ocean", null, builtIn = false)
        val reefStyle = StyleEntry(MilkdropStyles.of("reef.milk"), "Coral Reef", StyleGroup.MILKDROP)
        val oceanStyle = StyleEntry(MilkdropStyles.of("Ocean.milk"), "Ocean", StyleGroup.MILKDROP)

        val listed = StyleEntries.withMilkdrop(listOf(tunnel, plain), listOf(reef, ocean))
        assertEquals(listOf(tunnel, reefStyle, oceanStyle), listed)
        // A later listing replaces the presets of the earlier one.
        assertEquals(listOf(tunnel, oceanStyle), StyleEntries.withMilkdrop(listed, listOf(ocean)))
        // With no presets at all, only the presets go.
        assertEquals(listOf(tunnel), StyleEntries.withMilkdrop(listed, emptyList()))
        assertEquals(listOf(tunnel, plain), StyleEntries.withMilkdrop(listOf(tunnel, plain), emptyList()))
    }

    @Test
    fun `a Style's name is made from its id`() {
        assertEquals("Myco Polycephalum", StyleEntries.name(SceneId("myco_polycephalum")))
        assertEquals("Orb Lattice", StyleEntries.name(SceneId("orb_lattice")))
        assertEquals("Acid Tv", StyleEntries.name(SceneId("acid__tv_")))
    }
}
