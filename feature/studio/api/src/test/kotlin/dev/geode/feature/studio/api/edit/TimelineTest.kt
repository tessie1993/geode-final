package dev.geode.feature.studio.api.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Edit's timeline model, moved from the old editor with its rules unchanged (#318). */
class TimelineTest {
    private val media = LaneId("media")
    private val media2 = LaneId("media2")
    private val text = LaneId("text")

    private fun video(
        id: String,
        start: Long,
        duration: Long,
        sourceIn: Long = 0L,
        sourceDuration: Long = 0L,
    ) = Clip(ClipId(id), ClipContent.Video("content://$id"), start, duration, sourceIn, sourceDuration)

    private fun timeline(
        vararg clips: Clip,
        locked: Boolean = false,
    ) = Timeline(
        listOf(
            Lane(media, LaneKind.Media, clips = clips.toList(), locked = locked),
            Lane(media2, LaneKind.Media),
            Lane(text, LaneKind.Text),
        ),
    )

    private fun applied(result: EditResult): EditResult.Applied {
        assertTrue("expected an applied edit, got $result", result is EditResult.Applied)
        return result as EditResult.Applied
    }

    private fun EditResult.Applied.clips(lane: LaneId = media): List<Clip> = timeline.lane(lane)!!.clips

    // Clip

    @Test
    fun `a clip knows its end, its span and what it overlaps`() {
        val clip = video("a", 100, 200)
        assertEquals(300L, clip.endMs)
        assertTrue(clip.spans(100))
        assertTrue(clip.spans(299))
        assertFalse(clip.spans(300))
        assertFalse(clip.spans(99))
        assertTrue(clip.overlaps(video("b", 299, 10)))
        assertFalse(clip.overlaps(video("b", 300, 10)))
        assertFalse(clip.overlaps(video("b", 90, 10)))
        assertTrue(clip.overlaps(video("b", 91, 10)))
        assertFalse(clip.hasBoundedSource)
        assertTrue(video("a", 0, 10, sourceDuration = 5).hasBoundedSource)
    }

    @Test
    fun `a clip shifts, never before zero, and reads its source under the playhead`() {
        val clip = video("a", 100, 200, sourceIn = 50)
        assertEquals(150L, clip.shiftedBy(50).startMs)
        assertEquals(0L, clip.shiftedBy(-500).startMs)
        assertEquals(50L, clip.sourceAt(0))
        assertEquals(80L, clip.sourceAt(130))
        assertEquals(250L, clip.sourceAt(1_000))
    }

    @Test
    fun `trimming the head carries the source in-point and stops at the source's start`() {
        val clip = video("a", 1_000, 1_000, sourceIn = 300, sourceDuration = 5_000)
        val later = clip.trimmedToStart(1_200)!!
        assertEquals(1_200L, later.startMs)
        assertEquals(800L, later.durationMs)
        assertEquals(500L, later.sourceInMs)
        val earlier = clip.trimmedToStart(0)!!
        assertEquals(700L, earlier.startMs)
        assertEquals(1_300L, earlier.durationMs)
        assertEquals(0L, earlier.sourceInMs)
        assertNull(clip.trimmedToStart(1_990))
        assertEquals(MIN_CLIP_DURATION_MS, clip.trimmedToStart(2_000 - MIN_CLIP_DURATION_MS)!!.durationMs)
    }

    @Test
    fun `an unbounded clip's head trims back to zero`() {
        val clip = Clip(ClipId("t"), ClipContent.Text("hi"), 500, 500)
        assertEquals(0L, clip.trimmedToStart(-10)!!.startMs)
        assertEquals(1_000L, clip.trimmedToStart(-10)!!.durationMs)
    }

