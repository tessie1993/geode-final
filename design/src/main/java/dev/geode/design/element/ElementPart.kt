package dev.geode.design.element

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.geode.design.forest.ForestMotion
import dev.geode.design.forest.WcagContrast
import dev.geode.design.theme.LocalReducedMotion
import dev.geode.design.theme.StoneComponent
import dev.geode.design.theme.StoneState

/**
 * How an Element's control bodies are drawn in code until its Blender art lands (#105, #180 decision
 * 1). Either way the body is opaque and nothing behind it is sampled or blurred.
 */
enum class PartFinish {
    /** Flat token colours, with the inner light and the selection rim. */
    FLAT,

    /**
     * Water's sea glass: pebbles painted to read as translucent, inner-lit 3D glass (a refracted tint,
     * thin bright edges and a dense centre, a dome light, a specular rim and a wet streak), resting on
     * wet stone with a caustic and a contact shadow. Slabs carry a meniscus along their top edge.
     */
    SEA_GLASS,
}

/** The three bodies a part is made of. */
enum class PartBody {
    /** A domed pebble lit from within: buttons, chips, tiles, knobs and thumbs. */
    PEBBLE,

    /** An opaque slab text sits on: rows, cards, sheets, dialogs and the bar. */
    SLAB,

    /** A channel or still pool cut into the stone: tracks, fields, toggles and rings. */
    RECESS,
}

/** The body [component] is made of, the same in every Element. */
fun partBody(component: StoneComponent): PartBody =
    when (component) {
        StoneComponent.ALBUM_TILE,
        StoneComponent.CHIP,
        StoneComponent.COMPACT_BUTTON,
        StoneComponent.ICON_BUTTON,
        StoneComponent.KNOB,
        StoneComponent.PRIMARY_BUTTON,
        StoneComponent.SECONDARY_BUTTON,
        StoneComponent.SLIDER_THUMB,
        -> PartBody.PEBBLE

        StoneComponent.BOTTOM_SHEET,
        StoneComponent.CARD,
        StoneComponent.DIALOG,
        StoneComponent.LIST_ROW,
        StoneComponent.MINI_PLAYER,
        StoneComponent.NAVIGATION_BAR,
        -> PartBody.SLAB

        StoneComponent.PROGRESS_RING,
        StoneComponent.SLIDER_TRACK,
        StoneComponent.TEXT_FIELD,
        StoneComponent.TOGGLE,
        -> PartBody.RECESS
    }

/** The Element outline [component] is cut to. */
fun partShape(
    shapes: ElementShapes,
    component: StoneComponent,
): Shape =
    when (component) {
        StoneComponent.PRIMARY_BUTTON, StoneComponent.SECONDARY_BUTTON, StoneComponent.COMPACT_BUTTON -> shapes.button
        StoneComponent.CHIP, StoneComponent.TOGGLE, StoneComponent.TEXT_FIELD, StoneComponent.SLIDER_TRACK -> shapes.chip
        StoneComponent.ICON_BUTTON, StoneComponent.KNOB, StoneComponent.SLIDER_THUMB, StoneComponent.PROGRESS_RING -> CircleShape
        StoneComponent.ALBUM_TILE, StoneComponent.CARD, StoneComponent.LIST_ROW, StoneComponent.MINI_PLAYER, StoneComponent.DIALOG ->
            shapes.card
        StoneComponent.BOTTOM_SHEET -> shapes.sheet
        StoneComponent.NAVIGATION_BAR -> RectangleShape
    }

/** The opaque colour of [component]'s body in [state], before its inner light. A disabled part is dry and dim. */
fun partFill(
    colors: ElementColors,
    component: StoneComponent,
    state: StoneState,
): Color {
    val selected = state == StoneState.SELECTED
    val wet =
        when (component) {
            StoneComponent.PRIMARY_BUTTON -> colors.accent
            StoneComponent.SECONDARY_BUTTON -> colors.secondary
            StoneComponent.ALBUM_TILE, StoneComponent.COMPACT_BUTTON, StoneComponent.SLIDER_THUMB -> colors.active
            StoneComponent.CHIP -> if (selected) colors.active else colors.secondary
            StoneComponent.ICON_BUTTON, StoneComponent.KNOB, StoneComponent.DIALOG -> colors.surfaceHigh
            StoneComponent.BOTTOM_SHEET, StoneComponent.CARD, StoneComponent.LIST_ROW, StoneComponent.MINI_PLAYER ->
                if (state == StoneState.PRESSED) colors.surfaceHigh else colors.surface
            StoneComponent.NAVIGATION_BAR, StoneComponent.TEXT_FIELD -> colors.backgroundDeep
            StoneComponent.SLIDER_TRACK -> colors.surface
            StoneComponent.TOGGLE -> if (selected) colors.active else colors.divider
            StoneComponent.PROGRESS_RING -> colors.divider
        }
    return if (state == StoneState.DISABLED) WcagContrast.over(wet, DRY_OPACITY, colors.background) else wet
}

