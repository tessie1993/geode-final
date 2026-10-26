package dev.geode.feature.studio.core

import dev.geode.feature.studio.api.RenderAspect

/**
 * The part of a picture a reframe keeps (#328), in the Edit pipeline's normalised coordinates: -1 to 1
 * across ([left] to [right]) and up ([bottom] to [top]).
 */
data class CropBox(
    val left: Float,
    val right: Float,
    val bottom: Float,
    val top: Float,
) {
    companion object {
        private const val SPAN = 2f
        private const val EDGE = 1f

        /**
         * The box of [aspect]'s shape, as large as fits a picture [width] by [height], slid across by
         * [cropX] (0 at the left edge, 1 at the right) when the picture is wider than the shape, or down
         * by [cropY] (0 at the top) when it is taller. Null for a picture of no size.
         */
        fun of(
            width: Int,
            height: Int,
            aspect: RenderAspect,
            cropX: Float,
            cropY: Float,
        ): CropBox? {
            if (width <= 0 || height <= 0) return null
            val picture = width.toFloat() / height
            val shape = aspect.wide.toFloat() / aspect.high
            return if (picture > shape) {
                val kept = SPAN * shape / picture
                val left = -EDGE + (SPAN - kept) * cropX.coerceIn(0f, 1f)
                CropBox(left, left + kept, -EDGE, EDGE)
            } else {
                val kept = SPAN * picture / shape
                val top = EDGE - (SPAN - kept) * cropY.coerceIn(0f, 1f)
                CropBox(-EDGE, EDGE, top - kept, top)
            }
        }
    }
}