    @Test
    fun `trimming the tail never passes the source's end`() {
        val clip = video("a", 1_000, 1_000, sourceIn = 300, sourceDuration = 2_000)
        assertEquals(500L, clip.trimmedToEnd(1_500)!!.durationMs)
        assertEquals(1_700L, clip.trimmedToEnd(9_000)!!.durationMs)
        assertNull(clip.trimmedToEnd(1_010))
        assertEquals(MIN_CLIP_DURATION_MS, clip.trimmedToEnd(1_000 + MIN_CLIP_DURATION_MS)!!.durationMs)
        val unbounded = video("b", 0, 100)
        assertEquals(9_000L, unbounded.trimmedToEnd(9_000)!!.durationMs)
        val pastSource = video("c", 1_000, 100, sourceIn = 3_000, sourceDuration = 2_000)
        assertNull(pastSource.trimmedToEnd(2_000))
    }

    @Test
    fun `a split gives the tail its own id, the rest of the source and no transition`() {
        val clip = video("a", 1_000, 1_000, sourceIn = 200).copy(transition = ClipTransition("fade"))
        val split = clip.splitAt(1_400, ClipId("b"))!!
        assertEquals(400L, split.head.durationMs)
        assertEquals(ClipId("a"), split.head.id)
        assertEquals(ClipTransition("fade"), split.head.transition)
        assertEquals(ClipId("b"), split.tail.id)
        assertEquals(1_400L, split.tail.startMs)
        assertEquals(600L, split.tail.durationMs)
        assertEquals(600L, split.tail.sourceInMs)
        assertNull(split.tail.transition)
        assertNull(clip.splitAt(1_000 + MIN_CLIP_DURATION_MS - 1, ClipId("b")))
        assertNull(clip.splitAt(2_000 - MIN_CLIP_DURATION_MS + 1, ClipId("b")))
        assertTrue(clip.splitAt(1_000 + MIN_CLIP_DURATION_MS, ClipId("b")) != null)
        assertTrue(clip.splitAt(2_000 - MIN_CLIP_DURATION_MS, ClipId("b")) != null)
    }

    @Test
    fun `carving a span out of a clip keeps what is left`() {
        val clip = video("a", 1_000, 1_000)
        assertSame(clip, clip.carvedOut(0, 1_000))
        assertSame(clip, clip.carvedOut(2_000, 3_000))
        assertNull(clip.carvedOut(1_000, 2_000))
        assertNull(clip.carvedOut(500, 2_500))
        assertEquals(500L, clip.carvedOut(1_500, 2_500)!!.durationMs)
        val tail = clip.carvedOut(500, 1_200)!!
        assertEquals(1_200L, tail.startMs)
        assertEquals(800L, tail.durationMs)
    }

    // Lane

    @Test
    fun `a lane keeps its clips in start order and finds them`() {
        val a = video("a", 500, 100)
        val b = video("b", 0, 100)
        val lane = Lane(media, LaneKind.Media).withClips(listOf(a, b))
        assertEquals(listOf(b, a), lane.clips)
        assertEquals(a, lane.clipAt(550))
        assertNull(lane.clipAt(300))
        assertEquals(b, lane.clip(ClipId("b")))
        assertNull(lane.clip(ClipId("z")))
        assertEquals(listOf(0L, 100L, 500L, 600L), lane.edgesMs())
    }

    @Test
    fun `a lane's ripple moves what starts at or after the cut`() {
        val lane = Lane(media, LaneKind.Media, clips = listOf(video("a", 0, 100), video("b", 100, 100), video("c", 50, 300)))
        val moved = lane.rippled(RippleShift(100, 50))
        assertEquals(listOf(0L, 50L, 150L), moved.clips.map { it.startMs })
        assertEquals(listOf("a", "c", "b"), moved.clips.map { it.id.value })
    }

    // Timeline: adding

    @Test
    fun `a clip lands on a lane of its kind`() {
        val result = applied(timeline().addClip(media, video("a", 0, 100)))
        assertEquals(listOf(ClipId("a")), result.affected)
        assertNull(result.ripple)
        assertEquals(listOf(video("a", 0, 100)), result.clips())
    }