/**
 * The colour of text and glyphs on [component] in [state]: dark on a lit pebble, light on a dark one
 * or a slab. A disabled part keeps its colour; the control fades it.
 */
fun partContent(
    colors: ElementColors,
    component: StoneComponent,
    state: StoneState,
): Color =
    when (partFill(colors, component, if (state == StoneState.DISABLED) StoneState.DEFAULT else state)) {
        colors.accent -> colors.onAccent
        colors.active, colors.secondary -> colors.onActive
        else -> colors.textPrimary
    }

/**
 * How strongly [component]'s core is lit in [state], 0 to 1: brighter when pressed or focused, fullest
 * when selected, off when dry (disabled). The dark pebbles (icon, knob) stay dimmer, so light glyphs
 * keep their contrast.
 */
fun partCore(
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

/** The colour of [component]'s inner light: a sunrise warmth in the accent pebble, the Element's glow elsewhere. */
fun partLight(
    colors: ElementColors,
    component: StoneComponent,
): Color = if (component == StoneComponent.PRIMARY_BUTTON) WcagContrast.over(Color.White, ACCENT_LIGHT, colors.accent) else colors.glow

/**
 * The colour a label sits on at the centre of a pebble whose body is [fill], lit to [core]. Through
 * sea glass that is the glass over the river floor, darkened at its dense centre. The core is a
 * radial glow, so a label sees every value between no core and [core].
 */
fun partFace(
    colors: ElementColors,
    finish: PartFinish,
    component: StoneComponent,
    fill: Color,
    core: Float,
): Color {
    val body =
        when (finish) {
            PartFinish.FLAT -> fill
            PartFinish.SEA_GLASS ->
                WcagContrast.over(
                    colors.backgroundDeep,
                    DENSE_ALPHA,
                    WcagContrast.over(fill, BODY_ALPHA, colors.backgroundDeep),
                )
        }
    return WcagContrast.over(partLight(colors, component), core, body)
}

/** How long a part takes to reach [state]: the Element's own timing, or a short crossfade with motion reduced. */
fun partMillis(
    motion: ElementMotion,
    state: StoneState,
    reducedMotion: Boolean,
): Int =
    when {
        reducedMotion -> ForestMotion.REDUCED_PULSE_MILLIS
        state == StoneState.PRESSED -> motion.pressMillis
        state == StoneState.FOCUSED -> motion.focusMillis
        state == StoneState.SELECTED -> motion.selectMillis
        else -> motion.releaseMillis
    }

/** How far [component] sinks when pressed: the accent pebble deepest, other pebbles a little, the rest not at all. */
fun partSink(
    component: StoneComponent,
    state: StoneState,
    reducedMotion: Boolean,
): Dp =
    when {
        state != StoneState.PRESSED || reducedMotion || partBody(component) != PartBody.PEBBLE -> 0.dp
        component == StoneComponent.PRIMARY_BUTTON -> DEEP_SINK
        else -> SINK
    }

/**
 * [component]'s body in [state], in the current Element ([LocalElementTokens]) and its [PartFinish].
 * The colour, the inner light and the sink ease over the Element's timings; with motion reduced they
 * cross-fade to the same end state. Shapes and brushes are built once per size, and the animated
 * values are read only while drawing.
 */
@Composable
fun ElementPart(
    component: StoneComponent,
    state: StoneState,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = LocalReducedMotion.current,
) {
    val tokens = LocalElementTokens.current
    val colors = tokens.colors
    val ms = partMillis(tokens.motion, state, reducedMotion)
    val fill by animateColorAsState(partFill(colors, component, state), tween(ms), label = "element-part-fill")
    val core by animateFloatAsState(partCore(component, state), tween(ms), label = "element-part-core")
    val sink by animateDpAsState(partSink(component, state, reducedMotion), tween(tokens.motion.pressMillis), label = "element-part-sink")
    val shape = partShape(tokens.shapes, component)
    val finish = tokens.finish
    val body = partBody(component)
    val light = partLight(colors, component)
    val selected = state == StoneState.SELECTED
    val focused = state == StoneState.FOCUSED
    val wet = state != StoneState.DISABLED
    Spacer(
        modifier.drawWithCache {
            val glass = finish == PartFinish.SEA_GLASS
            val bodySize = if (glass && body == PartBody.PEBBLE) Size(size.width, size.height - CONTACT_SHADOW.toPx()) else size
            val outline = shape.createOutline(bodySize, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val rim = SELECTED_RIM.toPx()
            val inner = shape.createOutline(Size(bodySize.width - rim, bodySize.height - rim), layoutDirection, this)
            onDrawBehind {
                when (body) {
                    PartBody.PEBBLE -> {
                        if (glass) seaGlassBed(colors, fill, bodySize, wet)
                        translate(top = sink.toPx()) {
                            pebble(colors, glass, path, outline, bodySize, fill, light, core, wet)
                            if (selected) translate(rim / 2f, rim / 2f) { drawOutline(inner, colors.active, style = Stroke(rim)) }
                        }
                    }
                    PartBody.SLAB -> slab(colors, glass, outline, fill, selected, inner, rim)
                    PartBody.RECESS ->
                        if (component == StoneComponent.PROGRESS_RING) {
                            val width = RING_WIDTH.toPx()
                            drawCircle(fill, radius = size.minDimension / 2f - width / 2f, style = Stroke(width))
                        } else {
                            recess(colors, outline, fill, selected)
                        }
                }
                // A still ring marks keyboard and D-pad focus instead of an outline glow.
                if (focused) translate(rim / 2f, rim / 2f) { drawOutline(inner, colors.active, style = Stroke(rim)) }
            }
        },
    )
}

/** Beneath a sea-glass pebble: light focused through the glass onto the wet stone, and the contact shadow. */
private fun DrawScope.seaGlassBed(
    colors: ElementColors,
    fill: Color,
    body: Size,
    wet: Boolean,
) {
    val shadow = size.height - body.height
    if (wet) {
        drawOval(
            Brush.radialGradient(
                listOf(fill.copy(alpha = CAUSTIC_ALPHA), Color.Transparent),
                center = Offset(size.width / 2f, body.height),
                radius = size.width * CAUSTIC_REACH,
            ),
            topLeft = Offset(0f, size.height - shadow * CAUSTIC_HEIGHT),
            size = Size(size.width, shadow * CAUSTIC_HEIGHT),
        )
    }
    drawOval(
        colors.backgroundDeep.copy(alpha = SHADOW_ALPHA),
        topLeft = Offset(size.width * SHADOW_INSET, size.height - 2 * shadow),
        size = Size(size.width * (1f - 2 * SHADOW_INSET), 2 * shadow),
    )
}

/**
 * A pebble. Flat, it is its fill and its inner light. As sea glass it is painted in layers, from the
 * bottom up: the water behind seen upside down through the lens, a body thin at its edges and dense
 * through the middle, the darker thick centre, the inner light, the dome's top light and bottom shade,
 * a bright thin edge, then a specular rim and a curved wet streak.
 */
private fun DrawScope.pebble(
    colors: ElementColors,
    glass: Boolean,
    path: Path,
    outline: Outline,
    body: Size,
    fill: Color,
    light: Color,
    core: Float,
    wet: Boolean,
) {
    val middle = Offset(body.width / 2f, body.height / 2f)
    clipPath(path) {
        if (glass) {
            drawRect(
                Brush.verticalGradient(listOf(WcagContrast.over(fill, REFRACTED_TINT, colors.background), colors.backgroundDeep)),
                size = body,
            )
            drawRect(
                Brush.verticalGradient(
                    0f to fill.copy(alpha = EDGE_ALPHA),
                    THICK_START to fill.copy(alpha = BODY_ALPHA),
                    1f - THICK_START to fill.copy(alpha = BODY_ALPHA),
                    1f to fill.copy(alpha = EDGE_ALPHA),
                    endY = body.height,
                ),
                size = body,
            )
            drawRect(
                Brush.radialGradient(
                    listOf(colors.backgroundDeep.copy(alpha = DENSE_ALPHA), Color.Transparent),
                    center = middle,
                    radius = body.height * DENSE_REACH,
                ),
                size = body,
            )
        } else {
            drawRect(fill, size = body)
        }
        if (core > 0f) {
            drawRect(
                Brush.radialGradient(
                    listOf(light.copy(alpha = core), Color.Transparent),
                    center = Offset(body.width * CORE_X, body.height * CORE_Y),
                    radius = body.height * CORE_REACH,
                ),
                size = body,
            )
        }
        if (glass) {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = TOP_LIGHT_ALPHA),
                    TOP_LIGHT_END to Color.Transparent,
                    BOTTOM_SHADE_START to Color.Transparent,
                    1f to colors.backgroundDeep.copy(alpha = BOTTOM_SHADE_ALPHA),
                    endY = body.height,
                ),
                size = body,
            )
            drawOutline(outline, colors.glow.copy(alpha = THIN_EDGE_ALPHA), style = Stroke(THIN_EDGE.toPx()))
        }
    }
    if (glass && wet) wetLight(body)
}

