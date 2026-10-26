package dev.geode.feature.studio.ui

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.geode.engine.types.Millis
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.TrackId
import dev.geode.feature.studio.api.EditSource
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderExtras
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderId
import dev.geode.feature.studio.api.RenderJob
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderLook
import dev.geode.feature.studio.api.RenderPhase
import dev.geode.feature.studio.api.RenderPreset
import dev.geode.feature.studio.api.RenderProblem
import dev.geode.feature.studio.api.RenderStatus
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.api.SaveFolder
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.api.edit.ClipEdit

// The Studio screens' previews (ADR-0003). The theme is Material's own, as in the other features' screens.

@Composable
private fun PreviewTheme(
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) { Surface(content = content) }
}

internal object PreviewStudio {
    const val FOLDER = "content://com.android.externalstorage.documents/tree/primary%3AMovies%2FGeode"

    val trip = Project(ProjectId(1), "Summer trip", Millis(3))
    val gigs = Project(ProjectId(2), "Gigs", Millis(2))
    val drafts = Project(ProjectId(3), "Drafts", Millis(1))

    private fun video(
        id: Long,
        kind: VideoKind,
        name: String,
        length: Long,
        width: Int,
        height: Int,
        song: String,
    ) = Video(VideoId(id), trip.id, kind, name, "content://videos/$name", null, Millis(length), width, height, song)

    val videos =
        listOf(
            video(1, VideoKind.RENDER, "Beach at dusk.mp4", 214_000, 1920, 1080, "Midnight City"),
            video(2, VideoKind.RECORDING, "Dunes live.mp4", 65_000, 1080, 1920, "Teardrop"),
            video(3, VideoKind.SHORT, "Waves.mp4", 30_000, 1080, 1920, ""),
            video(4, VideoKind.EDIT, "Trip cut.mp4", 182_000, 1080, 1080, "Midnight City"),
        )

    val state =
        StudioState(
            loading = false,
            projects = listOf(trip, gigs, drafts),
            videos = videos,
            defaultProject = gigs.id,
            saveFolder = SaveFolder.Chosen(FOLDER),
        )

    val night = RenderTrack(TrackId("content://media/12"), "Midnight City", Millis(244_000))
    val tide = RenderTrack(TrackId("content://media/7"), "Teardrop", Millis(330_000))
    val dusk = RenderTrack(TrackId("content://media/9"), "Dusk", Millis(198_000))

    private fun render(
        id: Long,
        batch: Long,
        track: RenderTrack,
        status: RenderStatus,
    ) = RenderJob(
        RenderId(id),
        RenderId(batch),
        listOf(track),
        RenderLook.Current,
        RenderFormat(),
        RenderLength.WholeTrack,
        trip.id,
        RenderExtras(),
        status,
    )

    /** A finished Track, and a playlist with one track rendering, one waiting and one failed. */
    val queued =
        state.copy(
            renders =
                listOf(
                    render(1, 1, dusk, RenderStatus.Done),
                    render(2, 2, night, RenderStatus.Running(RenderPhase.RENDERING, 0.42f, 190)),
                    render(3, 2, tide, RenderStatus.Queued),
                    render(4, 2, dusk, RenderStatus.Failed(RenderProblem.ENCODER_REFUSED)),
                ),
        )

    val choices =
        RenderChoices(
            tracks = listOf(dusk, night, tide),
            playlists = listOf(RenderPlaylist("Evening", listOf(night, tide))),
            presets = listOf(LookChoice(PresetId("Night drive"), "Night drive")),
        )
}

@Preview(name = "Project list")
@Composable
internal fun ProjectListPreview() = PreviewTheme { ProjectListScreen(PreviewStudio.state.toProjectList(), onAction = {}) }

