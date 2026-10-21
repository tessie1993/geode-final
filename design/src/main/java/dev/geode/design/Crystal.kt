package dev.geode.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.geode.design.theme.LocalThemePack
import dev.geode.design.theme.accentTextColor
import kotlin.math.max
import kotlin.math.roundToInt

fun crystalShardShape(
    cut: Dp = CrystalRadius.ShardCut,
    minor: Dp = CrystalRadius.ShardMinor,
): Shape = RoundedCornerShape((cut + minor) * SHARD_ROUNDING)

@Composable
fun CrystalGem(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = CrystalSize.Gem,
    glow: Boolean = true,
) {
    Box(
        modifier
            .size(size)
            .then(if (glow) Modifier.softGlow(color, size) else Modifier)
            .rotate(GEM_ROTATION_DEGREES)
            .background(Brush.linearGradient(listOf(lerp(color, Color.White, GEM_HIGHLIGHT), color))),
    )
}

@Composable
fun CrystalOverline(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = accentTextColor(),
) {
    Text(
        text.uppercase(),
        modifier,
        style =
            MaterialTheme.typography.labelSmall.copy(
                letterSpacing = CrystalLetterSpacing.Overline,
                fontWeight = FontWeight.Medium,
            ),
        color = color.copy(alpha = CrystalAlpha.OVERLINE),
    )
}

@Composable
fun GlowTitle(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineSmall,
    glow: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text,
        modifier,
        style =
            style.copy(
                shadow = Shadow(color = glow.copy(alpha = CrystalAlpha.TITLE_GLOW), blurRadius = CrystalGlow.TITLE_BLUR),
            ),
        color = MaterialTheme.colorScheme.onBackground,
    )
}

fun Modifier.softGlow(
    color: Color,
    radius: Dp = CrystalGlow.Soft,
    strength: Float = 1f,
): Modifier =
    drawBehind {
        val r = max(size.width, size.height) / 2f + radius.toPx()
        drawCircle(
            brush =
                Brush.radialGradient(
                    0f to color.copy(alpha = SOFT_GLOW_ALPHA * strength),
                    1f to Color.Transparent,
                    center = center,
                    radius = r,
                ),
            radius = r,
            center = center,
        )
    }

fun Modifier.crystalPanel(
    opacity: Float,
    tint: Color,
    glow: Color,
    corner: Dp = CrystalRadius.Panel,
    glowStrength: Float = 1f,
    facets: Float = 1f,
    prismatic: Boolean = false,
    sheen: Color = glow,
): Modifier =
    composed {
        val pack = LocalThemePack.current
        val tile = ImageBitmap.imageResource(pack.material.tile)
        val alpha = opacity.coerceIn(0f, 1f)
        val texAlpha = (pack.material.surfaceOpacity * facets.coerceIn(0f, FACETS_MAX)).coerceIn(0f, 1f)
        val shape = RoundedCornerShape(corner)
        val borderBrush = if (prismatic) prismaticBorder(glow, sheen) else glowBorder(glow, glowStrength)
        this
            .clip(shape)
            .background(tint.copy(alpha = alpha))
            .drawBehind {
                if (texAlpha > 0f) {
                    val tw = tile.width
                    val th = tile.height
                    if (tw > 0 && th > 0) {
                        clipRect {
                            var y = 0
                            while (y < size.height.roundToInt()) {
                                var x = 0
                                while (x < size.width.roundToInt()) {
                                    drawImage(
                                        tile,
                                        dstOffset = IntOffset(x, y),
                                        dstSize = IntSize(tw, th),
                                        alpha = texAlpha,
                                    )
                                    x += tw
                                }
                                y += th
                            }
                        }
                    }
                }
            }.border(CrystalSize.Hairline, borderBrush, shape)
    }

private fun prismaticBorder(
    glow: Color,
    sheen: Color,
): Brush =
    Brush.sweepGradient(
        listOf(
            glow.copy(alpha = 0.9f),
            Color.White.copy(alpha = 0.95f),
            sheen.copy(alpha = 0.85f),
            glow.copy(alpha = 0.35f),
            sheen.copy(alpha = 0.8f),
            Color.White.copy(alpha = 0.9f),
            glow.copy(alpha = 0.9f),
        ),
    )

private fun glowBorder(
    glow: Color,
    strength: Float,
): Brush =
    Brush.verticalGradient(
        0f to glow.copy(alpha = (0.85f * strength).coerceAtMost(1f)),
        0.55f to glow.copy(alpha = 0.22f * strength),
        1f to glow.copy(alpha = 0.45f * strength),
    )

fun Modifier.luminousHairline(glow: Color): Modifier =
    background(
        Brush.horizontalGradient(
            0f to Color.Transparent,
            0.5f to glow.copy(alpha = HAIRLINE_PEAK_ALPHA),
            1f to Color.Transparent,
        ),
    )

@Composable
fun CrystalBackground(
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") reducedMotion: Boolean = false,
) {
    val pack = LocalThemePack.current
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(modifier) {
        val art =
            when {
                maxWidth > maxHeight -> pack.material.ambientLandscape
                else -> pack.material.ambientPortrait
            }
        Image(
            painter = painterResource(art),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(cs.background.copy(alpha = CrystalAlpha.BACKGROUND_TINT)),
        )
    }
}

/** How much rounder than the sum of its cuts a shard's corner is. */
private const val SHARD_ROUNDING = 1.2f

/** A gem is a square turned to stand on a corner. */
private const val GEM_ROTATION_DEGREES = 45f

/** How far toward white a gem's lit side is. */
private const val GEM_HIGHLIGHT = 0.55f

/** The alpha at the centre of a full-strength [softGlow]. */
private const val SOFT_GLOW_ALPHA = 0.45f

/** The most a panel's facet texture may be boosted. */
private const val FACETS_MAX = 1.5f

/** The alpha at the middle of a [luminousHairline]. */
private const val HAIRLINE_PEAK_ALPHA = 0.7f
