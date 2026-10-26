package dev.geode.feature.customization.core

import dev.geode.engine.types.MilkdropStyles
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.ExtraSpec
import dev.geode.feature.customization.api.MACRO_COUNT
import dev.geode.feature.customization.api.MACRO_REST
import dev.geode.feature.customization.api.MacroLink
import dev.geode.feature.customization.api.MacroRole
import dev.geode.feature.customization.api.ReactionId
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SignalTrim
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.StyleFamily
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.core.Fixtures.GONE
import dev.geode.feature.customization.core.Fixtures.JULIA
import dev.geode.feature.customization.core.Fixtures.MILKDROP
import dev.geode.feature.customization.core.Fixtures.NEBULA
import dev.geode.feature.customization.core.Fixtures.TUNNEL
import dev.geode.feature.customization.core.Fixtures.julia
import dev.geode.feature.customization.core.Fixtures.styles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The built-in Style manifests (the #11 audit), the proposed Macro mapping, and the defaults built from them. */
class StyleCatalogueTest {
    private val delta = 1e-5f

    @Test
    fun `every Style the audit keeps is there once, and nothing that was removed`() {
        val designed = BuiltInStyles.designed
        val ids = BuiltInStyles.all.map { it.id.value }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(EXPECTED_STYLES, designed.size)
        assertEquals(designed, BuiltInStyles.all.take(designed.size))
        assertTrue(ids.none { it.startsWith("silk") })
        assertEquals(10, designed.count { it.family == StyleFamily.LIFE })
        assertEquals(10, designed.count { it.family == StyleFamily.MYCELIUM })
        assertEquals(10, designed.count { it.family == StyleFamily.ACID })
        assertEquals(9, designed.count { it.family == StyleFamily.FLUID })
        assertEquals(11, designed.count { it.family == StyleFamily.CYMATICS })
        assertEquals(82, designed.count { it.family == StyleFamily.SHADER })
    }

    @Test
    fun `every Style's Extras are its pack's, and every pack's Extras reach a Style`() {
        val packs = PackExtras.styles.associateBy { it.id }
        BuiltInStyles.all.forEach { manifest ->
            assertEquals(manifest.id.value, packs[manifest.id.value]?.extras.orEmpty(), manifest.extras)
        }
        val ids = BuiltInStyles.all.map { it.id.value }.toSet()
        assertTrue(PackExtras.styles.all { it.id in ids })
    }

    @Test
    fun `a pack the audit does not cover still reaches Customize with its Extras`() {
        val swirl = extra(ExtraKind.SWIRL, 0f, 2f, 1f)
        val audited = listOf(styles.manifestOf(JULIA))
        val catalogue =
            BuiltInStyles.catalogueOf(
                audited,
                listOf(pack("julia", StyleFamily.SHADER, swirl), pack("new_flow", StyleFamily.FLUID, swirl)),
            )
        assertEquals(listOf("julia", "new_flow"), catalogue.map { it.id.value })
        assertEquals(audited.single(), catalogue.first())
        val added = catalogue.last()
        assertEquals(StyleFamily.FLUID, added.family)
        assertEquals(listOf(swirl), added.extras)
        assertFalse(added.ownFlight)
        assertTrue(added.reactions.isEmpty() && added.shared.isEmpty())
        assertEquals(DefaultMacros.forStyle(StyleFamily.FLUID, ownFlight = false, extras = setOf(ExtraKind.SWIRL)), added.macros)
    }

    @Test
    fun `there is no strobe or flash to set`() {
        val names = SharedProperty.entries.map { it.name }
        assertTrue(names.none { it.contains("STROBE") || it.contains("FLASH") })
    }

    @Test
    fun `every Style has the 6 shared Macros, linked only to what it has`() {
        BuiltInStyles.all.forEach { manifest ->
            val customization = Defaults.customization(manifest)
            assertEquals(MacroRole.entries.map { it.title }, customization.macros.map { it.name })
            assertEquals(MACRO_COUNT, customization.macros.size)
            assertTrue(manifest.id.value, customization.macros.all { it.value == MACRO_REST && it.links.isNotEmpty() })
            assertEquals(manifest.id.value, customization, Rules.prune(customization))
        }
    }

    @Test
    fun `every default Reaction listens to a Sound signal and reaches the Style`() {
        BuiltInStyles.all.forEach { manifest ->
            val customization = Defaults.customization(manifest)
            assertEquals(manifest.reactions.size, customization.reactions.size)
            assertTrue(customization.reactions.all { it.source is ReactionSource.Sound })
            assertEquals((1..manifest.reactions.size).map { ReactionId(it) }, customization.reactions.map { it.id })
            customization.reactions.filter { Rules.luminous(it.target) }.forEach { assertEquals(NO_FLASH_MIN_RISE, it.rise) }
        }
    }

    @Test
    fun `every Extra starts inside its range`() {
        BuiltInStyles.all.flatMap { it.extras }.forEach { spec ->
            assertTrue(spec.toString(), spec.default in 0f..1f && spec.nativeMin < spec.nativeMax)
        }
    }

    @Test
    fun `a pack's Extra starts at its default, so the Style draws as its pack tunes it`() {
        val centre = extra(ExtraKind.CENTER_X, -1f, 1f, 0f)
        assertEquals(0.5f, centre.default, delta)
        assertEquals(-1f, centre.nativeMin)
        assertEquals(1f, centre.nativeMax)
        assertFalse(centre.stepped)
        assertEquals(ExtraSpec(ExtraKind.SCALE, 0f, 2f, 0.5f), extra(ExtraKind.SCALE, 0f, 2f, 1f))
        assertEquals(ExtraSpec(ExtraKind.SCALE, 1f, 3f, 0.25f), extra(ExtraKind.SCALE, 1f, 3f, 1.5f))
        assertEquals(ExtraSpec(ExtraKind.VARIANT, 0f, 9f, 1f / 3f, stepped = true), extra(ExtraKind.VARIANT, 0f, 9f, 3f))
        val orb = pack("orb", StyleFamily.SHADER, extra(ExtraKind.SCALE, 0f, 2f, 1f), extra(ExtraKind.FOG, 0f, 2f, 1f))
        assertEquals(listOf(ExtraKind.SCALE, ExtraKind.FOG), orb.extras.map { it.kind })
        assertEquals(StyleFamily.SHADER, orb.family)
        assertEquals(0.5f, julia.extras.getValue(ExtraKind.CENTER_X), delta)
    }

    @Test
    fun `default Reactions carry today's couplings`() {
        val bass = BuiltInStyles.react(SoundSignal.BASS, ExtraKind.SCALE, 0.2f)
        assertEquals(ReactionSource.Sound(SoundSignal.BASS), bass.source)
        assertEquals(Target.Extra(ExtraKind.SCALE), bass.target)
        assertEquals(0.2f, bass.amount)
        assertEquals(DEFAULT_RISE, bass.rise)
        assertEquals(DEFAULT_FALL, bass.fall)
        assertEquals(Target.Shared(SharedProperty.ZOOM), BuiltInStyles.react(SoundSignal.BASS, SharedProperty.ZOOM, 0.1f).target)
        val tunnel = styles.manifestOf(TUNNEL)
        assertTrue(tunnel.ownFlight)
        assertTrue(
            tunnel.reactions.any { it.source == ReactionSource.Sound(SoundSignal.LEVEL) && it.target == Target.Extra(ExtraKind.FLIGHT) },
        )
    }

    @Test
    fun `retired in-Style effects start in the Shared core`() {
        assertEquals(0.3f, Fixtures.defaults(NEBULA).shared[SharedProperty.VIGNETTE])
        assertEquals(1f, Fixtures.defaults(Fixtures.KALEIDO).shared[SharedProperty.KALEIDOSCOPE])
        assertEquals(SharedProperty.GLITCH.max, Fixtures.defaults(SceneId("acid_mosh")).shared[SharedProperty.GLITCH])
        assertEquals(0.6f, Fixtures.defaults(SceneId("acid_scan")).shared[SharedProperty.FISHEYE])
        assertFalse(SharedProperty.SCANLINES in styles.manifestOf(SceneId("acid_mosh")).shared)
        assertEquals(0.32f, Fixtures.defaults(SceneId("fluid_ink")).shared.getValue(SharedProperty.GLOW), delta)
    }

    @Test
    fun `the Style families declare the Extras their renderers map`() {
        fun kinds(id: String) = styles.manifestOf(SceneId(id)).extras.map { it.kind }
        assertEquals(listOf(ExtraKind.GROWTH, ExtraKind.SPREAD, ExtraKind.VARIANT, ExtraKind.FORCE), kinds("life_coral"))
        assertEquals(
            listOf(ExtraKind.REACH, ExtraKind.TURN, ExtraKind.DRIFT, ExtraKind.DECAY, ExtraKind.FORCE),
            kinds("myco_capillary"),
        )
        assertEquals(
            listOf(ExtraKind.SWIRL, ExtraKind.VISCOSITY, ExtraKind.DECAY, ExtraKind.SIZE, ExtraKind.FORCE),
            kinds("fluid_smoke"),
        )
        assertEquals(
            listOf(ExtraKind.DRIFT, ExtraKind.VISCOSITY, ExtraKind.FORCE, ExtraKind.FOG, ExtraKind.SWIRL),
            kinds(Fixtures.WATER.value),
        )
        val droste = styles.manifestOf(SceneId("acid_droste")).extras.associateBy { it.kind }
        assertEquals(0f, droste.getValue(ExtraKind.VARIANT).default)
        assertTrue(droste.getValue(ExtraKind.VARIANT).stepped)
        assertEquals(0.5f, droste.getValue(ExtraKind.FLIGHT).default, delta)
        assertFalse(droste.getValue(ExtraKind.FLIGHT).stepped)
        assertTrue(ExtraKind.DECAY in kinds("chladni_sand"))
    }

    @Test
    fun `MilkDrop has the Shared core only, and its Macros skip speed and drive`() {
        val milk = styles.manifestOf(MILKDROP)
        assertTrue(milk.extras.isEmpty() && milk.reactions.isEmpty())
        val targets = milk.macros.flatMap { it.links }.map { it.target }
        assertFalse(Target.Shared(SharedProperty.SPEED) in targets)
        assertFalse(Target.Shared(SharedProperty.AUDIO_DRIVE) in targets)
    }

    @Test
    fun `the proposed Macro mapping picks the Style's own fitting Extras`() {
        val flat = DefaultMacros.forStyle(StyleFamily.SHADER, ownFlight = false, extras = setOf(ExtraKind.DRIFT, ExtraKind.COUNT))
        val flying = DefaultMacros.forStyle(StyleFamily.SHADER, ownFlight = true, extras = setOf(ExtraKind.FLIGHT, ExtraKind.FOG))
        assertEquals(
            listOf(
                MacroLink(Target.Shared(SharedProperty.SPEED), 0.12f, 0.5f),
                MacroLink(Target.Shared(SharedProperty.AUDIO_DRIVE), 0.15f, 0.7f),
                MacroLink(Target.Extra(ExtraKind.DRIFT), 0.2f, 0.9f),
            ),
            flat[0].links,
        )
        assertEquals(Target.Extra(ExtraKind.FLIGHT), flying[0].links.last().target)
        assertEquals(listOf(Target.Shared(SharedProperty.ZOOM)), flat[1].links.map { it.target })
        assertEquals(listOf(Target.Shared(SharedProperty.PUSH_IN), Target.Extra(ExtraKind.FOG)), flying[1].links.map { it.target })
        assertEquals(Target.Extra(ExtraKind.COUNT), flat[4].links.last().target)
        assertTrue(flat[5].links.all { it.target is Target.Shared })
        assertEquals(DefaultMacros.LINKS.keys, MacroRole.entries.toSet())
        assertEquals(DefaultMacros.MILKDROP_LINKS.keys, MacroRole.entries.toSet())
    }

    @Test
    fun `a Customization starts from its manifest`() {
        assertEquals(JULIA, julia.style)
        assertEquals(SharedProperty.entries.toSet(), julia.shared.keys)
        assertEquals(SharedProperty.entries.associateWith { it.default }, julia.shared)
        assertEquals(SoundSignal.entries.associateWith { SignalTrim.NEUTRAL }, julia.signals)
        assertTrue(julia.modulators.isEmpty())
    }

    @Test
    fun `an unknown Style gets a bare manifest`() {
        assertNull(styles[GONE])
        val bare = styles.manifestOf(GONE)
        assertEquals(GONE, bare.id)
        assertTrue(bare.extras.isEmpty() && bare.reactions.isEmpty() && bare.shared.isEmpty())
        assertFalse(bare.ownFlight)
        assertEquals(MACRO_COUNT, bare.macros.size)
        assertEquals(styles.manifestOf(JULIA), styles[JULIA])
        assertEquals(BuiltInStyles.all, styles.styles)
    }

    @Test
    fun `every MilkDrop preset is a Style with the MilkDrop manifest under its own id`() {
        val ocean = MilkdropStyles.of("Ocean Deep.milk")
        assertEquals("milkdrop:Ocean Deep.milk", ocean.value)
        assertEquals("Ocean Deep.milk", MilkdropStyles.file(ocean))
        val manifest = styles[ocean] ?: throw AssertionError("no manifest for $ocean")
        assertEquals(ocean, manifest.id)
        assertEquals(styles.manifestOf(MILKDROP).copy(id = ocean), manifest)
        assertEquals(manifest, styles.manifestOf(ocean))
        assertEquals(ocean, Defaults.customization(manifest).style)
    }

    @Test
    fun `only an id with a file after the prefix names a MilkDrop preset`() {
        assertNull(MilkdropStyles.file(MILKDROP))
        assertNull(MilkdropStyles.file(MilkdropStyles.of("")))
        assertNull(MilkdropStyles.file(SceneId("my milkdrop:x.milk")))
        assertNull(styles[MilkdropStyles.of("")])
        assertNull(styles[SceneId("milk:Ocean.milk")])
    }

    @Test
    fun `without the MilkDrop family no MilkDrop preset is known`() {
        val noMilkdrop = StyleCatalogue(listOf(styles.manifestOf(JULIA)))
        assertNull(noMilkdrop[MilkdropStyles.of("Ocean.milk")])
        assertEquals(StyleFamily.SHADER, noMilkdrop.manifestOf(MilkdropStyles.of("Ocean.milk")).family)
    }

    private companion object {
        const val EXPECTED_STYLES = 135
    }
}