    @Test
    fun `adding is refused on a missing, locked or wrong lane, and for a sliver`() {
        assertEquals(
            EditResult.Rejected(EditError.LaneNotFound(LaneId("x"))),
            timeline().addClip(LaneId("x"), video("a", 0, 100)),
        )
        assertEquals(EditResult.Rejected(EditError.LaneLocked(media)), timeline(locked = true).addClip(media, video("a", 0, 100)))
        assertEquals(
            EditResult.Rejected(EditError.WrongLaneKind(text, LaneKind.Text, LaneKind.Media)),
            timeline().addClip(text, video("a", 0, 100)),
        )
        assertEquals(EditResult.Rejected(EditError.TooShort), timeline().addClip(media, video("a", 0, MIN_CLIP_DURATION_MS - 1)))
        assertTrue(timeline().addClip(media, video("a", 0, MIN_CLIP_DURATION_MS)) is EditResult.Applied)
    }

    @Test
    fun `an overlap is refused, rippled in, or overwritten`() {
        val base = timeline(video("a", 0, 1_000), video("b", 1_000, 1_000))
        val incoming = video("n", 1_000, 500)
        assertEquals(EditResult.Rejected(EditError.Overlaps(ClipId("b"))), base.addClip(media, incoming))
        val rippled = applied(base.addClip(media, incoming, OverlapPolicy.RIPPLE))
        assertEquals(listOf("a" to 0L, "n" to 1_000L, "b" to 1_500L), rippled.clips().map { it.id.value to it.startMs })
        val over = applied(base.addClip(media, video("n", 500, 1_000), OverlapPolicy.OVERWRITE))
        assertEquals(
            listOf(Triple("a", 0L, 500L), Triple("n", 500L, 1_000L), Triple("b", 1_500L, 500L)),
            over.clips().map { Triple(it.id.value, it.startMs, it.durationMs) },
        )
        val swallowed = applied(base.addClip(media, video("n", 0, 2_000), OverlapPolicy.OVERWRITE))
        assertEquals(listOf("n"), swallowed.clips().map { it.id.value })
    }

    @Test
    fun `an edit that would cut a clip in two needs the caller to split it`() {
        val base = timeline(video("a", 0, 1_000))
        assertEquals(
            EditResult.Rejected(EditError.NeedsSplit(ClipId("a"))),
            base.addClip(media, video("n", 400, 200), OverlapPolicy.OVERWRITE),
        )
        assertEquals(
            EditResult.Rejected(EditError.NeedsSplit(ClipId("a"))),
            base.addClip(media, video("n", 400, 200), OverlapPolicy.RIPPLE),
        )
        val atStart = applied(base.addClip(media, video("n", 0, 200), OverlapPolicy.RIPPLE))
        assertEquals(listOf("n" to 0L, "a" to 200L), atStart.clips().map { it.id.value to it.startMs })
    }

    // Timeline: moving

    @Test
    fun `a clip moves along its lane, never before zero`() {
        val base = timeline(video("a", 0, 100), video("b", 500, 100))
        assertEquals(1_000L, applied(base.moveClip(ClipId("a"), 1_000)).timeline.clip(ClipId("a"))!!.startMs)
        assertEquals(0L, applied(timeline(video("b", 500, 100)).moveClip(ClipId("b"), -50)).timeline.clip(ClipId("b"))!!.startMs)
        assertEquals(EditResult.Rejected(EditError.Overlaps(ClipId("b"))), base.moveClip(ClipId("a"), 450))
        assertEquals(listOf(ClipId("a")), applied(base.moveClip(ClipId("a"), 200)).affected)
    }

    @Test
    fun `a clip moves to another lane of its kind`() {
        val base = timeline(video("a", 0, 100))
        val moved = applied(base.moveClip(ClipId("a"), 300, media2))
        assertTrue(moved.clips().isEmpty())
        assertEquals(listOf(300L), moved.clips(media2).map { it.startMs })
        assertEquals(listOf(ClipId("a")), moved.affected)
    }

