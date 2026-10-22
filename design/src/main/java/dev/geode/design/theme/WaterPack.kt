package dev.geode.design.theme

import android.os.VibrationEffect
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.geode.design.forest.WcagContrast

/**
 * Water's colour tokens, from the Skin design in #105. WaterPackTest checks each text pairing
 * against WCAG AA.
 */
object WaterTokens {
    /** Deep teal-black water. */
    val Background = Color(0xFF071A1C)

    /** The river floor. */
    val BackgroundDeep = Color(0xFF041113)

    /** A wet slate slab: rows, sheet, card. */
    val Surface = Color(0xFF10282B)

    /** A raised slab: a pressed row, a dialog. */
    val SurfaceHigh = Color(0xFF173538)

    /** Titles and labels. */
    val Text = Color(0xFFE6F4F1)

    /** "artist · album", lengths, hints. */
    val Muted = Color(0xFF9FC2BD)

    /** Amber sunrise: the play pebble and primary actions. */
    val Amber = Color(0xFFF2B35E)

    /** Milky aqua: selected, on, focused, current fill. */
    val Aqua = Color(0xFF7FD8CF)

    /** Sea-green: the second pebble tone, chips. Decorative. */
    val SeaGreen = Color(0xFF5FBFA0)

    /** A pebble's inner core. Decorative. */
    val Glow = Color(0xFFA8F0E6)

    /** Error text and icons. */
    val Danger = Color(0xFFF08A7A)

    /** Slab edges and hairlines. Decorative, never the only cue. */
    val Divider = Color(0xFF2A4A4C)

    /** Text on a lit pebble. */
    val OnPebble = Background
}

/** The three bodies a Water part is made of. */
internal enum class WaterBody {
    /** A domed sea-glass pebble lit from within. */
    PEBBLE,

    /** An opaque wet slate slab. */
    SLAB,

    /** A channel or still pool cut into the stone. */
    RECESS,
}

internal fun waterBody(component: StoneComponent): WaterBody =
    when (component) {
        StoneComponent.ALBUM_TILE,
        StoneComponent.CHIP,
        StoneComponent.COMPACT_BUTTON,
        StoneComponent.ICON_BUTTON,
        StoneComponent.KNOB,
        StoneComponent.PRIMARY_BUTTON,
        StoneComponent.SECONDARY_BUTTON,
        StoneComponent.SLIDER_THUMB,
        -> WaterBody.PEBBLE

        StoneComponent.BOTTOM_SHEET,
        StoneComponent.CARD,
        StoneComponent.DIALOG,
        StoneComponent.LIST_ROW,
        StoneComponent.MINI_PLAYER,
        StoneComponent.NAVIGATION_BAR,
        -> WaterBody.SLAB

        StoneComponent.PROGRESS_RING,
        StoneComponent.SLIDER_TRACK,
        StoneComponent.TEXT_FIELD,
        StoneComponent.TOGGLE,
        -> WaterBody.RECESS
    }

/** The opaque colour of [component]'s body in [state], before its inner light. Disabled parts are dry and dim. */
internal fun waterFill(
    component: StoneComponent,
    state: StoneState,
): Color {
    val wet =
        when (component) {
            StoneComponent.PRIMARY_BUTTON -> WaterTokens.Amber
            StoneComponent.SECONDARY_BUTTON -> WaterTokens.SeaGreen
            StoneComponent.ALBUM_TILE, StoneComponent.COMPACT_BUTTON, StoneComponent.SLIDER_THUMB -> WaterTokens.Aqua
            StoneComponent.CHIP -> if (state == StoneState.SELECTED) WaterTokens.Aqua else WaterTokens.SeaGreen
            StoneComponent.ICON_BUTTON, StoneComponent.KNOB, StoneComponent.DIALOG -> WaterTokens.SurfaceHigh
            StoneComponent.BOTTOM_SHEET, StoneComponent.CARD, StoneComponent.LIST_ROW, StoneComponent.MINI_PLAYER ->
                if (state == StoneState.PRESSED) WaterTokens.SurfaceHigh else WaterTokens.Surface
            StoneComponent.NAVIGATION_BAR, StoneComponent.TEXT_FIELD -> WaterTokens.BackgroundDeep
            StoneComponent.SLIDER_TRACK -> WaterTokens.Surface
            StoneComponent.TOGGLE -> if (state == StoneState.SELECTED) WaterTokens.Aqua else WaterTokens.Divider
            StoneComponent.PROGRESS_RING -> WaterTokens.Divider
        }
    return if (state == StoneState.DISABLED) WcagContrast.over(wet, DRY_OPACITY, WaterTokens.Background) else wet
}

