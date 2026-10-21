package dev.geode.design.water

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.geode.design.forest.ForestShapes
import dev.geode.design.forest.LocalForestColors
import dev.geode.design.forest.LocalForestMode
import dev.geode.design.forest.LocalForestThemeId
import kotlin.math.ceil

/**
 * The art behind a screen's frosted stone glass, recorded once per frame so each glass panel can
 * blur the part it covers. It also keeps the screen's [LiveBlurBudget]: at most two panels blur
 * live at once, and any further panel is drawn as [GlassTier.Tinted].
 *
 * Only Compose-drawn art can be a backdrop. The Visual is a GL surface that no Compose blur can
 * sample, so the Player keeps its gradient over the Visual and never puts glass on it.
 */
@Stable
class GlassBackdropState internal constructor() {
    internal var layer: GraphicsLayer? by mutableStateOf(null)
    internal var origin by mutableStateOf(Offset.Zero)
    private var budget by mutableStateOf(LiveBlurBudget())

    /** How many live blurred surfaces the screen draws now; never more than [LiveBlurBudget.MAX_LIVE_BLURS]. */
    val liveBlurs: Int get() = budget.live

    internal fun request(holder: Any) {
        budget = budget.request(holder)
    }

    internal fun release(holder: Any) {
        budget = budget.release(holder)
    }

    internal fun holds(holder: Any): Boolean = budget.holds(holder)
}

/** A [GlassBackdropState] for one screen. */
@Composable
fun rememberGlassBackdropState(): GlassBackdropState = remember { GlassBackdropState() }

private val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdropState?> { null }

/** True inside a glass panel, where no further glass may be drawn (never glass on glass). */
private val LocalInsideGlass = staticCompositionLocalOf { false }

/**
 * A screen with frosted stone glass: [backdrop] is the living art behind (drawn by Compose), and
 * [content] holds the controls, whose [StoneGlass] panels blur that art.
 */
@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    state: GlassBackdropState = rememberGlassBackdropState(),
    backdrop: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val layer = rememberGraphicsLayer()
    DisposableEffect(state, layer) {
        state.layer = layer
        onDispose { state.layer = null }
    }
    Box(modifier) {
        Box(
            Modifier
                .matchParentSize()
                .onGloballyPositioned { state.origin = it.positionInRoot() }
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                },
            content = backdrop,
        )
        CompositionLocalProvider(LocalGlassBackdrop provides state) { content() }
    }
}

/**
 * A panel of frosted stone glass: the backdrop blurred live behind it, the theme's stone tint at an
 * opacity that keeps text legible, a faint scanned stone grain, a soft inner shadow at the bottom
 * and a thin edge that catches light at its top-start corner. Touches send a water ripple through
 * it and play a watery haptic.
 *
 * - [carriesText]: glass under text never drops under 88 % opacity, rising toward 96 % as the
 *   [backdropLuminance] (0 black to 1 white, sampled once from the art behind) rises.
 * - [reduceTransparency]: the person asked for opaque surfaces.
 * - Glass is never drawn on glass: a [StoneGlass] inside another draws only its content.
 * - Without a [GlassBackdrop], or once two panels already blur, the panel is [GlassTier.Tinted].
 */
@Composable
fun StoneGlass(
    modifier: Modifier = Modifier,
    shape: Shape = ForestShapes.Card,
    carriesText: Boolean = true,
    backdropLuminance: Float = 0f,
    reduceTransparency: Boolean = false,
    ripples: WaterRippleState? = rememberWaterRippleState(),
    content: @Composable BoxScope.() -> Unit,
) {
    if (LocalInsideGlass.current) {
        Box(modifier, content = content)
        return
    }
    val backdrop = LocalGlassBackdrop.current
    val holder = remember { Any() }
    if (backdrop != null) {
        DisposableEffect(backdrop, holder) {
            backdrop.request(holder)
            onDispose { backdrop.release(holder) }
        }
    }
    val tier =
        GlassTier.select(
            sdkInt = Build.VERSION.SDK_INT,
            reduceTransparency = reduceTransparency,
            blurGranted = backdrop?.holds(holder) == true,
        )
    val material = StoneGlassMaterial.of(LocalForestThemeId.current, LocalForestMode.current, LocalForestColors.current)
    val grain = ImageBitmap.imageResource(material.grain.drawable)
    val opacity = GlassOpacity.of(backdropLuminance, carriesText, tier, reduceTransparency)
    val blurLayer = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    val haptics = LocalWateryHaptics.current
    Box(
        modifier
            .shadow(
                elevation = WaterTokens.ShadowElevation,
                shape = shape,
                clip = false,
                ambientColor = material.shadow.copy(alpha = WaterTokens.SHADOW_ALPHA),
                spotColor = material.shadow.copy(alpha = WaterTokens.SHADOW_ALPHA),
            ).onGloballyPositioned { origin = it.positionInRoot() }
            .clip(shape)
            .then(if (ripples != null) Modifier.waterRipple(ripples, haptics) else Modifier)
            .drawWithCache {
                val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
                val blurPx = WaterTokens.BlurRadius.toPx()
                val edgePx = WaterTokens.EdgeLightWidth.toPx()
                val grainBrush = ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated))
                val edgeBrush =
                    Brush.linearGradient(
                        0f to material.edgeLight.copy(alpha = WaterTokens.EDGE_LIGHT_ALPHA),
                        WaterTokens.EDGE_LIGHT_REACH to Color.Transparent,
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    )
                val innerShadow =
                    Brush.verticalGradient(
                        WaterTokens.INNER_SHADOW_START to Color.Transparent,
                        1f to material.shadow.copy(alpha = WaterTokens.INNER_SHADOW_ALPHA),
                    )
                onDrawWithContent {
                    val source = backdrop?.layer
                    val backdropOrigin = backdrop?.origin ?: Offset.Zero
                    clipPath(outline) {
                        if (tier == GlassTier.Frosted && source != null) {
                            val pad = ceil(blurPx).toInt()
                            blurLayer.renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
                            blurLayer.topLeft = IntOffset(-pad, -pad)
                            blurLayer.record(IntSize(size.width.toInt() + 2 * pad, size.height.toInt() + 2 * pad)) {
                                val shift = backdropOrigin - origin
                                translate(shift.x + pad, shift.y + pad) { drawLayer(source) }
                            }
                            drawLayer(blurLayer)
                        }
                        drawRect(material.tint.copy(alpha = opacity))
                        drawRect(grainBrush, alpha = material.grainAlpha, blendMode = BlendMode.Overlay)
                        drawRect(innerShadow)
                        drawPath(outline, edgeBrush, style = Stroke(width = edgePx * 2))
                    }
                    drawContent()
                }
            },
    ) {
        CompositionLocalProvider(LocalInsideGlass provides true) { content() }
    }
}
