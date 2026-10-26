package dev.geode.design.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The accent tint and the backdrop the person picks (#398), and how a style takes them. */
class GlassChoicesTest {
    @Test
    fun `sky is the palette's own accent`() {
        for (dark in listOf(true, false)) {
            val palette = GlassPalettes.of(dark)
            assertEquals(palette, palette.withAccent(GlassAccent.SKY.colors(dark)))
        }
    }

    @Test
    fun `a tint sets every accent role and nothing else`() {
        val tint = GlassAccent.ROSE.colors(dark = true)
        val tinted = GlassPalettes.Dark.withAccent(tint)
        assertEquals(tint.accent, tinted.accent)
        assertEquals(tint.onAccent, tinted.onAccent)
        assertEquals(tint.accentContainer, tinted.accentContainer)
        assertEquals(tint.onAccentContainer, tinted.onAccentContainer)
        assertEquals(tint.selected, tinted.selected)
        assertEquals(GlassPalettes.Dark.warm, tinted.warm)
        assertEquals(GlassPalettes.Dark.textPrimary, tinted.textPrimary)
        assertEquals(GlassPalettes.Dark.fields, tinted.fields)
    }

    @Test
    fun `every tint differs from every other, and its night form from its day form`() {
        for (dark in listOf(true, false)) {
            assertEquals(
                GlassAccent.entries.size,
                GlassAccent.entries
                    .map { it.colors(dark).accent }
                    .toSet()
                    .size,
            )
        }
        for (accent in GlassAccent.entries) assertNotEquals(accent.colors(true), accent.colors(false))
    }

    @Test
    fun `only the artwork backdrop shows the song's art`() {
        assertTrue(GlassBackdropChoice.ARTWORK.showsArtwork)
        for (choice in GlassBackdropChoice.entries - GlassBackdropChoice.ARTWORK) assertFalse(choice.name, choice.showsArtwork)
    }

    @Test
    fun `each backdrop has its own fields, and plain has none`() {
        for (dark in listOf(true, false)) {
            val mist = GlassPalettes.of(dark).fields
            assertEquals(mist, GlassBackdropChoice.ARTWORK.fields(dark))
            assertEquals(mist, GlassBackdropChoice.MIST.fields(dark))
            assertTrue(GlassBackdropChoice.PLAIN.fields(dark).isEmpty())
            val calm = listOf(GlassBackdropChoice.MIST, GlassBackdropChoice.DUSK, GlassBackdropChoice.AURORA).map { it.fields(dark) }
            assertEquals(calm.size, calm.toSet().size)
            calm.forEach { assertEquals(3, it.size) }
        }
        assertNotEquals(GlassBackdropChoice.DUSK.fields(true), GlassBackdropChoice.DUSK.fields(false))
        assertNotEquals(GlassBackdropChoice.AURORA.fields(true), GlassBackdropChoice.AURORA.fields(false))
    }

    @Test
    fun `a style carries its tint and backdrop into its colours`() {
        val style = GlassStyle.of(dark = false, tier = GlassTier.MATTE, accent = GlassAccent.TEAL, backdrop = GlassBackdropChoice.DUSK)
        assertSame(GlassAccent.TEAL, style.accent)
        assertSame(GlassBackdropChoice.DUSK, style.backdrop)
        assertEquals(GlassAccent.TEAL.colors(dark = false).accent, style.colors.accent)
        assertEquals(GlassAccent.TEAL.colors(dark = false).selected, style.colors.selected)
        assertEquals(GlassBackdropChoice.DUSK.fields(dark = false), style.colors.fields)
    }

    @Test
    fun `a style starts on sky and the artwork`() {
        val style = GlassStyle.of(dark = true, tier = GlassTier.FULL)
        assertSame(GlassAccent.SKY, style.accent)
        assertSame(GlassBackdropChoice.ARTWORK, style.backdrop)
    }
}
