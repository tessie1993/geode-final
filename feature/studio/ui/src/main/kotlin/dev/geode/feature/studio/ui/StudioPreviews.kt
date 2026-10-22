package dev.geode.feature.studio.ui

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.api.UNSORTED_NAME
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.VideoKind

// The previews the screenshot tests render (ADR-0003). The theme is Material's own, as in the other
// features' screens.

@Composable
private fun PreviewTheme(
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) { Surface(content = content) }
}

internal object PreviewStudio {
    val trip = Project(ProjectId(1), "Summer trip", "content://tree/trip", Millis(3))
    val gigs = Project(ProjectId(2), "Gigs", "content://tree/gigs", Millis(2))
    val unsorted = Project(ProjectId(3), UNSORTED_NAME, null, Millis(1))

    private fun video(
        id: Long,
        kind: VideoKind,
        name: String,
        length: Long,
        width: Int,
        height: Int,
        song: String,
    ) = Video(VideoId(id), trip.id, kind, name, "content://trip/$name", null, Millis(length), width, height, song)

    val videos =
        listOf(
            video(1, VideoKind.RENDER, "Beach at dusk.mp4", 214_000, 1920, 1080, "Midnight City"),
            video(2, VideoKind.RECORDING, "Dunes live.mp4", 65_000, 1080, 1920, "Teardrop"),
            video(3, VideoKind.SHORT, "Waves.mp4", 30_000, 1080, 1920, ""),
            video(4, VideoKind.EDIT, "Trip cut.mp4", 182_000, 1080, 1080, "Midnight City"),
        )

    val state = StudioState(loading = false, projects = listOf(trip, gigs, unsorted), videos = videos)
}

@Preview(name = "Project list")
@Composable
internal fun ProjectListPreview() = PreviewTheme { ProjectListScreen(PreviewStudio.state.toProjectList(), onAction = {}) }

@Preview(name = "Project list, dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun ProjectListDarkPreview() = PreviewTheme(dark = true) { ProjectListScreen(PreviewStudio.state.toProjectList(), onAction = {}) }

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
internal fun VideoPreview() =
    PreviewTheme { VideoScreen(checkNotNull(PreviewStudio.state.toVideoModel(VideoId(1))), onAction = {}) }

@Preview(name = "Delete a Project")
@Composable
internal fun DeleteProjectPreview() = PreviewTheme { DeleteDialog(PreviewStudio.trip.name, onChoice = {}) }
