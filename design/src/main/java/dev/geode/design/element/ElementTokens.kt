package dev.geode.design.element

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily

/**
 * Everything an Element sets for the App UI, as one typed set that every screen and common component
 * reads through [LocalElementTokens]. Every Element fills the same set ([ElementSkins]), so a screen
 * never asks which Element is on: logic and layout stay the same, only these values change.
 *
 * The values are the look and nothing else. They can be restyled, and the art slots filled, without
 * touching any screen or rule.
 */
@Immutable
data class ElementTokens(
    /** What the Element is called in code and in test names, never on screen (screens keep their own strings). */
    val key: String,
    val colors: ElementColors,
    val type: ElementType,
    val shapes: ElementShapes,
    val motion: ElementMotion,
    val art: ElementArt = ElementArt.None,
    /** How the control bodies are drawn until the Element's art lands ([ElementPart]). */
    val finish: PartFinish = PartFinish.FLAT,
)

/**
 * The colour roles of one Element. Dark only in the first wave. Every text role keeps WCAG AA on every
 * ground ([ElementContrastRules]), so text may sit on any of them.
 */
@Immutable
data class ElementColors(
    /** The screen's ground. */
    val background: Color,
    /** The deepest ground: under the bar and the Player. */
    val backgroundDeep: Color,
    /** A label surface: rows, sheets and dialogs. Text sits on it. */
    val surface: Color,
    /** A raised or pressed label surface. */
    val surfaceHigh: Color,
    /** Titles and labels. */
    val textPrimary: Color,
    /** Supporting text: "artist · album", lengths, hints. */
    val textSecondary: Color,
    /** The warm accent of primary actions, such as play. Usable as text. */
    val accent: Color,
    /** Text and glyphs on a filled [accent]. */
    val onAccent: Color,
    /** Selected, on, focused and the current fill. Usable as text. */
    val active: Color,
    /** Text and glyphs on a filled [active]. */
    val onActive: Color,
    /** The second tone of controls, such as idle chips. Decorative: never the only cue. */
    val secondary: Color,
    /** The inner light of a control. Decorative. */
    val glow: Color,
    /** Error text and icons. */
    val error: Color,
    /** Edges and hairlines. Decorative: never the only cue. */
    val divider: Color,
)

/** The two faces an Element is set in: a display face for headlines and a text face for the rest. */
@Immutable
data class ElementType(
    val display: FontFamily,
    val text: FontFamily,
)

/** The organic outlines of an Element's surfaces. */
@Immutable
data class ElementShapes(
    /** Buttons and the play control. */
    val button: Shape,
    /** Chips and small toggles. */
    val chip: Shape,
    /** Cards, rows and label surfaces. */
    val card: Shape,
    /** Bottom sheets: square where they meet the screen's bottom. */
    val sheet: Shape,
    /** The selected place's mark on the bar. */
    val barItem: Shape,
)

/** How long an Element's motions take, in milliseconds. Reduced motion skips them all. */
@Immutable
data class ElementMotion(
    val pressMillis: Int,
    val releaseMillis: Int,
    val focusMillis: Int,
    /** The Element's selection motion, such as Water's stone-spin. */
    val selectMillis: Int,
    /** Moving from one place to another. */
    val pageMillis: Int,
)

/**
 * The Element's art, made with gflow per Element (#105 and the other asset sets). Each slot is a
 * drawable or null; a null slot is drawn in flat token colours, which is how every Element ships until
 * its asset set lands.
 */
@Immutable
data class ElementArt(
    /** The bar's surface texture (asset B1), tiled across the bar. */
    @param:DrawableRes val barSurface: Int? = null,
    /** The calm surface under text (asset S1), tiled under label surfaces. */
    @param:DrawableRes val labelSurface: Int? = null,
) {
    companion object {
        /** No art yet: flat token colours everywhere. */
        val None = ElementArt()
    }
}