/**
 * How strongly a pebble's core is lit in [state]: brighter when pressed or focused, fullest when
 * selected, off when dry (disabled). Dark pebbles (icon, knob) stay dimmer so light glyphs keep
 * their contrast.
 */
internal fun waterCore(
    component: StoneComponent,
    state: StoneState,
): Float {
    val core =
        when (state) {
            StoneState.DEFAULT -> CORE_IDLE
            StoneState.FOCUSED, StoneState.PRESSED -> CORE_PRESSED
            StoneState.SELECTED -> CORE_SELECTED
            StoneState.DISABLED -> 0f
        }
    val dark = component == StoneComponent.ICON_BUTTON || component == StoneComponent.KNOB
    return if (dark) core * DARK_CORE_SHARE else core
}

/**
 * The darkest the glass gets where a label sits: the body's colour over the river floor, under the
 * dense centre's shade. The see-through edges are outside the label's padding.
 */
internal fun waterPebbleBody(fill: Color): Color =
    WcagContrast.over(
        WaterTokens.BackgroundDeep,
        DENSE_ALPHA,
        WcagContrast.over(fill, BODY_ALPHA, WaterTokens.BackgroundDeep),
    )

/** The colour of a pebble's lit core at [core] strength. */
internal fun waterPebbleCore(
    component: StoneComponent,
    fill: Color,
    core: Float,
): Color = lerp(fill, if (component == StoneComponent.PRIMARY_BUTTON) AMBER_CORE else WaterTokens.Glow, core)

/**
 * Draws a Water part in code. Pebbles read as translucent sea glass, faked with gradients; slabs and
 * channels are opaque wet stone. Nothing is blurred.
 *
 * ponytail: the art in #105 (gflow N1–N5 pebbles, S1 wet slate, T1 touch ring) and the Blender
 * pebble renders are not in the repo yet, so each body is drawn here. When they land as
 * `design/src/main/res/drawable-nodpi/tp_water_<component>_<state>.webp` (the Lapis Lazuli naming),
 * fill the pack's `surfaces` like `lapisLazuli` and drop `drawn`.
 */
@Composable
internal fun WaterSurface(
    component: StoneComponent,
    state: StoneState,
    modifier: Modifier,
    reducedMotion: Boolean,
) {
    val motion = WaterMotion
    val ms =
        when {
            reducedMotion -> motion.reduceMotionCrossfadeMs
            state == StoneState.PRESSED -> motion.pressDurationMs
            state == StoneState.FOCUSED -> motion.focusDurationMs
            state == StoneState.SELECTED -> motion.selectedDurationMs
            else -> motion.releaseDurationMs
        }
    val fill by animateColorAsState(waterFill(component, state), tween(ms), label = "water-fill")
    val core by animateFloatAsState(waterCore(component, state), tween(ms), label = "water-core")
    val sinkTarget =
        when {
            state != StoneState.PRESSED || reducedMotion -> 0.dp
            component == StoneComponent.PRIMARY_BUTTON -> DEEP_SINK
            else -> SINK
        }
    val sink by animateDpAsState(sinkTarget, tween(motion.pressDurationMs), label = "water-sink")
    val focused = state == StoneState.FOCUSED
    val selected = state == StoneState.SELECTED
    val wet = state != StoneState.DISABLED
    Canvas(modifier) {
        when (waterBody(component)) {
            WaterBody.PEBBLE -> pebble(waterPebbleCore(component, fill, 1f), core, fill, sink.toPx(), wet, selected)
            WaterBody.SLAB -> slab(fill, selected)
            WaterBody.RECESS ->
                if (component == StoneComponent.PROGRESS_RING) {
                    // ponytail: the tide-line fill needs the progress value, which the surface is not given.
                    drawCircle(fill, radius = size.minDimension / 2f - RING_WIDTH.toPx() / 2f, style = Stroke(RING_WIDTH.toPx()))
                } else {
                    recess(fill, selected)
                }
        }
        // A still caustic ring marks keyboard and D-pad focus instead of an outline.
        if (focused) outlineRound(WaterTokens.Aqua, FOCUS_STROKE.toPx(), corner(component))
    }
}

