package dev.geode.design.ambient

/**
 * The three depth layers a floating asset is made for. Focus blur is baked into the asset by the
 * asset pipeline (`tools/floating_assets`), never blurred at runtime: [focusBlurBaked] says which
 * layers carry it. Deeper layers drift slower and follow scrolling less, which gives the parallax.
 *
 * Declared back to front: the layer draws them in this order.
 */
enum class DepthLayer(
    /** How long an asset of this layer takes to drift once down the layer's height, in seconds. */
    val traverseSeconds: Float,
    /** How much of the screen's scroll this layer follows, from 0 (none) to 1 (all of it). */
    val parallax: Float,
    /** The opacity the layer's assets are drawn at. */
    val alpha: Float,
    /** Whether the pipeline bakes a focus blur into this layer's assets. */
    val focusBlurBaked: Boolean,
) {
    /** Small, hazy and slow, far behind the screen. */
    Far(traverseSeconds = 120f, parallax = 0.15f, alpha = 0.55f, focusBlurBaked = true),

    /** Sharp, in focus. */
    Mid(traverseSeconds = 75f, parallax = 0.4f, alpha = 0.9f, focusBlurBaked = false),

    /** Larger, softly defocused and more transparent, closest to the glass. */
    Near(traverseSeconds = 45f, parallax = 0.8f, alpha = 0.7f, focusBlurBaked = true),
}
