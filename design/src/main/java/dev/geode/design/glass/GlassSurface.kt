package dev.geode.design.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.toSize
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.geode.design.theme.LocalReducedMotion

/**
 * Live content that glass bars and sheets frost: a page and its backdrop, recorded so glass over it can
 * blur it. Made with [rememberGlassSource] and attached with [glassSource].
 */
@Stable
class GlassSource internal constructor(
    internal val haze: HazeState,
)

/** A [GlassSource] that lives as long as the composition. */
@Composable
fun rememberGlassSource(): GlassSource {
    val haze = rememberHazeState()
    return remember(haze) { GlassSource(haze) }
}

/**
 * Records this content as [source], the live backdrop of the glass that takes it. Glass may never sit
 * inside the content it frosts, so live glass goes beside its source, never under it.
 */
fun Modifier.glassSource(source: GlassSource): Modifier = hazeSource(source.haze)

/** What lies behind a glass surface, which decides how it is frosted. */
sealed interface GlassBackdrop {
    /** Content that moves under the glass, such as a list scrolling under a bar: blurred live. */
    data class Live(
        val source: GlassSource,
    ) : GlassBackdrop

    /**
     * A still backdrop, already blurred and clamped (the calm backdrop, baked album art), or another
     * glass surface: a thin tint over it is enough.
     */
    data object Baked : GlassBackdrop

    /** The live Visual, which Compose cannot see: matte until the engine frosts it ([VisualFrost]). */
    data object Visual : GlassBackdrop

    /** Anything at all, neither blurred nor clamped, such as a sheet over another window: always matte. */
    data object Unknown : GlassBackdrop
}

/** Which of a glass surface's edges catch the light. */
enum class GlassEdge {
    /** Every edge, for a card or a panel. */
    ALL,

    /** The top edge only, for a bar or a sheet whose other edges meet the screen's. */
    TOP,

    /** None, for glass inside other glass. */
    NONE,
}

/** How a glass surface is filled. */
enum class GlassFill {
    /** The backdrop blurred live, clamped and tinted. */
    LIVE,

    /** A thin tint over a backdrop that is already blurred and clamped. */
    TINT,

    /** An opaque tint: nothing behind it is blurred or clamped. */
    MATTE,
}

/** Picks a surface's [GlassFill] from the tier and what lies behind it. */
object GlassFills {
    /**
     * Matte in the Matte tier and over unknown content; live blur over moving content only in the Full
     * tier (Calm turns it matte); a thin tint over the live Visual only while the engine is [frosting]
     * it; a thin tint over a baked backdrop otherwise.
     */
    fun of(
        tier: GlassTier,
        backdrop: GlassBackdrop,
        frosting: Boolean,
    ): GlassFill =
        when {
            tier == GlassTier.MATTE || backdrop == GlassBackdrop.Unknown -> GlassFill.MATTE
            backdrop is GlassBackdrop.Live -> if (tier == GlassTier.FULL) GlassFill.LIVE else GlassFill.MATTE
            backdrop == GlassBackdrop.Visual -> if (frosting) GlassFill.TINT else GlassFill.MATTE
            else -> GlassFill.TINT
        }
}

/**
 * Frosted, matte glass of [kind] in [shape] behind this content: the backdrop (blurred live, baked, or
 * the engine's frost of the Visual), clamped and tinted with the theme's glass colour, then a fine grain
 * and a hairline edge that catches the light. The tier ([LocalGlassStyle]) decides how much of it is
 * drawn ([GlassFills]).
 */
@Composable
fun Modifier.glassSurface(
    kind: GlassKind = GlassKind.CARD,
    shape: Shape = RectangleShape,
    backdrop: GlassBackdrop = GlassBackdrop.Baked,
    edge: GlassEdge = GlassEdge.ALL,
): Modifier {
    val style = LocalGlassStyle.current
    val frost = LocalVisualFrost.current
    val overVisual = backdrop == GlassBackdrop.Visual
    val fill = GlassFills.of(style.tier, backdrop, frosting = overVisual && frost?.frosting == true)
    val blur = rememberLiveBlur(kind, style)
    val light = style.colors.textPrimary
    val edgeBrush = remember(light) { edgeBrushOf(light) }
    val source = (backdrop as? GlassBackdrop.Live)?.source
    val filled =
        when {
            fill == GlassFill.LIVE && source != null -> Modifier.hazeBlur(HazeInput.Sources(source.haze), style = blur)
            fill == GlassFill.TINT -> Modifier.background(style.colors.glass.copy(alpha = style.tintAlpha(kind)))
            else -> Modifier.background(style.colors.glass.copy(alpha = kind.matteAlpha))
        }
    val covering = if (overVisual && frost != null) Modifier.coverVisual(frost, shape) else Modifier
    return this
        .then(covering)
        .clip(shape)
        .then(filled)
        .drawBehind { drawRect(GlassGrain.brush, alpha = GlassTokens.GRAIN_ALPHA, blendMode = BlendMode.Overlay) }
        .then(
            when (edge) {
                GlassEdge.ALL -> Modifier.border(GlassTokens.Edge, edgeBrush, shape)
                GlassEdge.TOP -> Modifier.topEdge(light)
                GlassEdge.NONE -> Modifier
            },
        )
}

