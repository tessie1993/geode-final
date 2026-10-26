package dev.geode.feature.studio.handlers.edit

import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderCodec

/** One uniform a GL Transition takes, with its default [values]; [type] is its GLSL type. */
class TransitionParam(
    val name: String,
    val type: String,
    val values: FloatArray,
)

/**
 * A GL Transition as the Edit pipeline draws it between two clips: its `transition(vec2 uv)` GLSL
 * and its uniforms.
 */
class TransitionShader(
    val glsl: String,
    val params: List<TransitionParam>,
)

/**
 * Where the Edit pipeline finds a transition's shader by the id a clip names (#318). `:app` fills it
 * from the engine's transition catalogue, which Studio's handlers may not reach (ADR-0005). Null for an
 * id it does not know: the clip then cuts.
 */
fun interface TransitionShaders {
    fun shader(id: String): TransitionShader?
}

/**
 * What an Edit is exported as (#318): its [aspect], cropping to fill, its frame rate, and whether a
 * looped playback must not hitch on a partial last frame ([loopSafe]).
 */
data class EditOutput(
    val aspect: RenderAspect,
    val fps: Int,
    val loopSafe: Boolean = false,
    val codec: RenderCodec = RenderCodec.H264,
)

/** The video type [codec] is encoded as: HEVC only when the phone has an encoder for it, H.264 otherwise. */
internal fun mimeTypeOf(codec: RenderCodec): String {
    if (codec == RenderCodec.H264) return MediaFormat.MIMETYPE_VIDEO_AVC
    val hevc = MediaFormat.MIMETYPE_VIDEO_HEVC
    val found =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(hevc, ignoreCase = true) }
        }
    return if (found) hevc else MediaFormat.MIMETYPE_VIDEO_AVC
}

/**
 * Runs [block] where a failure is acceptable (letting a resource go that may already be gone), and
 * logs it rather than dropping it.
 */
internal inline fun bestEffort(
    tag: String,
    what: String,
    block: () -> Unit,
) {
    runCatching(block).onFailure { Log.w(tag, "$what failed", it) }
}