    @Test
    fun `moving is refused for what is missing, locked, of another kind or in the way`() {
        val base = timeline(video("a", 0, 100))
        assertEquals(EditResult.Rejected(EditError.ClipNotFound(ClipId("z"))), base.moveClip(ClipId("z"), 0))
        assertEquals(EditResult.Rejected(EditError.LaneNotFound(LaneId("x"))), base.moveClip(ClipId("a"), 0, LaneId("x")))
        assertEquals(
            EditResult.Rejected(EditError.WrongLaneKind(text, LaneKind.Text, LaneKind.Media)),
            base.moveClip(ClipId("a"), 0, text),
        )
        val locked = timeline(video("a", 0, 100), locked = true)
        assertEquals(EditResult.Rejected(EditError.LaneLocked(media)), locked.moveClip(ClipId("a"), 10))
        val lockedTarget =
            base.copy(lanes = base.lanes.map { if (it.id == media2) it.copy(locked = true) else it })
        assertEquals(EditResult.Rejected(EditError.LaneLocked(media2)), lockedTarget.moveClip(ClipId("a"), 0, media2))
        val busy = base.addClip(media2, video("b", 0, 100)).let { applied(it).timeline }
        assertEquals(EditResult.Rejected(EditError.Overlaps(ClipId("b"))), busy.moveClip(ClipId("a"), 50, media2))
    }

    // Timeline: trimming, splitting, duplicating, deleting

    @Test
    fun `a trim stops at its neighbours`() {
        val base = timeline(video("a", 0, 1_000), video("b", 1_000, 1_000), video("c", 2_500, 500))
        val head = applied(base.trimClip(ClipId("b"), ClipEdge.START, 500)).timeline.clip(ClipId("b"))!!
        assertEquals(1_000L, head.startMs)
        val later = applied(base.trimClip(ClipId("b"), ClipEdge.START, 1_200)).timeline.clip(ClipId("b"))!!
        assertEquals(1_200L, later.startMs)
        assertEquals(800L, later.durationMs)
        val tail = applied(base.trimClip(ClipId("b"), ClipEdge.END, 9_000)).timeline.clip(ClipId("b"))!!
        assertEquals(1_500L, tail.durationMs)
        val first = applied(base.trimClip(ClipId("a"), ClipEdge.START, -100)).timeline.clip(ClipId("a"))!!
        assertEquals(0L, first.startMs)
        val last = applied(base.trimClip(ClipId("c"), ClipEdge.END, 9_000)).timeline.clip(ClipId("c"))!!
        assertEquals(6_500L, last.durationMs)
        assertEquals(EditResult.Rejected(EditError.TooShort), base.trimClip(ClipId("b"), ClipEdge.END, 1_001))
        assertEquals(EditResult.Rejected(EditError.ClipNotFound(ClipId("z"))), base.trimClip(ClipId("z"), ClipEdge.END, 1))
        assertEquals(
            EditResult.Rejected(EditError.LaneLocked(media)),
            timeline(video("a", 0, 1_000), locked = true).trimClip(ClipId("a"), ClipEdge.END, 500),
        )
    }

    @Test
    fun `a split puts both halves on the lane`() {
        val base = timeline(video("a", 0, 1_000))
        val split = applied(base.splitClip(ClipId("a"), 400, ClipId("b")))
        assertEquals(listOf(ClipId("a"), ClipId("b")), split.affected)
        assertEquals(listOf(400L, 600L), split.clips().map { it.durationMs })
        assertEquals(EditResult.Rejected(EditError.OutsideClip), base.splitClip(ClipId("a"), 5_000, ClipId("b")))
        assertEquals(EditResult.Rejected(EditError.ClipNotFound(ClipId("z"))), base.splitClip(ClipId("z"), 400, ClipId("b")))
        assertEquals(
            EditResult.Rejected(EditError.LaneLocked(media)),
            timeline(video("a", 0, 1_000), locked = true).splitClip(ClipId("a"), 400, ClipId("b")),
        )
    }

