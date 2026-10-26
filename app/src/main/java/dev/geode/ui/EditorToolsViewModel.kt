package dev.geode.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.geode.R
import dev.geode.editor.transientEnvelopeOf
import dev.geode.engine.types.Millis
import dev.geode.export.ChapterFormat
import dev.geode.export.ChapterMarkers
import dev.geode.export.ChapterWriteResult
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.studio.api.EditSource
import dev.geode.feature.studio.api.edit.ClipEdit
import dev.geode.feature.studio.api.edit.MarkerSet
import dev.geode.feature.studio.api.edit.SubtitleCue
import dev.geode.feature.studio.api.edit.Subtitles
import dev.geode.feature.studio.api.edit.TimedLyric
import dev.geode.feature.studio.api.edit.TransientEnvelope
import dev.geode.feature.studio.api.edit.TransientSource
import dev.geode.feature.studio.handlers.edit.CubeLut
import dev.geode.feature.studio.handlers.edit.clipping
import dev.geode.feature.studio.handlers.edit.videoEffects
import dev.geode.feature.studio.ui.ChapterFile
import dev.geode.feature.studio.ui.ChapterWrite
import dev.geode.feature.studio.ui.EditorTools
import dev.geode.render.TransitionCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What Studio's editors need from the rest of the app (#318), which Studio may not reach itself
 * (ADR-0005): the engine's GL Transitions, the Style the Visual shows, where the player is, the playing
 * song's synced lyrics and its analysed hits, chapter files, and the clip preview drawn through the
 * Edit pipeline's own effects.
 */
@HiltViewModel
// Playback's and Customization's Command and Fact are in/out, so Kotlin gives these constructor
// parameters Java wildcards the provided stores lack; without this Dagger finds no binding for them.
@JvmSuppressWildcards
class EditorToolsViewModel
    @Inject
    constructor(
        private val application: Application,
        private val analysis: TrackAnalysisController,
        private val lyrics: LyricsController,
        private val playback: Playback,
        private val customization: CustomizationFeature,
    ) : ViewModel(),
        EditorTools {
        override val transitions: List<String> by lazy { TransitionCatalog.library(application).map { it.name } }

        override fun currentStyle(): String = customization.state.value.current.style.value

        override fun playbackPositionMs(): Long? {
            val player = playback.state.value.player
            if (!OldScreenModels.meantToPlay(player)) return null
            return OldScreenModels.positionAt(player, Millis(SystemClock.elapsedRealtime())).value
        }

        override fun lyricCues(): List<SubtitleCue>? =
            lyrics.lyrics.value
                ?.takeIf { it.synced }
                ?.let { found -> Subtitles.fromLyrics(found.lines.map { TimedLyric(it.timeMs, it.text) }, trackDurationMs()) }
                ?.takeIf { it.isNotEmpty() }

        override fun transientEnvelope(source: TransientSource): TransientEnvelope? =
            analysis.timeline?.let { transientEnvelopeOf(it, source) }

        override suspend fun writeChapters(
            markers: MarkerSet,
            durationMs: Long,
            file: ChapterFile,
            uri: String,
        ): ChapterWrite =
            withContext(Dispatchers.IO) {
                val chapters = ChapterMarkers.of(markers, durationMs)
                val result =
                    runCatching {
                        application.contentResolver.openOutputStream(Uri.parse(uri))?.use { out -> chapters.writeTo(out, formatOf(file)) }
                            ?: ChapterWriteResult.Failed(NO_OUTPUT_STREAM)
                    }.getOrElse { ChapterWriteResult.Failed(it.message ?: it.toString()) }
                when (result) {
                    ChapterWriteResult.Written -> ChapterWrite.Written
                    ChapterWriteResult.Skipped -> ChapterWrite.Skipped
                    is ChapterWriteResult.Failed -> ChapterWrite.Failed(result.message)
                }
            }

        @Composable
        override fun ClipPreview(
            source: EditSource,
            edit: ClipEdit,
            modifier: Modifier,
        ) = EditPreview(source, edit, modifier)

        @Composable
        override fun Frame(
            file: String,
            atMs: Long,
            modifier: Modifier,
        ) = VideoFrame(file, atMs, modifier, corner = 4.dp)

        /** The playing track's length, for placing lyric captions along it. */
        private fun trackDurationMs(): Long =
            OldScreenModels
                .queueOf(playback.state.value.player)
                ?.current
                ?.duration
                ?.value ?: 0L

        private fun formatOf(file: ChapterFile): ChapterFormat =
            when (file) {
                ChapterFile.DESCRIPTION -> ChapterFormat.DESCRIPTION
                ChapterFile.WEB_VTT -> ChapterFormat.WEB_VTT
                ChapterFile.FFMETADATA -> ChapterFormat.FFMETADATA
            }

        private companion object {
            const val NO_OUTPUT_STREAM = "The destination could not be opened."
        }
    }

/**
 * The clip editor's preview: [source] looping, muted, with [edit]'s trim, speed and effects, rebuilt a
 * moment after the edit settles. A tap pauses and plays it.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun EditPreview(
    source: EditSource,
    edit: ClipEdit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var playing by remember(source.file) { mutableStateOf(true) }
    val player =
        remember(source.file) {
            ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                playWhenReady = true
            }
        }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player, edit) {
        delay(REBUILD_SETTLE_MS)
        val lut = edit.lutUri?.let { uri -> withContext(Dispatchers.IO) { CubeLut.load(context, uri) } }
        player.setVideoEffects(edit.videoEffects(lut))
        player.setMediaItems(
            listOf(
                MediaItem
                    .Builder()
                    .setUri(source.file)
                    .setClippingConfiguration(edit.clipping())
                    .build(),
            ),
        )
        player.playbackParameters = PlaybackParameters(edit.speed)
        player.prepare()
    }
    LaunchedEffect(player, playing) { player.playWhenReady = playing }
    Box(modifier.background(Color.Black).clickable { playing = !playing }, contentAlignment = Alignment.Center) {
        AndroidView(factory = { SurfaceView(it).also { view -> player.setVideoSurfaceView(view) } }, modifier = Modifier.fillMaxWidth())
        if (!playing) {
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = stringResource(R.string.studio_preview_toggle),
                tint = Color.White.copy(alpha = PAUSED_ICON_ALPHA),
                modifier = Modifier.zIndex(1f).size(44.dp),
            )
        }
    }
}

private const val REBUILD_SETTLE_MS = 250L
private const val PAUSED_ICON_ALPHA = 0.85f
