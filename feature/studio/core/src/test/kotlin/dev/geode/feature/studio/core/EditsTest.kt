package dev.geode.feature.studio.core

import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.EditCommand
import dev.geode.feature.studio.api.EditCut
import dev.geode.feature.studio.api.EditJob
import dev.geode.feature.studio.api.EditSession
import dev.geode.feature.studio.api.EditSource
import dev.geode.feature.studio.api.EditSources
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderExtras
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderId
import dev.geode.feature.studio.api.RenderJob
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderLook
import dev.geode.feature.studio.api.RenderLoudness
import dev.geode.feature.studio.api.RenderPhase
import dev.geode.feature.studio.api.RenderStatus
import dev.geode.feature.studio.api.SaveFolder
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.api.edit.Clip
import dev.geode.feature.studio.api.edit.ClipContent
import dev.geode.feature.studio.api.edit.ClipEdge
import dev.geode.feature.studio.api.edit.ClipEdit
import dev.geode.feature.studio.api.edit.ClipId
import dev.geode.feature.studio.api.edit.ClipTransition
import dev.geode.feature.studio.api.edit.EditError
import dev.geode.feature.studio.api.edit.EditorHistory
import dev.geode.feature.studio.api.edit.EditorProject
import dev.geode.feature.studio.api.edit.Interpolation
import dev.geode.feature.studio.api.edit.Keyframe
import dev.geode.feature.studio.api.edit.KeyframeId
import dev.geode.feature.studio.api.edit.KeyframeSheet
import dev.geode.feature.studio.api.edit.KeyframeTrack
import dev.geode.feature.studio.api.edit.Lane
import dev.geode.feature.studio.api.edit.LaneId
import dev.geode.feature.studio.api.edit.LaneKind
import dev.geode.feature.studio.api.edit.Marker
import dev.geode.feature.studio.api.edit.MarkerId
import dev.geode.feature.studio.api.edit.MarkerSet
import dev.geode.feature.studio.api.edit.ParamId
import dev.geode.feature.studio.api.edit.ParamValue
import dev.geode.feature.studio.api.edit.Timeline
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Studio's editors as a transition table (ADR-0003): each editor state against each command and event (#318). */
class EditsTest {
    private val trip = Project(ProjectId(1), "Trip", Millis(10))
    private val gigs = Project(ProjectId(2), "Gigs", Millis(20))
    private val beach =
        Video(VideoId(4), trip.id, VideoKind.RENDER, "beach.mp4", "content://videos/beach.mp4", null, Millis(9_000), 1920, 1080, "Song")
    private val beachSource = EditSource(beach.file, beach.name, beach.length, beach.width, beach.height)
    private val picked = EditSource("content://picked/dune.mp4", "dune", Millis(5_000), 720, 1280)
    private val folder = SaveFolder.Chosen("content://tree/videos")
    private val format = RenderFormat(aspect = RenderAspect.R9_16, fps = 30)
    private val extras = RenderExtras(RenderLoudness.MINUS_14_LUFS)

    private val ready =
        StudioState(
            loading = false,
            projects = listOf(trip, gigs),
            videos = listOf(beach),
            defaultProject = gigs.id,
            renderFormat = format,
            renderExtras = extras,
        )

    private val media = LaneId("lane-1")
    private val text = LaneId("lane-2")
    private val grade = ClipEdit(brightness = 0.3f)
    private val shot = Clip(ClipId("clip-1"), ClipContent.Video("content://v/a", grade), 0L, 4_000L, sourceDurationMs = 10_000L)
    private val words = Clip(ClipId("clip-2"), ClipContent.Text("Hi"), 1_000L, 2_000L)
    private val mark = Marker(MarkerId("marker-1"), 500L)
    private val mediaLane = Lane(media, LaneKind.Media, "Media 1", listOf(shot))
    private val textLane = Lane(text, LaneKind.Text, "Text 1", listOf(words))
    private val cut = EditorProject(Timeline(listOf(mediaLane, textLane), 4_000L), MarkerSet(listOf(mark)))
    private val brightness = ParamId("clip.brightness")

    private fun editing(
        session: EditSession,
        from: StudioState = ready,
    ) = from.copy(edit = session)

    private fun open(
        project: EditorProject = cut,
        playheadMs: Long = 0L,
        refused: EditError? = null,
    ) = editing(EditSession.Cut(EditorHistory(project), playheadMs, refused))

    private fun command(command: StudioCommand) = StudioEvent.Command(command)

    private data class Row(
        val name: String,
        val from: StudioState,
        val event: StudioEvent,
        val to: Next<StudioState, StudioEffect>,
    )

