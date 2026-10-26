package dev.geode.studio.media

import android.media.MediaCodecList
import android.media.MediaFormat
import dev.geode.feature.studio.api.RenderCodec

/** The MIME type the phone's encoders know [this] codec by. */
val RenderCodec.mimeType: String
    get() =
        when (this) {
            RenderCodec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            RenderCodec.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
        }

/** This codec when the phone has an encoder for it, otherwise H.264, which every phone has. */
fun RenderCodec.available(): RenderCodec = if (this == RenderCodec.H264 || hasEncoder(mimeType)) this else RenderCodec.H264

private fun hasEncoder(mimeType: String): Boolean =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
    }