private fun DrawScope.corner(component: StoneComponent): Float =
    if (waterBody(component) == WaterBody.PEBBLE) size.minDimension / 2f else SLAB_CORNER.toPx()

/**
 * A translucent 3D sea-glass pebble, faked with layered gradients: no blur, nothing rendered
 * behind it is sampled. From the bottom up: a caustic glow and contact shadow on the stone beneath,
 * a refracted (flipped) copy of the water's tint, a body that is dense at its thick centre and
 * thin and see-through at its edges, the emissive core when active, top light and bottom shade,
 * then a specular rim highlight and a curved wet streak.
 */
private fun DrawScope.pebble(
    lit: Color,
    core: Float,
    fill: Color,
    sink: Float,
    wet: Boolean,
    selected: Boolean,
) {
    val shadow = CONTACT_SHADOW.toPx()
    val body = Size(size.width, size.height - shadow)
    val radius = CornerRadius(body.minDimension / 2f)
    val top = Offset(0f, sink)
    // Beneath: light focused through the glass onto the wet stone, and the contact shadow.
    if (wet) {
        drawOval(
            Brush.radialGradient(
                listOf(fill.copy(alpha = CAUSTIC_ALPHA), Color.Transparent),
                center = Offset(size.width / 2f, size.height - shadow),
                radius = size.width * CAUSTIC_REACH,
            ),
            topLeft = Offset(0f, size.height - shadow * CAUSTIC_HEIGHT),
            size = Size(size.width, shadow * CAUSTIC_HEIGHT),
        )
    }
    drawOval(
        WaterTokens.BackgroundDeep.copy(alpha = SHADOW_ALPHA),
        topLeft = Offset(size.width * SHADOW_INSET, size.height - 2 * shadow),
        size = Size(size.width * (1f - 2 * SHADOW_INSET), 2 * shadow),
    )
    val shape = Path().apply { addRoundRect(RoundRect(top.x, top.y, body.width, top.y + body.height, radius)) }
    clipPath(shape) {
        // The water behind, seen upside down through the lens.
        drawRect(Brush.verticalGradient(listOf(lerp(WaterTokens.Background, fill, REFRACTED_TINT), WaterTokens.BackgroundDeep)))
        // The glass: thin and see-through at the top and bottom edges, dense through the middle.
        drawRect(
            Brush.verticalGradient(
                0f to fill.copy(alpha = EDGE_ALPHA),
                THICK_START to fill.copy(alpha = BODY_ALPHA),
                1f - THICK_START to fill.copy(alpha = BODY_ALPHA),
                1f to fill.copy(alpha = EDGE_ALPHA),
                startY = top.y,
                endY = top.y + body.height,
            ),
        )
        val middle = Offset(body.width / 2f, top.y + body.height / 2f)
        // Denser, so darker, toward the thick centre.
        drawRect(
            Brush.radialGradient(
                listOf(WaterTokens.BackgroundDeep.copy(alpha = DENSE_ALPHA), Color.Transparent),
                center = middle,
                radius = body.height * DENSE_REACH,
            ),
        )
        // The emissive core, lit only when the pebble is active.
        if (core > 0f) {
            drawRect(
                Brush.radialGradient(
                    listOf(lit.copy(alpha = core), Color.Transparent),
                    center = Offset(body.width * CORE_X, top.y + body.height * CORE_Y),
                    radius = body.height * CORE_REACH,
                ),
            )
        }
        // Light from above, shade below, for the dome.
        drawRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = TOP_LIGHT_ALPHA),
                TOP_LIGHT_END to Color.Transparent,
                BOTTOM_SHADE_START to Color.Transparent,
                1f to WaterTokens.BackgroundDeep.copy(alpha = BOTTOM_SHADE_ALPHA),
                startY = top.y,
                endY = top.y + body.height,
            ),
        )
        // The thin edge catches the light.
        drawPath(shape, WaterTokens.Glow.copy(alpha = THIN_EDGE_ALPHA), style = Stroke(THIN_EDGE.toPx()))
    }
    if (!wet) return
    val inset = SPECULAR_INSET.toPx()
    val r = radius.x - inset
    // The specular rim highlight round the top-left of the dome.
    val rim =
        Path().apply {
            arcTo(
                Rect(inset, top.y + inset, inset + 2 * r, top.y + inset + 2 * r),
                startAngleDegrees = SPECULAR_START,
                sweepAngleDegrees = SPECULAR_SWEEP,
                forceMoveTo = true,
            )
            lineTo(maxOf(inset + r, body.width * SPECULAR_LENGTH), top.y + inset)
        }
    drawPath(rim, Color.White.copy(alpha = SPECULAR_ALPHA), style = Stroke(SPECULAR_WIDTH.toPx(), cap = StrokeCap.Round))
    // A curved wet streak across the upper body.
    val streak =
        Path().apply {
            moveTo(body.width * STREAK_X0, top.y + body.height * STREAK_Y0)
            quadraticTo(
                body.width * STREAK_CX,
                top.y + body.height * STREAK_CY,
                body.width * STREAK_X1,
                top.y + body.height * STREAK_Y1,
            )
        }
    drawPath(streak, Color.White.copy(alpha = STREAK_ALPHA), style = Stroke(STREAK_WIDTH.toPx(), cap = StrokeCap.Round))
    if (selected) {
        val rimWidth = SELECTED_RIM.toPx()
        drawRoundRect(
            WaterTokens.Aqua,
            topLeft = top + Offset(rimWidth / 2f, rimWidth / 2f),
            size = Size(body.width - rimWidth, body.height - rimWidth),
            cornerRadius = radius,
            style = Stroke(rimWidth),
        )
    }
}
private fun DrawScope.slab(
    fill: Color,
    selected: Boolean,
) {
    val corner = CornerRadius(SLAB_CORNER.toPx())
    drawRoundRect(fill, cornerRadius = corner)
    // The 1 dp meniscus along the top edge, then the slab's hairline edge.
    val meniscus = MENISCUS.toPx()
    drawLine(
        WaterTokens.Glow.copy(alpha = MENISCUS_ALPHA),
        start = Offset(SLAB_CORNER.toPx(), meniscus),
        end = Offset(size.width - SLAB_CORNER.toPx(), meniscus),
        strokeWidth = meniscus,
    )
    if (selected) {
        outlineRound(WaterTokens.Aqua, SELECTED_RIM.toPx(), SLAB_CORNER.toPx())
    } else {
        outlineRound(WaterTokens.Divider, meniscus, SLAB_CORNER.toPx())
    }
}