    private fun row(
        name: String,
        from: StudioState,
        event: StudioEvent,
        to: StudioState,
        vararg effects: StudioEffect,
    ) = Row(name, from, event, Next(to, effects.toList()))

    /** A change to [cut] at [playheadMs] that makes [after]: one step of undo, saved behind. */
    private fun made(
        name: String,
        edit: EditCommand,
        after: EditorProject,
        before: EditorProject = cut,
        playheadMs: Long = 0L,
    ) = row(
        name,
        open(before, playheadMs),
        command(edit),
        editing(EditSession.Cut(EditorHistory(before).push(after), playheadMs)).copy(cutRevision = 1),
        Perform(EditWork.SaveCut(after, 1)),
    )

    /** A change to [cut] that is refused with [error]: the cut stays and says why. */
    private fun refused(
        name: String,
        edit: EditCommand,
        error: EditError,
        playheadMs: Long = 0L,
    ) = row(name, open(cut, playheadMs), command(edit), open(cut, playheadMs, error))

    private fun unchanged(
        name: String,
        edit: EditCommand,
        from: StudioState = open(),
    ) = row(name, from, command(edit), from)

    private fun EditorProject.lanes(vararg lanes: Lane): EditorProject = copy(timeline = timeline.copy(lanes = lanes.toList()))

    private fun EditorProject.lasting(durationMs: Long): EditorProject = copy(timeline = timeline.copy(durationMs = durationMs))

    private val openRows: List<Row> =
        listOf(
            row(
                "the clip editor opens on a library video",
                ready,
                command(EditCommand.OpenVideo(beach.id)),
                editing(EditSession.OneClip(beachSource)),
            ),
            row("a video that is gone opens nothing", ready, command(EditCommand.OpenVideo(VideoId(99))), ready),
            row(
                "a picked file is read first",
                ready,
                command(EditCommand.OpenFile(picked.file)),
                editing(EditSession.Reading),
                Perform(EditWork.ReadSource(picked.file)),
            ),
            row("a blank file opens nothing", ready, command(EditCommand.OpenFile(" ")), ready),
            row(
                "the read file opens in the clip editor",
                editing(EditSession.Reading),
                EditEvent.SourceRead(picked),
                editing(EditSession.OneClip(picked)),
            ),
            row(
                "a file that cannot be read closes and says so",
                editing(EditSession.Reading),
                EditEvent.SourceRead(null),
                ready,
                ReportFailure("Could not read that video"),
            ),
            row("a file read after the editor closed is dropped", ready, EditEvent.SourceRead(picked), ready),
            row(
                "the clip editor's edit changes",
                editing(EditSession.OneClip(picked)),
                command(EditCommand.ChangeClip(grade)),
                editing(EditSession.OneClip(picked, grade)),
            ),
            row("no clip edit changes while the clip editor is closed", open(), command(EditCommand.ChangeClip(grade)), open()),
            row(
                "the Timeline loads its saved cut",
                editing(EditSession.OneClip(picked)),
                command(EditCommand.OpenCut),
                editing(EditSession.LoadingCut),
                Perform(EditWork.LoadCut()),
            ),
            row("an open Timeline stays as it is", open(playheadMs = 700), command(EditCommand.OpenCut), open(playheadMs = 700)),
            row(
                "the loaded cut opens, its length fitted to its clips",
                editing(EditSession.LoadingCut),
                EditEvent.CutLoaded(cut.lasting(0L)),
                open(cut),
            ),
            row("a cut loaded after the Timeline closed is dropped", ready, EditEvent.CutLoaded(cut), ready),
            row(
                "the earlier editor's cuts are listed",
                ready,
                EditEvent.CutsListed(listOf("Gig", "Trailer")),
                ready.copy(savedCuts = listOf("Gig", "Trailer")),
            ),
            row(
                "an earlier cut opens by its name",
                ready.copy(savedCuts = listOf("Gig")),
                command(EditCommand.OpenSavedCut("Gig")),
                editing(EditSession.LoadingCut, ready.copy(savedCuts = listOf("Gig"))),
                Perform(EditWork.LoadCut("Gig")),
            ),
            row(
                "a cut the earlier editor did not keep opens nothing",
                ready.copy(savedCuts = listOf("Gig")),
                command(EditCommand.OpenSavedCut("Other")),
                ready.copy(savedCuts = listOf("Gig")),
            ),
            row(
                "an earlier cut already open stays as it is",
                editing(EditSession.Cut(EditorHistory(cut), 300L, name = "Gig"), ready.copy(savedCuts = listOf("Gig"))),
                command(EditCommand.OpenSavedCut("Gig")),
                editing(EditSession.Cut(EditorHistory(cut), 300L, name = "Gig"), ready.copy(savedCuts = listOf("Gig"))),
            ),
            row(
                "the Timeline's own cut opens in place of an earlier one",
                editing(EditSession.Cut(EditorHistory(cut), name = "Gig")),
                command(EditCommand.OpenCut),
                editing(EditSession.LoadingCut),
                Perform(EditWork.LoadCut()),
            ),
            row(
                "an earlier cut opens under its name",
                editing(EditSession.LoadingCut),
                EditEvent.CutLoaded(cut, "Gig"),
                editing(EditSession.Cut(EditorHistory(cut), name = "Gig")),
            ),
            row(
                "a change to an earlier cut is kept under its name",
                editing(EditSession.Cut(EditorHistory(cut), name = "Gig")),
                command(EditCommand.DeleteMarker(mark.id)),
                editing(EditSession.Cut(EditorHistory(cut).push(cut.copy(markers = MarkerSet())), name = "Gig")).copy(cutRevision = 1),
                Perform(EditWork.SaveCut(cut.copy(markers = MarkerSet()), 1, "Gig")),
            ),
            row("closing the Timeline", open(), command(EditCommand.Close), ready),
            row("closing the clip editor", editing(EditSession.OneClip(picked)), command(EditCommand.Close), ready),
        )

