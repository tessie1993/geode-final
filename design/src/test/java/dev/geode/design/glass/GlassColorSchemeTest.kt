package dev.geode.design.glass

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screens use Material's own controls (#355), which read Material's colour roles, so the roles as
 * [glassColorScheme] maps them must keep WCAG AA too: every text role on every container it is drawn on,
 * in the dark and light modes, in every tier and with the person's adjustments. A translucent container
 * is checked over the worst backdrop it may cover: a matte one (menus, dialogs, sheets) over the worst
 * backdrop unclamped, a thin one (filled cards and fields) over the worst clamped backdrop.
 */
class GlassColorSchemeTest {
    private val styles =
        listOf(true, false).flatMap { dark ->
            GlassTier.entries.map { tier -> GlassStyle.of(dark, tier) } +
                listOf(
                    GlassStyle.of(dark, GlassTier.FULL, accentIntensity = 0.5f),
                    GlassStyle.of(dark, GlassTier.FULL, accentIntensity = 1.5f),
                    GlassStyle.of(dark, GlassTier.FULL, dim = 1f, barOpacity = 1f),
                )
        }

    private fun mode(style: GlassStyle) = "${if (style.dark) "dark" else "light"} ${style.tier}"

    /** What [container] looks like once drawn over the worst backdrop it may cover in [style]. */
    private fun ground(
        container: Color,
        style: GlassStyle,
    ): Color {
        val worst = GlassContrast.worstBackdrop(style.dark)
        val behind =
            if (container.alpha >= GlassKind.CARD.matteAlpha) worst else GlassContrast.clamped(worst, style.colors.base, style.clamp)
        return WcagContrast.over(container.copy(alpha = 1f), container.alpha, behind)
    }

    /** Every text role on the container it is drawn on, as Material's components pair them. */
    private fun pairs(
        scheme: ColorScheme,
        style: GlassStyle,
    ): List<ContrastPair> {
        val onContainers =
            listOf(
                "on-primary on primary" to (scheme.onPrimary to scheme.primary),
                "on-primary container on primary container" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                "on-secondary on secondary" to (scheme.onSecondary to scheme.secondary),
                "on-secondary container on secondary container" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
                "on-tertiary on tertiary" to (scheme.onTertiary to scheme.tertiary),
                "on-tertiary container on tertiary container" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
                "on-error on error" to (scheme.onError to scheme.error),
                "on-error container on error container" to (scheme.onErrorContainer to scheme.errorContainer),
                "inverse on-surface on inverse surface" to (scheme.inverseOnSurface to scheme.inverseSurface),
            ).map { (name, pair) -> ContrastPair(name, pair.first, ground(pair.second, style), WcagContrast.AA_TEXT) }
        val containers =
            listOf(
                "surface variant" to scheme.surfaceVariant,
                "surface container" to scheme.surfaceContainer,
                "surface container high" to scheme.surfaceContainerHigh,
                "surface container highest" to scheme.surfaceContainerHighest,
                "surface container low" to scheme.surfaceContainerLow,
                "surface container lowest" to scheme.surfaceContainerLowest,
                "surface bright" to scheme.surfaceBright,
                "surface dim" to scheme.surfaceDim,
            )
        val texts =
            listOf(
                "on-surface" to scheme.onSurface,
                "on-surface variant" to scheme.onSurfaceVariant,
                "primary" to scheme.primary,
                "error" to scheme.error,
            )
        val onSurfaces =
            containers.flatMap { (containerName, container) ->
                texts.map { (textName, text) ->
                    ContrastPair("$textName on $containerName", text, ground(container, style), WcagContrast.AA_TEXT)
                }
            }
        return onContainers + onSurfaces
    }

    @Test
    fun `every Material text role keeps WCAG AA on its container in every mode`() {
        for (style in styles) {
            val scheme = glassColorScheme(style.colors, style.dark)
            val failures = pairs(scheme, style).filterNot { it.passes }
            assertTrue(mode(style) + ": " + failures.joinToString { "${it.name} is ${it.ratio}:1" }, failures.isEmpty())
        }
    }

    @Test
    fun `the page roles are clear, so the backdrop behind every page shows through`() {
        for (style in styles) {
            val scheme = glassColorScheme(style.colors, style.dark)
            assertEquals(0f, scheme.background.alpha, 0f)
            assertEquals(0f, scheme.surface.alpha, 0f)
        }
    }

    @Test
    fun `menus, dialogs and sheets are matte, since they open over anything`() {
        for (style in styles) {
            val scheme = glassColorScheme(style.colors, style.dark)
            for (container in listOf(scheme.surfaceContainer, scheme.surfaceContainerHigh, scheme.surfaceContainerLow)) {
                assertTrue(mode(style), container.alpha >= GlassKind.CARD.matteAlpha)
            }
        }
    }

    @Test
    fun `control outlines keep 3 to 1 on every container`() {
        for (style in styles) {
            val scheme = glassColorScheme(style.colors, style.dark)
            for (container in listOf(scheme.surfaceVariant, scheme.surfaceContainerHigh, scheme.surfaceContainerHighest)) {
                val ratio = WcagContrast.ratio(scheme.outline, ground(container, style))
                assertTrue("${mode(style)}: outline is $ratio:1", ratio >= WcagContrast.AA_LARGE_TEXT)
            }
        }
    }

    @Test
    fun `a scheme whose secondary text vanishes into thin glass is caught`() {
        val style = GlassStyle.of(dark = true, tier = GlassTier.FULL)
        val bad = glassColorScheme(style.colors.copy(textSecondary = style.colors.glass), dark = true)
        assertTrue(pairs(bad, style).any { !it.passes && it.name.startsWith("on-surface variant") })
    }
}