/** A hairline of [light] along the top edge only, drawn over the content. */
private fun Modifier.topEdge(light: Color): Modifier =
    drawWithContent {
        drawContent()
        val width = GlassTokens.Edge.toPx()
        drawRect(light.copy(alpha = GlassTokens.EDGE_TOP_ALPHA), size = Size(size.width, width))
    }

/**
 * Glass over the live Visual comes and goes with a fade, never a slide, so the engine's frost and the
 * panel never drift apart (#270). It appears at once under reduced motion.
 */
@Composable
fun GlassFade(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(if (reduced) snap() else tween(GlassTokens.FADE_MILLIS)),
        exit = fadeOut(if (reduced) snap() else tween(GlassTokens.FADE_MILLIS)),
    ) {
        content()
    }
}

/**
 * Shows [target] through [content], and cross-fades when it changes: a calm fade, never a slide, for
 * tab changes and for panels and sheets over the live Visual (#355). The content of the state being
 * left stays on screen while it fades out, so a panel closing with a null [target] fades too. It snaps
 * under reduced motion.
 */
@Composable
fun <T> GlassCrossfade(
    target: T,
    modifier: Modifier = Modifier,
    label: String = "glassCrossfade",
    content: @Composable (T) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    Crossfade(
        targetState = target,
        modifier = modifier,
        animationSpec = if (reduced) snap() else tween(GlassTokens.FADE_MILLIS),
        label = label,
        content = content,
    )
}

/** The live blur for [kind] in [style]: blurred, a little desaturated, clamped toward the base, tinted. */
@Composable
private fun rememberLiveBlur(
    kind: GlassKind,
    style: GlassStyle,
): HazeBlurStyle =
    remember(kind, style) {
        val saturation = ColorMatrix().apply { setToSaturation(GlassTokens.SATURATION) }
        HazeBlurStyle {
            blurRadius(kind.blur)
            // The grain is drawn by the surface itself, the same in every tier.
            noiseFactor(0f)
            colorEffects(
                listOf(
                    HazeColorEffect.colorFilter(ColorFilter.colorMatrix(saturation)),
                    HazeColorEffect.tint(style.colors.base.copy(alpha = 1f - style.clamp)),
                    HazeColorEffect.tint(style.colors.glass.copy(alpha = style.tintAlpha(kind))),
                ),
            )
            fallbackColorEffect(HazeColorEffect.tint(style.colors.glass.copy(alpha = kind.matteAlpha)))
        }
    }

/** The edge light: brightest at the top, faint in the middle, a little brighter at the bottom. */
private fun edgeBrushOf(light: Color): Brush =
    Brush.verticalGradient(
        0f to light.copy(alpha = GlassTokens.EDGE_TOP_ALPHA),
        EDGE_MIDDLE to light.copy(alpha = GlassTokens.EDGE_MIDDLE_ALPHA),
        1f to light.copy(alpha = GlassTokens.EDGE_BOTTOM_ALPHA),
    )

/** Tells the engine where this glass covers the Visual, so it can frost that region (#349). */
@Composable
private fun Modifier.coverVisual(
    frost: VisualFrost,
    shape: Shape,
): Modifier {
    val key = remember { Any() }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    DisposableEffect(frost, key) { onDispose { frost.uncover(key) } }
    return onGloballyPositioned { coordinates ->
        val outline = shape.createOutline(coordinates.size.toSize(), direction, density)
        val corner = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
        frost.cover(key, coordinates.boundsInWindow(), corner)
    }
}

/** Where along the edge the light is faintest. */
private const val EDGE_MIDDLE = 0.5f