    private val clipRows: List<Row> =
        listOf(
            made(
                "a new lane goes under the others with a new id",
                EditCommand.AddLane(LaneKind.Audio, " Music "),
                cut.lanes(mediaLane, textLane, Lane(LaneId("lane-3"), LaneKind.Audio, "Music")),
            ),
            made(
                "a lane is muted and locked",
                EditCommand.SetLane(text, muted = true, locked = true),
                cut.lanes(mediaLane, textLane.copy(muted = true, locked = true)),
            ),
            refused(
                "a lane that is gone cannot be set",
                EditCommand.SetLane(LaneId("x"), true, false),
                EditError.LaneNotFound(LaneId("x")),
            ),
            made(
                "a clip lands at the playhead with a new id, and the cut grows to hold it",
                EditCommand.AddClip(media, ClipContent.Still("content://s/b"), 2_000L),
                cut
                    .lanes(
                        mediaLane.copy(clips = listOf(shot, Clip(ClipId("clip-3"), ClipContent.Still("content://s/b"), 5_000L, 2_000L))),
                        textLane,
                    ).lasting(7_000L),
                playheadMs = 5_000L,
            ),
            refused(
                "a clip on top of another is refused",
                EditCommand.AddClip(media, ClipContent.Still("content://s/b"), 2_000L),
                EditError.Overlaps(shot.id),
                playheadMs = 1_000L,
            ),
            refused(
                "a clip on the wrong kind of lane is refused",
                EditCommand.AddClip(text, ClipContent.Still("content://s/b"), 2_000L),
                EditError.WrongLaneKind(text, LaneKind.Text, LaneKind.Media),
                playheadMs = 5_000L,
            ),
            made(
                "a clip moves, and the cut's length follows",
                EditCommand.MoveClip(words.id, 4_000L),
                cut.lanes(mediaLane, textLane.copy(clips = listOf(words.copy(startMs = 4_000L)))).lasting(6_000L),
            ),
            made(
                "a clip's end is trimmed",
                EditCommand.TrimClip(shot.id, ClipEdge.END, 2_000L),
                cut.lanes(mediaLane.copy(clips = listOf(shot.copy(durationMs = 2_000L))), textLane).lasting(3_000L),
            ),
            made(
                "a clip splits, its tail with a new id",
                EditCommand.SplitClip(shot.id, 1_000L),
                cut.lanes(
                    mediaLane.copy(
                        clips =
                            listOf(
                                shot.copy(durationMs = 1_000L),
                                shot.copy(id = ClipId("clip-3"), startMs = 1_000L, durationMs = 3_000L, sourceInMs = 1_000L),
                            ),
                    ),
                    textLane,
                ),
            ),
            refused("a split outside the clip is refused", EditCommand.SplitClip(words.id, 3_500L), EditError.OutsideClip),
            made(
                "a clip is deleted, leaving its gap",
                EditCommand.DeleteClip(words.id),
                cut.lanes(mediaLane, textLane.copy(clips = emptyList())),
            ),
            made(
                "a ripple delete closes the gap on its lane",
                EditCommand.DeleteClip(shot.id, ripple = true),
                cut.lanes(mediaLane.copy(clips = listOf(words.copy(id = ClipId("clip-9"), startMs = 1_000L))), textLane).lasting(3_000L),
                before = cut.lanes(mediaLane.copy(clips = listOf(shot, words.copy(id = ClipId("clip-9"), startMs = 5_000L))), textLane),
            ),
            made(
                "a clip is duplicated after itself with a new id",
                EditCommand.DuplicateClip(words.id),
                cut
                    .lanes(
                        mediaLane,
                        textLane.copy(clips = listOf(words, words.copy(id = ClipId("clip-3"), startMs = 3_000L))),
                    ).lasting(5_000L),
            ),
            made(
                "a clip is turned off",
                EditCommand.EnableClip(words.id, false),
                cut.lanes(mediaLane, textLane.copy(clips = listOf(words.copy(enabled = false)))),
            ),
            refused(
                "a clip that is gone cannot be turned off",
                EditCommand.EnableClip(ClipId("x"), false),
                EditError.ClipNotFound(ClipId("x")),
            ),
            made(
                "a clip opens with a transition",
                EditCommand.SetTransition(shot.id, ClipTransition("fade", 400L)),
                cut.lanes(mediaLane.copy(clips = listOf(shot.copy(transition = ClipTransition("fade", 400L)))), textLane),
            ),
            refused(
                "a transition on a clip that is gone is refused",
                EditCommand.SetTransition(ClipId("x"), null),
                EditError.ClipNotFound(ClipId("x")),
            ),
            row(
                "a change after a refusal clears it",
                open(cut, 0L, EditError.OutsideClip),
                command(EditCommand.EnableClip(words.id, false)),
                editing(
                    EditSession.Cut(
                        EditorHistory(cut).push(cut.lanes(mediaLane, textLane.copy(clips = listOf(words.copy(enabled = false))))),
                    ),
                ).copy(cutRevision = 1),
                Perform(EditWork.SaveCut(cut.lanes(mediaLane, textLane.copy(clips = listOf(words.copy(enabled = false)))), 1)),
            ),
            row(
                "each save is numbered after the last, so an older one never lands over it",
                open().copy(cutRevision = 41),
                command(EditCommand.EnableClip(words.id, false)),
                editing(
                    EditSession.Cut(
                        EditorHistory(cut).push(cut.lanes(mediaLane, textLane.copy(clips = listOf(words.copy(enabled = false))))),
                    ),
                ).copy(cutRevision = 42),
                Perform(EditWork.SaveCut(cut.lanes(mediaLane, textLane.copy(clips = listOf(words.copy(enabled = false)))), 42)),
            ),
            row(
                "a refused change saves nothing and keeps the count",
                open().copy(cutRevision = 7),
                command(EditCommand.SplitClip(words.id, 3_500L)),
                open(cut, 0L, EditError.OutsideClip).copy(cutRevision = 7),
            ),
            unchanged("a change that changes nothing is no step of undo", EditCommand.EnableClip(words.id, true)),
            unchanged("the Timeline's changes do nothing while it is closed", EditCommand.AddLane(LaneKind.Text, "Text"), ready),
        )

