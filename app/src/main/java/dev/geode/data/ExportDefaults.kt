package dev.geode.data

import dev.geode.export.ExportCodec
import dev.geode.export.ExportQuality
import dev.geode.export.ExportRatio
import dev.geode.export.LoudnessTarget

data class ExportDefaults(
    val quality: ExportQuality = ExportQuality.FHD1080,
    val fps: Int = 60,
    val ratio: ExportRatio = ExportRatio.R16_9,
    val loopSafe: Boolean = false,
    val codec: ExportCodec = ExportCodec.H264,
    val loudnessTargetId: String = LoudnessTarget.LeaveAsIs.id,
)

internal fun exportCodecLabel(codec: ExportCodec): String =
    when (codec) {
        ExportCodec.H264 -> "H.264"
        ExportCodec.HEVC -> "HEVC"
    }

internal fun exportQualityLabel(quality: ExportQuality): String =
    when (quality) {
        ExportQuality.HD720 -> "720p"
        ExportQuality.FHD1080 -> "1080p"
        ExportQuality.UHD4K -> "4K"
    }