    @Test
    fun `a duplicate lands after the original, rippling by default`() {
        val base = timeline(video("a", 0, 1_000), video("b", 1_000, 500))
        val copy = applied(base.duplicateClip(ClipId("a"), ClipId("c")))
        assertEquals(listOf("a" to 0L, "c" to 1_000L, "b" to 2_000L), copy.clips().map { it.id.value to it.startMs })
        assertEquals(listOf(ClipId("c")), copy.affected)
        val placed = applied(base.duplicateClip(ClipId("b"), ClipId("c"), atMs = 3_000))
        assertEquals(3_000L, placed.timeline.clip(ClipId("c"))!!.startMs)
        val clamped = applied(base.duplicateClip(ClipId("b"), ClipId("c"), atMs = -5, policy = OverlapPolicy.OVERWRITE))
        assertEquals(0L, clamped.timeline.clip(ClipId("c"))!!.startMs)
        assertEquals(EditResult.Rejected(EditError.ClipNotFound(ClipId("z"))), base.duplicateClip(ClipId("z"), ClipId("c")))
        assertEquals(
            EditResult.Rejected(EditError.LaneLocked(media)),
            timeline(video("a", 0, 1_000), locked = true).duplicateClip(ClipId("a"), ClipId("c")),
        )
    }

    @Test
    fun `a delete leaves the hole`() {
        val base = timeline(video("a", 0, 1_000), video("b", 1_000, 500))
        val deleted = applied(base.deleteClip(ClipId("a")))
        assertEquals(listOf(1_000L), deleted.clips().map { it.startMs })
        assertEquals(listOf(ClipId("a")), deleted.affected)
        assertEquals(EditResult.Rejected(EditError.ClipNotFound(ClipId("z"))), base.deleteClip(ClipId("z")))
        val locked = timeline(video("a", 0, 1_000), locked = true)
        assertEquals(EditResult.Rejected(EditError.LaneLocked(media)), locked.deleteClip(ClipId("a")))
    }

    @Test
    fun `a ripple delete closes the gap on its lane only, or on every lane`() {
        val base =
            timeline(video("a", 0, 1_000), video("b", 1_000, 500)).let { t ->
                applied(t.addClip(media2, video("c", 1_200, 300))).timeline
            }
        val lane = applied(base.rippleDeleteClip(ClipId("a")))
        assertEquals(listOf(0L), lane.clips().map { it.startMs })
        assertEquals(listOf(1_200L), lane.clips(media2).map { it.startMs })
        assertEquals(RippleShift(1_000, -1_000, RippleScope.LANE, setOf(ClipId("b"))), lane.ripple)
        assertEquals(listOf(ClipId("a")), lane.affected)
        val all = applied(base.rippleDeleteClip(ClipId("a"), RippleScope.ALL_LANES))
        assertEquals(listOf(200L), all.clips(media2).map { it.startMs })
        assertEquals(RippleShift(1_000, -1_000, RippleScope.ALL_LANES, setOf(ClipId("b"), ClipId("c"))), all.ripple)
        assertEquals(EditResult.Rejected(EditError.ClipNotFound(ClipId("z"))), base.rippleDeleteClip(ClipId("z")))
        assertEquals(
            EditResult.Rejected(EditError.LaneLocked(media)),
            timeline(video("a", 0, 1_000), locked = true).rippleDeleteClip(ClipId("a")),
        )
    }

    @Test
    fun `a ripple leaves locked lanes and lanes it was not asked to move`() {
        val base =
            Timeline(
                listOf(
                    Lane(media, LaneKind.Media, clips = listOf(video("a", 1_000, 100))),
                    Lane(media2, LaneKind.Media, clips = listOf(video("b", 1_000, 100)), locked = true),
                    Lane(text, LaneKind.Text, clips = listOf(Clip(ClipId("t"), ClipContent.Text("hi"), 1_000, 100))),
                ),
            )
        val moved = base.ripple(RippleShift(500, 200), setOf(media, media2))
        assertEquals(1_200L, moved.clip(ClipId("a"))!!.startMs)
        assertEquals(1_000L, moved.clip(ClipId("b"))!!.startMs)
        assertEquals(1_000L, moved.clip(ClipId("t"))!!.startMs)
        assertEquals(1_200L, base.ripple(RippleShift(500, 200)).clip(ClipId("t"))!!.startMs)
    }

