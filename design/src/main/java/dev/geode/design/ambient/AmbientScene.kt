package dev.geode.design.ambient

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * One asset placed in the ambient layer. Positions are fractions of the layer's width and height,
 * so the same placement fits any screen.
 */
@Immutable
data class AmbientSprite(
    val asset: ShippableAsset,
    /** Where the asset's centre starts across the layer, from 0 (left edge) to 1 (right edge). */
    val startX: Float,
    /** Where the asset's centre starts down the layer, from 0 (top) to 1 (bottom). */
    val startY: Float,
    /** Where in its sway the asset starts, in radians. */
    val swayPhase: Float,
    /** The asset's turn when placed, in degrees. */
    val startRotation: Float,
    /** Which way the asset turns while it drifts: 1 clockwise, -1 anticlockwise. */
    val spin: Int,
)

/** Where a sprite is drawn at one moment: its centre as fractions of the layer, and its turn in degrees. */
data class SpritePose(
    val x: Float,
    val y: Float,
    val rotation: Float,
)

/**
 * The placement and drift of the floating assets, as pure maths: the same seed always gives the
 * same scene, so screenshots are stable. Few, large and slow, with the centre left clear and the
 * assets near the edges (`docs/design/research-high-end-references.md`, section 4).
 */
object AmbientScene {
    /** The most assets on screen at once. */
    const val MAX_ON_SCREEN = 12

    /** The seed the layer uses unless it is given one. */
    const val DEFAULT_SEED = 72L

    /** Assets start within this fraction of the width from either side, which keeps the centre clear. */
    const val EDGE_BAND = 0.32f

    /** How far past the top and bottom an asset drifts before it wraps round, as a fraction of the height. */
    const val WRAP_MARGIN = 0.15f

    /** How far an asset sways from side to side, as a fraction of the width. */
    const val SWAY_FRACTION = 0.02f

    /** How long one sway from side to side and back takes, in seconds. */
    const val SWAY_PERIOD_SECONDS = 18f

    /** How fast an asset turns while it drifts, in degrees per second. */
    const val SPIN_DEGREES_PER_SECOND = 1.5f

    /** The largest turn an asset is placed with, either way, in degrees. */
    const val MAX_START_ROTATION = 30f

    private const val FULL_TURN_RADIANS = 2.0 * PI
    private const val CLOCKWISE = 1
    private const val ANTICLOCKWISE = -1

    /**
     * Places up to [maxOnScreen] sprites of [assets], reusing assets in turn when there are fewer
     * than that, alternating left and right edges. Sorted back to front by [DepthLayer] so they
     * draw in order. Empty when there is nothing approved to show.
     */
    fun place(
        assets: List<ShippableAsset>,
        seed: Long,
        maxOnScreen: Int = MAX_ON_SCREEN,
    ): List<AmbientSprite> {
        if (assets.isEmpty() || maxOnScreen <= 0) return emptyList()
        val random = Random(seed)
        val sprites =
            List(maxOnScreen) { index ->
                val fromEdge = random.nextFloat() * EDGE_BAND
                AmbientSprite(
                    asset = assets[index % assets.size],
                    startX = if (index % 2 == 0) fromEdge else 1f - fromEdge,
                    startY = random.nextFloat(),
                    swayPhase = (random.nextDouble() * FULL_TURN_RADIANS).toFloat(),
                    startRotation = (random.nextFloat() * 2f - 1f) * MAX_START_ROTATION,
                    spin = if (random.nextBoolean()) CLOCKWISE else ANTICLOCKWISE,
                )
            }
        return sprites.sortedBy { it.asset.layer.ordinal }
    }

    /**
     * Where [sprite] is [elapsedSeconds] into the drift, with the screen scrolled by [scrollFraction]
     * of its height. At zero seconds and no scroll it is exactly where it was placed, which is how
     * the still mode shows it. It drifts down at its layer's pace, sways gently, follows the scroll
     * by its layer's parallax, and wraps round once it is out of sight.
     */
    fun poseAt(
        sprite: AmbientSprite,
        elapsedSeconds: Float,
        scrollFraction: Float = 0f,
    ): SpritePose {
        val layer = sprite.asset.layer
        val swayAngle = FULL_TURN_RADIANS * elapsedSeconds / SWAY_PERIOD_SECONDS + sprite.swayPhase
        val sway = sin(swayAngle) - sin(sprite.swayPhase.toDouble())
        return SpritePose(
            x = sprite.startX + SWAY_FRACTION * sway.toFloat(),
            y = wrap(sprite.startY + elapsedSeconds / layer.traverseSeconds - scrollFraction * layer.parallax),
            rotation = sprite.startRotation + sprite.spin * SPIN_DEGREES_PER_SECOND * elapsedSeconds,
        )
    }

    /**
     * [y] brought back into the band from just above the top to just below the bottom. A value
     * already in the band is returned as it is: shifting it out and back would round it, and a
     * sprite at rest must sit exactly where it was placed.
     */
    fun wrap(y: Float): Float {
        if (y >= -WRAP_MARGIN && y < 1f + WRAP_MARGIN) return y
        val span = 1f + 2f * WRAP_MARGIN
        return (y + WRAP_MARGIN).mod(span) - WRAP_MARGIN
    }
}