/** The specular rim round the top-left of the dome, and a curved wet streak across its upper body. */
private fun DrawScope.wetLight(body: Size) {
    val inset = SPECULAR_INSET.toPx()
    val r = (body.minDimension / 2f - inset).coerceAtLeast(0f)
    val rim =
        Path().apply {
            arcTo(
                Rect(inset, inset, inset + 2 * r, inset + 2 * r),
                startAngleDegrees = SPECULAR_START,
                sweepAngleDegrees = SPECULAR_SWEEP,
                forceMoveTo = true,
            )
            lineTo(maxOf(inset + r, body.width * SPECULAR_LENGTH), inset)
        }
    drawPath(rim, Color.White.copy(alpha = SPECULAR_ALPHA), style = Stroke(SPECULAR_WIDTH.toPx(), cap = StrokeCap.Round))
    val streak =
        Path().apply {
            moveTo(body.width * STREAK_X0, body.height * STREAK_Y0)
            quadraticTo(body.width * STREAK_CX, body.height * STREAK_CY, body.width * STREAK_X1, body.height * STREAK_Y1)
        }
    drawPath(streak, Color.White.copy(alpha = STREAK_ALPHA), style = Stroke(STREAK_WIDTH.toPx(), cap = StrokeCap.Round))
}

/** A slab: its fill, a meniscus along the top edge when it is sea glass, and its hairline or selected rim. */
private fun DrawScope.slab(
    colors: ElementColors,
    glass: Boolean,
    outline: Outline,
    fill: Color,
    selected: Boolean,
    inner: Outline,
    rim: Float,
) {
    drawOutline(outline, fill)
    val hairline = MENISCUS.toPx()
    if (glass) {
        val inset = size.minDimension / 2f * MENISCUS_INSET
        drawLine(
            colors.glow.copy(alpha = MENISCUS_ALPHA),
            Offset(inset, hairline),
            Offset(size.width - inset, hairline),
            strokeWidth = hairline,
        )
    }
    if (selected) {
        translate(rim / 2f, rim / 2f) { drawOutline(inner, colors.active, style = Stroke(rim)) }
    } else {
        drawOutline(outline, colors.divider, style = Stroke(hairline))
    }
}