    private val caption = Clip(ClipId("anything"), ClipContent.Text("La la"), 2_500L, 1_000L)

    private val laneRows: List<Row> =
        listOf(
            made(
                "captions come on a lane of their own, each clip with a new id",
                EditCommand.AddClips(LaneKind.Text, " Captions ", listOf(caption), ownLane = true),
                cut.lanes(
                    mediaLane,
                    textLane,
                    Lane(LaneId("lane-3"), LaneKind.Text, "Captions", listOf(caption.copy(id = ClipId("clip-3")))),
                ),
            ),
            made(
                "clips lay over the first lane of their kind, overwriting what they cover",
                EditCommand.AddClips(LaneKind.Text, "Captions", listOf(caption), ownLane = false),
                cut.lanes(
                    mediaLane,
                    textLane.copy(clips = listOf(words.copy(durationMs = 1_500L), caption.copy(id = ClipId("clip-3")))),
                ),
            ),
            made(
                "clips of a kind with no lane get a new one",
                EditCommand.AddClips(
                    LaneKind.Visual,
                    "Visual",
                    listOf(caption.copy(content = ClipContent.Scene("tunnel"))),
                    ownLane = false,
                ),
                cut.lanes(
                    mediaLane,
                    textLane,
                    Lane(
                        LaneId("lane-3"),
                        LaneKind.Visual,
                        "Visual",
                        listOf(caption.copy(id = ClipId("clip-3"), content = ClipContent.Scene("tunnel"))),
                    ),
                ),
            ),
            unchanged("no clips change nothing", EditCommand.AddClips(LaneKind.Text, "Captions", emptyList(), ownLane = true)),
        )

