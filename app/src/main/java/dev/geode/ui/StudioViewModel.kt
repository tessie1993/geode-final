package dev.geode.ui

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.geode.editor.TransientEnvelope
import dev.geode.editor.TransientSource
import dev.geode.engine.types.Millis
import dev.geode.export.StudioClip
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.studio.api.edit.ClipEdit
import dev.geode.feature.studio.api.edit.ClipId
import dev.geode.feature.studio.api.edit.EditResult
import dev.geode.feature.studio.api.edit.EditorProject
import dev.geode.feature.studio.api.edit.KeyframeId
import dev.geode.feature.studio.api.edit.MarkerId
import dev.geode.feature.studio.api.edit.SubtitleCue
import dev.geode.feature.studio.api.edit.Subtitles
import dev.geode.feature.studio.api.edit.TimedLyric
import dev.geode.render.scene.SceneParams
import dev.geode.ui.studio.EditorActions
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * The old Studio export sheet and timeline editor, over the export, editor, analysis and lyrics layer
 * now provided as app-scoped DI singletons (#17). The Studio feature's own store is separate
 * (ADR-0005); this keeps the old export pipeline reachable exactly as it was until Studio's own export
 * lands.
 */
@HiltViewModel
// Playback's and Customization's Command and Fact are in/out, so Kotlin gives these constructor
// parameters Java wildcards the provided stores lack; without this Dagger finds no binding for them.
@JvmSuppressWildcards
class StudioViewModel
    @Inject
    constructor(
        private val exportController: ExportController,
        private val editorController: EditorController,
        private val analysis: TrackAnalysisController,
        private val lyrics: LyricsController,
        private val playback: Playback,
        private val customization: CustomizationFeature,
    ) : ViewModel(),
        EditorActions {
        val studio: StateFlow<StudioUiState> get() = exportController.studio

        val editor: StateFlow<EditorUiState> get() = editorController.state

        override fun edit(transform: (EditorProject) -> EditorProject) = editorController.edit(transform)

        override fun apply(result: EditResult) = editorController.apply(result)

        override fun undo() = editorController.undo()

        override fun redo() = editorController.redo()

        override fun setPlayhead(ms: Long) = editorController.setPlayhead(ms)

        override fun newClipId(): ClipId = editorController.newClipId()

        override fun newMarkerId(): MarkerId = editorController.newMarkerId()

        override fun newKeyframeId(): KeyframeId = editorController.newKeyframeId()

        override fun transientEnvelope(source: TransientSource): TransientEnvelope? =
            analysis.timeline?.let { TransientEnvelope.from(it, source) }

        override fun currentSceneId(): String = customization.state.value.current.style.value

        override fun currentSceneParams(): SceneParams = SceneParams.DEFAULT

        override fun playbackPositionMs(): Long? {
            val player = playback.state.value.player
            if (!OldScreenModels.meantToPlay(player)) return null
            return OldScreenModels.positionAt(player, Millis(SystemClock.elapsedRealtime())).value
        }

        override fun describeMedia(
            uri: Uri,
            onReady: (StudioClip) -> Unit,
        ) = exportController.describeStudioClip(uri, onReady)

        override fun exportProject(destination: Uri?) =
            exportController.startProjectExport(editorController.state.value.project, destination)

        override fun cancelProjectExport() = exportController.cancelStudioExport()

        override fun lyricCues(): List<SubtitleCue>? =
            lyrics.lyrics.value
                ?.takeIf { it.synced }
                ?.let { found -> Subtitles.fromLyrics(found.lines.map { TimedLyric(it.timeMs, it.text) }, trackDurationMs()) }
                ?.takeIf { it.isNotEmpty() }

        val exportState: StateFlow<ExportUiState> get() = exportController.exportState

        /** The song's tempo from the export's own analysis, for the export dialog (0 until known). */
        val exportTempo: StateFlow<Float> get() = analysis.tempo

        fun prepareExportTempo() = exportController.prepareTempo()

        fun cancelExport() = exportController.cancelExport()

        fun resetExportState() = exportController.resetExportState()

        fun refreshStudioClips() = exportController.refreshStudioClips()

        fun describeStudioClip(
            uri: Uri,
            onReady: (StudioClip) -> Unit,
        ) = exportController.describeStudioClip(uri, onReady)

        fun renameStudioClip(
            uri: String,
            name: String,
            onResult: (Boolean) -> Unit,
        ) = exportController.renameStudioClip(uri, name, onResult)

        fun deleteStudioClip(
            uri: String,
            onResult: (Boolean) -> Unit,
        ) = exportController.deleteStudioClip(uri, onResult)

        fun startStudioExport(
            clip: StudioClip,
            edit: ClipEdit,
            destination: Uri? = null,
        ) = exportController.startStudioExport(clip, edit, destination)

        fun cancelStudioExport() = exportController.cancelStudioExport()

        fun clearStudioResult() = exportController.clearStudioResult()

        val loopState: StateFlow<LoopUiState> get() = exportController.loopState

        fun cancelLoopRender() = exportController.cancelLoopRender()

        fun clearLoopResult() = exportController.clearLoopResult()

        /** The playing track's length, for placing lyric captions along it. */
        private fun trackDurationMs(): Long =
            OldScreenModels
                .queueOf(playback.state.value.player)
                ?.current
                ?.duration
                ?.value ?: 0L
    }