/** A recess cut into the stone: shade under the top lip, a little light on the bottom lip. */
private fun DrawScope.recess(
    colors: ElementColors,
    outline: Outline,
    fill: Color,
    lit: Boolean,
) {
    drawOutline(outline, fill)
    val lip = MENISCUS.toPx()
    val inset = size.minDimension / 2f
    drawLine(colors.backgroundDeep, Offset(inset, lip), Offset(size.width - inset, lip), strokeWidth = 2 * lip)
    drawLine(
        if (lit) colors.glow else colors.divider,
        Offset(inset, size.height - lip),
        Offset(size.width - inset, size.height - lip),
        strokeWidth = lip,
    )
}

/** A disabled part is dry: its colour at 40 % over the ground. */
private const val DRY_OPACITY = 0.4f
private const val CORE_IDLE = 0f
private const val CORE_PRESSED = 0.5f
private const val CORE_SELECTED = 0.7f
private const val DARK_CORE_SHARE = 0.5f

/** How far the accent pebble's light warms toward white. */
private const val ACCENT_LIGHT = 0.45f

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

/** How far in from each end the meniscus stops, as a share of the slab's half height. */
private const val MENISCUS_INSET = 0.5f
private val MENISCUS = 1.dp
private val SELECTED_RIM = 2.dp
private val CONTACT_SHADOW = 3.dp
private val SINK = 1.dp
private val DEEP_SINK = 2.dp
private val RING_WIDTH = 4.dp