    private val track = KeyframeTrack(brightness, listOf(Keyframe(KeyframeId("key-1"), 0L, ParamValue.Scalar(0.3f))), shot.id)
    private val keyed = cut.copy(keyframes = KeyframeSheet(listOf(track)))

    private val markRows: List<Row> =
        listOf(
            made(
                "markers are placed with new ids",
                EditCommand.AddMarkers(listOf(Marker(MarkerId(""), 2_000L), Marker(MarkerId(""), 3_000L))),
                cut.copy(markers = MarkerSet(listOf(mark, Marker(MarkerId("marker-2"), 2_000L), Marker(MarkerId("marker-3"), 3_000L)))),
            ),
            made(
                "a marker moves",
                EditCommand.MoveMarker(mark.id, 800L),
                cut.copy(markers = MarkerSet(listOf(mark.copy(atMs = 800L)))),
            ),
            unchanged("a marker that is gone does not move", EditCommand.MoveMarker(MarkerId("x"), 800L)),
            made("a marker is deleted", EditCommand.DeleteMarker(mark.id), cut.copy(markers = MarkerSet())),
            made(
                "a clip's track starts from the clip's value at the playhead",
                EditCommand.AddTrack(brightness, shot.id),
                keyed.copy(
                    keyframes =
                        KeyframeSheet(
                            listOf(track.copy(keys = listOf(Keyframe(KeyframeId("key-1"), 600L, ParamValue.Scalar(0.3f))))),
                        ),
                ),
                playheadMs = 600L,
            ),
            made(
                "a track over the whole cut starts from the value as shot",
                EditCommand.AddTrack(brightness, null),
                cut.copy(
                    keyframes =
                        KeyframeSheet(
                            listOf(KeyframeTrack(brightness, listOf(Keyframe(KeyframeId("key-1"), 0L, ParamValue.Scalar(0f))))),
                        ),
                ),
            ),
            unchanged("a value no clip has cannot be animated", EditCommand.AddTrack(ParamId("scene.zoom"), null)),
            unchanged("a track already there is not added again", EditCommand.AddTrack(brightness, shot.id), open(keyed)),
            made(
                "a key holds the track's value where it lands",
                EditCommand.AddKey(brightness, shot.id, 2_000L),
                keyed.copy(
                    keyframes =
                        KeyframeSheet(
                            listOf(
                                track.copy(
                                    keys =
                                        track.keys + Keyframe(KeyframeId("key-2"), 2_000L, ParamValue.Scalar(0.3f)),
                                ),
                            ),
                        ),
                ),
                before = keyed,
            ),
            unchanged("a key on no track is not added", EditCommand.AddKey(brightness, null, 2_000L), open(keyed)),
            made(
                "a key's curve changes",
                EditCommand.ChangeKey(brightness, shot.id, track.keys.single().copy(interpolation = Interpolation.Hold)),
                keyed.copy(
                    keyframes =
                        KeyframeSheet(
                            listOf(track.copy(keys = listOf(track.keys.single().copy(interpolation = Interpolation.Hold)))),
                        ),
                ),
                before = keyed,
            ),
            unchanged(
                "a key of another shape is refused and changes nothing",
                EditCommand.ChangeKey(brightness, shot.id, track.keys.single().copy(value = ParamValue.Toggle(true))),
                open(keyed),
            ),
            made(
                "a key moves",
                EditCommand.MoveKey(brightness, shot.id, KeyframeId("key-1"), 1_200L),
                keyed.copy(keyframes = KeyframeSheet(listOf(track.copy(keys = listOf(track.keys.single().copy(atMs = 1_200L)))))),
                before = keyed,
            ),
            made(
                "a key is deleted",
                EditCommand.DeleteKey(KeyframeId("key-1")),
                keyed.copy(keyframes = KeyframeSheet(listOf(track.copy(keys = emptyList())))),
                before = keyed,
            ),
            unchanged("a key that is gone is not deleted", EditCommand.DeleteKey(KeyframeId("x")), open(keyed)),
        )

    private val after = cut.lanes(mediaLane, textLane.copy(clips = emptyList()))
    private val withPast = editing(EditSession.Cut(EditorHistory(cut).push(after)))
    private val undone = editing(EditSession.Cut(EditorHistory(cut).push(after).undo()))

