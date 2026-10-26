package dev.geode.feature.studio.handlers

import dev.geode.engine.types.Millis
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.TrackId
import dev.geode.feature.studio.api.EditCut
import dev.geode.feature.studio.api.EditJob
import dev.geode.feature.studio.api.EditSource
import dev.geode.feature.studio.api.EditSources
import dev.geode.feature.studio.api.FadeCurve
import dev.geode.feature.studio.api.MadeFrom
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderExtras
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderLook
import dev.geode.feature.studio.api.RenderLoudness
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.api.TrackJoin
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.api.edit.ClipEdit
import dev.geode.feature.studio.core.NewRender
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A Render's choices as Studio's database keeps them (#338), with the extras and links of #328 and #329. */
class RenderSpecFormatTest {
    private val night = RenderTrack(TrackId("content://media/12"), "Night Drive", Millis(215_000), "The Neon")

    @Test
    fun `a Render's extras and its Track's artist are kept`() {
        val render =
            NewRender(
                listOf(night),
                RenderLook.Current,
                RenderFormat(),
                RenderLength.WholeTrack,
                ProjectId(1),
                RenderExtras(RenderLoudness.MINUS_14_LUFS, captions = true, titleCard = true, endCard = false),
            )

        assertEquals(render, RenderSpecFormat.decode(RenderSpecFormat.encode(render)))
    }

    @Test
    fun `a Short keeps what it is made from and its loudness`() {
        val source = EditSource("content://tree/take.mp4", "take.mp4", Millis(40_000), 1080, 1920)
        val edit = ClipEdit(endMs = 30_000L, ratio = RenderAspect.R9_16, cropX = 0.2f)
        val render =
            NewRender(
                emptyList(),
                RenderLook.Current,
                RenderFormat(aspect = RenderAspect.R9_16, fps = 30),
                RenderLength.WholeTrack,
                ProjectId(2),
                RenderExtras(RenderLoudness.MINUS_14_LUFS),
                edit = EditJob("take Short", EditCut.OneClip(source, edit), EditSources.REFERENCE, RenderLoudness.MINUS_14_LUFS),
                madeFrom = MadeFrom(source.file, VideoKind.SHORT),
            )

        assertEquals(render, RenderSpecFormat.decode(RenderSpecFormat.encode(render)))
    }

    @Test
    fun `a Render saved before the extras reads with none, and a kind this version does not know links nothing`() {
        val old =
            JSONObject()
                .put("version", 1)
                .put("tracks", org.json.JSONArray().put(JSONObject().put("id", "content://media/12").put("title", "Night Drive")))
                .put("project", 1)
                .put("loudness", "MINUS_11_LUFS")
                .put("madeFrom", JSONObject().put("file", "content://x").put("kind", "HOLOGRAM"))

        val read = RenderSpecFormat.decode(old.toString())

        assertEquals(RenderExtras(RenderLoudness.MINUS_11_LUFS), read.extras)
        assertEquals("", read.tracks.single().artist)
        assertNull(read.madeFrom)
    }

    @Test
    fun `a continuous video keeps the look of each track and how its tracks join`() {
        val tide = RenderTrack(TrackId("content://media/7"), "Low Tide", Millis(187_000))
        val calm = RenderLook.Preset(PresetId("Calm"))
        val render =
            NewRender(
                listOf(night, tide),
                RenderLook.Current,
                RenderFormat(),
                RenderLength.WholeTrack,
                ProjectId(1),
                RenderExtras(),
                trackLooks = listOf(calm, RenderLook.Current),
                join = TrackJoin(Millis(6_000), FadeCurve.SMOOTH),
            )
        val gapped = render.copy(join = TrackJoin(gap = Millis(200)))
        assertEquals(gapped, RenderSpecFormat.decode(RenderSpecFormat.encode(gapped)))

        assertEquals(render, RenderSpecFormat.decode(RenderSpecFormat.encode(render)))
    }

    @Test
    fun `a Render saved before continuous videos joins nothing, and an unknown curve is equal power`() {
        val old =
            JSONObject()
                .put("version", 1)
                .put("tracks", org.json.JSONArray().put(JSONObject().put("id", "content://media/12").put("title", "Night Drive")))
                .put("project", 1)
        val read = RenderSpecFormat.decode(old.toString())
        assertEquals(emptyList<RenderLook>(), read.trackLooks)
        assertEquals(TrackJoin(), read.join)

        val odd = old.put("join", JSONObject().put("crossfadeMs", -5).put("curve", "WOBBLE").put("gapMs", -1))
        assertEquals(TrackJoin(Millis.ZERO, FadeCurve.EQUAL_POWER), RenderSpecFormat.decode(odd.toString()).join)
    }

    @Test
    fun `the looks of a continuous video's tracks are kept in order`() {
        val looks = listOf(FrozenLook("{\"style\":\"fluid_ink\"}"), FrozenLook("calm"), FrozenLook("calm"))
        assertEquals(looks, TrackLooksFormat.decode(TrackLooksFormat.encode(looks)))
        assertEquals(emptyList<FrozenLook>(), TrackLooksFormat.decode(null))
        assertEquals(emptyList<FrozenLook>(), TrackLooksFormat.decode("not a list"))
    }
}