@Preview(name = "Project list, dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun ProjectListDarkPreview() = PreviewTheme(dark = true) { ProjectListScreen(PreviewStudio.state.toProjectList(), onAction = {}) }

@Preview(name = "Project list, no folder or default yet")
@Composable
internal fun ProjectListUnsetPreview() =
    PreviewTheme {
        val unset = PreviewStudio.state.copy(defaultProject = null, saveFolder = SaveFolder.NotChosen)
        ProjectListScreen(unset.toProjectList(), onAction = {})
    }

@Preview(name = "Project list, empty")
@Composable
internal fun ProjectListEmptyPreview() = PreviewTheme { ProjectListScreen(StudioState(loading = false).toProjectList(), onAction = {}) }

@Preview(name = "Project")
@Composable
internal fun ProjectPreview() =
    PreviewTheme { ProjectScreen(checkNotNull(PreviewStudio.state.toProjectModel(PreviewStudio.trip.id, null)), onAction = {}) }

@Preview(name = "Project, Recordings only")
@Composable
internal fun ProjectFilteredPreview() =
    PreviewTheme {
        ProjectScreen(checkNotNull(PreviewStudio.state.toProjectModel(PreviewStudio.trip.id, VideoKind.RECORDING)), onAction = {})
    }

@Preview(name = "Video")
@Composable
internal fun VideoPreview() = PreviewTheme { VideoScreen(checkNotNull(PreviewStudio.state.toVideoModel(VideoId(1))), onAction = {}) }

@Preview(name = "Delete a Project with videos")
@Composable
internal fun DeleteProjectPreview() =
    PreviewTheme {
        DeleteProjectPrompt(checkNotNull(PreviewStudio.state.toProjectModel(PreviewStudio.trip.id, null)).deletePrompt, onChoice = {})
    }

@Preview(name = "Delete an empty Project")
@Composable
internal fun DeleteEmptyProjectPreview() =
    PreviewTheme {
        DeleteProjectPrompt(checkNotNull(PreviewStudio.state.toProjectModel(PreviewStudio.gigs.id, null)).deletePrompt, onChoice = {})
    }

@Preview(name = "Studio first-run step")
@Composable
internal fun SetupPreview() = PreviewTheme { StudioSetupScreen(setupModel(null), onAction = {}) }

@Preview(name = "Studio first-run step, folder picked")
@Composable
internal fun SetupPickedPreview() = PreviewTheme { StudioSetupScreen(setupModel(PreviewStudio.FOLDER), onAction = {}) }

@Preview(name = "Project list with the render queue")
@Composable
internal fun RenderQueuePreview() =
    PreviewTheme { ProjectListScreen(PreviewStudio.queued.toProjectList(), onAction = {}, queue = PreviewStudio.queued.toRenderQueue()) }

@Preview(name = "Project list, Visuals off")
@Composable
internal fun RenderVisualsOffPreview() =
    PreviewTheme {
        val off = PreviewStudio.state.copy(visualsOff = true)
        ProjectListScreen(off.toProjectList(), onAction = {}, queue = off.toRenderQueue())
    }

@Preview(name = "Render setup")
@Composable
internal fun RenderSetupPreview() =
    PreviewTheme {
        val draft = RenderDraft.startingFrom(PreviewStudio.state).track(PreviewStudio.night)
        RenderSetupDialog(draft, PreviewStudio.choices, PreviewStudio.state.projects, onDraft = {}, onRender = {}, onDismiss = {})
    }

@Preview(name = "Render setup, a playlist for Shorts", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun RenderSetupPlaylistPreview() =
    PreviewTheme(dark = true) {
        val draft =
            RenderDraft
                .startingFrom(PreviewStudio.state)
                .playlist(PreviewStudio.choices.playlists.first())
                .preset(RenderPreset.TIKTOK_AND_SHORTS)
        RenderSetupDialog(draft, PreviewStudio.choices, PreviewStudio.state.projects, onDraft = {}, onRender = {}, onDismiss = {})
    }

@Preview(name = "Make Short, cropped")
@Composable
internal fun ShortPreview() =
    PreviewTheme {
        val beach = PreviewStudio.videos.first()
        val source = EditSource(beach.file, beach.name, beach.length, beach.width, beach.height)
        val edit = ClipEdit(startMs = 12_000L, endMs = 47_000L, ratio = RenderAspect.R9_16, cropX = 0.3f)
        ShortScreen(ShortModel(source, edit, canRedraw = true, redraw = false, PreviewStudio.state.projects, beach.project), onAction = {})
    }

@Preview(name = "Make Short, drawn again", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun ShortRedrawPreview() =
    PreviewTheme(dark = true) {
        val beach = PreviewStudio.videos.first()
        val source = EditSource(beach.file, beach.name, beach.length, beach.width, beach.height)
        val edit = ClipEdit(endMs = 59_000L, ratio = RenderAspect.R9_16)
        ShortScreen(ShortModel(source, edit, canRedraw = true, redraw = true, PreviewStudio.state.projects, beach.project), onAction = {})
    }

@Preview(name = "Export as")
@Composable
internal fun ExportAsPreview() =
    PreviewTheme {
        ExportAsDialog(checkNotNull(PreviewStudio.state.toVideoModel(VideoId(2)))) {}
    }
