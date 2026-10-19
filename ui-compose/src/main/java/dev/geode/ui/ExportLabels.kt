package dev.geode.ui

import dev.geode.export.ExportCodec
import dev.geode.export.ExportQuality

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
