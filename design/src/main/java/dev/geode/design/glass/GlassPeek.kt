package dev.geode.design.glass

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.geode.design.theme.LocalReducedMotion
import kotlinx.coroutines.launch

// A panel over the live Visual that a downward drag lowers out of the way, down to a slim peek bar,
// so the Visual it changes shows in full (#412). It fades, never slides, as glass over the Visual does
// (#270, #355).

/** Where a [PeekPanel] rests: the whole panel, or only its peek bar at the bottom. */
enum class PeekValue(
    /** How far toward the peek bar the panel is at rest here: 0 is the whole panel, 1 the bar alone. */
    val fraction: Float,
) {
    EXPANDED(0f),
    PEEK(1f),
}

/**
 * What a drag let go does: rest at one of the [PeekValue]s, or, from the panel already whole, a pull
 * up past it ([PULLED_UP]), or, from the peek bar, a pull down past it ([PULLED_DOWN]).
 */
enum class PeekOutcome { EXPANDED, PEEK, PULLED_UP, PULLED_DOWN }

/** The pure rules of a [PeekPanel]'s drag, as fractions of the way from the whole panel (0) to the peek bar (1). */
object PeekRules {
    /** A slow release switches only once the drag has covered this share of the way. */
    const val SETTLE_SHARE = 0.25f

    /** [fraction] moved by a drag of [delta] pixels (down is positive) over a way [distance] pixels long. */
    fun dragged(
        fraction: Float,
        delta: Float,
        distance: Float,
    ): Float = if (distance <= 0f) fraction else (fraction + delta / distance).coerceIn(0f, 1f)

    /** The pixels of a drag of [delta] from [fraction] that go past either end of the way: negative above it. */
    fun overshoot(
        fraction: Float,
        delta: Float,
        distance: Float,
    ): Float {
        val at = fraction * distance + delta
        return when {
            at < 0f -> at
            at > distance -> at - distance
            else -> 0f
        }
    }

    /**
     * Where a drag that started at rest at [start] settles once let go at [fraction] with [velocity]
     * pixels a second (down is positive): a fling past [flingVelocity] goes its way, a slow release
     * switches once it covered [SETTLE_SHARE] of the way.
     */
    fun settle(
        start: PeekValue,
        fraction: Float,
        velocity: Float,
        flingVelocity: Float,
    ): PeekValue =
        when {
            velocity > flingVelocity -> PeekValue.PEEK
            velocity < -flingVelocity -> PeekValue.EXPANDED
            start == PeekValue.EXPANDED -> if (fraction >= SETTLE_SHARE) PeekValue.PEEK else PeekValue.EXPANDED
            else -> if (fraction <= 1f - SETTLE_SHARE) PeekValue.EXPANDED else PeekValue.PEEK
        }

    /**
     * What letting go does: [settle], unless the drag pulled [pulled] pixels past the end it started
     * at by more than [threshold], up from the whole panel or down from the peek bar.
     */
    fun release(
        start: PeekValue,
        fraction: Float,
        velocity: Float,
        pulled: Float,
        threshold: Float,
        flingVelocity: Float,
    ): PeekOutcome =
        when {
            start == PeekValue.EXPANDED && fraction <= 0f && pulled <= -threshold -> PeekOutcome.PULLED_UP
            start == PeekValue.PEEK && fraction >= 1f && pulled >= threshold -> PeekOutcome.PULLED_DOWN
            settle(start, fraction, velocity, flingVelocity) == PeekValue.PEEK -> PeekOutcome.PEEK
            else -> PeekOutcome.EXPANDED
        }

    /**
     * The part of a scroll of [delta] pixels a lowered panel takes before its list scrolls: an upward
     * scroll raises the panel back first, up to the whole panel.
     */
    fun preScroll(
        fraction: Float,
        delta: Float,
        distance: Float,
    ): Float = if (fraction > 0f && delta < 0f) maxOf(delta, -fraction * distance) else 0f

    /**
     * The part of a scroll of [delta] pixels left over by the list the panel takes: a downward drag
     * once the list is at its top lowers the panel instead of overscrolling. A fling is never taken.
     */
    fun postScroll(
        fraction: Float,
        delta: Float,
        fromDrag: Boolean,
    ): Float = if (fromDrag && delta > 0f && fraction < 1f) delta else 0f

