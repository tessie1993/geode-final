package dev.geode.feature.studio.handlers.edit

import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderSize
import dev.geode.feature.studio.api.edit.Clip
import dev.geode.feature.studio.api.edit.ClipContent
import dev.geode.feature.studio.api.edit.ClipEdit
import dev.geode.feature.studio.api.edit.ClipId
import dev.geode.feature.studio.api.edit.ClipLook
import dev.geode.feature.studio.api.edit.ClipTransition
import dev.geode.feature.studio.api.edit.EditorProject
import dev.geode.feature.studio.api.edit.Lane
import dev.geode.feature.studio.api.edit.LaneId
import dev.geode.feature.studio.api.edit.LaneKind
import dev.geode.feature.studio.api.edit.Marker
import dev.geode.feature.studio.api.edit.MarkerId
import dev.geode.feature.studio.api.edit.MarkerSet
import dev.geode.feature.studio.api.edit.Timeline
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor's saved cut (#318), and the old editor's projects brought up to it (#347): a real sample of
 * the old editor's version, with its legacy names, opens with nothing lost (ADR-0004).
 */
class EditorProjectFormatTest {
    private fun sample(): String = checkNotNull(javaClass.classLoader?.getResource("editor/editor-v1.json")) { "no sample" }.readText()

    private fun videoEdit(
        project: EditorProject,
        clip: String,
    ): ClipEdit = (project.timeline.clip(ClipId(clip))?.content as ClipContent.Video).edit

    @Test
    fun `an old editor project opens with its lanes, clips, transitions and markers`() {
        val project = EditorProjectFormat.fromJson(sample())

        assertEquals(listOf(LaneId("lane-a"), LaneId("lane-b")), project.timeline.lanes.map { it.id })
        assertEquals(listOf(LaneKind.Media, LaneKind.Text), project.timeline.lanes.map { it.kind })
        assertTrue(project.timeline.lanes[1].locked)
        assertEquals(8_000L, project.timeline.durationMs)
        val beach = checkNotNull(project.timeline.clip(ClipId("clip-a")))
        assertEquals(ClipTransition("fade", 400L), beach.transition)
        assertEquals(1_000L, beach.sourceInMs)
        assertEquals("content://media/external/video/media/31", (beach.content as ClipContent.Video).uri)
        assertEquals(ClipContent.Text("Hello"), project.timeline.clip(ClipId("clip-c"))?.content)
        assertEquals(listOf("Drop"), project.markers.markers.map { it.name })
    }

    @Test
    fun `the old export's ratio, quality and look become Studio's shape, size and look`() {
        val edit = videoEdit(EditorProjectFormat.fromJson(sample()), "clip-a")

        assertEquals(RenderAspect.R9_16, edit.ratio)
        assertEquals(RenderSize.UHD4K, edit.quality)
        assertEquals(ClipLook.NEON, edit.look)
        assertEquals(1_000L, edit.startMs)
        assertEquals(6_000L, edit.endMs)
        assertEquals(0.22f, edit.contrast)
        assertTrue(edit.mute)
        assertEquals("Summer", edit.caption)
        assertEquals(1.2f, edit.gammaBlue)
    }

    @Test
    fun `a legacy name this version has no match for falls back to the default`() {
        val edit = videoEdit(EditorProjectFormat.fromJson(sample()), "clip-b")

        assertEquals(ClipLook.NONE, edit.look)
        assertNull(edit.ratio)
        assertEquals(RenderSize.FHD1080, edit.quality)
    }

    @Test
    fun `every legacy ratio, quality and look has its match`() {
        val pairs =
            listOf("R16_9", "R9_16", "R1_1", "R4_5", "R4_3", "R21_9").map { name ->
                val old = oldProjectWith(JSONObject().put("ratio", name))
                videoEdit(EditorProjectFormat.fromJson(old.toString()), "clip-1").ratio?.name
            }
        assertEquals(listOf("R16_9", "R9_16", "R1_1", "R4_5", "R4_3", "R21_9"), pairs)
        val sizes =
            listOf("HD720", "FHD1080", "UHD4K").map { name ->
                videoEdit(EditorProjectFormat.fromJson(oldProjectWith(JSONObject().put("quality", name)).toString()), "clip-1").quality
            }
        assertEquals(listOf(RenderSize.HD720, RenderSize.FHD1080, RenderSize.UHD4K), sizes)
        val looks =
            ClipLook.entries.map { look ->
                videoEdit(EditorProjectFormat.fromJson(oldProjectWith(JSONObject().put("look", look.name)).toString()), "clip-1").look
            }
        assertEquals(ClipLook.entries, looks)
    }

    @Test
    fun `upgrading leaves the old project untouched and stamps the current version`() {
        val old = JSONObject(sample())

        val upgraded = OldEditorProjects.upgrade(old)

        assertEquals(EditorProjectFormat.VERSION, upgraded.getInt("version"))
        assertEquals(1, old.getInt("version"))
        val oldEdit =
            old
                .getJSONObject("timeline")
                .getJSONArray("lanes")
                .getJSONObject(0)
                .getJSONArray("clips")
                .getJSONObject(1)
        assertEquals("SEPIA", oldEdit.getJSONObject("content").getJSONObject("edit").getString("look"))
        val newEdit =
            upgraded
                .getJSONObject("timeline")
                .getJSONArray("lanes")
                .getJSONObject(0)
                .getJSONArray("clips")
                .getJSONObject(1)
                .getJSONObject("content")
                .getJSONObject("edit")
        assertFalse(newEdit.has("look"))
        assertFalse(newEdit.has("ratio"))
    }

    @Test
    fun `a cut saved now reads back as it was`() {
        val clip =
            Clip(
                ClipId("clip-1"),
                ClipContent.Video("content://v/a", ClipEdit(look = ClipLook.MONO, ratio = RenderAspect.R4_5, quality = RenderSize.QHD1440)),
                0L,
                4_000L,
                sourceDurationMs = 9_000L,
            )
        val project =
            EditorProject(
                Timeline(listOf(Lane(LaneId("lane-1"), LaneKind.Media, "Media 1", listOf(clip))), 4_000L),
                MarkerSet(listOf(Marker(MarkerId("marker-1"), 1_000L, "Here"))),
            )

        val json = EditorProjectFormat.toJson(project)

        assertEquals(EditorProjectFormat.VERSION, JSONObject(json).getInt("version"))
        assertEquals(project, EditorProjectFormat.fromJson(json))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a project from a newer app is refused`() {
        EditorProjectFormat.fromJson(JSONObject(sample()).put("version", EditorProjectFormat.VERSION + 1).toString())
    }

    /** An old editor project with one video clip whose edit is [edit]. */
    private fun oldProjectWith(edit: JSONObject): JSONObject {
        val content = JSONObject().put("type", "video").put("uri", "content://v/a").put("edit", edit)
        val clip =
            JSONObject()
                .put("id", "clip-1")
                .put("startMs", 0)
                .put("durationMs", 1_000)
                .put("content", content)
        val lane =
            JSONObject()
                .put("id", "lane-1")
                .put("kind", "media")
                .put("clips", org.json.JSONArray().put(clip))
        return JSONObject()
            .put("version", OldEditorProjects.VERSION)
            .put("timeline", JSONObject().put("lanes", org.json.JSONArray().put(lane)))
    }
}