private fun DrawScope.recess(
    fill: Color,
    lit: Boolean,
) {
    val corner = CornerRadius(minOf(SLAB_CORNER.toPx(), size.minDimension / 2f))
    drawRoundRect(fill, cornerRadius = corner)
    // Cut into the stone: shade under the top lip, a little light on the bottom lip.
    val lip = MENISCUS.toPx()
    drawLine(WaterTokens.BackgroundDeep, Offset(corner.x, lip), Offset(size.width - corner.x, lip), strokeWidth = 2 * lip)
    drawLine(
        if (lit) WaterTokens.Glow else WaterTokens.Divider,
        Offset(corner.x, size.height - lip),
        Offset(size.width - corner.x, size.height - lip),
        strokeWidth = lip,
    )
}

private fun DrawScope.outlineRound(
    color: Color,
    width: Float,
    corner: Float,
) {
    drawRoundRect(
        color,
        topLeft = Offset(width / 2f, width / 2f),
        size = Size(size.width - width, size.height - width),
        cornerRadius = CornerRadius(corner),
        style = Stroke(width),
    )
}

internal val WaterMotion =
    StoneMotion(
        pressDurationMs = 90,
        pressScale = 0.975f,
        innerGlowGain = 1.3f,
        releaseDurationMs = 220,
        focusDurationMs = 160,
        edgeLightGain = 1.2f,
        selectedDurationMs = 400,
        // Reduced motion swaps the stone-spin for a 200 ms glow swell.
        reduceMotionCrossfadeMs = 200,
        inkBloomMs = 600,
        rippleRingMs = 450,
        selectSpinMs = 400,
        scrollWakeMs = 800,
    )