    /** Whether the panel at [fraction] is away from where it rests at [value], so a let-go must settle it. */
    fun isMoved(
        value: PeekValue,
        fraction: Float,
    ): Boolean = fraction != value.fraction

    /** Whether the whole panel is drawn: everywhere but at rest on the peek bar. */
    fun showsPanel(
        value: PeekValue,
        fraction: Float,
    ): Boolean = value != PeekValue.PEEK || fraction < 1f

    /** Whether the peek bar is drawn: whenever the panel is lowered at all. */
    fun showsBar(
        value: PeekValue,
        fraction: Float,
    ): Boolean = value == PeekValue.PEEK || fraction > 0f
}

/**
 * The state of a [PeekPanel]: where it rests ([value]) and how far toward the peek bar it is now
 * ([fraction]). Remember it with [rememberPeekState], which keeps [value] across a rotation.
 */
@Stable
class PeekState internal constructor(
    initial: PeekValue,
) {
    /** Where the panel rests, or is settling to. */
    var value by mutableStateOf(initial)
        private set

    /** How far toward the peek bar the panel is now: 0 the whole panel, 1 the bar alone. */
    var fraction by mutableFloatStateOf(initial.fraction)
        private set

    /** The panel's height, the length of the way, once measured. */
    internal var distance = 0f
    internal var fallbackDistance = 0f
    internal var threshold = 0f
    internal var flingVelocity = 0f
    internal var reducedMotion = false
    private var pulled = 0f
    private var dragStart = initial

    private val way: Float
        get() = if (distance > 0f) distance else fallbackDistance

    internal val draggable = DraggableState(::dragBy)

    /** Lowers the panel to its peek bar. */
    suspend fun peek() = animateTo(PeekValue.PEEK)

    /** Brings the whole panel back. */
    suspend fun expand() = animateTo(PeekValue.EXPANDED)

    /** Settles at [target]: a fade, or at once under reduced motion. A new drag stops it. */
    suspend fun animateTo(target: PeekValue) {
        value = target
        draggable.drag(MutatePriority.Default) {
            if (reducedMotion) {
                fraction = target.fraction
            } else {
                animate(fraction, target.fraction, animationSpec = tween(GlassTokens.FADE_MILLIS)) { at, _ -> fraction = at }
            }
        }
    }

    internal fun startDrag() {
        pulled = 0f
        dragStart = value
    }

    internal fun dragBy(delta: Float) {
        pulled += PeekRules.overshoot(fraction, delta, way)
        fraction = PeekRules.dragged(fraction, delta, way)
    }

    /** Lets go of a drag at [velocity] pixels a second, and settles where it leads. */
    internal suspend fun release(velocity: Float): PeekOutcome {
        val outcome = PeekRules.release(dragStart, fraction, velocity, pulled, threshold, flingVelocity)
        pulled = 0f
        animateTo(if (outcome == PeekOutcome.PEEK) PeekValue.PEEK else PeekValue.EXPANDED)
        return outcome
    }

    /** Lets a list in the panel lower it from its top and raise it back before it scrolls again. */
    internal val nestedScroll =
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val used = PeekRules.preScroll(fraction, available.y, way)
                if (used != 0f) fraction = PeekRules.dragged(fraction, used, way)
                return Offset(0f, used)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val used = PeekRules.postScroll(fraction, available.y, fromDrag = source == NestedScrollSource.UserInput)
                if (used != 0f) {
                    if (!PeekRules.isMoved(value, fraction)) startDrag()
                    fraction = PeekRules.dragged(fraction, used, way)
                }
                return Offset(0f, used)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!PeekRules.isMoved(value, fraction)) return Velocity.Zero
                startDrag()
                release(available.y)
                return available
            }
        }

    companion object {
        /** Keeps where the panel rests across a rotation or the process coming back. */
        val Saver: Saver<PeekState, String> = Saver(save = { it.value.name }, restore = { PeekState(PeekValue.valueOf(it)) })
    }
}

