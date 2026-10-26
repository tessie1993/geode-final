package dev.geode.feature.studio.handlers.edit

import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import dev.geode.feature.studio.api.EditJob
import dev.geode.feature.studio.api.EditSource
import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderCodec
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderPhase
import dev.geode.feature.studio.api.RenderProblem
import dev.geode.feature.studio.api.edit.EditorProject
import dev.geode.feature.studio.core.RenderEnd

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

/**
 * Exports one Edit from the render queue (#318): its cut through Media3 at [format]'s shape, frame
 * rate and codec, saved and handed back as an Edit's video. It reports [progress] (0..1), stops soon
 * after [isCancelled] turns true, and says how it ended. It may throw; the handlers report that as an
 * engine failure.
 */
fun interface EditEngine {
    suspend fun export(
        job: EditJob,
        format: RenderFormat,
        progress: (RenderPhase, Float) -> Unit,
        isCancelled: () -> Boolean,
    ): RenderEnd

    companion object {
        /** No exporting: every Edit fails, as on a build with no Edit pipeline wired. */
        val None: EditEngine = EditEngine { _, _, _, _ -> RenderEnd.Failed(RenderProblem.ENGINE_FAILED) }
    }
}

/**
 * Where the Timeline's cut is kept between visits (#318): `:app` keeps it as the old editor did, in
 * `files/editor`, so a cut made before the move opens as it was, and so do the old editor's other
 * projects (#347), by their names. A null name is the Timeline's own cut. [load] is null when there is
 * none.
 */
interface EditDrafts {
    suspend fun load(name: String?): EditorProject?

    suspend fun save(
        project: EditorProject,
        name: String?,
    )

    /** The names of the cuts kept besides the Timeline's own: the old editor's other projects (#347). */
    suspend fun names(): List<String>

    companion object {
        /** Keeps nothing: the Timeline always opens empty. */
        val None: EditDrafts =
            object : EditDrafts {
                override suspend fun load(name: String?): EditorProject? = null

                override suspend fun save(
                    project: EditorProject,
                    name: String?,
                ) = Unit

                override suspend fun names(): List<String> = emptyList()
            }
    }
}

/**
 * The video files an Edit is cut from (#318): reading a picked file's name, length and size, and
 * copying a source into the Save folder for Settings' Copy, returning the copy's uri. Either may throw.
 */
interface EditVideos {
    /** [file] as the clip editor needs it, or null when it is no video that can be read. */
    suspend fun read(file: String): EditSource?

    /** Copies [file] into the document tree [folder]; the copy's content uri. */
    suspend fun copy(
        file: String,
        folder: String,
    ): String

    companion object {
        /** Reads nothing and copies nothing. */
        val None: EditVideos =
            object : EditVideos {
                override suspend fun read(file: String): EditSource? = null

                override suspend fun copy(
                    file: String,
                    folder: String,
                ): String = error("no copies")
            }
    }
}

/** What Studio's Edits need beyond its storage (#318): the Timeline's [drafts], the [videos] and the [engine]. */
class EditTools(
    val drafts: EditDrafts = EditDrafts.None,
    val videos: EditVideos = EditVideos.None,
    val engine: EditEngine = EditEngine.None,
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