    private val historyRows: List<Row> =
        listOf(
            row(
                "undo goes back a step and saves",
                withPast,
                command(EditCommand.Undo),
                undone.copy(cutRevision = 1),
                Perform(EditWork.SaveCut(cut, 1)),
            ),
            row(
                "redo goes forward a step and saves",
                undone.copy(cutRevision = 1),
                command(EditCommand.Redo),
                withPast.copy(cutRevision = 2),
                Perform(EditWork.SaveCut(after, 2)),
            ),
            unchanged("nothing to undo", EditCommand.Undo),
            unchanged("nothing to redo", EditCommand.Redo),
            row(
                "an undone refusal is forgotten",
                editing(EditSession.Cut(EditorHistory(cut).push(after), refused = EditError.TooShort)),
                command(EditCommand.Undo),
                undone.copy(cutRevision = 1),
                Perform(EditWork.SaveCut(cut, 1)),
            ),
            row("the playhead moves, saving nothing", open(), command(EditCommand.MovePlayhead(2_500L)), open(playheadMs = 2_500L)),
            row("the playhead stops at the start", open(playheadMs = 900L), command(EditCommand.MovePlayhead(-5L)), open()),
            unchanged("no playhead while the Timeline is closed", EditCommand.MovePlayhead(10L), editing(EditSession.OneClip(picked))),
        )

    private fun queued(
        cut: EditCut,
        name: String,
        project: ProjectId = gigs.id,
        sources: EditSources = EditSources.REFERENCE,
        folder: String? = null,
    ) = Perform(
        EditWork.QueueEdit(
            NewRender(
                emptyList(),
                RenderLook.Current,
                format,
                RenderLength.WholeTrack,
                project,
                extras,
                edit = EditJob(name, cut, sources),
            ),
            folder,
        ),
    )

    private val video = cut.lanes(mediaLane)
    private val wordsOnly = cut.lanes(textLane)

    private val exportRows: List<Row> =
        listOf(
            row(
                "a clip is queued for export into the default Project, named after its video",
                editing(EditSession.OneClip(beachSource, grade)),
                command(EditCommand.Export()),
                editing(EditSession.OneClip(beachSource, grade)),
                queued(EditCut.OneClip(beachSource, grade), "beach"),
            ),
            row(
                "a clip trimmed to nothing is not exported",
                editing(EditSession.OneClip(beachSource, ClipEdit(startMs = 9_000L))),
                command(EditCommand.Export()),
                editing(EditSession.OneClip(beachSource, ClipEdit(startMs = 9_000L))),
                ReportFailure(NOTHING_TO_EXPORT),
            ),
            row(
                "the Timeline's cut is queued into the Project chosen",
                open(video),
                command(EditCommand.Export(trip.id)),
                open(video),
                queued(EditCut.Cut(video), CUT_NAME, trip.id),
            ),
            row(
                "an earlier cut is exported under its name",
                editing(EditSession.Cut(EditorHistory(video), name = "Gig")),
                command(EditCommand.Export(trip.id)),
                editing(EditSession.Cut(EditorHistory(video), name = "Gig")),
                queued(EditCut.Cut(video), "Gig", trip.id),
            ),
            row(
                "a cut with a blank name is exported as a cut",
                editing(EditSession.Cut(EditorHistory(video), name = " ")),
                command(EditCommand.Export(trip.id)),
                editing(EditSession.Cut(EditorHistory(video), name = " ")),
                queued(EditCut.Cut(video), CUT_NAME, trip.id),
            ),
            row(
                "a cut with no video is not exported",
                open(wordsOnly),
                command(EditCommand.Export()),
                open(wordsOnly),
                ReportFailure(NO_VIDEO_TO_EXPORT),
            ),
            row(
                "with no Project to go to nothing is exported",
                open(video).copy(defaultProject = null),
                command(EditCommand.Export(ProjectId(99))),
                open(video).copy(defaultProject = null),
                ReportFailure(NO_EDIT_PROJECT_REFUSAL),
            ),
            row(
                "copies need a save folder",
                open(video).copy(editSources = EditSources.COPY),
                command(EditCommand.Export()),
                open(video).copy(editSources = EditSources.COPY),
                ReportFailure(NO_FOLDER_FOR_COPIES),
            ),
            row(
                "with Copy the save folder goes with the Edit",
                open(video).copy(editSources = EditSources.COPY, saveFolder = folder),
                command(EditCommand.Export()),
                open(video).copy(editSources = EditSources.COPY, saveFolder = folder),
                queued(EditCut.Cut(video), CUT_NAME, sources = EditSources.COPY, folder = folder.uri),
            ),
            row(
                "with Reference nothing is copied, whatever the folder",
                open(video).copy(saveFolder = folder),
                command(EditCommand.Export()),
                open(video).copy(saveFolder = folder),
                queued(EditCut.Cut(video), CUT_NAME, folder = folder.uri),
            ),
            unchanged("nothing is exported with no editor open", EditCommand.Export(), ready),
            unchanged("nothing is exported while a file is read", EditCommand.Export(), editing(EditSession.Reading)),
        )

