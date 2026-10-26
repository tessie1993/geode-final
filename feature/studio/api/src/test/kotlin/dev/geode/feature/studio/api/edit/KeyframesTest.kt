package dev.geode.feature.studio.api.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keyframes and their curves, moved from the old editor with their rules unchanged (#318). */
class KeyframesTest {
    private val opacity = ParamId("clip.opacity")
    private val clip = ClipId("c")

    private fun key(
        id: String,
        at: Long,
        value: ParamValue,
        interpolation: Interpolation = Interpolation.Linear,
    ) = Keyframe(KeyframeId(id), at, value, interpolation)

    private fun scalar(v: Float) = ParamValue.Scalar(v)

    private fun applied(result: KeyframeResult): KeyframeTrack {
        assertTrue("expected an applied change, got $result", result is KeyframeResult.Applied)
        return (result as KeyframeResult.Applied).track
    }

    private fun scalarAt(
        track: KeyframeTrack,
        at: Long,
    ): Float = (track.valueAt(at) as ParamValue.Scalar).value

    @Test
    fun `each value knows its kind`() {
        assertEquals(ParamKind.SCALAR, scalar(1f).kind)
        assertEquals(ParamKind.VECTOR2, ParamValue.Vector2(0f, 0f).kind)
        assertEquals(ParamKind.COLOUR, ParamValue.Colour(0f, 0f, 0f).kind)
        assertEquals(ParamKind.TOGGLE, ParamValue.Toggle(true).kind)
        assertEquals(ParamKind.CHOICE, ParamValue.Choice(2).kind)
        assertEquals(1f, ParamValue.Colour(0f, 0f, 0f).a)
    }

    @Test
    fun `a straight bezier is linear and every curve runs from 0 to 1`() {
        val straight = BezierCurve(0.25f, 0.25f, 0.75f, 0.75f)
        assertEquals(0.5f, straight.eval(0.5f), 1e-4f)
        assertEquals(0.2f, straight.eval(0.2f), 1e-4f)
        for (shape in EaseShape.entries) {
            assertEquals(shape.name, 0f, shape.curve.eval(0f), 1e-4f)
            assertEquals(shape.name, 1f, shape.curve.eval(1f), 1e-4f)
            assertEquals(shape.name, 1f, shape.curve.eval(2f), 1e-4f)
            assertEquals(shape.name, 0f, shape.curve.eval(-1f), 1e-4f)
        }
    }

    @Test
    fun `ease in starts slow and ease out starts fast`() {
        val easeIn = EaseShape.IN.curve.eval(0.25f)
        val easeOut = EaseShape.OUT.curve.eval(0.25f)
        assertTrue("ease in at a quarter: $easeIn", easeIn < 0.12f)
        assertTrue("ease out at a quarter: $easeOut", easeOut > 0.35f)
        assertEquals(0.5f, EaseShape.IN_OUT.curve.eval(0.5f), 1e-3f)
        assertEquals(0.0934f, EaseShape.IN.curve.eval(0.25f), 2e-3f)
        assertEquals(0.3781f, EaseShape.OUT.curve.eval(0.25f), 2e-3f)
        assertEquals(0.7756f, EaseShape.SMOOTH.curve.eval(0.5f), 2e-3f)
    }

    @Test
    fun `a curve whose time stalls still solves`() {
        // Flat at both ends in time: Newton finds no slope there and bisection takes over.
        val stalled = BezierCurve(1f, 0f, 0f, 1f)
        val mid = stalled.eval(0.5f)
        assertEquals(0.5f, mid, 1e-3f)
        assertTrue(stalled.eval(0.1f) < stalled.eval(0.2f))
        assertEquals(BezierCurve(1f, 0f, 0f, 1f).eval(0.3f), BezierCurve(3f, 0f, -2f, 1f).eval(0.3f), 1e-6f)
    }

    @Test
    fun `interpolations shape the way between keys`() {
        assertEquals(0f, Interpolation.Hold.fractionAt(0.7f))
        assertEquals(0.7f, Interpolation.Linear.fractionAt(0.7f))
        assertEquals(1f, Interpolation.Linear.fractionAt(1.5f))
        assertEquals(0f, Interpolation.Linear.fractionAt(-0.5f))
        assertEquals(EaseShape.OUT.curve.eval(0.3f), Interpolation.Ease(EaseShape.OUT).fractionAt(0.3f))
        val custom = BezierCurve(0.1f, 0.6f, 0.3f, 0.9f)
        assertEquals(custom.eval(0.3f), Interpolation.Custom(custom).fractionAt(0.3f))
    }

    @Test
    fun `a track holds before its first key and after its last, and blends between`() {
        val track = KeyframeTrack(opacity, listOf(key("a", 1_000, scalar(0f)), key("b", 2_000, scalar(1f))))
        assertEquals(ParamKind.SCALAR, track.kind)
        assertFalse(track.isEmpty)
        assertEquals(0f, scalarAt(track, 0))
        assertEquals(0f, scalarAt(track, 1_000))
        assertEquals(0.25f, scalarAt(track, 1_250), 1e-5f)
        assertEquals(1f, scalarAt(track, 2_000))
        assertEquals(1f, scalarAt(track, 9_000))
        val empty = KeyframeTrack(opacity)
        assertNull(empty.valueAt(0))
        assertNull(empty.kind)
        assertTrue(empty.isEmpty)
    }

    @Test
    fun `a long track finds the keys around any time`() {
        val keys = (0 until 20).map { key("k$it", it * 100L, scalar(it.toFloat())) }
        val track = KeyframeTrack(opacity, keys)
        assertEquals(7.5f, scalarAt(track, 750), 1e-5f)
        assertEquals(0.5f, scalarAt(track, 50), 1e-5f)
        assertEquals(18.5f, scalarAt(track, 1_850), 1e-5f)
        assertEquals(13f, scalarAt(track, 1_300), 1e-5f)
    }

    @Test
    fun `held keys jump and eased keys bend`() {
        val held = KeyframeTrack(opacity, listOf(key("a", 0, scalar(0f), Interpolation.Hold), key("b", 1_000, scalar(1f))))
        assertEquals(0f, scalarAt(held, 999))
        assertEquals(1f, scalarAt(held, 1_000))
        val easeIn = Interpolation.Ease(EaseShape.IN)
        val eased = KeyframeTrack(opacity, listOf(key("a", 0, scalar(0f), easeIn), key("b", 1_000, scalar(2f))))
        assertEquals(2f * EaseShape.IN.curve.eval(0.25f), scalarAt(eased, 250), 1e-5f)
    }

    @Test
    fun `vectors and colours blend per channel, toggles and choices hold`() {
        val move =
            KeyframeTrack(ParamId("p"), listOf(key("a", 0, ParamValue.Vector2(0f, 10f)), key("b", 100, ParamValue.Vector2(10f, 0f))))
        assertEquals(ParamValue.Vector2(5f, 5f), move.valueAt(50))
        val tint =
            KeyframeTrack(
                ParamId("t"),
                listOf(key("a", 0, ParamValue.Colour(0f, 1f, 0f, 0f)), key("b", 100, ParamValue.Colour(1f, 0f, 0.5f, 1f))),
            )
        assertEquals(ParamValue.Colour(0.5f, 0.5f, 0.25f, 0.5f), tint.valueAt(50))
        val toggle = KeyframeTrack(ParamId("s"), listOf(key("a", 0, ParamValue.Toggle(false)), key("b", 100, ParamValue.Toggle(true))))
        assertEquals(ParamValue.Toggle(false), toggle.valueAt(99))
        val choice = KeyframeTrack(ParamId("c"), listOf(key("a", 0, ParamValue.Choice(1)), key("b", 100, ParamValue.Choice(3))))
        assertEquals(ParamValue.Choice(1), choice.valueAt(50))
    }

    @Test
    fun `mismatched neighbours hold the first value`() {
        val keys = listOf(key("a", 0, scalar(1f)), key("b", 100, ParamValue.Toggle(true)))
        assertEquals(scalar(1f), KeyframeTrack(ParamId("x"), keys).valueAt(50))
        val vector = listOf(key("a", 0, ParamValue.Vector2(1f, 1f)), key("b", 100, scalar(2f)))
        assertEquals(ParamValue.Vector2(1f, 1f), KeyframeTrack(ParamId("x"), vector).valueAt(50))
        val colour = listOf(key("a", 0, ParamValue.Colour(1f, 1f, 1f)), key("b", 100, scalar(2f)))
        assertEquals(ParamValue.Colour(1f, 1f, 1f), KeyframeTrack(ParamId("x"), colour).valueAt(50))
    }

    @Test
    fun `a key is added in time order, and replaces one on the same instant or with the same id`() {
        val track = applied(KeyframeTrack(opacity).withKey(key("b", 2_000, scalar(1f))))
        val two = applied(track.withKey(key("a", 1_000, scalar(0f))))
        assertEquals(listOf("a", "b"), two.keys.map { it.id.value })
        val sameInstant = applied(two.withKey(key("c", 1_000, scalar(0.5f))))
        assertEquals(listOf("c", "b"), sameInstant.keys.map { it.id.value })
        val sameId = applied(sameInstant.withKey(key("c", 3_000, scalar(0.5f))))
        assertEquals(listOf(2_000L, 3_000L), sameId.keys.map { it.atMs })
        assertEquals(
            KeyframeResult.Rejected(KeyframeError.KindMismatch(ParamKind.SCALAR, ParamKind.TOGGLE)),
            sameId.withKey(key("d", 500, ParamValue.Toggle(true))),
        )
        assertEquals(key("b", 2_000, scalar(1f)), sameId.key(KeyframeId("b")))
        assertNull(sameId.key(KeyframeId("z")))
    }

    @Test
    fun `a key is dragged, snapped, never before zero, and takes the place of one already there`() {
        val track = KeyframeTrack(opacity, listOf(key("a", 1_000, scalar(0f)), key("b", 2_000, scalar(1f))))
        assertEquals(listOf(2_000L, 2_500L), applied(track.moveKey(KeyframeId("a"), 2_500)).keys.map { it.atMs })
        assertEquals(0L, applied(track.moveKey(KeyframeId("a"), -10)).keys.first().atMs)
        val onto = applied(track.moveKey(KeyframeId("a"), 2_000))
        assertEquals(listOf("a"), onto.keys.map { it.id.value })
        val snap = SnapMode.Magnetic(setOf(SnapTarget.MARKERS), toleranceMs = 50)
        val snapped = applied(track.moveKey(KeyframeId("a"), 1_480, snap, SnapContext(markerMs = listOf(1_500))))
        assertEquals(1_500L, snapped.key(KeyframeId("a"))!!.atMs)
        assertEquals(KeyframeResult.Rejected(KeyframeError.KeyNotFound(KeyframeId("z"))), track.moveKey(KeyframeId("z"), 0))
    }

    @Test
    fun `a key's curve changes, a key goes, and the whole track shifts or ripples`() {
        val track = KeyframeTrack(opacity, listOf(key("a", 1_000, scalar(0f)), key("b", 2_000, scalar(1f))))
        val held = applied(track.withInterpolation(KeyframeId("a"), Interpolation.Hold))
        assertEquals(Interpolation.Hold, held.key(KeyframeId("a"))!!.interpolation)
        assertEquals(Interpolation.Linear, held.key(KeyframeId("b"))!!.interpolation)
        assertEquals(
            KeyframeResult.Rejected(KeyframeError.KeyNotFound(KeyframeId("z"))),
            track.withInterpolation(KeyframeId("z"), Interpolation.Hold),
        )
        assertEquals(listOf("b"), track.removeKey(KeyframeId("a")).keys.map { it.id.value })
        assertEquals(track, track.removeKey(KeyframeId("z")))
        assertEquals(listOf(1_500L, 2_500L), track.shiftedBy(500).keys.map { it.atMs })
        assertEquals(listOf(0L, 500L), track.shiftedBy(-1_500).keys.map { it.atMs })
        assertEquals(listOf(1_000L, 1_700L), track.rippled(RippleShift(1_500, -300)).keys.map { it.atMs })
        assertEquals(listOf(0L, 1_000L), track.rippled(RippleShift(1_000, -1_000)).keys.map { it.atMs })
        assertEquals(listOf(0L, 0L), track.rippled(RippleShift(0, -5_000)).keys.map { it.atMs })
    }

    @Test
    fun `a sheet keeps one track per parameter and scope`() {
        val programme = KeyframeTrack(opacity, listOf(key("p", 0, scalar(0.2f))))
        val scoped = KeyframeTrack(opacity, listOf(key("s", 0, scalar(0.8f))), clip)
        val sheet = KeyframeSheet().withTrack(programme).withTrack(scoped)
        assertEquals(programme, sheet.track(opacity))
        assertEquals(scoped, sheet.track(opacity, clip))
        assertNull(sheet.track(ParamId("other")))
        assertEquals(listOf(scoped), sheet.tracksFor(clip))
        assertEquals(listOf(programme), sheet.tracksFor(null))
        val replaced = sheet.withTrack(programme.copy(enabled = false))
        assertEquals(2, replaced.tracks.size)
        assertFalse(replaced.track(opacity)!!.enabled)
        assertEquals(listOf(scoped), sheet.removeTrack(opacity).tracks)
        assertEquals(listOf(programme), sheet.removeTrack(opacity, clip).tracks)
        assertEquals(listOf(programme), sheet.removeTracksFor(clip).tracks)
        assertEquals(sheet, KeyframeSheet().withTracks(listOf(programme, scoped)))
    }

    @Test
    fun `a clip's own track wins over the programme's, and disabled tracks are skipped`() {
        val programme = KeyframeTrack(opacity, listOf(key("p", 0, scalar(0.2f))))
        val scoped = KeyframeTrack(opacity, listOf(key("s", 0, scalar(0.8f))), clip)
        val zoom = KeyframeTrack(ParamId("scene.zoom"), listOf(key("z", 0, scalar(2f))))
        val off = KeyframeTrack(ParamId("off"), listOf(key("o", 0, scalar(1f))), enabled = false)
        val empty = KeyframeTrack(ParamId("empty"))
        val sheet = KeyframeSheet(listOf(programme, scoped, zoom, off, empty))
        assertEquals(mapOf(opacity to scalar(0.2f), ParamId("scene.zoom") to scalar(2f)), sheet.valuesAt(100))
        assertEquals(mapOf(opacity to scalar(0.8f), ParamId("scene.zoom") to scalar(2f)), sheet.valuesAt(100, clip))
        assertEquals(scalar(0.2f), sheet.valuesAt(100, ClipId("other"))[opacity])
    }

    @Test
    fun `a sheet ripples clip tracks with their clips and programme tracks only with everything`() {
        val programme = KeyframeTrack(opacity, listOf(key("p", 2_000, scalar(0f))))
        val moved = KeyframeTrack(opacity, listOf(key("m", 2_000, scalar(0f))), clip)
        val stayed = KeyframeTrack(opacity, listOf(key("s", 2_000, scalar(0f))), ClipId("other"))
        val sheet = KeyframeSheet(listOf(programme, moved, stayed))
        val lane = sheet.rippled(RippleShift(1_000, -500, RippleScope.LANE, setOf(clip)))
        assertEquals(
            2_000L,
            lane
                .track(opacity)!!
                .keys
                .single()
                .atMs,
        )
        assertEquals(
            1_500L,
            lane
                .track(opacity, clip)!!
                .keys
                .single()
                .atMs,
        )
        assertEquals(
            2_000L,
            lane
                .track(opacity, ClipId("other"))!!
                .keys
                .single()
                .atMs,
        )
        val all = sheet.rippled(RippleShift(1_000, -500, RippleScope.ALL_LANES, setOf(clip)))
        assertEquals(
            1_500L,
            all
                .track(opacity)!!
                .keys
                .single()
                .atMs,
        )
        val shifted = sheet.shiftClip(clip, 300)
        assertEquals(
            2_300L,
            shifted
                .track(opacity, clip)!!
                .keys
                .single()
                .atMs,
        )
        assertEquals(
            2_000L,
            shifted
                .track(opacity)!!
                .keys
                .single()
                .atMs,
        )
    }
}
