package dev.geode.design.theme

import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Draws a part in a state in code, for a pack whose art is not made yet. */
typealias StoneDrawing =
    @Composable (component: StoneComponent, state: StoneState, modifier: Modifier, reducedMotion: Boolean) -> Unit

data class ThemePack(
    val slug: String,
    val palette: StonePalette,
    val motion: StoneMotion,
    val material: StoneMaterial,
    val sounds: StoneSounds,
    val surfaces: Map<StoneComponent, StoneStateArt>,
    /** Draws every part in code in place of [surfaces]; null for a pack with art for every part. */
    val drawn: StoneDrawing? = null,
    /** The text colour on the pack's lit pebbles (buttons, chips); null keeps the theme's onSurface. */
    val pebbleText: Color? = null,
    /** The pack's own haptic patterns. A cue missing here plays the platform's, if it has one. */
    val haptics: Map<StoneHapticCue, StoneHapticPattern> = emptyMap(),
) {
    fun surface(component: StoneComponent): StoneStateArt =
        requireNotNull(surfaces[component]) {
            "theme pack '$slug' is missing surface art for $component"
        }
}

data class StonePalette(
    val background: Color,
    val backgroundDeep: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val glow: Color,
    val onBackground: Color,
    val onSurface: Color,
    val muted: Color,
    val outline: Color,
    val danger: Color,
)

data class StoneMotion(
    val pressDurationMs: Int,
    val pressScale: Float,
    val innerGlowGain: Float,
    val releaseDurationMs: Int,
    val focusDurationMs: Int,
    val edgeLightGain: Float,
    val selectedDurationMs: Int,
    val reduceMotionCrossfadeMs: Int,
    /** Touch: an ink bloom spreading from the touch point and fading, in ms; 0 for none. */
    val inkBloomMs: Int = 0,
    /** Touch: a ring spreading from the touch point and fading, in ms; 0 for none. Never driven by audio. */
    val rippleRingMs: Int = 0,
    /** Selection: one full turn of the selected part about its vertical axis, in ms; 0 for none. */
    val selectSpinMs: Int = 0,
    /** Scrolling a list leaves a wake behind its rows that settles over this long, in ms; 0 for none. */
    val scrollWakeMs: Int = 0,
)

/** The pack's material art. A null drawable is art not made yet: panels go untextured, the background plain. */
data class StoneMaterial(
    @param:DrawableRes val tile: Int?,
    @param:DrawableRes val glowOverlay: Int?,
    @param:DrawableRes val refractionOverlay: Int?,
    @param:DrawableRes val ambientPortrait: Int?,
    @param:DrawableRes val ambientLandscape: Int?,
    @param:DrawableRes val ambientSquare: Int?,
    val backgroundOpacity: Float,
    val surfaceOpacity: Float,
    val disabledOpacity: Float,
)

data class StoneSounds(
    @param:RawRes val click: Int?,
    @param:RawRes val confirm: Int?,
    @param:RawRes val swoop: Int?,
)

enum class StoneComponent {
    ALBUM_TILE,
    BOTTOM_SHEET,
    CARD,
    CHIP,
    COMPACT_BUTTON,
    DIALOG,
    ICON_BUTTON,
    KNOB,
    LIST_ROW,
    MINI_PLAYER,
    NAVIGATION_BAR,
    PRIMARY_BUTTON,
    PROGRESS_RING,
    SECONDARY_BUTTON,
    SLIDER_THUMB,
    SLIDER_TRACK,
    TEXT_FIELD,
    TOGGLE,
}

enum class StoneState {
    DEFAULT,
    FOCUSED,
    PRESSED,
    SELECTED,
    DISABLED,
}

data class StoneStateArt(
    @param:DrawableRes val default: Int,
    @param:DrawableRes val focused: Int,
    @param:DrawableRes val pressed: Int,
    @param:DrawableRes val selected: Int,
    @param:DrawableRes val disabled: Int,
) {
    @DrawableRes
    fun forState(state: StoneState): Int =
        when (state) {
            StoneState.DEFAULT -> default
            StoneState.FOCUSED -> focused
            StoneState.PRESSED -> pressed
            StoneState.SELECTED -> selected
            StoneState.DISABLED -> disabled
        }
}

fun stoneStateOf(
    enabled: Boolean = true,
    pressed: Boolean = false,
    selected: Boolean = false,
    focused: Boolean = false,
): StoneState =
    when {
        !enabled -> StoneState.DISABLED
        pressed -> StoneState.PRESSED
        selected -> StoneState.SELECTED
        focused -> StoneState.FOCUSED
        else -> StoneState.DEFAULT
    }
