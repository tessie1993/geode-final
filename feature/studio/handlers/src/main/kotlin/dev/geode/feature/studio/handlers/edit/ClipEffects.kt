package dev.geode.feature.studio.handlers.edit

import android.text.SpannableString
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SingleColorLut
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.edit.ClipEdit
import dev.geode.feature.studio.core.CropBox
import dev.geode.feature.studio.core.FrameSize
import dev.geode.feature.studio.core.RenderPlan

/*
 * A clip's edit (#318) as the Media3 effects that carry it out. The edit itself is data in Studio's
 * core; these are its Android side.
 */

/** The source span the edit keeps. */
fun ClipEdit.clipping(): MediaItem.ClippingConfiguration =
    MediaItem.ClippingConfiguration
        .Builder()
        .setStartPositionMs(startMs.coerceAtLeast(0L))
        .apply { if (endMs > startMs) setEndPositionMs(endMs) }
        .build()

/**
 * [lut] is the loaded table behind [ClipEdit.lutUri]; callers resolve it because loading needs a content
 * resolver. [source] is the clip's picture size, which a moved crop needs (#328).
 */
@UnstableApi
fun ClipEdit.videoEffects(
    lut: CubeLut? = null,
    source: FrameSize? = null,
): List<Effect> = gradeEffects(lut, source) + listOfNotNull(captionEffect())

/**
 * Grade, LUT, gamma, rotation and reframe, in that order; the caption is drawn after everything else.
 * A reframe crops to the middle of the picture, or where the crop was moved to when the clip's [source]
 * size is known and it is not rotated (Make Short, #328).
 */
@UnstableApi
fun ClipEdit.gradeEffects(
    lut: CubeLut? = null,
    source: FrameSize? = null,
): List<Effect> =
    buildList {
        if (brightness != 0f) add(Brightness(brightness))
        if (contrast != 0f) add(Contrast(contrast))
        if (saturation != 0f || hueDegrees != 0f) {
            add(
                HslAdjustment
                    .Builder()
                    .adjustSaturation(saturation)
                    .adjustHue(hueDegrees)
                    .build(),
            )
        }
        if (monochrome) add(RgbFilter.createGrayscaleFilter())
        if (invert) add(RgbFilter.createInvertedFilter())
        if (lut != null) add(SingleColorLut.createFromCube(lut.cube))
        if (hasGamma) add(SingleColorLut.createFromCube(CubeLut.gamma(gammaRed, gammaGreen, gammaBlue).cube))
        if (rotationDegrees != 0f) {
            add(
                ScaleAndRotateTransformation
                    .Builder()
                    .setRotationDegrees(rotationDegrees)
                    .build(),
            )
        }
        ratio?.let { shape ->
            movedCrop(shape, source)?.let { box -> add(Crop(box.left, box.right, box.bottom, box.top)) }
            val frame = RenderPlan.frameOf(RenderFormat(aspect = shape, size = quality))
            add(Presentation.createForWidthAndHeight(frame.width, frame.height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
        }
    }

/** The crop of [shape] where it was moved to in a [source]-sized picture; null when it sits in the middle or cannot be placed. */
private fun ClipEdit.movedCrop(
    shape: RenderAspect,
    source: FrameSize?,
): CropBox? {
    val moved = cropX != ClipEdit.CENTRED || cropY != ClipEdit.CENTRED
    if (!moved || source == null || rotationDegrees != 0f) return null
    return CropBox.of(source.width, source.height, shape, cropX, cropY)
}

/** The caption, low in the frame; null without one. */
@UnstableApi
fun ClipEdit.captionEffect(): Effect? =
    caption.takeIf { it.isNotBlank() }?.let { text ->
        OverlayEffect(
            listOf(
                TextOverlay.createStaticTextOverlay(
                    SpannableString(text),
                    StaticOverlaySettings
                        .Builder()
                        .setBackgroundFrameAnchor(0f, CAPTION_BACKGROUND_Y)
                        .setOverlayFrameAnchor(0f, CAPTION_OVERLAY_Y)
                        .build(),
                ),
            ),
        )
    }

/** The clip's one speed, or null at normal speed. */
@UnstableApi
fun ClipEdit.speedProvider(): SpeedProvider? =
    if (speed == 1f) {
        null
    } else {
        val constant = speed
        object : SpeedProvider {
            override fun getSpeed(timeUs: Long): Float = constant

            override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
        }
    }

private const val CAPTION_BACKGROUND_Y = -0.82f
private const val CAPTION_OVERLAY_Y = -1f