    private val editJob = EditJob("beach", EditCut.OneClip(beachSource, grade), EditSources.REFERENCE)
    private val waitingEdit =
        RenderJob(
            RenderId(5),
            RenderId(5),
            emptyList(),
            RenderLook.Current,
            format,
            RenderLength.WholeTrack,
            gigs.id,
            extras,
            edit = editJob,
        )
    private val runningEdit = waitingEdit.copy(status = RenderStatus.Running(RenderPhase.PREPARING, 0f))
    private val waitingRender = waitingEdit.copy(id = RenderId(4), batch = RenderId(4), edit = null)
    private val saved = NewVideo(VideoKind.EDIT, "beach", "content://media/7", null, Millis(4_000), 1920, 1080, "")

    private val queueRows: List<Row> =
        listOf(
            row(
                "an Edit's export starts while Visuals off holds the Renders before it",
                ready.copy(visualsOff = true),
                StudioEvent.RendersQueued(listOf(waitingEdit)),
                ready.copy(visualsOff = true, renders = listOf(runningEdit)),
                Perform(StudioWork.Render(runningEdit)),
            ),
            row(
                "a Render still waits while Visuals off is on, and an Edit queued after it goes first",
                ready.copy(visualsOff = true, renders = listOf(waitingRender)),
                StudioEvent.RendersQueued(listOf(waitingEdit)),
                ready.copy(visualsOff = true, renders = listOf(waitingRender, runningEdit)),
                Perform(StudioWork.Render(runningEdit)),
            ),
            row(
                "an exported Edit lands in its Project as an Edit, with no setup to make it again",
                ready.copy(renders = listOf(runningEdit)),
                StudioEvent.RenderEnded(waitingEdit.id, RenderEnd.Saved(saved)),
                ready.copy(renders = listOf(waitingEdit.copy(status = RenderStatus.Done))),
                Perform(StudioWork.AddVideos(gigs.id, listOf(saved))),
            ),
            row(
                "Settings' choice for an Edit's videos is followed",
                ready,
                StudioEvent.RenderSettingsChanged(RenderSettings(false, format, extras, editSources = EditSources.COPY)),
                ready.copy(editSources = EditSources.COPY),
            ),
        )

    @Test
    fun `every row of the transition table`() {
        for (row in openRows + clipRows + laneRows + markRows + historyRows + exportRows + queueRows) {
            assertEquals(row.name, row.to, StudioReducer.reduce(row.from, row.event))
        }
    }

    @Test
    fun `an Edit's export is named after its job`() {
        assertEquals("beach", waitingEdit.title)
        val render = NewRender(emptyList(), RenderLook.Current, format, RenderLength.WholeTrack, gigs.id, extras, edit = editJob)
        assertEquals(waitingEdit, render.queuedAs(RenderId(5), RenderId(5)))
        assertEquals(render, waitingEdit.toNewRender())
    }

    @Test
    fun `an Edit's sources are its videos, sounds, stills, overlays and grade files, each once`() {
        val project =
            cut.lanes(
                mediaLane.copy(
                    clips =
                        listOf(
                            shot.copy(content = ClipContent.Video("content://v/a", ClipEdit(lutUri = "content://lut/teal"))),
                            shot.copy(id = ClipId("b"), content = ClipContent.Still("content://s/b")),
                            shot.copy(id = ClipId("c"), content = ClipContent.Video("content://v/a")),
                        ),
                ),
                textLane,
                Lane(
                    LaneId("o"),
                    LaneKind.Overlay,
                    clips = listOf(shot.copy(id = ClipId("o"), content = ClipContent.Overlay("content://o/grain"))),
                ),
                Lane(
                    LaneId("s"),
                    LaneKind.Audio,
                    clips = listOf(shot.copy(id = ClipId("s"), content = ClipContent.Audio("content://a/song"))),
                ),
                Lane(LaneId("v"), LaneKind.Visual, clips = listOf(shot.copy(id = ClipId("v"), content = ClipContent.Scene("tunnel")))),
            )
        assertEquals(
            listOf("content://v/a", "content://lut/teal", "content://s/b", "content://o/grain", "content://a/song"),
            EditCut.Cut(project).sources(),
        )
        assertEquals(listOf(beach.file), EditCut.OneClip(beachSource, grade).sources())
        assertEquals(
            listOf(beach.file, "content://lut/teal"),
            EditCut.OneClip(beachSource, ClipEdit(lutUri = "content://lut/teal")).sources(),
        )
    }