    @Test
    fun `the timeline finds lanes and clips, and lists every edge but the one dragged`() {
        val base = timeline(video("a", 0, 1_000), video("b", 1_000, 500))
        assertEquals(media, base.laneOf(ClipId("b"))!!.id)
        assertNull(base.laneOf(ClipId("z")))
        assertNull(base.lane(LaneId("x")))
        assertNull(base.clip(ClipId("z")))
        assertEquals(listOf(0L, 1_000L, 1_500L), base.edgesMs())
        assertEquals(listOf(0L, 1_000L), base.edgesMs(exclude = ClipId("b")))
    }

    // EditorProject

    @Test
    fun `a project folds an edit in, and a refused one changes nothing`() {
        val project = EditorProject(timeline(video("a", 0, 1_000)))
        assertSame(project, project.apply(EditResult.Rejected(EditError.TooShort)))
        val added = project.apply(project.timeline.addClip(media, video("b", 2_000, 100)))
        assertEquals(
            2,
            added.timeline
                .lane(media)!!
                .clips.size,
        )
    }

    @Test
    fun `a ripple on one lane moves keyframes of the clips it moved but never the markers`() {
        val marker = Marker(MarkerId("m"), 2_000)
        val scoped = KeyframeTrack(ParamId("clip.speed"), listOf(Keyframe(KeyframeId("k"), 1_500, ParamValue.Scalar(1f))), ClipId("b"))
        val programme = KeyframeTrack(ParamId("scene.zoom"), listOf(Keyframe(KeyframeId("p"), 1_500, ParamValue.Scalar(1f))))
        val project =
            EditorProject(
                timeline(video("a", 0, 1_000), video("b", 1_000, 1_000)),
                MarkerSet(listOf(marker)),
                KeyframeSheet(listOf(scoped, programme)),
            )
        val lane = project.apply(project.timeline.rippleDeleteClip(ClipId("a")))
        assertEquals(listOf(2_000L), lane.markers.timesMs)
        assertEquals(
            500L,
            lane.keyframes
                .track(ParamId("clip.speed"), ClipId("b"))!!
                .keys
                .single()
                .atMs,
        )
        assertEquals(
            1_500L,
            lane.keyframes
                .track(ParamId("scene.zoom"))!!
                .keys
                .single()
                .atMs,
        )
        val all = project.apply(project.timeline.rippleDeleteClip(ClipId("a"), RippleScope.ALL_LANES))
        assertEquals(listOf(1_000L), all.markers.timesMs)
        assertEquals(
            500L,
            all.keyframes
                .track(ParamId("scene.zoom"))!!
                .keys
                .single()
                .atMs,
        )
    }

    @Test
    fun `a project snaps to the playhead, its markers and its clip edges`() {
        val project = EditorProject(timeline(video("a", 0, 1_000)), MarkerSet(listOf(Marker(MarkerId("m"), 700))))
        assertEquals(SnapContext(250, listOf(700L), listOf(0L, 1_000L)), project.snapContext(250))
        assertEquals(SnapContext(250, listOf(700L), emptyList()), project.snapContext(250, ClipId("a")))
    }

    // Snapping

    @Test
    fun `free placement never snaps`() {
        assertEquals(SnapResult(123, null), SnapMode.Free.resolve(123, SnapContext(120)))
    }

