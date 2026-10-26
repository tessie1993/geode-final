package dev.geode.design.glass

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect

/**
 * The engine's frost of the live Visual (#349). Compose cannot see the Visual: it is a GL surface
 * behind a hole in the window. So glass over it tells the engine where it is, and the engine blurs the
 * Visual inside that region; the glass then only adds its tint, grain and edge. It carries geometry
 * only, never audio, and the App UI never reacts to sound.
 *
 * Until something provides it through [LocalVisualFrost], glass over the Visual is matte.
 */
interface VisualFrost {
    /** Whether the engine is frosting the Visual now. While it is not, glass over the Visual stays matte. */
    val frosting: Boolean

    /** The glass known by [key] covers [bounds], in window pixels, with corners of [cornerRadius] pixels. */
    fun cover(
        key: Any,
        bounds: Rect,
        cornerRadius: Float,
    )

    /** The glass known by [key] no longer covers the Visual. */
    fun uncover(key: Any)
}

/** The frost of the live Visual, when the engine offers one (#349); null keeps glass over it matte. */
val LocalVisualFrost = staticCompositionLocalOf<VisualFrost?> { null }
