package dev.geode.design.element

import androidx.compose.ui.text.font.FontWeight
import dev.geode.design.forest.ForestColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** How an Element reaches Material's roles and the forest roles, so components that read either follow it. */
class ElementThemeTest {
    private val stone = ElementSkins.Stone.colors

    @Test
    fun `Material's roles take the Element's colours`() {
        val scheme = elementColorScheme(stone)

        assertEquals(stone.active, scheme.primary)
        assertEquals(stone.onActive, scheme.onPrimary)
        assertEquals(stone.accent, scheme.secondary)
        assertEquals(stone.onAccent, scheme.onSecondary)
        assertEquals(stone.secondary, scheme.tertiary)
        assertEquals(stone.background, scheme.background)
        assertEquals(stone.textPrimary, scheme.onBackground)
        assertEquals(stone.background, scheme.surface)
        assertEquals(stone.textPrimary, scheme.onSurface)
        assertEquals(stone.surface, scheme.surfaceVariant)
        assertEquals(stone.textSecondary, scheme.onSurfaceVariant)
        assertEquals(stone.backgroundDeep, scheme.surfaceContainerLowest)
        assertEquals(stone.surface, scheme.surfaceContainer)
        assertEquals(stone.surfaceHigh, scheme.surfaceContainerHigh)
        assertEquals(stone.error, scheme.error)
        assertEquals(stone.divider, scheme.outlineVariant)
    }

    @Test
    fun `screens on the forest roles get the Element's colours`() {
        assertEquals(
            ForestColors(
                base = stone.background,
                glass = stone.surface,
                textPrimary = stone.textPrimary,
                textSecondary = stone.textSecondary,
                accent = stone.active,
                highlight = stone.accent,
                onAccent = stone.onActive,
            ),
            forestColorsOf(stone),
        )
    }

    @Test
    fun `headlines are set in the display face and everything else in the text face`() {
        val type = ElementSkins.Water.type
        val typography = elementTypography(type)

        for (style in listOf(typography.displayLarge, typography.displaySmall, typography.headlineMedium, typography.headlineSmall)) {
            assertSame(type.display, style.fontFamily)
        }
        for (style in listOf(typography.titleLarge, typography.bodyMedium, typography.labelSmall)) {
            assertSame(type.text, style.fontFamily)
        }
        assertEquals(ElementTypeScale.BodyMedium, typography.bodyMedium.fontSize)
        assertEquals(FontWeight.Light, typography.displayLarge.fontWeight)
        assertEquals(ElementTypeScale.TitleLarge * ElementTypeScale.TEXT_LINE_HEIGHT, typography.titleLarge.lineHeight)
        assertEquals(ElementTypeScale.DisplaySmall * ElementTypeScale.DISPLAY_LINE_HEIGHT, typography.displaySmall.lineHeight)
    }
}