    @Test
    fun `magnetic placement takes the nearest target within reach`() {
        val context = SnapContext(playheadMs = 1_000, markerMs = listOf(2_000, 2_040), clipEdgeMs = listOf(3_000))
        val all = SnapMode.Magnetic(SnapTarget.entries.toSet(), toleranceMs = 50)
        assertEquals(SnapResult(1_000, SnapTarget.PLAYHEAD), all.resolve(1_030, context))
        assertEquals(SnapResult(2_040, SnapTarget.MARKERS), all.resolve(2_030, context))
        assertEquals(SnapResult(3_000, SnapTarget.CLIP_EDGES), all.resolve(3_050, context))
        assertEquals(SnapResult(3_051, null), all.resolve(3_051, context))
        assertEquals(2_000L, all.snap(1_990, context))
        val markersOnly = SnapMode.Magnetic(setOf(SnapTarget.MARKERS), toleranceMs = 50)
        assertEquals(SnapResult(1_010, null), markersOnly.resolve(1_010, context))
        assertEquals(SnapResult(5, null), all.resolve(5, SnapContext(playheadMs = 1_000)))
        assertEquals(DEFAULT_SNAP_TOLERANCE_MS, SnapMode.Magnetic(setOf(SnapTarget.PLAYHEAD)).toleranceMs)
    }

    @Test
    fun `equally near targets go to the playhead first, a clip edge last`() {
        val context = SnapContext(playheadMs = 1_000, markerMs = listOf(1_020), clipEdgeMs = listOf(1_020))
        val all = SnapMode.Magnetic(SnapTarget.entries.toSet(), toleranceMs = 50)
        assertEquals(SnapResult(1_000, SnapTarget.PLAYHEAD), all.resolve(1_010, context))
        val edgesAndMarkers = SnapMode.Magnetic(setOf(SnapTarget.MARKERS, SnapTarget.CLIP_EDGES), toleranceMs = 50)
        assertEquals(SnapResult(1_020, SnapTarget.MARKERS), edgesAndMarkers.resolve(1_010, context))
    }

    // Transitions

    @Test
    fun `a transition's length is held to its bounds, and comes from the clip before`() {
        assertEquals(ClipTransition.MIN_TRANSITION_MS, ClipTransition("fade", 10).boundedDurationMs)
        assertEquals(ClipTransition.MAX_TRANSITION_MS, ClipTransition("fade", 60_000).boundedDurationMs)
        assertEquals(1_500L, ClipTransition("fade", 1_500).boundedDurationMs)
        assertEquals(ClipTransition.DEFAULT_TRANSITION_MS, ClipTransition("fade").durationMs)
        assertEquals(listOf(400L, 800L, 1_500L, 3_000L), ClipTransition.DURATION_CHOICES_MS)
        val a = video("a", 0, 1_000)
        val b = video("b", 1_000, 1_000)
        val c = video("c", 3_000, 1_000)
        val lane = Lane(media, LaneKind.Media, clips = listOf(a, b, c))
        assertEquals(b, lane.predecessorOf(c))
        assertEquals(a, lane.predecessorOf(b))
        assertNull(lane.predecessorOf(a))
    }

    // History

    @Test
    fun `undo and redo keep the previous values`() {
        val first = EditorProject()
        val second = EditorProject(timeline(video("a", 0, 100)))
        val third = EditorProject(timeline(video("b", 0, 100)))
        val history = EditorHistory(first).push(second).push(third)
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
        val undone = history.undo()
        assertEquals(second, undone.present)
        assertTrue(undone.canRedo)
        assertEquals(listOf(third), undone.future)
        assertEquals(third, undone.redo().present)
        assertEquals(first, undone.undo().present)
        assertFalse(undone.undo().canUndo)
        val fresh = EditorHistory(first)
        assertSame(fresh, fresh.undo())
        assertSame(fresh, fresh.redo())
    }

    @Test
    fun `a no-op edit keeps the redo, a new edit drops it, and the past is capped`() {
        val first = EditorProject()
        val second = EditorProject(timeline(video("a", 0, 100)))
        val history = EditorHistory(first).push(second).undo()
        assertSame(history, history.push(first))
        assertTrue(history.push(EditorProject(timeline(video("c", 0, 100)))).future.isEmpty())
        val capped = (1..5).fold(EditorHistory(first)) { h, i -> h.push(EditorProject(timeline(video("x$i", 0, 100))), limit = 3) }
        assertEquals(3, capped.past.size)
        assertEquals(100, EditorHistory.DEFAULT_LIMIT)
    }
}