    @Test
    fun `a copied Edit points at the copies, and a source not copied stays where it was`() {
        val copies =
            mapOf(
                "content://v/a" to "content://tree/a",
                "content://lut/teal" to "content://tree/teal",
                beach.file to "content://tree/beach",
            )
        val project =
            cut.lanes(
                mediaLane.copy(
                    clips =
                        listOf(
                            shot.copy(content = ClipContent.Video("content://v/a", ClipEdit(lutUri = "content://lut/teal"))),
                            shot.copy(id = ClipId("b"), content = ClipContent.Still("content://s/b")),
                        ),
                ),
                textLane,
                Lane(
                    LaneId("o"),
                    LaneKind.Overlay,
                    clips = listOf(shot.copy(id = ClipId("o"), content = ClipContent.Overlay("content://v/a"))),
                ),
                Lane(
                    LaneId("s"),
                    LaneKind.Audio,
                    clips = listOf(shot.copy(id = ClipId("s"), content = ClipContent.Audio("content://v/a"))),
                ),
            )
        val moved = EditCut.Cut(project).relinked(copies) as EditCut.Cut
        assertEquals(
            listOf("content://tree/a", "content://tree/teal", "content://s/b"),
            EditCut.Cut(moved.project).sources(),
        )
        assertEquals(
            words,
            moved.project.timeline
                .lane(text)
                ?.clips
                ?.single(),
        )
        assertEquals(
            EditCut.OneClip(beachSource.copy(file = "content://tree/beach"), ClipEdit(lutUri = "content://tree/teal")),
            EditCut.OneClip(beachSource, ClipEdit(lutUri = "content://lut/teal")).relinked(copies),
        )
        assertEquals(EditCut.OneClip(beachSource, grade), EditCut.OneClip(beachSource, grade).relinked(emptyMap()))
    }

    @Test
    fun `a cut has video only with an enabled video or still on a media lane`() {
        assertTrue(video.hasVideo())
        assertTrue(cut.lanes(mediaLane.copy(clips = listOf(shot.copy(content = ClipContent.Still("content://s/b"))))).hasVideo())
        assertFalse(cut.lanes(mediaLane.copy(clips = listOf(shot.copy(enabled = false)))).hasVideo())
        assertFalse(wordsOnly.hasVideo())
        assertFalse(cut.lanes(Lane(LaneId("o"), LaneKind.Overlay, clips = listOf(shot))).hasVideo())
        assertFalse(EditorProject().hasVideo())
    }

    @Test
    fun `an Edit is named after its video without the extension, or Cut`() {
        assertEquals("beach", EditCut.OneClip(beachSource, grade).name())
        assertEquals("dune", EditCut.OneClip(picked, grade).name())
        assertEquals(CUT_NAME, EditCut.OneClip(picked.copy(name = ".mp4"), grade).name())
        assertEquals(CUT_NAME, EditCut.Cut(cut).name())
    }

    @Test
    fun `a new id skips the ones taken`() {
        assertEquals("clip-1", freshId("clip-", emptySet()))
        assertEquals("clip-3", freshId("clip-", setOf("a", "b")))
        assertEquals("clip-4", freshId("clip-", setOf("clip-3", "x", "y")))
        assertEquals("clip-6", freshId("clip-", setOf("clip-1", "clip-2", "clip-4", "clip-5")))
    }

    @Test
    fun `a cut fits its last clip`() {
        assertEquals(
            4_000L,
            cut
                .lasting(0L)
                .fitted()
                .timeline.durationMs,
        )
        assertEquals(cut, cut.fitted())
        assertEquals(0L, EditorProject(Timeline(listOf(Lane(media, LaneKind.Media)), 900L)).fitted().timeline.durationMs)
    }

    @Test
    fun `every Edit failure has its own wording`() {
        val edits = listOf(StudioFailure.READ_VIDEO, StudioFailure.LOAD_CUT, StudioFailure.SAVE_CUT, StudioFailure.COPY_SOURCES)
        assertEquals(edits.size, edits.map(::describe).toSet().size)
        assertEquals("Could not copy the Edit's videos, so it was not exported", describe(StudioFailure.COPY_SOURCES))
    }
}