/** A [PeekState] at [initial], kept across a rotation, that follows reduced motion. */
@Composable
fun rememberPeekState(initial: PeekValue = PeekValue.EXPANDED): PeekState {
    val state = rememberSaveable(saver = PeekState.Saver) { PeekState(initial) }
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    SideEffect {
        state.reducedMotion = reduced
        with(density) {
            state.threshold = PULL_THRESHOLD.toPx()
            state.flingVelocity = FLING_VELOCITY.toPx()
            state.fallbackDistance = FALLBACK_DISTANCE.toPx()
        }
    }
    return state
}

/**
 * The drag of a [PeekPanel]: put it on the panel's handle, so the handle always drags, and the peek
 * bar has it already. A drag down lowers the panel toward its peek bar, a drag up raises it back.
 * Pulling up past the whole panel calls [onPullUp]; pulling down past the peek bar calls [onPullDown].
 */
fun Modifier.peekDrag(
    state: PeekState,
    onPullUp: (() -> Unit)? = null,
    onPullDown: (() -> Unit)? = null,
): Modifier =
    draggable(
        state.draggable,
        Orientation.Vertical,
        onDragStarted = { state.startDrag() },
        onDragStopped = { velocity ->
            when (state.release(velocity)) {
                PeekOutcome.PULLED_UP -> onPullUp?.invoke()
                PeekOutcome.PULLED_DOWN -> onPullDown?.invoke()
                PeekOutcome.EXPANDED, PeekOutcome.PEEK -> Unit
            }
        },
    )

/**
 * A panel over the live Visual that can be lowered out of the way to watch what it changes (#412).
 * A drag down on its handle ([peekDrag]), or on its list once that is scrolled to the top, fades the
 * panel ([content]) out toward a slim glass bar at the bottom ([peekBar]); the changes made in it keep
 * applying live. A tap on the bar's handle, read out as [expandLabel], a drag up on the bar, or Back
 * brings the panel back. A drag down on the bar past its end calls [onDismiss], when there is one.
 *
 * It fades rather than slides, as glass over the Visual does (#270), and snaps under reduced motion.
 * While it rests on the bar the panel is not drawn at all, so it no longer covers the Visual; keep any
 * state the panel must not lose, such as its scroll position, above it.
 */
@Composable
fun PeekPanel(
    state: PeekState,
    expandLabel: String,
    peekBar: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val expand: () -> Unit = { scope.launch { state.expand() } }
    BackHandler(enabled = state.value == PeekValue.PEEK, onBack = expand)
    Box(modifier) {
        if (PeekRules.showsPanel(state.value, state.fraction)) {
            Box(
                Modifier
                    .onSizeChanged { state.distance = it.height.toFloat() }
                    .graphicsLayer { alpha = 1f - state.fraction }
                    .nestedScroll(state.nestedScroll),
            ) { content() }
        }
        if (PeekRules.showsBar(state.value, state.fraction)) {
            PeekBar(state, expandLabel, expand, onDismiss, Modifier.align(Alignment.BottomCenter), peekBar)
        }
    }
}

/** The slim glass bar a lowered [PeekPanel] leaves at the bottom: a handle to bring it back, then [content]. */
@Composable
private fun PeekBar(
    state: PeekState,
    expandLabel: String,
    expand: () -> Unit,
    onDismiss: (() -> Unit)?,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)
    val colors = LocalGlassStyle.current.colors
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = state.fraction }
            .glassSurface(GlassKind.SHEET, shape, GlassBackdrop.Visual, GlassEdge.TOP)
            .peekDrag(state, onPullDown = onDismiss)
            .padding(horizontal = GlassSpacing.Large),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(GlassSize.TapTarget)
                .clickable(role = Role.Button, onClick = expand)
                .semantics { contentDescription = expandLabel },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(GlassSize.HandleWidth, GlassSize.HandleHeight)
                    .background(colors.textSecondary, CircleShape),
            )
        }
        CompositionLocalProvider(LocalContentColor provides colors.textPrimary) {
            Column(Modifier.padding(bottom = GlassSpacing.Small), content = content)
        }
    }
}

/** How far a drag must pull past the end it started at to pull up or down past the panel. */
private val PULL_THRESHOLD = 56.dp

/** A release faster than this, per second, goes the way it was flung. */
private val FLING_VELOCITY = 400.dp

/** The length of the way before the panel was ever measured, as when it comes back on its bar after a rotation. */
private val FALLBACK_DISTANCE = 320.dp
