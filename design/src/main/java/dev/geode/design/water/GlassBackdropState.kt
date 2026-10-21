package dev.geode.design.water

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.GraphicsLayer

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
