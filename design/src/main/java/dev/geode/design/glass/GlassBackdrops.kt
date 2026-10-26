package dev.geode.design.glass

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.geode.design.theme.LocalReducedMotion

/**
 * The calm backdrop behind Library, Studio and Settings: the theme's base with a few large, soft fields
 * of colour (the backdrop the person picked, [GlassBackdropChoice]), like light through frosted glass,
 * and the grain. It never moves, so after its first frame it
 * costs nothing to draw. Every text role stays legible straight on it ([GlassContrastRules]).
 */
@Composable
fun CalmBackdrop(modifier: Modifier = Modifier) {
    Spacer(modifier.fillMaxSize().calmBackdrop())
}

/** The [CalmBackdrop] drawn behind this content, for a whole-screen page such as first-run setup. */
@Composable
fun Modifier.calmBackdrop(): Modifier {
    val style = LocalGlassStyle.current
    return drawWithCache {
        val fields = style.colors.fields.map { fieldBrush(it, size, style.fieldStrength) }
        onDrawBehind {
            drawRect(style.colors.base)
            fields.forEach { drawRect(it) }
            drawRect(GlassGrain.brush, alpha = GlassTokens.GRAIN_ALPHA, blendMode = BlendMode.Overlay)
        }
    }
}

/**
 * The backdrop behind Home and the Player: the playing song's [artwork], blurred once and clamped toward
 * the theme's base so glass over it stays legible, with the grain. It cross-fades when the song changes.
 * Without art, in the Matte tier, and when the person picked a calm backdrop ([GlassBackdropChoice]),
 * it is the [CalmBackdrop].
 */
@Composable
fun ArtworkBackdrop(
    artwork: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val style = LocalGlassStyle.current
    val reduced = LocalReducedMotion.current
    val shown = artwork.takeIf { style.tier != GlassTier.MATTE && style.backdrop.showsArtwork }
    Crossfade(
        targetState = shown,
        modifier = modifier,
        animationSpec = if (reduced) snap() else tween(GlassTokens.BACKDROP_FADE_MILLIS),
        label = "artworkBackdrop",
    ) { art ->
        if (art == null) CalmBackdrop() else BakedArtwork(art, style)
    }
}

/** [art] blurred and clamped. A still layer, so it is blurred once and then drawn from cache. */
@Composable
private fun BakedArtwork(
    art: ImageBitmap,
    style: GlassStyle,
) {
    val quieter = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(GlassTokens.SATURATION) }) }
    Box(Modifier.fillMaxSize().background(style.colors.base)) {
        Image(
            art,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().blur(GlassTokens.ArtworkBlur),
            contentScale = ContentScale.Crop,
            colorFilter = quieter,
        )
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                // The clamp: only style.clamp of the art shows through the base.
                drawRect(style.colors.base.copy(alpha = 1f - style.clamp))
                drawRect(GlassGrain.brush, alpha = GlassTokens.GRAIN_ALPHA, blendMode = BlendMode.Overlay)
            },
        )
    }
}

/** One soft field of colour, fading from its heart to nothing at its reach. */
private fun fieldBrush(
    field: GlassField,
    size: Size,
    strength: Float,
): Brush =
    Brush.radialGradient(
        0f to field.color.copy(alpha = field.alpha * strength),
        1f to field.color.copy(alpha = 0f),
        center = Offset(size.width * field.x, size.height * field.y),
        radius = (size.maxDimension * field.reach).coerceAtLeast(1f),
    )