/** Water's haptics (#105): the grilled Composition primitives, with the waveform fallbacks. */
internal val WaterHaptics =
    mapOf(
        // A single soft droplet.
        StoneHapticCue.TAP to
            StoneHapticPattern(
                primitives = listOf(HapticPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f)),
                timings = listOf(0L, 8L),
                amplitudes = listOf(0, 100),
            ),
        // A wet stone rolling, then settling, timed to the 400 ms spin.
        StoneHapticCue.SELECT to
            StoneHapticPattern(
                primitives =
                    listOf(
                        HapticPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.3f),
                        // ponytail: the delay counts from the end of the rise, whose length the device sets;
                        // 100 ms lands near 400 ms on a Pixel. Tune per device if it drifts.
                        HapticPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.5f, delayMs = 100),
                    ),
                timings = listOf(0L, 60L, 40L, 60L, 40L, 60L, 140L, 12L),
                amplitudes = listOf(0, 30, 0, 50, 0, 70, 0, 130),
            ),
        // A pebble set down.
        StoneHapticCue.TOGGLE_ON to
            StoneHapticPattern(
                primitives = listOf(HapticPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f)),
                timings = listOf(0L, 12L),
                amplitudes = listOf(0, 130),
            ),
        StoneHapticCue.TOGGLE_OFF to
            StoneHapticPattern(
                primitives = listOf(HapticPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.3f)),
                timings = listOf(0L, 6L),
                amplitudes = listOf(0, 75),
            ),
    )

/** Disabled parts are dry: their colour at 40 % over the water. */
private const val DRY_OPACITY = 0.4f
private const val CORE_IDLE = 0f
private const val CORE_PRESSED = 0.5f
private const val CORE_SELECTED = 0.7f
private const val DARK_CORE_SHARE = 0.5f

/** The amber pebble's core warms toward sunrise rather than aqua. */
private val AMBER_CORE = Color(0xFFFFD9A0)

/** Where the inner light sits, as a share of the pebble's width and height, and how far it reaches. */
private const val CORE_X = 0.42f
private const val CORE_Y = 0.38f
private const val CORE_REACH = 0.65f
private const val CAUSTIC_ALPHA = 0.3f
private const val CAUSTIC_REACH = 0.45f
private const val CAUSTIC_HEIGHT = 3f
private const val SHADOW_ALPHA = 0.75f
private const val SHADOW_INSET = 0.1f

/** How much of the pebble's own colour tints the refracted water seen through it. */
private const val REFRACTED_TINT = 0.18f
private const val EDGE_ALPHA = 0.5f
private const val BODY_ALPHA = 0.9f

/** Where the glass reaches full thickness, as a share of its height from each edge. */
private const val THICK_START = 0.3f
private const val DENSE_ALPHA = 0.15f
private const val DENSE_REACH = 0.9f
private const val TOP_LIGHT_ALPHA = 0.14f
private const val TOP_LIGHT_END = 0.4f
private const val BOTTOM_SHADE_START = 0.7f
private const val BOTTOM_SHADE_ALPHA = 0.25f
private const val THIN_EDGE_ALPHA = 0.3f
private val THIN_EDGE = 3.dp
private const val SPECULAR_START = 180f
private const val SPECULAR_SWEEP = 90f
private const val SPECULAR_LENGTH = 0.45f
private const val SPECULAR_ALPHA = 0.6f
private val SPECULAR_INSET = 2.dp
private val SPECULAR_WIDTH = 1.5.dp
private const val STREAK_X0 = 0.2f
private const val STREAK_Y0 = 0.42f
private const val STREAK_CX = 0.32f
private const val STREAK_CY = 0.16f
private const val STREAK_X1 = 0.58f
private const val STREAK_Y1 = 0.24f
private const val STREAK_ALPHA = 0.35f
private val STREAK_WIDTH = 2.dp
private const val MENISCUS_ALPHA = 0.45f
private val MENISCUS = 1.dp
private val SELECTED_RIM = 2.dp
private val FOCUS_STROKE = 2.dp
private val CONTACT_SHADOW = 3.dp
private val SINK = 1.dp
private val DEEP_SINK = 2.dp
private val SLAB_CORNER = 14.dp
private val RING_WIDTH = 4.dp
